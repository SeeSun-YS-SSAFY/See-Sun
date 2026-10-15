"""
운동 관련 데이터 시리얼라이저 모듈.

이 모듈은 운동 카테고리, 상세 정보, 루틴(Playlist), 세션(ExerciseSession) 등
운동 기능과 관련된 데이터의 변환 및 검증 로직을 담고 있다.
"""
from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from apps.core.media import media_url
from .models import (
    ExerciseCategory, Exercise, ExerciseMedia,
    Playlist, PlaylistItem
)

# -------------------------------------------------------------------------

class ExerciseCategorySerializer(serializers.ModelSerializer):
    """운동 카테고리 정보 시리얼라이저"""
    class Meta:
        model = ExerciseCategory
        fields = ('category_id', 'display_name')

# -------------------------------------------------------------------------

def guide_audios(exercise) -> list[dict]:
    """운동의 안내 음성 구간 목록 ``[{type, section, url}]`` (sequence 순). prefetch된 media_contents를 쓴다."""
    media = sorted((m for m in exercise.media_contents.all() if m.media_type == 'GUIDE_AUDIO'), key=lambda m: m.sequence)
    return [{'type': m.section_name, 'section': m.section_name, 'url': _versioned(m)} for m in media]


def _versioned(media):
    """녹음 주소 + 내용 해시(?v=, 다시 만든 녹음을 같은 경로에 깔아도 앱·CDN 캐시가 새 녹음을 받게)."""
    url = media.public_url
    if url and media.checksum:
        return f"{url}{'&' if '?' in url else '?'}v={media.checksum[:12]}"
    return url

def pictogram_urls(exercise) -> list[str]:
    """픽토그램 URL 목록(sequence 순)"""
    media = sorted((m for m in exercise.media_contents.all() if m.media_type == 'PICTOGRAM'), key=lambda m: m.sequence)
    return [m.public_url for m in media]


class PictogramFrameSerializer(serializers.Serializer):
    """
    픽토그램 한 장과 대본 위치. section·sentence_index는 이 그림이 보이기 시작하는 대본 위치(앱 문장 나누기 기준),
    label은 그림 이름(낭독용). 연결이 없으면 null(앱은 1초마다 모든 그림을 돌림).
    display_url은 여백·인쇄 글자를 잘라 그림을 크게 한 보기용 사본(원본은 url), redrawn은 대본에 맞게 다시 그린 그림(의뢰인 확인 필요).
    """
    url = serializers.CharField()
    sequence = serializers.IntegerField()
    section = serializers.CharField(allow_null=True)
    sentence_index = serializers.IntegerField(allow_null=True)
    label = serializers.CharField(allow_null=True)
    needs_client = serializers.BooleanField()
    display_url = serializers.CharField(allow_null=True)
    redrawn = serializers.BooleanField()


class PictogramCycleSerializer(serializers.Serializer):
    """반복·유지 구간: section의 start_sentence_index번째 문장부터 sequences 그림을 1초마다 되풀이(kind 반복|유지, 유지 그림 hold_sequence)."""
    section = serializers.CharField()
    sequences = serializers.ListField(child=serializers.IntegerField())
    kind = serializers.CharField(allow_null=True)
    start_sentence_index = serializers.IntegerField()
    hold_sequence = serializers.IntegerField(allow_null=True)


def pictogram_cycle(exercise):
    """운동의 픽토그램 반복 구간(PictogramCycleSerializer 형식) 또는 None."""
    cycle = exercise.pictogram_cycle
    if not cycle:
        return None
    return {
        'section': cycle.get('section_key'),
        'sequences': list(cycle.get('sequences') or []),
        'kind': cycle.get('kind'),
        'start_sentence_index': cycle.get('start_sentence_index') or 0,
        'hold_sequence': cycle.get('hold_sequence'),
    }


def pictogram_frames(exercise) -> list[dict]:
    """픽토그램과 대본 위치 목록(sequence 순, PictogramFrameSerializer 형식). 연결표: apps/exercises/pictogram_map.py"""
    media = sorted((m for m in exercise.media_contents.all() if m.media_type == 'PICTOGRAM'), key=lambda m: m.sequence)
    return [
        {
            'url': m.public_url,
            'sequence': m.sequence,
            'section': m.section or None,
            'sentence_index': m.sentence_index,
            'label': m.label_ko or None,
            'needs_client': m.needs_client,
            # 보기용 사본(여백·인쇄 글자를 잘라 그림을 크게). 없으면 null → 앱은 url(원본)을 쓴다.
            'display_url': media_url(m.display_key) if m.display_key else None,
            # 대본에 맞게 다시 그린 그림(의뢰인 확인 필요)
            'redrawn': m.redrawn,
        }
        for m in media
    ]

# -------------------------------------------------------------------------

