"""
로그인 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
import jwt
from django.conf import settings

User = get_user_model()


class LoginViewTests(TestCase):
    """로그인 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.login_url = '/api/v1/users/auth/login'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234'),
            birthdate='1990-01-01',
            gender='M',
            height_cm=175,
            weight_kg=70
        )
        
        # 프로필 미완성 사용자
        self.incomplete_user = User.objects.create(
            username='01087654321',
            phone_number='01087654321',
            name='김철수',
            pin_hash=make_password('5678')
        )
    
    def test_login_success_profile_completed(self):
        """로그인 성공 (프로필 완성) 테스트"""
        data = {
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('access_token', response.data)
        self.assertIn('refresh_token', response.data)
        self.assertEqual(response.data['expires_in'], 900)
        self.assertEqual(response.data['user']['user_id'], str(self.test_user.id))
        self.assertEqual(response.data['user']['display_name'], '홍길동')
        self.assertTrue(response.data['user']['profile_completed'])
        self.assertIn('메인 페이지', response.data['tts_message'])
    
    def test_login_success_profile_incomplete(self):
        """로그인 성공 (프로필 미완성) 테스트"""
        data = {
            'phone_number': '01087654321',
            'pin_number': '5678'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertFalse(response.data['user']['profile_completed'])
        self.assertIn('데이터 수집', response.data['tts_message'])
    
    def test_login_invalid_phone(self):
        """존재하지 않는 전화번호 테스트"""
        data = {
            'phone_number': '01099999999',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertEqual(response.data['error'], 'INVALID_CREDENTIALS')
        self.assertIn('실패', response.data['tts_message'])
    
    def test_login_invalid_pin(self):
        """잘못된 PIN 번호 테스트"""
        data = {
            'phone_number': '01012345678',
            'pin_number': '9999'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertEqual(response.data['error'], 'INVALID_CREDENTIALS')
    
    def test_login_missing_fields(self):
        """필수 필드 누락 테스트"""
        data = {
            'phone_number': '01012345678'
            # pin_number 누락
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_login_phone_with_hyphen(self):
        """하이픈 포함 전화번호 테스트 (자동 제거)"""
        data = {
            'phone_number': '010-1234-5678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
    
    def test_jwt_token_validity(self):
        """JWT 토큰 유효성 테스트"""
        data = {
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        access_token = response.data['access_token']
        
        # JWT 토큰 디코딩 (검증)
        try:
            # simplejwt는 HS256 알고리즘 사용
            decoded = jwt.decode(
                access_token,
                settings.SECRET_KEY,
                algorithms=['HS256']
            )
            self.assertEqual(decoded['user_id'], str(self.test_user.id))
        except jwt.InvalidTokenError:
            self.fail('Invalid JWT token')
    
    def test_login_with_device_hash(self):
        """device_hash 포함 로그인 테스트"""
        data = {
            'phone_number': '01012345678',
            'pin_number': '1234',
            'device_hash': 'test_device_hash_123'
        }
        
        response = self.client.post(self.login_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
