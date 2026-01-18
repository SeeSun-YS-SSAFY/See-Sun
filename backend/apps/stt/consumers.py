"""
WebSocket 기반 실시간 STT Consumer
PCM 오디오 데이터 직접 처리
"""
import json
import asyncio
import tempfile
import os
import traceback
import struct
import wave
from channels.generic.websocket import AsyncWebsocketConsumer
from google.cloud import speech

from .handlers.command import normalize_command


class STTConsumer(AsyncWebsocketConsumer):
    """
    WebSocket STT Consumer
    - PCM16 오디오 데이터 직접 수신
    - 순환 버퍼로 첫 음절 손실 방지
    """
    
    # Google STT 클라이언트 (싱글톤)
    _client = None
    
    @classmethod
    def get_client(cls):
        if cls._client is None:
            from google.oauth2 import service_account
            
            base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            credentials_path = os.path.join(base_dir, 'google-credentials.json')
            
            print(f"[WS STT] 인증 파일: {credentials_path}")
            
            credentials = service_account.Credentials.from_service_account_file(credentials_path)
            cls._client = speech.SpeechClient(credentials=credentials)
            print("[WS STT] Google Cloud STT 클라이언트 초기화 완료")
        return cls._client
    
    async def connect(self):
        """WebSocket 연결"""
        await self.accept()
        self.audio_data = None
        self.sample_rate = 16000
        self.audio_format = 'pcm16'
        print(f"[WS STT] 클라이언트 연결됨")
    
    async def disconnect(self, close_code):
        """WebSocket 연결 해제"""
        self.audio_data = None
        print(f"[WS STT] 연결 해제: {close_code}")
    
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
                    print(f"[WS STT] 오디오 메타: {self.sample_rate}Hz, {self.audio_format}")
                    
                elif command == 'process':
                    # 오디오 처리 요청
                    if self.audio_data:
                        await self.process_pcm_audio()
                    else:
                        await self.send(json.dumps({
                            'type': 'error',
                            'message': 'No audio data'
                        }))
            
            elif bytes_data:
                # PCM 데이터 수신
                self.audio_data = bytes_data
                print(f"[WS STT] PCM 수신: {len(bytes_data)} bytes")
                    
        except Exception as e:
            print(f"[WS STT] receive 오류: {e}")
            traceback.print_exc()
    
    async def process_pcm_audio(self):
        """PCM 오디오 처리"""
        if not self.audio_data:
            return
        
        pcm_data = self.audio_data
        self.audio_data = None
        
        # 최소 오디오 크기 체크
        if len(pcm_data) < 3200:  # ~100ms at 16kHz
            print(f"[WS STT] 오디오 너무 짧음: {len(pcm_data)} bytes")
            await self.send(json.dumps({
                'type': 'result',
                'message': '',
                'action': None
            }))
            return
        
        print(f"[WS STT] PCM 처리: {len(pcm_data)} bytes")
        
        wav_path = None
        
        try:
            # PCM → WAV 변환
            wav_path = tempfile.mktemp(suffix='.wav')
            
            with wave.open(wav_path, 'wb') as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)  # 16-bit
                wf.setframerate(self.sample_rate)
                wf.writeframes(pcm_data)
            
            wav_size = os.path.getsize(wav_path)
            print(f"[WS STT] WAV 생성: {wav_size} bytes")
            
            # Google Cloud STT
            text = await self.transcribe_with_google(wav_path)
            print(f"[WS STT] 인식 결과: '{text}'")
            
            # 명령어 분석 (Gemini)
            loop = asyncio.get_event_loop()
            command_result = await loop.run_in_executor(
                None,
                lambda: normalize_command(text)
            )
            
            action = command_result.get('action')
            confidence = command_result.get('confidence', 0.0)
            
            print(f"[WS STT] 명령어: {action} (신뢰도: {confidence})")
            
            # 결과 전송
            await self.send(json.dumps({
                'type': 'result',
                'message': text,
                'action': action,
                'confidence': confidence
            }))
            
        except Exception as e:
            print(f"[WS STT] process_pcm_audio 오류: {e}")
            traceback.print_exc()
            await self.send(json.dumps({
                'type': 'error',
                'message': str(e)
            }))
        finally:
            if wav_path and os.path.exists(wav_path):
                os.unlink(wav_path)
    
    async def transcribe_with_google(self, wav_path: str) -> str:
        """Google Cloud STT"""
        loop = asyncio.get_event_loop()
        
        def do_transcribe():
            client = self.get_client()
            
            with open(wav_path, 'rb') as f:
                content = f.read()
            
            audio = speech.RecognitionAudio(content=content)
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=self.sample_rate,
                language_code="ko-KR",
                enable_automatic_punctuation=True,
            )
            
            response = client.recognize(config=config, audio=audio)
            
            transcript = ""
            for result in response.results:
                transcript += result.alternatives[0].transcript
            
            return transcript.strip()
        
        return await loop.run_in_executor(None, do_transcribe)
