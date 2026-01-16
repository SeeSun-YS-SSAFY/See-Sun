"""
회원가입 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model

User = get_user_model()


class SignupViewTests(TestCase):
    """회원가입 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.signup_url = '/api/v1/users/auth/signup'
    
    def test_signup_success(self):
        """회원가입 성공 테스트"""
        data = {
            'name': '홍길동',
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertIn('user_id', response.data)
        self.assertIn('phone_number_masked', response.data)
        self.assertEqual(response.data['name'], '홍길동')
        self.assertEqual(response.data['phone_number_masked'], '010-****-5678')
        
        # DB에 사용자 생성 확인
        self.assertTrue(User.objects.filter(phone_number='01012345678').exists())
    
    def test_signup_duplicate_phone(self):
        """중복 전화번호 테스트"""
        # 첫 번째 회원가입
        User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='테스트'
        )
        
        # 중복 전화번호로 회원가입 시도
        data = {
            'name': '홍길동',
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
        self.assertIn('phone_number', response.data['errors'])
    
    def test_signup_invalid_phone(self):
        """잘못된 전화번호 형식 테스트"""
        data = {
            'name': '홍길동',
            'phone_number': '010123',  # 11자리 미만
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_signup_invalid_pin(self):
        """잘못된 PIN 번호 테스트"""
        data = {
            'name': '홍길동',
            'phone_number': '01012345678',
            'pin_number': '12'  # 4자리 미만
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_signup_pin_not_digit(self):
        """PIN 번호 숫자 아닌 경우 테스트"""
        data = {
            'name': '홍길동',
            'phone_number': '01012345678',
            'pin_number': 'abcd'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_signup_missing_fields(self):
        """필수 필드 누락 테스트"""
        data = {
            'name': '홍길동'
            # phone_number, pin_number 누락
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
    
    def test_signup_phone_with_hyphen(self):
        """하이픈 포함 전화번호 테스트 (자동 제거)"""
        data = {
            'name': '홍길동',
            'phone_number': '010-1234-5678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        
        # DB에 하이픈 없이 저장되었는지 확인
        user = User.objects.get(phone_number='01012345678')
        self.assertEqual(user.phone_number, '01012345678')
