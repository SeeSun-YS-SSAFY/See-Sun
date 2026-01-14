import pytest
from django.urls import reverse
from rest_framework.test import APIClient
from django.contrib.auth import get_user_model

User = get_user_model()

@pytest.mark.django_db
def test_update_profile():
    """
    사용자 프로필 업데이트 테스트.
    키, 몸무게, 운동 목적 등 서베이 정보를 업데이트합니다.
    """
    user = User.objects.create_user(username='tester', password='pw')
    client = APIClient()
    client.force_authenticate(user=user)
    
    url = reverse('users:user-detail', kwargs={'pk': user.pk}) 
    # ViewSet의 기본 라우팅은 detail URL이 pk를 포함함.
    # 현재 UserViewSet은 readonly가 아니므로 PATCH 지원해야 함.
    
    data = {
        'height': 175.5,
        'weight': 70.0,
        'exercise_purpose': 'diet'
    }
    
    response = client.patch(url, data)
    
    assert response.status_code == 200
    user.refresh_from_db()
    assert user.height == 175.5
    assert user.weight == 70.0
    assert user.exercise_purpose == 'diet'
