"""
프로필 API 테스트
"""
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from datetime import date

User = get_user_model()


class UserProfileViewTests(TestCase):
    """프로필 조회 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.profile_url = '/api/v1/users/profile/'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234'),
            birthdate=date(1990, 1, 1),
            gender='M',
            height_cm=175,
            weight_kg=70
        )
    
    def test_profile_view_success(self):
        """프로필 조회 성공 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        response = self.client.get(self.profile_url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data['name'], '홍길동')
        self.assertEqual(response.data['phone_number'], '01012345678')
        self.assertEqual(response.data['height_cm'], 175)
        self.assertEqual(response.data['weight_kg'], 70)
        self.assertEqual(response.data['gender'], 'M')
    
    def test_profile_view_unauthenticated(self):
        """인증되지 않은 사용자 테스트"""
        response = self.client.get(self.profile_url)
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)


class UserProfileCompletionViewTests(TestCase):
    """프로필 완성 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.completion_url = '/api/v1/users/profile/completion/'
        
        # 프로필 미완성 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234')
        )
    
    def test_profile_completion_success(self):
        """프로필 완성 성공 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'birthdate': '1990-01-01',
            'gender': 'M',
            'height_cm': 175,
            'weight_kg': 70
        }
        
        response = self.client.put(self.completion_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        # DB에서 사용자 정보 재조회
        self.test_user.refresh_from_db()
        self.assertEqual(self.test_user.height_cm, 175)
        self.assertEqual(self.test_user.weight_kg, 70)
        self.assertEqual(self.test_user.gender, 'M')
        # is_profile_completed는 UserProfileCompletionSerializer의 update에서 설정됨
    
    def test_profile_completion_missing_fields(self):
        """필수 필드 누락 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'height_cm': 175,
            'weight_kg': 70
            # gender, birthdate 누락
        }
        
        response = self.client.put(self.completion_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('gender', response.data)
        self.assertIn('birthdate', response.data)
    
    def test_profile_completion_invalid_gender(self):
        """잘못된 성별 값 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'birthdate': '1990-01-01',
            'gender': 'X',  # 잘못된 값
            'height_cm': 175,
            'weight_kg': 70
        }
        
        response = self.client.put(self.completion_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
    
    def test_profile_completion_unauthenticated(self):
        """인증되지 않은 사용자 테스트"""
        data = {
            'birthdate': '1990-01-01',
            'gender': 'M',
            'height_cm': 175,
            'weight_kg': 70
        }
        
        response = self.client.put(self.completion_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)


class UserProfileUpdateViewTests(TestCase):
    """프로필 수정 API 테스트"""
    
    def setUp(self):
        """테스트 초기화"""
        self.client = APIClient()
        self.update_url = '/api/v1/users/profile/edit/'
        
        # 테스트용 사용자 생성
        self.test_user = User.objects.create(
            username='01012345678',
            phone_number='01012345678',
            name='홍길동',
            pin_hash=make_password('1234'),
            birthdate=date(1990, 1, 1),
            gender='M',
            height_cm=175,
            weight_kg=70
        )
    
    def test_profile_update_partial_success(self):
        """부분 수정 성공 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'height_cm': 180,
            'weight_kg': 75
        }
        
        response = self.client.patch(self.update_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        # DB에서 사용자 정보 재조회
        self.test_user.refresh_from_db()
        self.assertEqual(self.test_user.height_cm, 180)
        self.assertEqual(self.test_user.weight_kg, 75)
        # 다른 필드는 변경되지 않음
        self.assertEqual(self.test_user.name, '홍길동')
        self.assertEqual(self.test_user.gender, 'M')
    
    def test_profile_update_name(self):
        """이름 수정 테스트"""
        self.client.force_authenticate(user=self.test_user)
        
        data = {
            'name': '김철수'
        }
        
        response = self.client.patch(self.update_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        self.test_user.refresh_from_db()
        self.assertEqual(self.test_user.name, '김철수')
    
    def test_profile_update_unauthenticated(self):
        """인증되지 않은 사용자 테스트"""
        data = {
            'height_cm': 180
        }
        
        response = self.client.patch(self.update_url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
