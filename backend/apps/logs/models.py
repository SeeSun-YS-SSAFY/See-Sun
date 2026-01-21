import uuid
from django.db import models
from django.conf import settings
from apps.exercises.models import Exercise, Playlist, PlaylistItem

# -----------------------------------------------------------------------------
# 3. 운동 세션 및 로그 (History) - Moved from exercises app
# -----------------------------------------------------------------------------

class ExerciseSession(models.Model):
    """
    사용자가 수행한 운동 세션(한 번의 운동 전체) 기록.
    """
    MODE_CHOICES = [
        ('CURRICULUM', '커리큘럼'),
        ('MANUAL', '매뉴얼'),
    ]
    ABNORMAL_END_REASON_CHOICES = [
        ('NORMAL', '정상 종료'),
        ('APP_KILLED', '앱 종료됨'),
        ('CRASH', '충돌'),
        ('UNKNOWN', '알 수 없음'),
    ]
    STATUS_CHOICES = [
        ('IN_PROGRESS', '진행 중'),
        ('COMPLETED', '완료됨'),
        ('ABORTED', '중단됨'),
    ]

    session_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='exercise_sessions', verbose_name="사용자")
    playlist = models.ForeignKey(Playlist, on_delete=models.SET_NULL, null=True, blank=True, related_name='sessions', verbose_name="플레이리스트")
    mode = models.CharField(max_length=20, choices=MODE_CHOICES, verbose_name="모드")
    
    # Simple name field added for standalone sessions
    exercise_name = models.CharField(max_length=255, help_text="운동 또는 플레이리스트 이름", blank=True, null=True)

    started_at = models.DateTimeField(auto_now_add=True, verbose_name="시작 시간")
    ended_at = models.DateTimeField(blank=True, null=True, verbose_name="종료 시간")
    duration = models.DurationField(null=True, blank=True, help_text="운동 지속 시간") # Added to match simplified version too
    duration_ms = models.IntegerField(blank=True, null=True, verbose_name="소요 시간(ms)")
    duration_seconds = models.FloatField(blank=True, null=True, verbose_name="소요 시간(초, 소수점)")
    
    last_ping_at = models.DateTimeField(blank=True, null=True, verbose_name="마지막 핑 시간")

    is_valid = models.BooleanField(default=True, verbose_name="유효 여부")
    status = models.CharField(
        max_length=20, 
        choices=STATUS_CHOICES, 
        default='IN_PROGRESS',
        verbose_name="상태"
    )
    abnormal_end_reason = models.CharField(max_length=20, choices=ABNORMAL_END_REASON_CHOICES, blank=True, null=True, verbose_name="비정상 종료 사유")
    device_id_hash = models.CharField(max_length=64, blank=True, null=True, verbose_name="디바이스 해시")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")

    class Meta:
        db_table = 'exercise_sessions'
        verbose_name = '운동 세션'
        verbose_name_plural = '운동 세션 목록'
        indexes = [
            models.Index(fields=['user', 'started_at']),
        ]
        ordering = ['-started_at']

    def __str__(self):
        return f"Session {self.session_id} - {self.user.username}"


class ExerciseSessionItem(models.Model):
    """
    세션 내에서 수행된 개별 운동 항목의 기록.
    """
    SKIP_REASON_CHOICES = [
        ('LOAD_FAIL', '로드 실패'),
        ('USER_SKIP', '사용자 스킵'),
        ('UNKNOWN', '알 수 없음'),
    ]

    session_item_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.ForeignKey(ExerciseSession, on_delete=models.CASCADE, related_name='items', verbose_name="세션")
    exercise = models.ForeignKey(Exercise, on_delete=models.CASCADE, related_name='session_logs', verbose_name="운동")
    playlist_item = models.ForeignKey(PlaylistItem, on_delete=models.SET_NULL, null=True, blank=True, verbose_name="플레이리스트 항목")
    
    sequence_no = models.IntegerField(verbose_name="순서")
    started_at = models.DateTimeField(blank=True, null=True, verbose_name="시작 시간")
    ended_at = models.DateTimeField(blank=True, null=True, verbose_name="종료 시간")
    duration_ms = models.IntegerField(blank=True, null=True, verbose_name="소요 시간(ms)")
    
    is_skipped = models.BooleanField(default=False, verbose_name="스킵 여부")
    skip_reason = models.CharField(max_length=20, choices=SKIP_REASON_CHOICES, blank=True, null=True, verbose_name="스킵 사유")
    rest_sec = models.IntegerField(default=10, verbose_name="휴식 시간(초)")

    class Meta:
        db_table = 'exercise_session_items'
        verbose_name = '세션 운동 항목'
        verbose_name_plural = '세션 운동 항목 목록'
        indexes = [
            models.Index(fields=['session', 'sequence_no']),
        ]


class ExerciseSessionEvent(models.Model):
    """
    세션 진행 중 발생한 상세 이벤트 로그 (재생, 일시정지, VUI 명령 등).
    """
    event_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.ForeignKey(ExerciseSession, on_delete=models.CASCADE, related_name='events', verbose_name="세션")
    session_item = models.ForeignKey(ExerciseSessionItem, on_delete=models.CASCADE, null=True, blank=True, related_name='events', verbose_name="세션 항목")
    
    event_time_ms = models.BigIntegerField(verbose_name="이벤트 발생 시간(세션 시작 후 ms)")
    event_type = models.CharField(max_length=50, verbose_name="이벤트 타입")
    payload = models.JSONField(blank=True, null=True, verbose_name="페이로드")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")

    class Meta:
        db_table = 'exercise_session_events'
        verbose_name = '세션 이벤트'
        verbose_name_plural = '세션 이벤트 목록'
        indexes = [
            models.Index(fields=['session', 'event_time_ms']),
        ]


class ExerciseLogObject(models.Model):
    """
    세션의 상세 로그 파일이 저장된 S3 객체에 대한 메타데이터.
    """
    UPLOAD_STATUS_CHOICES = [
        ('PENDING', '대기 중'),
        ('UPLOADED', '업로드 완료'),
        ('FAILED', '실패'),
    ]

    log_object_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.OneToOneField(ExerciseSession, on_delete=models.CASCADE, related_name='log_object', verbose_name="세션")
    
    s3_key = models.CharField(max_length=512, unique=True, verbose_name="S3 키")
    content_type = models.CharField(max_length=20, verbose_name="컨텐츠 타입")
    bytes = models.BigIntegerField(blank=True, null=True, verbose_name="크기(Bytes)")
    checksum = models.CharField(max_length=64, blank=True, null=True, verbose_name="체크섬")
    
    upload_status = models.CharField(max_length=20, choices=UPLOAD_STATUS_CHOICES, default='PENDING', verbose_name="업로드 상태")
    retry_count = models.IntegerField(default=0, verbose_name="재시도 횟수")
    last_error_code = models.CharField(max_length=50, blank=True, null=True, verbose_name="최근 에러 코드")
    last_error_at = models.DateTimeField(blank=True, null=True, verbose_name="최근 에러 일시")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")
    uploaded_at = models.DateTimeField(blank=True, null=True, verbose_name="업로드 일시")

    class Meta:
        db_table = 'exercise_log_objects'
        verbose_name = '운동 로그 객체'
        verbose_name_plural = '운동 로그 객체 목록'
        indexes = [
            models.Index(fields=['upload_status']),
        ]
