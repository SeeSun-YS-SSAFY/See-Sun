"""
회원탈퇴 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.token_blacklist.models import OutstandingToken, BlacklistedToken

User = get_user_model()


class UserDeleteViewTests(TestCase):
    """회원탈퇴 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.delete_url = '/api/v1/users/profile/'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234')
        )
        
        # Refresh Token 생성
        self.refresh = RefreshToken.for_user(self.test_user)
    
    def test_user_delete_success(self):
        """회원탈퇴 성공 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'confirmation': True
        }
        
        response = self.client.delete(self.delete_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data['message'], '회원탈퇴가 완료되었습니다.')
        self.assertIn('감사합니다', response.data['tts_message'])
        
        # DB에서 사용자 재조회
        self.test_user.refresh_from_db()
        self.assertTrue(self.test_user.is_deleted)
        self.assertIsNotNone(self.test_user.deleted_at)
        self.assertFalse(self.test_user.is_active)
    
    def test_user_delete_confirmation_false(self):
        """탈퇴 확인 거부 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'confirmation': False
        }
        
        response = self.client.delete(self.delete_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response.data['error'], 'CONFIRMATION_REQUIRED')
    
    def test_user_delete_missing_confirmation(self):
        """탈퇴 확인 누락 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {}
        
        response = self.client.delete(self.delete_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
    
    def test_user_delete_unauthenticated(self):
        """인증되지 않은 사용자 테스트"""
        data = {
            'confirmation': True
        }
        
        response = self.client.delete(self.delete_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
    
    def test_user_delete_tokens_blacklisted(self):
        """탈퇴 후 토큰 블랙리스트 확인 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        # Refresh Token 생성
        refresh = RefreshToken.for_user(self.test_user)
        
        data = {
            'confirmation': True
        }
        
        response = self.client.delete(self.delete_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        # 모든 토큰이 블랙리스트에 등록되었는지 확인
        outstanding_tokens = OutstandingToken.objects.filter(user=self.test_user)
        for token in outstanding_tokens:
            self.assertTrue(
                BlacklistedToken.objects.filter(token=token).exists(),
                f"Token {token.jti} should be blacklisted"
            )
    
    def test_user_delete_cannot_login_after_delete(self):
        """탈퇴 후 로그인 불가 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'confirmation': True
        }
        
        # 회원탈퇴
        response = self.client.delete(self.delete_url, data, format='json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        # 탈퇴 후 사용자 상태 확인
        self.test_user.refresh_from_db()
        self.assertFalse(self.test_user.is_active)
        self.assertTrue(self.test_user.is_deleted)
