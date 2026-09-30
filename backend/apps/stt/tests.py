from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status
from unittest.mock import patch
from django.core.files.uploadedfile import SimpleUploadedFile

class STTViewTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.url_base = '/api/v1/stt/'
        # 가짜 오디오 파일 생성
        self.audio_file = SimpleUploadedFile("test.webm", b"file_content", content_type="audio/webm")

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    @patch('apps.stt.services.gemini_service.GeminiService.normalize')
    def test_form_mode(self, mock_normalize, mock_transcribe, mock_convert_webm):
        """Form 모드 테스트: WebM -> Google STT -> Gemini Normalize"""
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "백칠십오"
        mock_normalize.return_value = {"normalized": "175", "raw": "백칠십오"}

        url = self.url_base + "form/"
        data = {'userinfo_stt': self.audio_file, 'field': 'height'}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        # mode 확인
        self.assertEqual(response.data.get('mode'), 'form')
        # 정규화 결과 확인
        self.assertEqual(response.data.get('normalized'), '175')
        
        mock_transcribe.assert_called_with(b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_normalize.assert_called_with("백칠십오", "height")

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    def test_listen_mode(self, mock_transcribe, mock_convert_webm):
        """Listen 모드 테스트: 로컬 Wake Word 감지"""
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "시선 코치 도와줘"
        
        url = self.url_base + "listen/"
        data = {'userinfo_stt': self.audio_file}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data.get('mode'), 'listen')
        self.assertTrue(response.data.get('wake_detected'))
        # Legacy compat message
        self.assertEqual(response.data.get('message'), "시선 코치 도와줘")

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    @patch('apps.stt.services.gemini_service.GeminiService.parse_command')
    def test_command_mode(self, mock_parse_command, mock_transcribe, mock_convert_webm):
        """Command 모드 테스트: 일반 명령 해석"""
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "홈으로 가"
        mock_parse_command.return_value = {"action": "navigate_home", "raw": "홈으로 가"}
        
        url = self.url_base + "command/"
        data = {'userinfo_stt': self.audio_file}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data.get('action'), 'navigate_home')

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    @patch('apps.stt.services.gemini_service.GeminiService.parse_full_command')
    def test_full_command_mode(self, mock_parse_full_command, mock_transcribe, mock_convert_webm):
        """Full Command 모드 테스트: 운동 명령 해석"""
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "그만"
        mock_parse_full_command.return_value = {"action": "pause", "raw": "그만"}
        
        url = self.url_base + "full_command/"
        data = {'userinfo_stt': self.audio_file}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data.get('action'), 'pause')
