"""
건강 설정(질환·알레르기) (03 문서 §4.2 HEALTH_PROFILE/USER_DISEASE/USER_ALLERGY, 02 문서 HLT-01~06)

- 민감정보이므로 **민감정보 동의가 있을 때만** 만들고, 철회·탈퇴 시 바로 지운다(05 문서 L-02).
- 질환·알레르기 선택값은 필드 암호화(EncryptedJSONField)로 저장한다(09 문서 2-7).
  설계서 ERD의 연결 테이블(USER_DISEASE, USER_ALLERGY) 대신 암호화된 코드 목록으로 둔다 —
  사용자별로만 읽고 사용자 간 조회가 없으므로 기능 차이는 없고, 평문 FK가 남지 않는다.
- 목표(goal)는 일반 개인정보라 User에 둔다(동의 없이도 목표 칼로리 계산).
"""
from django.conf import settings
from django.db import models

from apps.core.fields import EncryptedJSONField


class Disease(models.TextChoices):
    DIABETES = 'DIABETES', '당뇨'
    HYPERTENSION = 'HYPERTENSION', '고혈압'


class Allergen(models.Model):
    """알레르기 마스터(19종 + 추가 대비, 데이터로 관리)"""
    code = models.CharField(max_length=30, unique=True, verbose_name='코드')
    name_ko = models.CharField(max_length=50, verbose_name='이름')
    display_order = models.PositiveSmallIntegerField(verbose_name='표시 순서')
    keywords = models.JSONField(default=list, blank=True, verbose_name='음식명 키워드(포함 가능성 추정)')
    is_active = models.BooleanField(default=True, verbose_name='사용 여부')

    class Meta:
        db_table = 'allergens'
        verbose_name = '알레르기 항목'
        verbose_name_plural = '알레르기 항목 목록'
        ordering = ['display_order']

    def __str__(self):
        return self.name_ko


class HealthProfile(models.Model):
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='health_profile')
    diseases = EncryptedJSONField(default=list, blank=True, verbose_name='질환 코드(암호화)')
    allergen_codes = EncryptedJSONField(default=list, blank=True, verbose_name='알레르기 코드(암호화)')
    other_allergies = EncryptedJSONField(default=list, blank=True, verbose_name='기타 알레르기(암호화)')
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'health_profiles'
        verbose_name = '건강 설정'
        verbose_name_plural = '건강 설정 목록'
