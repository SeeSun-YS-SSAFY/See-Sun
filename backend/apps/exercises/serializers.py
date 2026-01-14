"""
운동 관련 데이터 시리얼라이저 모듈.

이 모듈은 운동 카테고리, 상세 정보, 기록 생성 등 운동 기능과 관련된
모든 데이터의 변환 및 검증 로직을 담고 있다.

Classes:
    ExerciseCategorySerializer: 카테고리 정보 직렬화
    ExerciseStepSerializer: 운동 단계 정보 직렬화
    ExerciseSerializer: 운동 상세 정보 (카테고리, 단계 포함) 직렬화
    ExerciseLogSerializer: 운동 기록 생성 및 조회용 직렬화
"""
from rest_framework import serializers
from .models import Exercise, ExerciseCategory, ExerciseStep, ExerciseLog

class ExerciseCategorySerializer(serializers.ModelSerializer):
    """
    운동 카테고리 정보를 변환하는 시리얼라이저.

    Attributes:
        code: 카테고리 코드
        name: 카테고리 이름
    """
    class Meta:
        model = ExerciseCategory
        fields = ('code', 'name')

class ExerciseStepSerializer(serializers.ModelSerializer):
    """
    운동의 단계별 상세 정보를 변환하는 시리얼라이저.

    Attributes:
        sequence: 단계 순서
        script: 안내 스크립트
        type: 가이드 타입
    """
    class Meta:
        model = ExerciseStep
        fields = ('sequence', 'script', 'type')

class ExerciseSerializer(serializers.ModelSerializer):
    """
    운동의 전체 상세 정보를 변환하는 시리얼라이저.

    기본 운동 정보 외에 카테고리 코드와 하위 단계 들을 포함하여 반환한다.

    Attributes:
        category_code: 소속 카테고리의 코드
        steps: 운동 단계 목록 (ExerciseStepSerializer)
    """
    category_code = serializers.CharField(source='category.code', read_only=True)
    steps = ExerciseStepSerializer(many=True, read_only=True)

    class Meta:
        model = Exercise
        fields = (
            'id', 'name', 'difficulty', 'description', 
            'category_code', 'steps'
        )

class ExerciseLogSerializer(serializers.ModelSerializer):
    """
    운동 기록 데이터를 변환하는 시리얼라이저.

    기록 생성 시에는 사용자 정보를 request context에서 가져와 자동 할당하며,
    조회 시에는 운동명(exercise_name)을 포함하여 반환한다.

    Attributes:
        exercise_name: 운동의 이름
        created_at: 기록 생성 일시 (읽기 전용)

    Methods:
        create: 운동 기록 생성
    """
    exercise_name = serializers.CharField(source='exercise.name', read_only=True)
    
    class Meta:
        model = ExerciseLog
        fields = (
            'id', 'exercise', 'exercise_name',
            'start_time', 'end_time', 'duration', 'count', 'created_at'
        )
        read_only_fields = ('user', 'created_at')

    def create(self, validated_data):
        """
        운동 기록을 생성한다.

        요청을 보낸 사용자(User)를 기록의 소유자로 할당한다.

        Args:
            validated_data (dict): 유효성 검증을 통과한 데이터

        Returns:
            ExerciseLog: 생성된 운동 기록 인스턴스
        """
        user = self.context['request'].user
        return ExerciseLog.objects.create(user=user, **validated_data)
