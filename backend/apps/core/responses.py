"""공통 오류 응답 형식: {"error": 코드, "tts_message": 낭독 문구, "errors": 필드 오류(선택)}"""
from rest_framework.exceptions import PermissionDenied
from rest_framework.response import Response


def error_response(code: str, tts_message: str, http_status: int, errors=None, **extra) -> Response:
    body = {'error': code, 'tts_message': tts_message}
    if errors is not None:
        body['errors'] = errors
    body.update(extra)
    return Response(body, status=http_status)


def validation_error(errors, tts_message='입력 정보를 확인해 주세요.') -> Response:
    return error_response('VALIDATION_ERROR', tts_message, 400, errors=errors)


class CodedPermissionDenied(PermissionDenied):
    """권한 거부를 공통 오류 형식으로 돌려준다"""

    def __init__(self, code: str, tts_message: str):
        super().__init__(detail={'error': code, 'tts_message': tts_message})
