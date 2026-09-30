"""
사용자 모델 정의 모듈.

이 모듈은 서비스의 사용자(User)를 정의하고 관리한다.
Django의 AbstractUser를 확장하여 추가 프로필 정보를 포함하며,
UUID를 기본키로 사용한다.

Classes:
    User: 사용자 모델 (UUID PK)
    UserAuthProvider: 사용자 인증 제공자 정보
"""
import uuid
from django.contrib.auth.models import AbstractUser
from django.db import models

class User(AbstractUser):
    """
    사용자 정보를 저장하는 모델.

    Django의 AbstractUser를 상속받아 기본 인증 필드 외에
    신체 정보(키, 몸무게)와 운동 목적 등의 프로필 정보를 확장하여 저장한다.
    기본키(PK)로 UUID를 사용한다.

    Attributes:
        id (UUID): 사용자 고유 ID (PK)
        name (str): 사용자 실명
        phone_number (str): 전화번호 (Unique)
        pin_hash (str): 간편 로그인용 PIN 해시
        birthdate (date): 생년월일
        gender (str): 성별 (M/F/U)
        height_cm (int): 키 (cm)
        weight_kg (int): 몸무게 (kg)
        is_profile_completed (bool): 프로필 완성 여부
    """
    
    # 기본키 UUID로 변경
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    
    # AbstractUser의 first_name, last_name은 사용하지 않음
    first_name = None
    last_name = None
    
    name = models.CharField(max_length=255, null=True, blank=True, verbose_name='이름')
    phone_number = models.CharField(max_length=20, unique=True, null=True, verbose_name='전화번호')
    pin_hash = models.CharField(max_length=255, null=True, blank=True, verbose_name='PIN 해시')
    
    birthdate = models.DateField(null=True, blank=True, verbose_name='생년월일')
    gender = models.CharField(
        max_length=1, 
        choices=[('M', '남성'), ('F', '여성'), ('U', '미선택')], 
        null=True, 
        blank=True, 
        verbose_name='성별'
    )
    height_cm = models.IntegerField(null=True, blank=True, verbose_name='키(cm)')
    weight_kg = models.IntegerField(null=True, blank=True, verbose_name='몸무게(kg)')
    is_profile_completed = models.BooleanField(default=False, verbose_name='프로필 완성 여부')
    
    # Soft Delete 필드
    is_deleted = models.BooleanField(default=False, verbose_name='탈퇴 여부')
    deleted_at = models.DateTimeField(null=True, blank=True, verbose_name='탈퇴 일시')

    class Meta:
        db_table = 'users'
        verbose_name = '사용자'
        verbose_name_plural = '사용자 목록'
        indexes = [
            models.Index(fields=['phone_number']),
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
