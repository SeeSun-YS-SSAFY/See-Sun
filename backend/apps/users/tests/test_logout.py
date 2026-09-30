"""
로그아웃 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from rest_framework_simplejwt.tokens import RefreshToken

User = get_user_model()


class LogoutViewTests(TestCase):
    """로그아웃 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.logout_url = '/api/v1/users/auth/logout'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234')
        )
        
        # Refresh Token 생성
        self.refresh = RefreshToken.for_user(self.test_user)
        self.access_token = str(self.refresh.access_token)
        self.refresh_token = str(self.refresh)
    
    def test_logout_success(self):
        """로그아웃 성공 테스트"""
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.access_token}')
        
        data = {
            'refresh_token': self.refresh_token
        }
        
        response = self.client.post(self.logout_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data['message'], '로그아웃이 완료되었습니다.')
        self.assertIn('다시 로그인', response.data['tts_message'])
    
    def test_logout_unauthenticated(self):
        """인증되지 않은 사용자 테스트"""
        data = {
            'refresh_token': self.refresh_token
        }
        
        response = self.client.post(self.logout_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
    
    def test_logout_invalid_token(self):
        """잘못된 Refresh Token 테스트"""
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.access_token}')
        
        data = {
            'refresh_token': 'invalid_token_string'
        }
        
        response = self.client.post(self.logout_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response.data['error'], 'INVALID_TOKEN')
    
    def test_logout_missing_token(self):
        """Refresh Token 누락 테스트"""
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.access_token}')
        
        data = {}
        
        response = self.client.post(self.logout_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_logout_blacklisted_token_reuse(self):
        """블랙리스트 등록 후 토큰 재사용 불가 테스트"""
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.access_token}')
        
        # 첫 번째 로그아웃
        data = {
            'refresh_token': self.refresh_token
        }
        response1 = self.client.post(self.logout_url, data, format='json')
        self.assertEqual(response1.status_code, status.HTTP_200_OK)
        
        # 같은 토큰으로 다시 로그아웃 시도
        response2 = self.client.post(self.logout_url, data, format='json')
        self.assertEqual(response2.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response2.data['error'], 'INVALID_TOKEN')
    
    def test_logout_empty_token(self):
        """빈 Refresh Token 테스트"""
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.access_token}')
        
        data = {
            'refresh_token': ''
        }
        
        response = self.client.post(self.logout_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
