"""
WebSocket 기반 실시간 STT Consumer
PCM 오디오 데이터 직접 처리
"""
import json
import asyncio
import logging
from channels.generic.websocket import AsyncWebsocketConsumer

from .services.google_stt_service import GoogleSTTService, GoogleSTTServiceException

logger = logging.getLogger(__name__)

class STTConsumer(AsyncWebsocketConsumer):
    """
    WebSocket STT Consumer
    - PCM16 오디오 데이터 직접 수신
    - 순환 버퍼로 첫 음절 손실 방지
    """
    
    def _normalize_command_sync(self, text: str) -> dict:
        """
        명령어 정규화를 수행합니다.

        테스트/로컬 환경에서 Gemini 의존성이 누락되면 WebSocket 자체가 죽지 않도록 방어합니다.
        """
        try:
            from .handlers.command import normalize_command

            return normalize_command(text)
        except Exception as e:
            logger.error(f"[WS STT] 명령어 분석 모듈 로딩/실행 실패: {e}", exc_info=True)
            return {"action": None, "confidence": 0.0}

    async def connect(self):
        """WebSocket 연결"""
        await self.accept()
        # 프론트에서 PCM이 청크 단위로 들어올 수 있어 누적 버퍼로 관리합니다.
        self.audio_buffer = bytearray()
        self.sample_rate = 16000
        self.audio_format = 'pcm16'
        logger.info("[WS STT] 클라이언트 연결됨")
    
    async def disconnect(self, close_code):
        """WebSocket 연결 해제"""
        self.audio_buffer = bytearray()
        logger.info(f"[WS STT] 연결 해제: {close_code}")
    
    async def receive(self, text_data=None, bytes_data=None):
        """메시지 수신"""
        try:
            if text_data:
                data = json.loads(text_data)
                command = data.get('command')
                
                if command == 'audio':
                    # 메타데이터 수신
                    self.sample_rate = data.get('sampleRate', 16000)
                    self.audio_format = data.get('format', 'pcm16')
                    logger.info(f"[WS STT] 오디오 메타: {self.sample_rate}Hz, {self.audio_format}")
                    
                elif command == 'process':
                    # 오디오 처리 요청
                    if self.audio_buffer:
                        await self.process_pcm_audio()
                    else:
                        await self.send(json.dumps({
                            'type': 'error',
                            'message': '오디오 데이터가 없습니다.'
                        }))
            
            elif bytes_data:
                # PCM 데이터 수신
                self.audio_buffer.extend(bytes_data)
                logger.info(f"[WS STT] PCM 청크 수신: {len(bytes_data)} bytes (누적: {len(self.audio_buffer)} bytes)")
                    
        except Exception as e:
            logger.error(f"[WS STT] receive 오류: {e}", exc_info=True)
    
    async def process_pcm_audio(self):
        """PCM 오디오 처리"""
        if not self.audio_buffer:
            return
        
        pcm_data = bytes(self.audio_buffer)
        self.audio_buffer = bytearray()
        
        # 최소 오디오 크기 체크
        if len(pcm_data) < 3200:  # ~100ms at 16kHz
            logger.info(f"[WS STT] 오디오가 너무 짧음: {len(pcm_data)} bytes")
            await self.send(json.dumps({
                'type': 'result',
                'message': '',
                'action': None
            }))
            return
        
        try:
            # 중요: WAV 헤더를 붙이지 않고 raw PCM 바이트를 그대로 전송합니다.
            prefix = pcm_data[:16]
            prefix_hex = prefix.hex()
            has_riff = prefix.startswith(b"RIFF")
            has_wave = b"WAVE" in pcm_data[:64]
            logger.info(f"[WS STT] STT 전송 직전 PCM prefix(hex): {prefix_hex}")
            logger.info(f"[WS STT] 헤더 검사: RIFF={has_riff}, WAVE={has_wave}")

            text = await GoogleSTTService.transcribe_async(
                pcm_data,
                self.sample_rate,
                encoding="LINEAR16",
            )
            logger.info(f"[WS STT] 인식 결과: '{text}'")
            
            # 명령어 분석 (Gemini)
            loop = asyncio.get_event_loop()
            command_result = await loop.run_in_executor(
                None,
                lambda: self._normalize_command_sync(text)
            )
            
            action = command_result.get('action')
            confidence = command_result.get('confidence', 0.0)
            
            logger.info(f"[WS STT] 명령어: {action} (신뢰도: {confidence})")
            
            # 결과 전송
            await self.send(json.dumps({
                'type': 'result',
                'message': text,
                'action': action,
                'confidence': confidence
            }))
            
        except GoogleSTTServiceException as e:
            logger.error(f"[WS STT] Google STT 에러: {e}", exc_info=True)
            await self.send(json.dumps({
                'type': 'error',
                'message': '음성 인식 중 오류가 발생했습니다.'
            }))
        except Exception as e:
            logger.error(f"[WS STT] process_pcm_audio 오류: {e}", exc_info=True)
            await self.send(json.dumps({
                'type': 'error',
                'message': '서버 처리 중 오류가 발생했습니다.'
            }))
