from django.urls import reverse
from rest_framework.test import APITestCase
from rest_framework import status
from unittest.mock import patch
from apps.users.dtos import AuthTokensDTO, UserDetailDTO

class GoogleLoginViewTests(APITestCase):
    """
    GoogleLoginView 통합 테스트
    """
    
    def setUp(self):
        self.url = reverse('users:google_login')
        
    @patch('apps.users.views.Container.get_social_login_service')
    def test_post_success(self, mock_get_service):
        """
        Google 로그인 API 성공 테스트
        """
        # Arrange
        mock_service = mock_get_service.return_value
        user_detail = UserDetailDTO(
            user_id='uuid_123',
            display_name='Test User',
            email='test@example.com',
            profile_completed=False
        )
        expected_tokens = AuthTokensDTO(
            access_token='access_123',
            refresh_token='refresh_123',
            user=user_detail,
            expires_in=900,
            is_new_user=True
        )
        mock_service.execute.return_value = expected_tokens
        
        data = {
            'code': 'dummy_code',
            'device_hash': 'hash_123',
            'redirect_uri': 'http://localhost:8000'
        }
        
        # Act
        response = self.client.post(self.url, data, format='json')
        
        # Assert
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data['access_token'], 'access_123')
        self.assertEqual(response.data['is_new_user'], True)
        self.assertEqual(response.data['user']['email'], 'test@example.com') # Nested Check
        
        mock_service.execute.assert_called_with(
            provider_name='GOOGLE',
            code='dummy_code',
            device_hash='hash_123',
            redirect_uri='http://localhost:8000'
        )

    def test_post_missing_code(self):
        """
        필수 파라미터(code) 누락 시 400 에러 테스트
        """
        data = {}
        response = self.client.post(self.url, data, format='json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
