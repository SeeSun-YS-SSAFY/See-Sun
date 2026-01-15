from django.test import TestCase
from django.contrib.auth import get_user_model
from apps.users.repositories import DjangoUserRepository
from apps.users.dtos import SocialUserDTO, AuthTokensDTO
from apps.users.models import UserAuthProvider

User = get_user_model()

class DjangoUserRepositoryTests(TestCase):
    """
    DjangoUserRepository 리포지토리 계층 테스트
    """
    
    def setUp(self):
        self.repository = DjangoUserRepository()
        
    def test_create_social_user(self):
        """
        소셜 유저 생성 테스트
        """
        # Arrange
        dto = SocialUserDTO(
            email='newuser@example.com',
            name='New User',
            provider='GOOGLE',
            provider_subject='unique_sub_123'
        )
        
        # Act
        user = self.repository.create_social_user(dto)
        
        # Assert
        self.assertTrue(User.objects.filter(email='newuser@example.com').exists())
        self.assertTrue(UserAuthProvider.objects.filter(provider_subject='unique_sub_123').exists())
        self.assertEqual(user.username, 'newuser@example.com') # username은 email로 설정됨
        
    def test_get_by_social_id_found(self):
        """
        존재하는 소셜 유저 조회 테스트
        """
        # Arrange
        user = User.objects.create(username='existing@example.com', email='existing@example.com')
        UserAuthProvider.objects.create(
            user=user,
            provider='GOOGLE',
            provider_subject='existing_sub_456'
        )
        
        # Act
        found_user = self.repository.get_by_social_id('GOOGLE', 'existing_sub_456')
        
        # Assert
        self.assertIsNotNone(found_user)
        self.assertEqual(found_user, user)
        
    def test_get_by_social_id_not_found(self):
        """
        존재하지 않는 소셜 유저 조회 실패 테스트
        """
        # Act
        found_user = self.repository.get_by_social_id('GOOGLE', 'unknown_sub')
        
        # Assert
        self.assertIsNone(found_user)

    def test_generate_tokens(self):
        """
        토큰 생성 테스트
        """
        # Arrange
        user = User.objects.create(username='tokenuser@example.com', email='tokenuser@example.com')
        
        # Act
        tokens = self.repository.generate_tokens(user)
        
        # Assert
        self.assertIsInstance(tokens, AuthTokensDTO)
        self.assertIsNotNone(tokens.access_token)
        self.assertIsNotNone(tokens.refresh_token)
