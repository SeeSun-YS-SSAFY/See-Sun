import pytest
from django.contrib.auth import get_user_model

User = get_user_model()

@pytest.mark.django_db
def test_create_user():
    """
    User 모델 생성 테스트.
    기본적인 회원 생성이 가능한지 검증.
    """
    user = User.objects.create_user(
        username='testuser',
        password='password123',
        email='test@example.com'
    )
    assert user.username == 'testuser'
    assert user.email == 'test@example.com'
    assert user.check_password('password123')
    assert user.is_active is True
