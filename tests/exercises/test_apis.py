import pytest
from rest_framework.test import APIClient
from django.urls import reverse
from apps.exercises.models import ExerciseCategory, Exercise

@pytest.mark.django_db
def test_exercise_list_filtering():
    """
    운동 목록 조회 및 필터링 테스트.
    난이도 및 카테고리로 필터링이 되는지 검증합니다.
    """
    # 데이터 셋업
    cat1 = ExerciseCategory.objects.create(name="Str", code="STR")
    cat2 = ExerciseCategory.objects.create(name="Cardio", code="CRD")
    
    Exercise.objects.create(category=cat1, name="Squat", difficulty="Hard")
    Exercise.objects.create(category=cat1, name="Lunge", difficulty="Medium")
    Exercise.objects.create(category=cat2, name="Run", difficulty="Easy")
    
    client = APIClient()
    url = reverse('exercises:exercise-list') # 예상 URL

    # 1. 전체 조회
    response = client.get(url)
    assert response.status_code == 200
    assert len(response.data) == 3
    
    # 2. 카테고리 필터링
    response = client.get(url, {'category': 'STR'})
    assert len(response.data) == 2
    assert response.data[0]['category_code'] == 'STR'
    
    # 3. 난이도 필터링
    response = client.get(url, {'difficulty': 'Easy'})
    assert len(response.data) == 1
    assert response.data[0]['name'] == 'Run'
