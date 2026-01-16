"""
토큰 갱신 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from rest_framework_simplejwt.tokens import RefreshToken

User = get_user_model()


class TokenRefreshViewTests(TestCase):
    """토큰 갱신 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.refresh_url = '/api/v1/users/auth/token/refresh'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234')
        )
        
        # Refresh Token 생성
        self.refresh = RefreshToken.for_user(self.test_user)
        self.refresh_token = str(self.refresh)
    
    def test_token_refresh_success(self):
        """토큰 갱신 성공 테스트"""
        data = {
            'refresh': self.refresh_token
        }
        
        response = self.client.post(self.refresh_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('access', response.data)
        # ROTATE_REFRESH_TOKENS=True이므로 새 refresh 토큰도 발급됨
        self.assertIn('refresh', response.data)
    
    def test_token_refresh_invalid_token(self):
        """잘못된 Refresh Token 테스트"""
        data = {
            'refresh': 'invalid_token_string'
        }
        
        response = self.client.post(self.refresh_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
    
    def test_token_refresh_missing_token(self):
        """Refresh Token 누락 테스트"""
        data = {}
        
        response = self.client.post(self.refresh_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
    
    def test_token_refresh_blacklisted_token(self):
        """블랙리스트 토큰 테스트"""
        # 토큰을 블랙리스트에 등록
        self.refresh.blacklist()
        
        data = {
            'refresh': self.refresh_token
        }
        
        response = self.client.post(self.refresh_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
    
    def test_token_refresh_new_access_token_valid(self):
        """갱신된 Access Token 유효성 테스트"""
        data = {
            'refresh': self.refresh_token
        }
        
        response = self.client.post(self.refresh_url, data, format='json')
        new_access_token = response.data['access']
        
        # 새 Access Token으로 인증 필요한 API 호출
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {new_access_token}')
        profile_response = self.client.get('/api/v1/users/profile/')
        
        # 인증 성공 (200 또는 404, 프로필이 없을 수 있음)
        self.assertIn(profile_response.status_code, [status.HTTP_200_OK, status.HTTP_404_NOT_FOUND])
