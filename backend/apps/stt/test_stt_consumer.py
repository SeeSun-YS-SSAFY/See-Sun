import pytest
from unittest.mock import AsyncMock, patch
from channels.db import database_sync_to_async
from channels.testing import WebsocketCommunicator
from django.contrib.auth import get_user_model
from django.test import override_settings
from rest_framework_simplejwt.tokens import AccessToken

from config.asgi import application


@database_sync_to_async
def _ws_path():
    user = get_user_model().objects.create_user(username="ws_tester", password="pw")
    return f"/ws/stt/?token={AccessToken.for_user(user)}"


@pytest.mark.asyncio
@pytest.mark.django_db(transaction=True)
class TestSTTConsumer:
    async def test_pcm_청크가_누적되고_raw_pcm만_google로_전달되어야_한다(self, caplog):
        """
        WebSocket에서 PCM이 청크로 들어와도 누적 처리되어야 하며,
        Google STT 호출 직전에 RIFF/WAVE 헤더가 없어야 한다.
        """
        # Arrange
        communicator = WebsocketCommunicator(application, await _ws_path())
        connected, _ = await communicator.connect()
        assert connected is True

        await communicator.send_json_to({"command": "audio", "sampleRate": 16000, "format": "pcm16"})

        chunk1 = b"\x01\x02\x03\x04" * 200  # 800 bytes
        chunk2 = b"\x05\x06\x07\x08" * 1000  # 4000 bytes (총 4800 bytes)

        # AsyncMock으로 외부 API 호출을 막습니다.
        with patch(
            "apps.stt.services.google_stt_service.GoogleSTTService.transcribe_async",
            new=AsyncMock(return_value="안녕하세요"),
        ) as mock_transcribe_async, patch(
            "apps.stt.consumers.STTConsumer._normalize_command_sync",
            return_value={"action": None, "confidence": 0.0},
        ):
            # Act
            await communicator.send_to(bytes_data=chunk1)
            await communicator.send_to(bytes_data=chunk2)
            await communicator.send_json_to({"command": "process"})

            response = await communicator.receive_json_from()

            # Assert: 응답 형태
            assert response["type"] == "result"
            assert response["message"] == "안녕하세요"

            # Assert: 호출 인자 (누적된 PCM + LINEAR16)
            assert mock_transcribe_async.await_count == 1
            called_pcm = mock_transcribe_async.await_args.args[0]
            called_rate = mock_transcribe_async.await_args.args[1]
            called_encoding = mock_transcribe_async.await_args.kwargs.get("encoding")
            assert called_pcm == chunk1 + chunk2
            assert called_rate == 16000
            assert called_encoding == "LINEAR16"

            # Assert: 헤더 부재 (증빙용)
            assert not called_pcm.startswith(b"RIFF")
            assert b"WAVE" not in called_pcm[:64]

        await communicator.disconnect()

    async def test_audio_없이_process를_요청하면_에러를_반환해야_한다(self):
        """오디오가 없는 상태에서 process를 요청하면 에러를 반환해야 한다."""
        communicator = WebsocketCommunicator(application, await _ws_path())
        connected, _ = await communicator.connect()
        assert connected is True

        await communicator.send_json_to({"command": "process"})
        response = await communicator.receive_json_from()

        assert response["type"] == "error"
        assert response["message"] == "오디오 데이터가 없습니다."

        await communicator.disconnect()

    async def test_토큰_없이_연결하면_거부해야_한다(self):
        communicator = WebsocketCommunicator(application, "/ws/stt/")
        connected, code = await communicator.connect()
        assert connected is False
        assert code == 4401

    async def test_버퍼_상한을_넘으면_에러_후_연결을_종료해야_한다(self):
        with override_settings(STT_WS_MAX_BUFFER_BYTES=1000):
            communicator = WebsocketCommunicator(application, await _ws_path())
            connected, _ = await communicator.connect()
            assert connected is True
            await communicator.send_to(bytes_data=b"\x00" * 1200)
            response = await communicator.receive_json_from()
            assert response["type"] == "error"
            output = await communicator.receive_output()
            assert output["type"] == "websocket.close"
            assert output["code"] == 4413
