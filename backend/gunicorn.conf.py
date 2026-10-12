"""
gunicorn 설정(운영). 값은 환경변수로 조정한다.
- WEB_CONCURRENCY: 워커 수(기본 3 — Lightsail 1~2GB VM 기준)
- GUNICORN_TIMEOUT: 요청 제한 시간(초)
"""
import os

bind = os.environ.get('GUNICORN_BIND', '0.0.0.0:8000')
workers = int(os.environ.get('WEB_CONCURRENCY', '3'))
threads = int(os.environ.get('GUNICORN_THREADS', '2'))
timeout = int(os.environ.get('GUNICORN_TIMEOUT', '30'))
graceful_timeout = 30
keepalive = 5
max_requests = 1000
max_requests_jitter = 100
# Caddy(같은 도커 네트워크) 뒤에 있으므로 X-Forwarded-* 를 신뢰
forwarded_allow_ips = os.environ.get('FORWARDED_ALLOW_IPS', '*')
accesslog = '-'
errorlog = '-'
# 접근 로그에 쿼리 문자열·사용자 정보를 남기지 않는다(개인정보 최소화)
access_log_format = '%(h)s "%(m)s %(U)s %(H)s" %(s)s %(B)s %(L)ss'
