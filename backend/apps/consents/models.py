"""
동의 문서·동의 기록 (05 문서 §3(B)·§4, 03 문서 §4.2 CONSENT_RECORD)

- ConsentDocument: 동의 항목별 문서 버전(약관·처리방침 본문은 url로 연결)
- ConsentRecord: 사용자의 동의·거부·철회 이력(추가만 하고 고치지 않는다). 버전·시각·방법(터치/음성)을 남긴다.
"""
from django.conf import settings
from django.db import models


class ConsentType(models.TextChoices):
    TOS = 'TOS', '이용약관'
    PRIVACY = 'PRIVACY', '개인정보 수집·이용'
    OVERSEAS = 'OVERSEAS', '국외이전 고지'
    SENSITIVE = 'SENSITIVE', '건강정보(민감정보) 처리'
    MARKETING = 'MARKETING', '광고성 정보 수신'


class ConsentMethod(models.TextChoices):
    TOUCH = 'TOUCH', '터치'
    VOICE = 'VOICE', '음성'


class DocumentKind(models.TextChoices):
    CONSENT = 'CONSENT', '동의'
    NOTICE = 'NOTICE', '고지 확인'


class ConsentDocument(models.Model):
    type = models.CharField(max_length=20, choices=ConsentType.choices, verbose_name='동의 항목')
    version = models.CharField(max_length=32, verbose_name='버전')
    title = models.CharField(max_length=200, verbose_name='제목')
    summary = models.TextField(blank=True, verbose_name='낭독용 요약')
    url = models.URLField(blank=True, verbose_name='전문 주소')
    required = models.BooleanField(default=False, verbose_name='필수 여부')
    # NOTICE = 동의가 아니라 고지(거부할 수 없는 확인). 기록은 agreed=true로 남기고 recorded_at이 확인 시각이다
    # (법적 검토 C-01: 국외이전은 전화번호 인증에 꼭 필요해 선택 동의로 둘 수 없음).
    kind = models.CharField(max_length=10, choices=DocumentKind.choices, default=DocumentKind.CONSENT, verbose_name='종류')
    effective_at = models.DateField(verbose_name='시행일')
    is_active = models.BooleanField(default=True, verbose_name='사용 여부')

    class Meta:
        db_table = 'consent_documents'
        verbose_name = '동의 문서'
        verbose_name_plural = '동의 문서 목록'
        constraints = [models.UniqueConstraint(fields=['type', 'version'], name='uniq_consent_doc_version')]
        ordering = ['type', '-effective_at']

    def __str__(self):
        return f'{self.type} {self.version}'


class ConsentRecord(models.Model):
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='consent_records')
    type = models.CharField(max_length=20, choices=ConsentType.choices, verbose_name='동의 항목')
    version = models.CharField(max_length=32, verbose_name='문서 버전')
    agreed = models.BooleanField(verbose_name='동의 여부')
    method = models.CharField(max_length=10, choices=ConsentMethod.choices, verbose_name='방법')
    recorded_at = models.DateTimeField(auto_now_add=True, verbose_name='기록 일시')
    withdrawn_at = models.DateTimeField(null=True, blank=True, verbose_name='철회 일시')
    # 입증 대비(법적 검토 C-07): 어느 앱 버전·화면에서 받았는지, 음성이면 재확인 질문을 통과했는지
    app_version = models.CharField(max_length=32, blank=True, default='', verbose_name='앱 버전')
    screen_id = models.CharField(max_length=40, blank=True, default='', verbose_name='화면 ID')
    voice_confirmed = models.BooleanField(null=True, blank=True, verbose_name='음성 재확인 통과')

    class Meta:
        db_table = 'consent_records'
        verbose_name = '동의 기록'
        verbose_name_plural = '동의 기록 목록'
        indexes = [models.Index(fields=['user', 'type', '-recorded_at'])]
        ordering = ['-recorded_at', '-id']