class ExerciseMediaSerializer(serializers.ModelSerializer):
    """운동 미디어 정보 시리얼라이저(url은 CDN 기준)"""
    url = serializers.CharField(source='public_url', read_only=True)
    section = serializers.CharField(source='section_name', read_only=True, allow_null=True)

    class Meta:
        model = ExerciseMedia
        fields = ('media_id', 'media_type', 'section', 'url', 'duration_ms')

class ExerciseSerializer(serializers.ModelSerializer):
    """운동 상세 정보 시리얼라이저"""
    category = ExerciseCategorySerializer(read_only=True)
    media_contents = ExerciseMediaSerializer(many=True, read_only=True)

    class Meta:
        model = Exercise
        fields = (
            'exercise_id', 'category', 'exercise_name', 'exercise_description',
            'first_description', 'main_form', 'form_description', 
            'stay_form', 'fixed_form', 'exercise_guide_text',
            'media_contents'
        )

# ----------------------------------------------------------------------------

class ExerciseDetailSerializer(serializers.ModelSerializer):
    """
    운동 상세 정보 시리얼라이저 (픽토그램 및 상세 가이드 포함)
    """
    category_name = serializers.CharField(source='category.display_name', read_only=True)
    exercise_guide = serializers.CharField(source='exercise_guide_text', read_only=True)
    pictograms = serializers.SerializerMethodField()
    pictogram_frames = serializers.SerializerMethodField()
    pictogram_cycle = serializers.SerializerMethodField()
    audios = serializers.SerializerMethodField()
    merged_audio_url = serializers.SerializerMethodField()

    class Meta:
        model = Exercise
        fields = (
            'exercise_id', 'exercise_name', 'category_name',
            'exercise_description',
            'first_description', 'main_form', 'form_description', 
            'stay_form', 'fixed_form',
            'exercise_guide', 'pictograms', 'pictogram_frames', 'pictogram_cycle', 'audios', 'merged_audio_url',
            'script_revised', 'audio_stale'
        )

    def get_pictograms(self, obj) -> list[str]:
        # 픽토그램 이미지 URL 리스트 반환(1차 웹·이전 앱 호환. 대본 위치는 pictogram_frames)
        return pictogram_urls(obj)

    @extend_schema_field(PictogramFrameSerializer(many=True))
    def get_pictogram_frames(self, obj):
        return pictogram_frames(obj)

    @extend_schema_field(PictogramCycleSerializer(allow_null=True))
    def get_pictogram_cycle(self, obj):
        return pictogram_cycle(obj)

    def get_audios(self, obj) -> list[dict]:
        # 2차(Android 문장 싱크): 구간별 안내 음성. 루틴 항목 audios[]와 같은 형식, 재생 순서(sequence)대로
        return guide_audios(obj)

    def get_merged_audio_url(self, obj) -> str:
        # 병합된 오디오 URL 반환
        return f"/api/v1/exercises/{obj.exercise_id}/audio/"

# ----------------------------------------------------------------------------

class ExerciseSimpleSerializer(serializers.ModelSerializer):
    """
    운동 목록 조회용 간략한 정보 시리얼라이저 (픽토그램 포함)
    """
    pictogram_url = serializers.SerializerMethodField()
    name_audio_url = serializers.SerializerMethodField()

    class Meta:
        model = Exercise
        fields = ('exercise_id', 'exercise_name', 'pictogram_url', 'name_audio_url')

    def get_pictogram_url(self, obj) -> str | None:
        # media_contents는 prefetch_related로 가져올 예정
        # media_type='PICTOGRAM'인 첫 번째 미디어의 url 반환
        media = next((m for m in obj.media_contents.all() if m.media_type == 'PICTOGRAM'), None)
        return media.public_url if media else None

    def get_name_audio_url(self, obj) -> str | None:
        # 운동 영문명 기반 음성 파일 URL 반환
        if obj.name_en:
            return media_url(f"prefix/navigation/exercise/{obj.name_en}.mp3")
        return None

# ----------------------------------------------------------------------------

