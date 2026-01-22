"""
운동 관련 데이터 시리얼라이저 모듈.

이 모듈은 운동 카테고리, 상세 정보, 루틴(Playlist), 세션(ExerciseSession) 등
운동 기능과 관련된 데이터의 변환 및 검증 로직을 담고 있다.
"""
from rest_framework import serializers
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

class ExerciseMediaSerializer(serializers.ModelSerializer):
    """운동 미디어 정보 시리얼라이저"""
    class Meta:
        model = ExerciseMedia
        fields = ('media_id', 'media_type', 'locale', 'url', 'duration_ms')

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
    audios = serializers.SerializerMethodField()

    class Meta:
        model = Exercise
        fields = (
            'exercise_id', 'exercise_name', 'category_name',
            'exercise_description',
            'first_description', 'main_form', 'form_description', 
            'stay_form', 'fixed_form',
            'exercise_guide', 'pictograms', 'audios'
        )

    def get_pictograms(self, obj):
        # 픽토그램 이미지 URL 리스트 반환
        return [
            m.url for m in obj.media_contents.all() 
            if m.media_type == 'PICTOGRAM'
        ]

    def get_audios(self, obj):
        # TTS 오디오 URL 리스트 반환 (타입 포함)
        # s3_key 필드를 오디오의 세부 타입(예: main_form)으로 사용 중
        return [
            {'type': m.s3_key, 'url': m.url}
            for m in obj.media_contents.all()
            if m.media_type == 'GUIDE_AUDIO'
        ]

# ----------------------------------------------------------------------------

class ExerciseSimpleSerializer(serializers.ModelSerializer):
    """
    운동 목록 조회용 간략한 정보 시리얼라이저 (픽토그램 포함)
    """
    pictogram_url = serializers.SerializerMethodField()

    class Meta:
        model = Exercise
        fields = ('exercise_id', 'exercise_name', 'pictogram_url')

    def get_pictogram_url(self, obj):
        # media_contents는 prefetch_related로 가져올 예정
        # media_type='PICTOGRAM'인 첫 번째 미디어의 url 반환
        media = next((m for m in obj.media_contents.all() if m.media_type == 'PICTOGRAM'), None)
        return media.url if media else None

# ----------------------------------------------------------------------------

class PlaylistItemSerializer(serializers.ModelSerializer):
    """플레이리스트 항목 시리얼라이저 (Read Only)"""
    exercise_id = serializers.UUIDField(source='exercise.exercise_id', read_only=True)
    exercise_name = serializers.CharField(source='exercise.exercise_name', read_only=True)
    exercise_guide_text = serializers.CharField(source='exercise.exercise_guide_text', read_only=True)
    audios = serializers.SerializerMethodField()
    pictograms = serializers.SerializerMethodField()

    class Meta:
        model = PlaylistItem
        fields = (
            'playlist_item_id', 'exercise_id', 'exercise_name', 'exercise_guide_text',
            'audios', 'pictograms',
            'sequence_no', 'set_count', 'reps_count', 
            'duration_sec', 'rest_sec', 'cue_overrides'
        )

    def get_audios(self, obj):
        # 해당 운동의 모든 TTS 오디오 URL 반환
        return [
            {'type': m.s3_key, 'url': m.url}
            for m in obj.exercise.media_contents.all()
            if m.media_type == 'GUIDE_AUDIO'
        ]

    def get_pictograms(self, obj):
        # 해당 운동의 모든 픽토그램 URL 반환
        return [
            m.url for m in obj.exercise.media_contents.all()
            if m.media_type == 'PICTOGRAM'
        ]

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