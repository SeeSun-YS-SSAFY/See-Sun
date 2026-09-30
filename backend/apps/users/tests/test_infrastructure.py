from unittest.mock import patch, Mock
from django.test import TestCase
from django.conf import settings
from apps.users.infrastructure import GoogleAuthProvider
from apps.users.dtos import SocialUserDTO

class GoogleAuthProviderTests(TestCase):
    """
    GoogleAuthProvider 인프라 계층 테스트
    """
    
    def setUp(self):
        self.provider = GoogleAuthProvider()
        
    @patch('requests.post')
    def test_exchange_code_for_token_success(self, mock_post):
        """
        Authorization Code 교환 성공 테스트
        """
        # Arrange
        mock_response = Mock()
        mock_response.json.return_value = {'access_token': 'dummy_access_token'}
        mock_response.ok = True
        mock_post.return_value = mock_response
        
        # Act
        token = self.provider.exchange_code_for_token('dummy_code')
        
        # Assert
        self.assertEqual(token, 'dummy_access_token')
        mock_post.assert_called_once()
        
    @patch('requests.get')
    def test_get_user_info_success(self, mock_get):
        """
        사용자 정보 조회 성공 테스트
        """
        # Arrange
        mock_response = Mock()
        mock_response.json.return_value = {
            'sub': '12345',
            'email': 'test@example.com',
            'name': 'Test User'
        }
        mock_response.ok = True
        mock_get.return_value = mock_response
        
        # Act
        user_dto = self.provider.get_user_info('dummy_access_token')
        
        # Assert
        self.assertIsInstance(user_dto, SocialUserDTO)
        self.assertEqual(user_dto.email, 'test@example.com')
        self.assertEqual(user_dto.provider_subject, '12345')
        