class PlaylistItemSerializer(serializers.ModelSerializer):
    """플레이리스트 항목 시리얼라이저 (Read Only)"""
    exercise_id = serializers.UUIDField(source='exercise.exercise_id', read_only=True)
    exercise_name = serializers.CharField(source='exercise.exercise_name', read_only=True)
    exercise_guide_text = serializers.CharField(source='exercise.exercise_guide_text', read_only=True)
    audios = serializers.SerializerMethodField()
    pictograms = serializers.SerializerMethodField()
    pictogram_frames = serializers.SerializerMethodField()
    pictogram_cycle = serializers.SerializerMethodField()
    # 대본을 고친 운동(의뢰인 확인 필요)·녹음 음성이 대본과 다름(앱은 운동 전체를 기기 음성으로 읽음)
    script_revised = serializers.BooleanField(source='exercise.script_revised', read_only=True)
    audio_stale = serializers.BooleanField(source='exercise.audio_stale', read_only=True)

    class Meta:
        model = PlaylistItem
        fields = (
            'playlist_item_id', 'exercise_id', 'exercise_name', 'exercise_guide_text',
            'audios', 'pictograms', 'pictogram_frames', 'pictogram_cycle', 'script_revised', 'audio_stale',
            'sequence_no', 'set_count', 'reps_count', 
            'duration_sec', 'rest_sec', 'cue_overrides'
        )

    def get_audios(self, obj) -> list[dict]:
        # 해당 운동의 모든 안내 음성 URL(재생 순서대로)
        return guide_audios(obj.exercise)

    def get_pictograms(self, obj) -> list[str]:
        # 해당 운동의 모든 픽토그램 URL 반환
        return pictogram_urls(obj.exercise)

    @extend_schema_field(PictogramFrameSerializer(many=True))
    def get_pictogram_frames(self, obj):
        return pictogram_frames(obj.exercise)

    @extend_schema_field(PictogramCycleSerializer(allow_null=True))
    def get_pictogram_cycle(self, obj):
        return pictogram_cycle(obj.exercise)

# ----------------------------------------------------------------------------

class PlaylistItemCreateSerializer(serializers.ModelSerializer):
    """플레이리스트 생성 시 항목 입력용 시리얼라이저"""
    exercise_id = serializers.UUIDField()

    class Meta:
        model = PlaylistItem
        fields = ('exercise_id', 'sequence_no', 'set_count', 'reps_count')

# ----------------------------------------------------------------------------

class PlaylistCreateSerializer(serializers.ModelSerializer):
    """플레이리스트 생성 시리얼라이저"""
    items = PlaylistItemCreateSerializer(many=True)

    class Meta:
        model = Playlist
        fields = ('playlist_id', 'title', 'items')

    def create(self, validated_data):
        items_data = validated_data.pop('items')
        user = self.context['request'].user
        
        from django.db import transaction
        with transaction.atomic():
            # 1. Playlist 생성
            playlist = Playlist.objects.create(user=user, mode='CUSTOM', status='ACTIVE', **validated_data)
            
            # 2. PlaylistItem 생성
            for item_data in items_data:
                exercise_id = item_data.pop('exercise_id')
                exercise = Exercise.objects.get(exercise_id=exercise_id)
                PlaylistItem.objects.create(playlist=playlist, exercise=exercise, **item_data)
        
        return playlist

# -------------------------------------------------------------------------

class PlaylistUpdateSerializer(serializers.ModelSerializer):
    """플레이리스트 기본 정보(제목) 수정 시리얼라이저"""
    class Meta:
        model = Playlist
        fields = ('title', 'status', 'mode')

# -------------------------------------------------------------------------

class PlaylistItemAddSerializer(serializers.ModelSerializer):
    """플레이리스트에 운동 추가 시리얼라이저"""
    exercise_id = serializers.UUIDField()
    sequence_no = serializers.IntegerField(required=False)

    class Meta:
        model = PlaylistItem
        fields = (
            'exercise_id', 'sequence_no', 'set_count', 'reps_count',
            'duration_sec', 'rest_sec'
        )

    def validate_exercise_id(self, value):
        if not Exercise.objects.filter(exercise_id=value).exists():
            raise serializers.ValidationError("존재하지 않는 운동 ID입니다.")
        return value
        
    def create(self, validated_data):
        exercise_id = validated_data.pop('exercise_id')
        exercise = Exercise.objects.get(exercise_id=exercise_id)
        playlist = self.context['playlist']
        
        # sequence_no가 제공되지 않은 경우, 자동으로 가장 마지막 번호 + 1 할당
        if 'sequence_no' not in validated_data:
            last_item = PlaylistItem.objects.filter(playlist=playlist).order_by('-sequence_no').first()
            validated_data['sequence_no'] = (last_item.sequence_no + 1) if last_item else 1
            
        return PlaylistItem.objects.create(playlist=playlist, exercise=exercise, **validated_data)

# -------------------------------------------------------------------------

class PlaylistItemUpdateSerializer(serializers.ModelSerializer):
    """플레이리스트 항목 상세 수정 시리얼라이저"""
    class Meta:
        model = PlaylistItem
        fields = (
            'sequence_no', 'set_count', 'reps_count',
            'duration_sec', 'rest_sec', 'cue_overrides'
        )

# -------------------------------------------------------------------------

class PlaylistSerializer(serializers.ModelSerializer):
    """플레이리스트(루틴) 시리얼라이저"""
    items = PlaylistItemSerializer(many=True, read_only=True)

    class Meta:
        model = Playlist
        fields = (
            'playlist_id', 'mode', 'title', 'status', 'items', 'created_at'
        )
        read_only_fields = ('user', 'created_at')

# -------------------------------------------------------------------------



# -------------------------------------------------------------------------



# -------------------------------------------------------------------------