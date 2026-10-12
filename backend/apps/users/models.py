"""
사용자 모델 정의 모듈.

이 모듈은 서비스의 사용자(User)를 정의하고 관리한다.
Django의 AbstractUser를 확장하여 추가 프로필 정보를 포함하며,
UUID를 기본키로 사용한다.

2차 변경(09 문서 2-5·2-7, 03 문서 §4.2)
- 전화번호는 필드 암호화(EncryptedCharField)로 저장하고, 조회·중복 검사는 HMAC 해시 색인(phone_hash)으로 한다.
  ``User.objects.filter(phone_number=...)`` 는 쿼리셋이 자동으로 ``phone_hash`` 조회로 바꾼다.
- Firebase UID, 목표·목표 칼로리, 앱 설정(JSON)을 추가했다.
- 탈퇴 시 ``account_withdrawn`` 신호로 각 앱이 자기 데이터를 파기한다.

Classes:
    User: 사용자 모델 (UUID PK)
    UserAuthProvider: 사용자 인증 제공자 정보
"""
import uuid

from django.contrib.auth.models import AbstractUser
from django.contrib.auth.models import UserManager as DjangoUserManager
from django.db import models
from django.dispatch import Signal

from apps.core import crypto
from apps.core.fields import EncryptedCharField

# 탈퇴 신호: sender=User, user=<User>, firebase_uid=<str|None>
account_withdrawn = Signal()


# 1차 데이터에는 미선택(U)이 있다. 2차 입력은 남/여만 받는다(02 문서 Q-05).
GENDER_CHOICES = [('M', '남성'), ('F', '여성'), ('U', '미선택')]
GENDER_CHOICES_V2 = [('M', '남성'), ('F', '여성')]


class Goal(models.TextChoices):
    LOSE_WEIGHT = 'LOSE_WEIGHT', '체중 감량'
    GAIN_MUSCLE = 'GAIN_MUSCLE', '근육 증진'
    MAINTAIN = 'MAINTAIN', '건강 유지'


class UserQuerySet(models.QuerySet):
    """phone_number 정확 일치 조회를 phone_hash 조회로 바꿔 주는 쿼리셋"""

    def _filter_or_exclude(self, negate, args, kwargs):
        return super()._filter_or_exclude(negate, args, _rewrite_phone_lookups(kwargs))


def _rewrite_phone_lookups(kwargs):
    if not any(k.startswith('phone_number') for k in kwargs):
        return kwargs
    rewritten = {}
    for key, value in kwargs.items():
        if key in ('phone_number', 'phone_number__exact'):
            if value is None:
                rewritten['phone_hash__isnull'] = True
            else:
                rewritten['phone_hash'] = crypto.phone_hash(value)
        elif key == 'phone_number__isnull':
            rewritten['phone_hash__isnull'] = value
        elif key == 'phone_number__in':
            rewritten['phone_hash__in'] = [crypto.phone_hash(v) for v in value]
        elif key.startswith('phone_number'):
            raise ValueError('암호화된 phone_number는 정확히 일치(=, __in, __isnull) 조회만 지원합니다.')
        else:
            rewritten[key] = value
    return rewritten


class UserManager(DjangoUserManager.from_queryset(UserQuerySet)):
    pass


