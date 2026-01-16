import pytest
from apps.exercises.models import ExerciseCategory, Exercise, ExerciseStep

@pytest.mark.django_db
def test_create_exercise_structure():
    """
    운동 모델 구조 테스트.
    카테고리 -> 운동 -> 단계(Step)로 이어지는 관계를 검증합니다.
    """
    # 1. 카테고리 생성
    category = ExerciseCategory.objects.create(
        name="Strengthening",
        code="STR"
    )
    
    # 2. 운동 생성
    exercise = Exercise.objects.create(
        category=category,
        name="Squat",
        difficulty="Easy",
        description="Basic leg exercise"
    )
    
    # 3. 단계 생성
    step1 = ExerciseStep.objects.create(
        exercise=exercise,
        sequence=1,
        script="Stand with feet shoulder-width apart.",
        type="TTS"
    )
    step2 = ExerciseStep.objects.create(
        exercise=exercise,
        sequence=2,
        script="Lower your hips back and down.",
        type="TTS"
    )
    
    assert exercise.category == category
    assert exercise.steps.count() == 2
    assert step1.exercise == exercise
