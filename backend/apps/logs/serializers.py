from rest_framework import serializers
from .models import ExerciseSession, ExerciseSessionItem
from apps.exercises.models import Exercise, Playlist, PlaylistItem

# -------------------------------------------------------------------------
# Session Start/End Serializers (New Feature)
# -------------------------------------------------------------------------

class ExerciseSessionStartSerializer(serializers.ModelSerializer):
    """운동 세션 시작 요청 시리얼라이저"""
    playlist_id = serializers.UUIDField(required=False, write_only=True)
    # 단일 운동 세션에서 프론트가 함께 보냄(현재 모델에는 저장하지 않음)
    exercise_id = serializers.UUIDField(required=False, write_only=True)

    class Meta:
        model = ExerciseSession
        fields = ('exercise_name', 'playlist_id', 'exercise_id')
        extra_kwargs = {
            'exercise_name': {'required': False} # playlist_id가 있으면 이름 자동 설정 가능
        }

    def validate(self, attrs):
        attrs.pop('exercise_id', None)
        # 본인 플레이리스트만 연결. 기본 루틴(카테고리) ID 등 플레이리스트가 아닌 값이 오면
        # 운동 이름이 있을 때는 세션을 막지 않고 플레이리스트 연결만 생략
        playlist_id = attrs.pop('playlist_id', None)
        if playlist_id:
            user = self.context['request'].user
            playlist = Playlist.objects.filter(playlist_id=playlist_id, user=user).first()
            if playlist:
                attrs['playlist'] = playlist
                if not attrs.get('exercise_name'):
                    attrs['exercise_name'] = playlist.title
            elif not attrs.get('exercise_name'):
                raise serializers.ValidationError({"playlist_id": "존재하지 않는 플레이리스트입니다."})

        if not attrs.get('exercise_name') and not attrs.get('playlist'):
             raise serializers.ValidationError("exercise_name 또는 playlist_id 중 하나는 필수입니다.")

        return attrs

    def create(self, validated_data):
        user = self.context['request'].user
        return ExerciseSession.objects.create(
            user=user, 
            status='IN_PROGRESS', 
            mode='MANUAL', # 기본값, 필요 시 확장
            **validated_data
        )

class ExerciseSessionEndSerializer(serializers.Serializer):
    """운동 세션 종료 요청 시리얼라이저 (입력값 없음)"""
    pass

# -------------------------------------------------------------------------
# Existing Logic Ported from Exercises App
# -------------------------------------------------------------------------

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
    """운동 세션 시리얼라이저 (조회용)"""
    segments = ExerciseSessionItemSerializer(many=True, read_only=True, source='items')

    class Meta:
        model = ExerciseSession
        fields = (
            'session_id', 'user', 'playlist', 'mode', 'exercise_name',
            'started_at', 'ended_at', 'duration_ms', 'duration', 'duration_seconds',
            'is_valid', 'status', 'abnormal_end_reason', 'segments'
        )
        read_only_fields = ('user', 'session_id', 'items', 'created_at')
