
from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase
from django.contrib.auth import get_user_model
from unittest.mock import patch, MagicMock

User = get_user_model()

class GoogleTTSAPITests(APITestCase):
    def setUp(self):
        # 테스트용 유저 생성 및 토큰 발급
        self.user = User.objects.create_user(
            username='ttstester',
            password='password123',
            name='TTS테스터',
            phone_number='01000000000'
        )
        # JWT 토큰을 얻기 위해 로그인하거나 강제로 클라이언트에 인증 헤더 추가
        # 여기서는 force_authenticate 사용
        self.client.force_authenticate(user=self.user)
        self.url = reverse('exercises:google_tts')  # app_name='exercises', name='google_tts'

    @patch('apps.exercises.google_tts.GoogleTTSClient')
    def test_tts_generation_success(self, MockTTSClient):
        """
        정상적인 텍스트 요청 시 오디오 바이너리를 반환해야 한다.
        """
        # Mock 설정
        mock_instance = MockTTSClient.return_value
        # 가짜 MP3 바이너리 데이터
        fake_audio_content = b'\xff\xf3\x44\xc4\x00\x00\x00\x03\x48\x00\x00\x00\x00' 
        mock_instance.synthesize_text.return_value = fake_audio_content

        payload = {'text': '안녕하세요, 테스트입니다.'}
        response = self.client.post(self.url, payload, format='json')

        # 상태 코드 확인
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        # 응답 타입 확인 (audio/mpeg)
        self.assertEqual(response['Content-Type'], 'audio/mpeg')
        # 응답 내용 확인
        self.assertEqual(response.content, fake_audio_content)
        
        # Mock 호출 확인
        mock_instance.synthesize_text.assert_called_once_with('안녕하세요, 테스트입니다.')

    def test_tts_missing_text(self):
        """
        텍스트 필드가 없으면 400 Bad Request를 반환해야 한다.
        """
        payload = {} # No text
        response = self.client.post(self.url, payload, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_tts_empty_text(self):
        """
        빈 텍스트를 보내면 400 Bad Request를 반환해야 한다.
        """
        payload = {'text': ''}
        response = self.client.post(self.url, payload, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_unauthorized_access(self):
        """
        로그인하지 않은 유저는 401 Unauthorized를 받아야 한다.
        """
        self.client.force_authenticate(user=None) # 로그아웃
        payload = {'text': '테스트'}
        response = self.client.post(self.url, payload, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
