"""
헬스체크 미들웨어

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
