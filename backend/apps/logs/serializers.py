from rest_framework import serializers
from .models import ExerciseSession, ExerciseSessionItem
from apps.exercises.models import Exercise, Playlist, PlaylistItem

# -------------------------------------------------------------------------
# Session Start/End Serializers (New Feature)
# -------------------------------------------------------------------------

class ExerciseSessionStartSerializer(serializers.ModelSerializer):
    """운동 세션 시작 요청 시리얼라이저"""
    playlist_id = serializers.UUIDField(required=False, write_only=True)
    
    class Meta:
        model = ExerciseSession
        fields = ('exercise_name', 'playlist_id')
        extra_kwargs = {
            'exercise_name': {'required': False} # playlist_id가 있으면 이름 자동 설정 가능
        }

    def validate(self, attrs):
        # 플레이리스트 ID가 있으면 검증
        if 'playlist_id' in attrs:
            try:
                playlist = Playlist.objects.get(playlist_id=attrs['playlist_id'])
                attrs['playlist'] = playlist
                if not attrs.get('exercise_name'):
                    attrs['exercise_name'] = playlist.title
            except Playlist.DoesNotExist:
                raise serializers.ValidationError({"playlist_id": "존재하지 않는 플레이리스트입니다."})
        
        if not attrs.get('exercise_name') and not attrs.get('playlist'):
             raise serializers.ValidationError("exercise_name 또는 playlist_id 중 하나는 필수입니다.")
             
        return attrs

    def create(self, validated_data):
        user = self.context['request'].user
        # playlist_id는 모델 필드가 아니므로 pop
        if 'playlist_id' in validated_data:
            del validated_data['playlist_id']
            
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
