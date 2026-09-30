import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from rest_framework import status
from rest_framework.test import APIClient
from unittest.mock import patch


@pytest.mark.django_db
class TestSTTView:
    def setup_method(self):
        self.client = APIClient()
        self.url_base = "/api/v1/stt/"

    @patch("apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes")
    @patch("apps.stt.services.google_stt_service.GoogleSTTService.transcribe")
    @patch("apps.stt.services.gemini_service.GeminiService.normalize")
    def test_form_mode_webm_opus_인코딩이_정확히_전달되어야_한다(self, mock_normalize, mock_transcribe, mock_convert_webm):
        """Form 모드에서 WEBM_OPUS 인코딩이 Google STT 호출까지 전달되어야 한다."""
        # Arrange
        audio_file = SimpleUploadedFile("test.webm", b"file_content", content_type="audio/webm")
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "백칠십오"
        mock_normalize.return_value = {"normalized": "175", "raw": "백칠십오"}

        # Act
        url = self.url_base + "form/"
        response = self.client.post(url, {"userinfo_stt": audio_file, "field": "height"}, format="multipart")

        # Assert
        assert response.status_code == status.HTTP_200_OK
        assert response.data.get("mode") == "form"
        assert response.data.get("stt_raw") == "백칠십오"
        mock_transcribe.assert_called_with(b"fake_audio_bytes", 16000, "WEBM_OPUS")

    @patch("apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes")
    @patch("apps.stt.services.google_stt_service.GoogleSTTService.transcribe")
    def test_listen_mode_웨이크워드가_정상_감지되어야_한다(self, mock_transcribe, mock_convert_webm):
        """Listen 모드에서 웨이크워드가 정상적으로 감지되어야 한다."""
        # Arrange
        audio_file = SimpleUploadedFile("test.webm", b"file_content", content_type="audio/webm")
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "시선 코치 도와줘"

        # Act
        url = self.url_base + "listen/"
        response = self.client.post(url, {"userinfo_stt": audio_file}, format="multipart")

        # Assert
        assert response.status_code == status.HTTP_200_OK
        assert response.data.get("mode") == "listen"
        assert response.data.get("wake_detected") is True

    @patch("apps.stt.services.google_stt_service.GoogleSTTService.transcribe")
    def test_10MB_초과_업로드는_즉시_차단되어야_한다(self, mock_transcribe):
        """10MB 초과 업로드는 Google STT 호출 전에 즉시 차단되어야 한다."""
        # Arrange
        big_bytes = b"a" * (10 * 1024 * 1024 + 1)
        audio_file = SimpleUploadedFile("big.webm", big_bytes, content_type="audio/webm")

        # Act
        url = self.url_base + "form/"
        response = self.client.post(url, {"userinfo_stt": audio_file, "field": "height"}, format="multipart")

        # Assert
        assert response.status_code == 400
        assert "10MB" in response.data.get("error", "")
        mock_transcribe.assert_not_called()

    @patch("apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes")
    @patch("apps.stt.services.google_stt_service.GoogleSTTService.transcribe")
    @patch("apps.stt.services.gemini_service.GeminiService.normalize")
    def test_기존_audio_키도_호환되어야_한다(self, mock_normalize, mock_transcribe, mock_convert_webm):
        """프론트(레거시)가 audio 키를 사용해도 호환되어야 한다."""
        # Arrange
        audio_file = SimpleUploadedFile("test.webm", b"file_content", content_type="audio/webm")
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.return_value = "백칠십오"
        mock_normalize.return_value = {"normalized": "175", "raw": "백칠십오"}

        # Act
        url = self.url_base + "form/"
        response = self.client.post(url, {"audio": audio_file, "field": "height"}, format="multipart")

        # Assert
        assert response.status_code == status.HTTP_200_OK
        assert response.data.get("mode") == "form"
        assert response.data.get("normalized") == "175"

    @patch("apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes")
    @patch("apps.stt.services.google_stt_service.GoogleSTTService.transcribe")
    def test_google_stt_예외는_503으로_응답해야_한다(self, mock_transcribe, mock_convert_webm):
        """Google STT 예외는 503으로 변환되어야 한다."""
        # Arrange
        from apps.stt.services.google_stt_service import GoogleSTTServiceException

        audio_file = SimpleUploadedFile("test.webm", b"file_content", content_type="audio/webm")
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000, "WEBM_OPUS")
        mock_transcribe.side_effect = GoogleSTTServiceException("임의 오류")

        # Act
        url = self.url_base + "form/"
        response = self.client.post(url, {"userinfo_stt": audio_file, "field": "height"}, format="multipart")

        # Assert
        assert response.status_code == 503
        assert response.data.get("error") == "임의 오류"

