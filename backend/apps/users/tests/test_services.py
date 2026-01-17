from unittest.mock import Mock
from django.test import TestCase
from apps.users.services import SocialLoginUseCase
from apps.users.dtos import SocialUserDTO, AuthTokensDTO, UserDetailDTO
from apps.users.interfaces import ISocialAuthProvider, IUserRepository

class SocialLoginUseCaseTests(TestCase):
    """
    SocialLoginUseCase 서비스 계층 테스트
    """
    
    def setUp(self):
        self.mock_auth_provider = Mock(spec=ISocialAuthProvider)
        self.mock_repo = Mock(spec=IUserRepository)
        self.service = SocialLoginUseCase(self.mock_auth_provider, self.mock_repo)
        
    def test_execute_success_new_user(self):
        """
        신규 유저 로그인 성공 시나리오
        """
        # Arrange
        code = "dummy_code"
        device_hash = "device_123"
        access_token = "provider_access_token"
        
        user_dto = SocialUserDTO(
            email="new@example.com",
            name="New User",
            provider="GOOGLE",
            provider_subject="sub_123"
        )
        
        self.mock_auth_provider.exchange_code_for_token.return_value = access_token
        self.mock_auth_provider.get_user_info.return_value = user_dto
        
        self.mock_repo.get_by_social_id.return_value = None # 신규 유저
        mock_user = Mock()
        self.mock_repo.create_social_user.return_value = mock_user
        
        user_detail = UserDetailDTO(user_id='1', display_name='New', email='new@example.com', profile_completed=False)
        expected_tokens = AuthTokensDTO(access_token="jwt_acc", refresh_token="jwt_ref", is_new_user=True, user=user_detail)
        self.mock_repo.generate_tokens.return_value = expected_tokens
        
        # Act
        result = self.service.execute("GOOGLE", code, device_hash)
        
        # Assert
        self.mock_auth_provider.exchange_code_for_token.assert_called_with(code=code, redirect_uri='postmessage')
        self.mock_auth_provider.get_user_info.assert_called_with(access_token)
        self.mock_repo.get_by_social_id.assert_called_with(provider="GOOGLE", provider_subject="sub_123")
        self.mock_repo.create_social_user.assert_called_with(user_dto)
        self.mock_repo.generate_tokens.assert_called_with(mock_user)
        self.assertEqual(result, expected_tokens)
        self.assertTrue(result.is_new_user)
        self.assertEqual(result.user.email, 'new@example.com')

    def test_execute_success_existing_user(self):
        """
        기존 유저 로그인 성공 시나리오
        """
        # Arrange
        code = "dummy_code"
        
        user_dto = SocialUserDTO(
            email="exist@example.com",
            provider="GOOGLE",
            provider_subject="sub_456"
        )
        
        self.mock_auth_provider.exchange_code_for_token.return_value = "token"
        self.mock_auth_provider.get_user_info.return_value = user_dto
        
        mock_existing_user = Mock()
        self.mock_repo.get_by_social_id.return_value = mock_existing_user
        
        user_detail = UserDetailDTO(user_id='2', display_name='Exist', email='exist@example.com', profile_completed=True)
        expected_tokens = AuthTokensDTO(access_token="jwt_acc", refresh_token="jwt_ref", is_new_user=False, user=user_detail)
        self.mock_repo.generate_tokens.return_value = expected_tokens
        
        # Act
        result = self.service.execute("GOOGLE", code)
        
        # Assert
        self.mock_repo.create_social_user.assert_not_called()
        self.assertEqual(result, expected_tokens)
        self.assertFalse(result.is_new_user)
