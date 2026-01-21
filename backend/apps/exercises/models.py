"""
운동(Exercise) 및 루틴, 세션 관련 데이터 모델 정의 모듈.

이 모듈은 운동 카테고리, 개별 운동(Exercise), 미디어,
플레이리스트(Routine), 그리고 운동 세션 및 로그 정보를 정의한다.
ERD 설계에 따라 모든 테이블은 UUID를 기본키로 사용한다.
"""
import uuid
from django.db import models
from django.conf import settings

# -----------------------------------------------------------------------------
# 1. 운동 기본 정보 (Master Data)
# -----------------------------------------------------------------------------

class ExerciseCategory(models.Model):
    """
    운동의 대분류를 관리하는 모델. (예: 근력, 유산소)
    """
    category_id = models.CharField(max_length=50, primary_key=True, verbose_name="카테고리 ID")
    display_name = models.CharField(max_length=255, verbose_name="표시 이름")
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")

    class Meta:
        db_table = 'exercise_category'
        verbose_name = '운동 카테고리'
        verbose_name_plural = '운동 카테고리 목록'

    def __str__(self):
        return self.display_name


class Exercise(models.Model):
    """
    개별 운동 종목 정보를 담는 모델.
    """
    exercise_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    category = models.ForeignKey(ExerciseCategory, on_delete=models.CASCADE, related_name='exercises', verbose_name="카테고리")
    exercise_name = models.CharField(max_length=255, verbose_name="운동명")
    exercise_description = models.TextField(blank=True, null=True, verbose_name="운동 설명")
    
    # 운동 상세 가이드
    first_description = models.TextField(blank=True, null=True, verbose_name="준비 자세 설명")
    main_form = models.TextField(blank=True, null=True, verbose_name="주요 동작 설명")
    form_description = models.TextField(blank=True, null=True, verbose_name="동작 상세 설명")
    stay_form = models.TextField(blank=True, null=True, verbose_name="유지 동작 설명")
    fixed_form = models.TextField(blank=True, null=True, verbose_name="고정 동작 설명")
    
    exercise_guide_text = models.TextField(blank=True, null=True, verbose_name="TTS 가이드 텍스트")
    is_active = models.BooleanField(default=True, verbose_name="활성 여부")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")
    updated_at = models.DateTimeField(auto_now=True, verbose_name="수정일")

    class Meta:
        db_table = 'exercise'
        verbose_name = '운동'
        verbose_name_plural = '운동 목록'
        indexes = [
            models.Index(fields=['category']),
            models.Index(fields=['is_active']),
            models.Index(fields=['exercise_name']),
        ]

    def __str__(self):
        return self.exercise_name


class ExerciseMedia(models.Model):
    """
    운동 관련 미디어 (픽토그램, 가이드 오디오 등) 정보를 담는 모델.
    """
    MEDIA_TYPE_CHOICES = [
        ('PICTOGRAM', '픽토그램'),
        ('GUIDE_AUDIO', '가이드 오디오'),
        ('TTS_PREGEN', 'TTS 미리 생성됨'),
    ]

    media_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    exercise = models.ForeignKey(Exercise, on_delete=models.CASCADE, related_name='media_contents', verbose_name="운동")
    media_type = models.CharField(max_length=20, choices=MEDIA_TYPE_CHOICES, verbose_name="미디어 타입")
    locale = models.CharField(max_length=10, default='ko-KR', verbose_name="로케일")
    
    s3_key = models.CharField(max_length=512, blank=True, null=True, verbose_name="S3 키")
    url = models.CharField(max_length=1024, blank=True, null=True, verbose_name="URL")
    duration_ms = models.IntegerField(blank=True, null=True, verbose_name="재생 시간(ms)")
    checksum = models.CharField(max_length=64, blank=True, null=True, verbose_name="체크섬")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")

    class Meta:
        db_table = 'exercise_media'
        verbose_name = '운동 미디어'
        verbose_name_plural = '운동 미디어 목록'
        indexes = [
            models.Index(fields=['exercise', 'media_type', 'locale']),
        ]


# -----------------------------------------------------------------------------
# 2. 플레이리스트 (Routine)
# -----------------------------------------------------------------------------

class Playlist(models.Model):
    """
    사용자의 운동 플레이리스트(루틴)를 정의하는 모델.
    """
    MODE_CHOICES = [
        ('CURRICULUM', '커리큘럼'),
        ('MANUAL', '매뉴얼'),
        ('CUSTOM', '사용자 정의'),
    ]
    STATUS_CHOICES = [
        ('ACTIVE', '활성'),
        ('ARCHIVED', '보관됨'),
    ]

    playlist_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='playlists', verbose_name="사용자")
    mode = models.CharField(max_length=20, choices=MODE_CHOICES, verbose_name="모드")
    title = models.CharField(max_length=255, verbose_name="제목")
    status = models.CharField(max_length=20, choices=STATUS_CHOICES, default='ACTIVE', verbose_name="상태")
    
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")
    updated_at = models.DateTimeField(auto_now=True, verbose_name="수정일")

    class Meta:
        db_table = 'playlists'
        verbose_name = '플레이리스트'
        verbose_name_plural = '플레이리스트 목록'
        indexes = [
            models.Index(fields=['user', 'status']),
        ]

    def __str__(self):
        return f"{self.title} ({self.user.username})"


class PlaylistItem(models.Model):
    """
    플레이리스트에 속한 개별 운동 항목을 정의하는 모델.
    순서, 세트 수, 횟수 등을 설정한다.
    """
    playlist_item_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    playlist = models.ForeignKey(Playlist, on_delete=models.CASCADE, related_name='items', verbose_name="플레이리스트")
    exercise = models.ForeignKey(Exercise, on_delete=models.CASCADE, related_name='playlist_items', verbose_name="운동")
    
    sequence_no = models.IntegerField(verbose_name="순서")
    set_count = models.IntegerField(verbose_name="세트 수")
    reps_count = models.IntegerField(blank=True, null=True, verbose_name="회수")
    duration_sec = models.IntegerField(blank=True, null=True, verbose_name="수행 시간(초)")
    rest_sec = models.IntegerField(default=10, verbose_name="휴식 시간(초)")
    cue_overrides = models.JSONField(blank=True, null=True, verbose_name="큐 오버라이드")

    class Meta:
        db_table = 'playlist_items'
        verbose_name = '플레이리스트 항목'
        verbose_name_plural = '플레이리스트 항목 목록'
        ordering = ['sequence_no']
        unique_together = [('playlist', 'sequence_no')]

    def __str__(self):
        return f"{self.playlist.title} - {self.sequence_no}. {self.exercise.exercise_name}"



