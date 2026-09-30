"""
이름 공백 검증 테스트 추가
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model

User = get_user_model()


class SignupNameValidationTests(TestCase):
    """이름 공백 검증 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.signup_url = '/api/v1/users/auth/signup'
    
    def test_signup_empty_name(self):
        """이름이 빈 문자열인 경우 테스트"""
        data = {
            'name': '',
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
        self.assertIn('name', response.data['errors'])
    
    def test_signup_whitespace_name(self):
        """이름이 공백만 있는 경우 테스트"""
        data = {
            'name': '   ',
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('errors', response.data)
        self.assertIn('name', response.data['errors'])
    
    def test_signup_name_with_spaces(self):
        """이름 앞뒤 공백 제거 테스트"""
        data = {
            'name': '  홍길동  ',
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post(self.signup_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        
        # DB에 공백 제거된 이름으로 저장되었는지 확인
        user = User.objects.get(phone_number='01012345678')
        self.assertEqual(user.name, '홍길동')
