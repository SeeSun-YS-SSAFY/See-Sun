from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser
from drf_spectacular.utils import extend_schema
from .services.audio_processor import AudioProcessor
from .services.stt_engine import STTEngine
from .services.gemini_service import GeminiService
import logging

logger = logging.getLogger(__name__)

class STTView(APIView):
    """
    통합 STT API
    - Faster-Whisper: 음성 → 텍스트
    - Gemini 1.5 Flash: 텍스트 → 구조화된 데이터 (NLU)
    """
    parser_classes = [MultiPartParser]

    @extend_schema(
        summary="통합 STT API",
        description="""
        mode에 따라 다른 처리 로직 수행:
        - form: 텍스트 정규화 (이름, 키, 몸무게 등 추출)
        - listen: 로컬 웨이크워드 감지 (Gemini 미사용)
        - command: 일반 시스템 명령 해석
        - full_command: 운동 제어 명령 해석
        """,
        request={
            'multipart/form-data': {
                'type': 'object',
                'properties': {
                    'userinfo_stt': {'type': 'string', 'format': 'binary'},
                    'field': {'type': 'string', 'description': 'form 모드일 때 필드명 (height, weight 등)'}
                },
                'required': ['userinfo_stt']
            }
        },
        responses={
            200: {
                'type': 'object',
                'properties': {
                    'mode': {'type': 'string'},
                    'stt_raw': {'type': 'string'},
                    'normalized': {'type': 'string', 'nullable': True},
                    'raw': {'type': 'string', 'nullable': True},
                    'wake_detected': {'type': 'boolean', 'nullable': True},
                    'action': {'type': 'string', 'nullable': True},
                }
            }
        }
    )
    def post(self, request, mode):
        audio_file = request.FILES.get('audio')
        field = request.data.get('field', None)

        if not audio_file:
            return Response({'error': 'No audio file provided'}, status=400)
        
        ALLOWED_MODES = ['form', 'listen', 'command', 'full_command', 'stt']
        if mode not in ALLOWED_MODES:
            return Response({'error': f'Invalid mode. Allowed: {ALLOWED_MODES}'}, status=400)

        temp_webm = None
        temp_wav = None

        try:
            # 1. 파일 전처리
            temp_webm = AudioProcessor.save_temp_file(audio_file)
            temp_wav = AudioProcessor.convert_to_wav(temp_webm)
            
            # 2. STT (Whisper) - 모든 모드 공통
            raw_text = STTEngine.transcribe(temp_wav)
            
            # 아무 말도 안 했을 경우
            if not raw_text:
                return Response({'error': 'No speech detected'}, status=200)

            result = {
                'mode': mode,
                'stt_raw': raw_text
            }

            # 3. 모드별 NLU logic
            if mode == 'listen':
                # Listen: 로컬에서 빠르게 감지
                wake_detected = STTEngine.detect_wake_word(raw_text)
                result['wake_detected'] = wake_detected
                result['message'] = raw_text # Legacy response compat

            elif mode == 'form':
                # Form: Gemini 정규화
                if not field:
                    # 필드가 없으면 raw만 반환
                    result['normalized'] = None
                    result['raw'] = raw_text
                else:
                    nlu_result = GeminiService.normalize(raw_text, field)
                    result.update(nlu_result)

            elif mode == 'command':
                # Command: 일반 명령 정규화
                nlu_result = GeminiService.parse_command(raw_text)
                result.update(nlu_result)
                result['message'] = raw_text # Legacy response compat

            elif mode == 'full_command':
                # Full Command: 운동 명령 정규화
                nlu_result = GeminiService.parse_full_command(raw_text)
                result.update(nlu_result)
            
            else: # stt (debug)
                result['message'] = raw_text

            return Response(result)

        except Exception as e:
            logger.error(f"STT Error: {str(e)}", exc_info=True)
            return Response({'error': str(e)}, status=500)
        
        finally:
            AudioProcessor.cleanup(temp_webm, temp_wav)
