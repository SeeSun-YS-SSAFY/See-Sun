import pytest
from rest_framework.test import APIClient
from django.urls import reverse
from django.utils import timezone
from apps.exercises.models import Exercise, ExerciseCategory
from django.contrib.auth import get_user_model

User = get_user_model()

@pytest.mark.django_db
def test_create_exercise_log():
    """
    운동 로그 생성 테스트.
    운동 세션 완료 후 로그 데이터(시작/종료/소요시간/횟수) 저장을 검증합니다.
    """
    # 데이터 셋업
    user = User.objects.create_user(username='logger', password='pw')
    cat = ExerciseCategory.objects.create(name="Str", code="STR")
    exercise = Exercise.objects.create(category=cat, name="Squat", difficulty="Hard")
    
    client = APIClient()
    client.force_authenticate(user=user)
    
    url = reverse('exercises:log-list') # 예상 URL
    
    start_time = timezone.now()
    end_time = start_time + timezone.timedelta(minutes=10)
    
    data = {
        'exercise': exercise.id,
        'start_time': start_time,
        'end_time': end_time,
        'duration': 600, # 10분 = 600초
        'count': 30
    }
    
    response = client.post(url, data)
    
    assert response.status_code == 201
    assert response.data['count'] == 30
    assert response.data['duration'] == 600
    assert response.data['exercise_name'] == "Squat" # 응답에 운동명 포함 여부 확인
