from django.test import TestCase
from rest_framework.test import APIClient
from unittest.mock import patch, MagicMock, mock_open
import io


class WebmSTTViewTests(TestCase):
    """STT API 테스트"""
    
    def setUp(self):
        self.client = APIClient()
    
    @patch('apps.stt.views.os.path.exists', return_value=True)
    @patch('apps.stt.views.os.unlink')
    @patch('apps.stt.views.tempfile.mktemp', return_value='/tmp/test.wav')
    @patch('apps.stt.views.tempfile.NamedTemporaryFile')
    @patch('apps.stt.views.subprocess.run')
    @patch('apps.stt.views.WebmSTTView.get_model')
    def test_form_mode(self, mock_model, mock_subprocess, mock_named_temp, mock_mktemp, mock_unlink, mock_exists):
        """form 모드: 일반 텍스트 반환"""
        mock_temp_instance = MagicMock()
        mock_temp_instance.__enter__.return_value = mock_temp_instance
        mock_temp_instance.name = '/tmp/test.webm'
        mock_named_temp.return_value = mock_temp_instance
        mock_subprocess.return_value = MagicMock(returncode=0)
        
        mock_segment = MagicMock()
        mock_segment.text = '김싸피'
        mock_model.return_value.transcribe.return_value = ([mock_segment], None)
        
        audio_file = io.BytesIO(b'fake_webm')
        audio_file.name = 'test.webm'
        
        response = self.client.post('/api/v1/users/webmstt/', {
            'userinfo_stt': audio_file,
            'mode': 'form'
        }, format='multipart')
        
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['message'], '김싸피')
        self.assertNotIn('wake_detected', response.data)
    
    @patch('apps.stt.views.os.path.exists', return_value=True)
    @patch('apps.stt.views.os.unlink')
    @patch('apps.stt.views.tempfile.mktemp', return_value='/tmp/test.wav')
    @patch('apps.stt.views.tempfile.NamedTemporaryFile')
    @patch('apps.stt.views.subprocess.run')
    @patch('apps.stt.views.WebmSTTView.get_model')
    def test_listen_mode_wake_detected(self, mock_model, mock_subprocess, mock_named_temp, mock_mktemp, mock_unlink, mock_exists):
        """listen 모드: 웨이크워드 감지"""
        mock_temp_instance = MagicMock()
        mock_temp_instance.__enter__.return_value = mock_temp_instance
        mock_temp_instance.name = '/tmp/test.webm'
        mock_named_temp.return_value = mock_temp_instance
        mock_subprocess.return_value = MagicMock(returncode=0)
        
        mock_segment = MagicMock()
        mock_segment.text = '시선 코치'
        mock_model.return_value.transcribe.return_value = ([mock_segment], None)
        
        audio_file = io.BytesIO(b'fake_webm')
        audio_file.name = 'test.webm'
        
        response = self.client.post('/api/v1/users/webmstt/', {
            'userinfo_stt': audio_file,
            'mode': 'listen'
        }, format='multipart')
        
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.data['wake_detected'])
    
    @patch('apps.stt.views.os.path.exists', return_value=True)
    @patch('apps.stt.views.os.unlink')
    @patch('apps.stt.views.tempfile.mktemp', return_value='/tmp/test.wav')
    @patch('apps.stt.views.tempfile.NamedTemporaryFile')
    @patch('apps.stt.views.subprocess.run')
    @patch('apps.stt.views.WebmSTTView.get_model')
    def test_command_mode_pause(self, mock_model, mock_subprocess, mock_named_temp, mock_mktemp, mock_unlink, mock_exists):
        """command 모드: 멈춤 명령 실행"""
        mock_temp_instance = MagicMock()
        mock_temp_instance.__enter__.return_value = mock_temp_instance
        mock_temp_instance.name = '/tmp/test.webm'
        mock_named_temp.return_value = mock_temp_instance
        mock_subprocess.return_value = MagicMock(returncode=0)
        
        mock_segment = MagicMock()
        mock_segment.text = '멈춤'
        mock_model.return_value.transcribe.return_value = ([mock_segment], None)
        
        audio_file = io.BytesIO(b'fake_webm')
        audio_file.name = 'test.webm'
        
        response = self.client.post('/api/v1/users/webmstt/', {
            'userinfo_stt': audio_file,
            'mode': 'command'
        }, format='multipart')
        
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['action'], 'pause')
    
    def test_no_file(self):
        """파일 없이 요청 시 400"""
        response = self.client.post('/api/v1/users/webmstt/', {}, format='multipart')
        self.assertEqual(response.status_code, 400)
