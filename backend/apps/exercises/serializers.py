"""
운동 관련 데이터 시리얼라이저 모듈.

이 모듈은 운동 카테고리, 상세 정보, 루틴(Playlist), 세션(ExerciseSession) 등
운동 기능과 관련된 데이터의 변환 및 검증 로직을 담고 있다.
"""
from rest_framework import serializers
from .models import (
    ExerciseCategory, Exercise, ExerciseMedia,
    Playlist, PlaylistItem,
    ExerciseSession, ExerciseSessionItem
)

class ExerciseCategorySerializer(serializers.ModelSerializer):
    """운동 카테고리 정보 시리얼라이저"""
    class Meta:
        model = ExerciseCategory
        fields = ('category_id', 'display_name')

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

class PlaylistItemSerializer(serializers.ModelSerializer):
    """플레이리스트 항목 시리얼라이저"""
    exercise = ExerciseSerializer(read_only=True)
    exercise_id = serializers.UUIDField(write_only=True)

    class Meta:
        model = PlaylistItem
        fields = (
            'playlist_item_id', 'exercise', 'exercise_id',
            'sequence_no', 'set_count', 'reps_count', 
            'duration_sec', 'rest_sec', 'cue_overrides'
        )

class PlaylistSerializer(serializers.ModelSerializer):
    """플레이리스트(루틴) 시리얼라이저"""
    items = PlaylistItemSerializer(many=True, read_only=True)

    class Meta:
        model = Playlist
        fields = (
            'playlist_id', 'mode', 'title', 'status', 'items', 'created_at'
        )
        read_only_fields = ('user', 'created_at')

class ExerciseSessionItemSerializer(serializers.ModelSerializer):
    """운동 세션 항목 시리얼라이저"""
    exercise_name = serializers.CharField(source='exercise.exercise_name', read_only=True)

    class Meta:
        model = ExerciseSessionItem
        fields = (
            'session_item_id', 'exercise', 'exercise_name', 'playlist_item',
            'sequence_no', 'started_at', 'ended_at', 'duration_ms',
            'is_skipped', 'skip_reason', 'rest_sec'
        )

class ExerciseSessionSerializer(serializers.ModelSerializer):
    """운동 세션 시리얼라이저"""
    items = ExerciseSessionItemSerializer(many=True, read_only=True)

    class Meta:
        model = ExerciseSession
        fields = (
            'session_id', 'playlist', 'mode', 
            'started_at', 'ended_at', 'duration_ms', 
            'is_valid', 'abnormal_end_reason', 'items'
        )
        read_only_fields = ('user', 'session_id', 'items')
