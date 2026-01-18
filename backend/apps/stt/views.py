from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser
from drf_spectacular.utils import extend_schema
import subprocess
import tempfile
import os


class WebmSTTView(APIView):
    """
    음성 파일을 텍스트로 변환하는 STT API (faster-whisper 사용)
    mode에 따라 다른 응답 형식 제공
    """
    parser_classes = [MultiPartParser]
    
    # 모델 싱글톤
    _model = None
    
    # 명령어 매핑 (우선순위: action 명령어 먼저, wake는 마지막)
    COMMAND_PATTERNS = [
        ('pause', ['멈춤', '정지', '스톱', '스탑']),
        ('resume', ['시작', '재시작', '재시적', '다시 시작', '플레이', '재생']),
        ('next', ['다음', '다음 동작', '넘어가', '넘겨']),
        ('previous', ['이전', '이전 동작', '뒤로']),
        ('faster', ['빠르게', '더 빠르게', '속도 올려', '빨리']),
        ('slower', ['느리게', '더 느리게', '속도 내려', '천천히']),
    ]
    
    WAKE_PATTERNS = ['시선 코치', '시선코치', '시선 고치', '시선고치']
    
    @classmethod
    def get_model(cls):
        """모델을 한 번만 로드 (싱글톤)"""
        if cls._model is None:
            from faster_whisper import WhisperModel
            cls._model = WhisperModel("small", device="cpu", compute_type="int8")
        return cls._model
    
    @classmethod
    def detect_wake(cls, text: str) -> bool:
        """웨이크워드 감지"""
        text_clean = text.replace(" ", "").lower()
        for pattern in cls.WAKE_PATTERNS:
            if pattern.replace(" ", "") in text_clean:
                return True
        return False
    
    @classmethod
    def match_command(cls, text: str) -> str:
        """텍스트에서 명령어 매칭"""
        text_clean = text.replace(" ", "").lower()
        
        for command, patterns in cls.COMMAND_PATTERNS:
            for pattern in patterns:
                if pattern.replace(" ", "") in text_clean:
                    return command
        return None
    
    @extend_schema(
        summary="음성 → 텍스트 변환 (STT)",
        description="webm 오디오 파일을 받아 텍스트로 변환. mode에 따라 다른 응답.",
        request={
            'multipart/form-data': {
                'type': 'object',
                'properties': {
                    'userinfo_stt': {'type': 'string', 'format': 'binary'},
                    'mode': {'type': 'string', 'enum': ['form', 'listen', 'command']}
                },
                'required': ['userinfo_stt']
            }
        },
        responses={
            200: {
                'type': 'object',
                'properties': {
                    'message': {'type': 'string'},
                    'wake_detected': {'type': 'boolean'},
                    'action': {'type': 'string'},
                }
            }
        }
    )
    def post(self, request):
        audio_file = request.FILES.get('userinfo_stt')
        mode = request.data.get('mode', 'form')
        
        if not audio_file:
            return Response({'error': 'No audio file provided'}, status=400)
        
        # 1. webm을 임시 파일로 저장
        with tempfile.NamedTemporaryFile(delete=False, suffix='.webm') as webm_temp:
            for chunk in audio_file.chunks():
                webm_temp.write(chunk)
            webm_path = webm_temp.name
        
        wav_path = None
        try:
            # 2. ffmpeg로 WAV 변환
            wav_path = tempfile.mktemp(suffix='.wav')
            subprocess.run([
                'ffmpeg', '-i', webm_path,
                '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', '-y', wav_path
            ], check=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
            
            # 3. STT 인식
            model = self.get_model()
            segments, info = model.transcribe(wav_path, language="ko")
            text = "".join([segment.text for segment in segments]).strip()
            
            # 4. 모드별 응답
            if mode == 'listen':
                # 웨이크워드 감지 모드
                wake_detected = self.detect_wake(text)
                return Response({
                    'message': text,
                    'wake_detected': wake_detected
                })
            elif mode == 'command':
                # 명령어 실행 모드
                action = self.match_command(text)
                return Response({
                    'message': text,
                    'action': action
                })
            else:
                # 기본 form 모드
                return Response({
                    'message': text
                })
            
        except subprocess.CalledProcessError as e:
            return Response({'error': f'ffmpeg error: {str(e)}'}, status=500)
        except Exception as e:
            return Response({'error': f'STT error: {str(e)}'}, status=500)
        finally:
            if os.path.exists(webm_path):
                os.unlink(webm_path)
            if wav_path and os.path.exists(wav_path):
                os.unlink(wav_path)
