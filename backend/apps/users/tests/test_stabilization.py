"""
1차 안정화(보안·결함 수정) 회귀 테스트
- 로그인 연속 실패 잠금, 탈퇴 계정 로그인 차단, 탈퇴 시 개인정보 파기
- 프로필 완성 시 이름·전화번호 저장(B3)
"""
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
from django.test import TestCase, override_settings
from rest_framework import status
from rest_framework.test import APIClient

User = get_user_model()
LOGIN_URL = '/api/v1/users/auth/login/'


def make_user(phone='01012345678', pin='1234', **extra):
    return User.objects.create(
        username=phone, phone_number=phone, name='홍길동', pin_hash=make_password(pin), **extra
    )


@override_settings(LOGIN_MAX_FAILURES=3)
class LoginLockoutTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = make_user()

    def _login(self, pin):
        return self.client.post(LOGIN_URL, {'phone_number': '01012345678', 'pin_number': pin}, format='json')

    def test_연속_실패하면_올바른_PIN도_잠금(self):
        for _ in range(3):
            self.assertEqual(self._login('0000').status_code, status.HTTP_401_UNAUTHORIZED)
        response = self._login('1234')
        self.assertEqual(response.status_code, status.HTTP_429_TOO_MANY_REQUESTS)
        self.assertEqual(response.data['error'], 'TOO_MANY_ATTEMPTS')

    def test_성공하면_실패_횟수_초기화(self):
        self._login('0000')
        self._login('0000')
        self.assertEqual(self._login('1234').status_code, status.HTTP_200_OK)
        self._login('0000')
        self._login('0000')
        self.assertEqual(self._login('1234').status_code, status.HTTP_200_OK)


class WithdrawTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = make_user(birthdate='1990-01-01', gender='M', height_cm=170, weight_kg=60)
        self.client.force_authenticate(self.user)

    def test_탈퇴하면_개인정보를_파기하고_로그인_불가(self):
        response = self.client.delete('/api/v1/users/profile/', {'confirmation': True}, format='json')
        self.assertEqual(response.status_code, status.HTTP_200_OK, response.data)

        self.user.refresh_from_db()
        self.assertFalse(self.user.is_active)
        self.assertTrue(self.user.is_deleted)
        self.assertIsNone(self.user.phone_number)
        self.assertIsNone(self.user.name)
        self.assertIsNone(self.user.birthdate)
        self.assertIsNone(self.user.pin_hash)

        login = APIClient().post(LOGIN_URL, {'phone_number': '01012345678', 'pin_number': '1234'}, format='json')
        self.assertEqual(login.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_탈퇴한_번호로_다시_가입_가능(self):
        self.client.delete('/api/v1/users/profile/', {'confirmation': True}, format='json')
        response = APIClient().post('/api/v1/users/auth/signup/', {
            'name': '홍길동', 'phone_number': '010-1234-5678', 'pin_number': '4321'
        }, format='json')
        self.assertEqual(response.status_code, status.HTTP_201_CREATED, response.data)


class ProfileCompletionPhoneTests(TestCase):
    URL = '/api/v1/users/profile/completion/'
    BODY = {'height_cm': 170, 'weight_kg': 60, 'gender': 'F', 'birthdate': '1995-05-05'}

    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create_user(username='google_user', password='x')
        self.client.force_authenticate(self.user)

    def test_이름과_전화번호를_함께_저장(self):
        response = self.client.put(self.URL, {**self.BODY, 'name': ' 김시선 ', 'phone_number': '010-9876-5432'}, format='json')
        self.assertEqual(response.status_code, status.HTTP_200_OK, response.data)
        self.user.refresh_from_db()
        self.assertEqual(self.user.phone_number, '01098765432')
        self.assertEqual(self.user.name, '김시선')
        self.assertTrue(self.user.is_profile_completed)

    def test_전화번호_없이도_완성_가능(self):
        response = self.client.put(self.URL, self.BODY, format='json')
        self.assertEqual(response.status_code, status.HTTP_200_OK, response.data)

    def test_다른_계정의_번호면_400(self):
        make_user(phone='01011112222')
        response = self.client.put(self.URL, {**self.BODY, 'phone_number': '01011112222'}, format='json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('phone_number', response.data['errors'])
