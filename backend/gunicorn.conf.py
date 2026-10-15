"""
gunicorn 설정(운영). 값은 환경변수로 조정한다.
- WEB_CONCURRENCY: 워커 수(기본 3 — Lightsail 1~2GB VM 기준)
- GUNICORN_TIMEOUT: 요청 제한 시간(초)
"""
import os

bind = os.environ.get('GUNICORN_BIND', '0.0.0.0:8000')
workers = int(os.environ.get('WEB_CONCURRENCY', '3'))
# 기본 1 = sync 워커. gthread(스레드 2 이상)는 max_requests로 워커를 새로 띄울 때 이미 받아 둔 연결을 처리하지 않고
# 닫아, Caddy가 요청 본문을 쓰다 "use of closed network connection" → 간헐 502(POST라 재시도 안 됨, 배포 리허설 B-13).
# sync 워커는 한 번에 한 연결만 받아 재기동 중에도 요청을 잃지 않는다. 동시 처리량은 WEB_CONCURRENCY로 늘린다.
threads = int(os.environ.get('GUNICORN_THREADS', '1'))
timeout = int(os.environ.get('GUNICORN_TIMEOUT', '30'))
graceful_timeout = 30
keepalive = 5
# 워커를 주기적으로 새로 띄워 메모리 누수를 막는다(리허설 staging은 작게 줘서 재기동 중 502가 없는지 시험)
max_requests = int(os.environ.get('GUNICORN_MAX_REQUESTS', '1000'))
max_requests_jitter = int(os.environ.get('GUNICORN_MAX_REQUESTS_JITTER', '100'))
# Caddy(같은 도커 네트워크) 뒤에 있으므로 X-Forwarded-* 를 신뢰
forwarded_allow_ips = os.environ.get('FORWARDED_ALLOW_IPS', '*')
accesslog = '-'
errorlog = '-'
# 접근 로그에 쿼리 문자열·사용자 정보를 남기지 않는다(개인정보 최소화)
access_log_format = '%(h)s "%(m)s %(U)s %(H)s" %(s)s %(B)s %(L)ss'
