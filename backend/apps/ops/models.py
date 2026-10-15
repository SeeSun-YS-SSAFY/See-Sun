"""
운영 콘솔(시선 ops) 기록

- MediaRevision: 운동 픽토그램·안내 음성 파일 교체 이력. 이전 파일은 지우지 않고(이름을 바꿔 새 파일을 둔다),
  이 행으로 되돌린다. link_exercise_media 처럼 미디어 행을 지우고 다시 만드는 명령이 있어 FK는 SET_NULL이고
  어떤 행이었는지(운동·종류·순서·구간)를 따로 남긴다.
- AppRelease: 운영 콘솔에서 게시한 앱 업데이트(APK) 이력. 서버의 manifest-<versionCode>.json 은 최신 + 이전 3개만
  남기므로(publish_apk.sh와 같은 정리) 전체 이력은 여기 남는다.
- AppPush: 새 버전을 게시하면 앱에 바로 알리는 FCM 토픽 푸시 기록(성공 메시지 ID 또는 실패 사유).
- AdminSecurity·RecoveryCode: 관리자 화면(/admin/, /ops/) 2단계 인증(TOTP)과 첫 로그인 비밀번호 변경.
행에는 개인정보를 넣지 않는다(취급자 계정 이름만).
"""
from django.conf import settings
from django.db import models

from apps.core.fields import EncryptedCharField


class MediaRevision(models.Model):
    media = models.ForeignKey('exercises.ExerciseMedia', on_delete=models.SET_NULL, null=True, blank=True,
                              related_name='ops_revisions', verbose_name='운동 미디어')
    exercise_id = models.UUIDField(verbose_name='운동 ID')
    media_type = models.CharField(max_length=20, verbose_name='미디어 종류')
    sequence = models.IntegerField(null=True, blank=True, verbose_name='순서')
    section = models.CharField(max_length=50, blank=True, verbose_name='음성 구간')
    old_s3_key = models.CharField(max_length=512, blank=True, verbose_name='이전 스토리지 키')
    old_url = models.CharField(max_length=1024, blank=True, verbose_name='이전 URL')
    old_checksum = models.CharField(max_length=64, blank=True, verbose_name='이전 체크섬')
    old_display_key = models.CharField(max_length=512, blank=True, verbose_name='이전 보기용 사본 키')
    old_duration_ms = models.IntegerField(null=True, blank=True, verbose_name='이전 재생 시간(ms)')
    new_key = models.CharField(max_length=512, verbose_name='새 파일 키')
    new_checksum = models.CharField(max_length=64, verbose_name='새 파일 sha256')
    size_bytes = models.PositiveIntegerField(verbose_name='새 파일 크기')
    content_type = models.CharField(max_length=50, verbose_name='새 파일 형식')
    actor = models.CharField(max_length=150, verbose_name='취급자')
    created_at = models.DateTimeField(auto_now_add=True, verbose_name='교체 일시')
    reverted_at = models.DateTimeField(null=True, blank=True, verbose_name='되돌린 일시')
    reverted_by = models.CharField(max_length=150, blank=True, verbose_name='되돌린 취급자')

    class Meta:
        db_table = 'ops_media_revisions'
        ordering = ['-created_at', '-id']
        verbose_name = '운동 미디어 교체 이력'
        verbose_name_plural = '운동 미디어 교체 이력'


class AppRelease(models.Model):
    version_code = models.PositiveIntegerField(unique=True, verbose_name='versionCode')
    version_name = models.CharField(max_length=64, verbose_name='versionName')
    min_version_code = models.PositiveIntegerField(default=0, verbose_name='minVersionCode')
    sha256 = models.CharField(max_length=64, verbose_name='APK sha256')
    size_bytes = models.PositiveBigIntegerField(verbose_name='APK 크기')
    apk_url = models.CharField(max_length=1024, verbose_name='APK 주소')
    notes = models.TextField(verbose_name='바뀐 점')
    published_at = models.DateTimeField(verbose_name='게시 일시')
    actor = models.CharField(max_length=150, verbose_name='취급자')

    class Meta:
        db_table = 'ops_app_releases'
        ordering = ['-version_code']
        verbose_name = '앱 업데이트 게시 이력'
        verbose_name_plural = '앱 업데이트 게시 이력'


class AdminSecurity(models.Model):
    """
    관리자(직원) 계정의 로그인 보안 상태 — 처리방침 11절 「관리자 화면은 2단계 인증」

    - otp_secret: TOTP 비밀값(필드 암호화). 등록을 마치기 전에는 otp_confirmed_at이 비어 있다.
    - otp_last_step: 마지막으로 받은 코드의 30초 구간 번호(같은 코드 재사용 막기)
    - must_change_password: ops_bootstrap_admin 으로 만든 계정은 첫 로그인 때 비밀번호부터 바꾼다
    """
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='admin_security')
    otp_secret = EncryptedCharField(max_length=64, null=True, blank=True, verbose_name='TOTP 비밀값(암호화)')
    otp_confirmed_at = models.DateTimeField(null=True, blank=True, verbose_name='2단계 인증 등록 일시')
    otp_last_step = models.BigIntegerField(null=True, blank=True, verbose_name='마지막 코드 구간')
    must_change_password = models.BooleanField(default=False, verbose_name='첫 로그인 때 비밀번호 변경')
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'ops_admin_security'
        verbose_name = '관리자 로그인 보안'
        verbose_name_plural = '관리자 로그인 보안'


class RecoveryCode(models.Model):
    """2단계 인증 복구 코드(한 번만 쓸 수 있음). 코드는 저장하지 않고 소금+SHA-256만 둔다."""
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='otp_recovery_codes')
    salt = models.CharField(max_length=32)
    code_hash = models.CharField(max_length=64)
    created_at = models.DateTimeField(auto_now_add=True)
    used_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = 'ops_recovery_codes'
        verbose_name = '2단계 인증 복구 코드'
        verbose_name_plural = '2단계 인증 복구 코드'


class AppPush(models.Model):
    """앱 업데이트 알림(FCM 토픽 푸시) 보낸 기록. 같은 버전을 다시 보낼 수 있다(앱은 설치된 버전보다 높을 때만 알림)."""
    SOURCE_CHOICES = [('publish', '콘솔 게시'), ('resend', '콘솔 다시 보내기'), ('command', '관리 명령')]

    version_code = models.PositiveIntegerField(db_index=True, verbose_name='versionCode')
    topic = models.CharField(max_length=100, verbose_name='토픽')
    message_id = models.CharField(max_length=300, blank=True, verbose_name='FCM 메시지 ID')
    error = models.CharField(max_length=300, blank=True, verbose_name='실패 사유')
    source = models.CharField(max_length=10, choices=SOURCE_CHOICES, verbose_name='보낸 곳')
    actor = models.CharField(max_length=150, verbose_name='취급자')
    created_at = models.DateTimeField(auto_now_add=True, verbose_name='보낸 일시')

    class Meta:
        db_table = 'ops_app_pushes'
        ordering = ['-created_at', '-id']
        verbose_name = '앱 업데이트 알림 기록'
        verbose_name_plural = '앱 업데이트 알림 기록'

    @property
    def ok(self):
        return bool(self.message_id) and not self.error
