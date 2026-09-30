from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser
from drf_spectacular.utils import extend_schema
from .services.audio_processor import AudioProcessor
from .services.google_stt_service import GoogleSTTService, GoogleSTTServiceException
from .services.gemini_service import GeminiService
from .services.wake_word import detect_wake_word
import logging

logger = logging.getLogger(__name__)

class STTView(APIView):
    """
    통합 STT API
    - Google STT: 음성 → 텍스트
    - Gemini: 텍스트 → 구조화된 데이터 (NLU)
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
        # 프론트/기존 연동 호환: userinfo_stt(권장) 또는 audio(레거시)를 모두 허용합니다.
        audio_file = request.FILES.get('userinfo_stt') or request.FILES.get('audio')
        field = request.data.get('field', None)

        if not audio_file:
            return Response({'error': '오디오 파일이 없습니다.'}, status=400)

        # Google STT 동기 요청은 약 10MB 제한이 있어, 과도한 업로드는 사전에 차단합니다.
        if getattr(audio_file, "size", 0) and audio_file.size > 10 * 1024 * 1024:
            return Response({'error': '오디오 파일이 너무 큽니다. (최대 10MB)'}, status=400)
        
        ALLOWED_MODES = ['form', 'listen', 'command', 'full_command', 'stt']
        if mode not in ALLOWED_MODES:
            return Response({'error': f'유효하지 않은 mode 입니다. 허용: {ALLOWED_MODES}'}, status=400)

        try:
            # 1. 파일 -> 바이트 변환 (변환 스킵, WEBM_OPUS)
            audio_bytes, sample_rate, encoding = AudioProcessor.convert_webm_to_bytes(audio_file)
            logger.info(f"[STTView] 업로드 오디오 처리: sample_rate={sample_rate}, encoding={encoding}")

            # 2. STT (Google) - 모든 모드 공통
            try:
                raw_text = GoogleSTTService.transcribe(audio_bytes, sample_rate, encoding)
            except GoogleSTTServiceException as e:
                logger.error(f"[STTView] Google STT 에러: {e}", exc_info=True)
                return Response({'error': str(e)}, status=503)
            
            # 아무 말도 안 했을 경우
            if not raw_text:
                return Response({'error': '음성을 감지할 수 없습니다.'}, status=200)

            result = {
                'mode': mode,
                'stt_raw': raw_text
            }

            # 3. 모드별 NLU logic
            if mode == 'listen':
                # Listen: 로컬에서 빠르게 감지
                wake_detected = detect_wake_word(raw_text)
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
            logger.error(f"[STTView] STT 처리 오류: {str(e)}", exc_info=True)
            return Response({'error': '서버 오류가 발생했습니다.'}, status=500)
