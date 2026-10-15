"""
헬스체크·요청 위생 미들웨어

컨테이너 헬스체크(docker/compose)는 내부 주소(localhost)·HTTP로 호출되므로
ALLOWED_HOSTS 검사와 HTTPS 리다이렉트를 거치면 실패한다. 그래서 미들웨어 맨 앞에서
``/healthz/`` 만 직접 응답한다. 응답에는 개인정보·설정값을 넣지 않는다.
"""
from django.db import connection
from django.http import JsonResponse

HEALTH_PATHS = ('/healthz', '/healthz/')


def check_database() -> bool:
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT 1')
            cursor.fetchone()
        return True
    except Exception:  # noqa: BLE001 — 헬스체크는 원인과 관계없이 실패만 알린다
        return False


class HealthCheckMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if request.path in HEALTH_PATHS and request.method in ('GET', 'HEAD'):
            db_ok = check_database()
            return JsonResponse(
                {'status': 'ok' if db_ok else 'error', 'db': 'ok' if db_ok else 'error'},
                status=200 if db_ok else 503,
            )
        return self.get_response(request)


class RejectNullBytesMiddleware:
    """
    경로·쿼리 문자열에 NUL(%00)이 있으면 400.

    PostgreSQL 텍스트에는 NUL을 넣을 수 없어 ``/foods/search/?q=%00`` 같은 요청이 DB 단계에서
    DataError → 500이 났다(배포 리허설 B-05). 본문 JSON 문자열은 DRF CharField가 이미 막는다.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        query = request.META.get('QUERY_STRING', '')
        if '\x00' in request.path or '%00' in query or '\x00' in query:
            return JsonResponse(
                {'error': 'VALIDATION_ERROR', 'tts_message': '입력 정보를 확인해 주세요.',
                 'errors': {'request': ['요청에 사용할 수 없는 문자(NUL)가 있습니다.']}},
                status=400,
            )
        return self.get_response(request)