class User(AbstractUser):
    """
    사용자 정보를 저장하는 모델.

    Attributes:
        id (UUID): 사용자 고유 ID (PK)
        name (str): 사용자 실명
        phone_number (str): 전화번호(암호화 저장, 숫자만)
        phone_hash (str): 전화번호 HMAC 해시(Unique, 조회 색인)
        firebase_uid (str): Firebase Auth UID(Unique)
        pin_hash (str): 1차 간편 로그인용 PIN 해시(2차 비활성)
        birthdate (date): 생년월일
        gender (str): 성별 (M/F, 1차 데이터는 U 가능)
        height_cm (int): 키 (cm)
        weight_kg (int): 몸무게 (kg)
        goal (str): 목표(체중 감량/근육 증진/건강 유지)
        target_kcal (int): 목표 칼로리(산식 모듈 계산값)
        app_settings (dict): 음성 안내·속도 등 앱 설정
        is_profile_completed (bool): 프로필 완성 여부
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)

    # AbstractUser의 first_name, last_name은 사용하지 않음
    first_name = None
    last_name = None

    name = models.CharField(max_length=255, null=True, blank=True, verbose_name='이름')
    phone_number = EncryptedCharField(max_length=20, null=True, blank=True, verbose_name='전화번호(암호화)')
    phone_hash = models.CharField(max_length=64, unique=True, null=True, blank=True, editable=False,
                                  verbose_name='전화번호 해시')
    firebase_uid = models.CharField(max_length=128, unique=True, null=True, blank=True, verbose_name='Firebase UID')
    pin_hash = models.CharField(max_length=255, null=True, blank=True, verbose_name='PIN 해시')

    birthdate = models.DateField(null=True, blank=True, verbose_name='생년월일')
    gender = models.CharField(
        max_length=1,
        choices=GENDER_CHOICES,
        null=True,
        blank=True,
        verbose_name='성별'
    )
    height_cm = models.IntegerField(null=True, blank=True, verbose_name='키(cm)')
    weight_kg = models.IntegerField(null=True, blank=True, verbose_name='몸무게(kg)')
    goal = models.CharField(max_length=20, choices=Goal.choices, null=True, blank=True, verbose_name='목표')
    target_kcal = models.IntegerField(null=True, blank=True, verbose_name='목표 칼로리')
    app_settings = models.JSONField(default=dict, blank=True, verbose_name='앱 설정')
    is_profile_completed = models.BooleanField(default=False, verbose_name='프로필 완성 여부')

    # Soft Delete 필드
    is_deleted = models.BooleanField(default=False, verbose_name='탈퇴 여부')
    deleted_at = models.DateTimeField(null=True, blank=True, verbose_name='탈퇴 일시')

    objects = UserManager()

    def save(self, *args, **kwargs):
        # 전화번호 정규화 + 해시 색인 동기화
        self.phone_number = crypto.normalize_phone(self.phone_number)
        self.phone_hash = crypto.phone_hash(self.phone_number) if self.phone_number else None
        update_fields = kwargs.get('update_fields')
        if update_fields is not None and 'phone_number' in update_fields:
            kwargs['update_fields'] = set(update_fields) | {'phone_hash'}
        super().save(*args, **kwargs)

    def withdraw(self):
        """
        회원탈퇴: 토큰 폐기 + 개인정보 즉시 파기(비식별화) 후 비활성화.
        식사·점수·건강정보·동의 기록·운동 기록 등 서비스 기록은 각 앱이
        ``account_withdrawn`` 신호를 받아 삭제한다(05 문서 §2 "탈퇴 시 파기").
        """
        from django.utils import timezone
        from rest_framework_simplejwt.token_blacklist.models import OutstandingToken, BlacklistedToken

        for token in OutstandingToken.objects.filter(user=self):
            BlacklistedToken.objects.get_or_create(token=token)
        self.auth_providers.all().delete()

        account_withdrawn.send(sender=User, user=self, firebase_uid=self.firebase_uid)

        self.username = f'deleted_{self.id}'
        self.email = ''
        self.name = None
        self.phone_number = None
        self.firebase_uid = None
        self.pin_hash = None
        self.birthdate = None
        self.gender = None
        self.height_cm = None
        self.weight_kg = None
        self.goal = None
        self.target_kcal = None
        self.app_settings = {}
        self.is_profile_completed = False
        self.set_unusable_password()
        self.is_deleted = True
        self.deleted_at = timezone.now()
        self.is_active = False
        self.save()

    class Meta:
        db_table = 'users'
        verbose_name = '사용자'
        verbose_name_plural = '사용자 목록'
        indexes = [
            models.Index(fields=['date_joined']),
        ]

    def __str__(self):
        return self.username


class UserAuthProvider(models.Model):
    """
    사용자의 소셜 로그인 연동 정보를 저장하는 모델.

    Attributes:
        auth_id (UUID): 인증 정보 고유 ID (PK)
        user (User): 연동된 사용자
        provider (str): 인증 제공자 (KAKAO, GOOGLE 등)
        provider_subject (str): 제공자 측 식별자 (sub, id)
        provider_email (str): 제공자 측 이메일
        linked_at (datetime): 연동 일시
        last_login_at (datetime): 최근 로그인 일시
    """
    PROVIDER_CHOICES = [
        ('LOCAL', '로컬'),
        ('GOOGLE', '구글'),
        ('KAKAO', '카카오'),
    ]

    auth_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='auth_providers', verbose_name='사용자')
    provider = models.CharField(max_length=20, choices=PROVIDER_CHOICES, verbose_name='인증 제공자')
    provider_subject = models.CharField(max_length=255, verbose_name='제공자 식별자')
    provider_email = models.CharField(max_length=255, null=True, blank=True, verbose_name='제공자 이메일')
    linked_at = models.DateTimeField(auto_now_add=True, verbose_name='연동 일시')
    last_login_at = models.DateTimeField(null=True, blank=True, verbose_name='최근 로그인 일시')

    class Meta:
        db_table = 'user_auth_provider'
        verbose_name = '사용자 인증 제공자'
        verbose_name_plural = '사용자 인증 제공자 목록'
        unique_together = [('user', 'provider')]
        indexes = [
            models.Index(fields=['user']),
            models.Index(fields=['provider', 'provider_subject']),
        ]

    def __str__(self):
        return f"{self.user.username} - {self.provider}"
