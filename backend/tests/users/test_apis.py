import pytest
from django.urls import reverse
from rest_framework.test import APIClient
from django.contrib.auth import get_user_model

User = get_user_model()

@pytest.mark.django_db
def test_signup_api():
    """
    회원가입 API 테스트.
    필수 필드(이메일, 비밀번호, 사용자명)로 가입 요청 시 201 응답과 유저 생성을 검증.
    """
    client = APIClient()
    url = reverse('users:signup')  # 예상 URL 네임스페이스
    data = {
        'username': 'newuser',
        'password': 'password123',
        'email': 'new@example.com'
    }
    
    response = client.post(url, data)
    
    assert response.status_code == 201
    assert User.objects.filter(username='newuser').exists()
    assert 'access' in response.data  # JWT 토큰 반환 여부 (선택적)

@pytest.mark.django_db
def test_signup_missing_fields():
    client = APIClient()
    url = reverse('users:signup')
    data = {
        'username': 'newuser'
        # password/email missing
    }
    response = client.post(url, data)
    assert response.status_code == 400
