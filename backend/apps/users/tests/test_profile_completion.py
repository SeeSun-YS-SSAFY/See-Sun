"""
프로필 완성 기능 통합 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from datetime import date

User = get_user_model()


class ProfileCompletionFlowTests(TestCase):
    """프로필 완성 플로우 통합 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        
        # 프로필 미완성 사용자 생성
        self.incomplete_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234')
            # birthdate, gender, height_cm, weight_kg 없음
        )
        
        # 프로필 완성 사용자 생성
        self.complete_user = User.objects.create(
            username='01099998888',
            phone_number='01099998888',
            name='김철수',
            pin_hash=make_password('1234'),
            birthdate=date(1990, 1, 1),
            gender='M',
            height_cm=175,
            weight_kg=70,
            is_profile_completed=True
        )
    
    def test_login_incomplete_profile(self):
        """프로필 미완성 사용자 로그인 테스트"""
        data = {
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        
        response = self.client.post('/api/v1/users/auth/login', data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertFalse(response.data['user']['profile_completed'])
        self.assertIn('사용자 데이터 수집', response.data['tts_message'])
        
        # DB 필드도 업데이트되었는지 확인
        self.incomplete_user.refresh_from_db()
        self.assertFalse(self.incomplete_user.is_profile_completed)
    
    def test_login_complete_profile(self):
        """프로필 완성 사용자 로그인 테스트"""
        data = {
            'phone_number': '01099998888',
            'pin_number': '1234'
        }
        
        response = self.client.post('/api/v1/users/auth/login', data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertTrue(response.data['user']['profile_completed'])
        self.assertIn('메인 페이지로 이동', response.data['tts_message'])
    
    def test_complete_profile_flow(self):
        """프로필 완성 플로우 전체 테스트"""
        # 1. 로그인 - 프로필 미완성 상태
        login_data = {
            'phone_number': '01012345678',
            'pin_number': '1234'
        }
        login_response = self.client.post('/api/v1/users/auth/login', login_data, format='json')
        self.assertFalse(login_response.data['user']['profile_completed'])
        
        # 2. 프로필 완성 API 호출
        self.client.force_authenticate(user=self.incomplete_user)
        
        completion_data = {
            'birthdate': '1990-01-01',
            'gender': 'M',
            'height_cm': 175,
            'weight_kg': 70
        }
        
        completion_response = self.client.put(
            '/api/v1/users/profile/completion/',
            completion_data,
            format='json'
        )
        
        self.assertEqual(completion_response.status_code, status.HTTP_200_OK)
        self.assertEqual(completion_response.data['message'], '프로필이 완성되었습니다.')
        self.assertTrue(completion_response.data['profile_completed'])
        self.assertIn('메인 화면으로 이동', completion_response.data['tts_message'])
        
        # 3. DB 확인
        self.incomplete_user.refresh_from_db()
        self.assertTrue(self.incomplete_user.is_profile_completed)
        self.assertEqual(self.incomplete_user.height_cm, 175)
        self.assertEqual(self.incomplete_user.weight_kg, 70)
        self.assertEqual(self.incomplete_user.gender, 'M')
        
        # 4. 재로그인 시 profile_completed = True
        login_response2 = self.client.post('/api/v1/users/auth/login', login_data, format='json')
        self.assertTrue(login_response2.data['user']['profile_completed'])
        self.assertIn('메인 페이지로 이동', login_response2.data['tts_message'])
    
    def test_profile_completion_validation_error(self):
        """프로필 완성 시 필수 필드 누락 테스트"""
        self.client.force_authenticate(user=self.incomplete_user)
        
        # 필수 필드 누락
        incomplete_data = {
            'height_cm': 175,
            'weight_kg': 70
            # gender, birthdate 누락
        }
        
        response = self.client.put(
            '/api/v1/users/profile/completion/',
            incomplete_data,
            format='json'
        )
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response.data['error'], 'VALIDATION_ERROR')
        self.assertIn('입력 정보를 확인', response.data['tts_message'])
        self.assertIn('errors', response.data)
