"""
배포 리허설용 일회성 환경 파일 생성기(커밋 금지 값 — 실행할 때마다 새로 만든다)

    python deploy/e2e/make_env.py staging   # → deploy/e2e/staging.env (staging 스택, 포트 28080/28443)
    python deploy/e2e/make_env.py rehearsal # → deploy/.env (운영 구성 리허설, 포트 18080/18443) — 이미 있으면 덮어쓰지 않음

.env.example을 그대로 복사한 뒤 비밀값만 무작위로 채운다. 클라우드·Firebase 값은 넣지 않는다.
"""
import base64
import re
import secrets
import sys
from pathlib import Path

DEPLOY = Path(__file__).resolve().parents[1]

PROFILES = {
    'rehearsal': {
        'path': DEPLOY / '.env',
        'ports': (18080, 18443),
        'extra': {'FIREBASE_PROJECT_ID': 'seesun-rehearsal-not-real'},
    },
    'staging': {
        'path': DEPLOY / 'e2e' / 'staging.env',
        'ports': (28080, 28443),
        # QA가 가입을 많이 하므로 토큰 교환 한도만 올린다(나머지 한도는 운영과 같게 둔다)
        'extra': {'THROTTLE_FIREBASE': '600/min', 'FIREBASE_CREDENTIALS_FILE': '',
                  # 관리자 화면을 문서용 공인 IP 하나만 허용 → 로컬에서 접근하면 404(법적 검토 S-01 차단 확인)
                  'ADMIN_ALLOWED_IPS': '203.0.113.10/32',
                  # 워커 재기동을 자주 일으켜 그 사이 502가 없는지 시험(배포 리허설 B-13)
                  'GUNICORN_MAX_REQUESTS': '40', 'GUNICORN_MAX_REQUESTS_JITTER': '10'},
    },
}


def build(profile: str, force: bool = False) -> Path:
    conf = PROFILES[profile]
    target = conf['path']
    if target.exists() and not force:
        print(f'이미 있음(그대로 둠): {target}')
        return target
    http_port, https_port = conf['ports']
    text = (DEPLOY / '.env.example').read_text(encoding='utf-8')
    values = {
        'API_DOMAIN': 'localhost',
        'ACME_EMAIL': 'ops@example.invalid',
        'DJANGO_SECRET_KEY': secrets.token_urlsafe(50),
        'DJANGO_ALLOWED_HOSTS': 'localhost',
        'DJANGO_CSRF_TRUSTED_ORIGINS': f'https://localhost:{https_port}',
        'POSTGRES_PASSWORD': secrets.token_urlsafe(32),
        'FIELD_ENCRYPTION_KEYS': base64.urlsafe_b64encode(secrets.token_bytes(32)).decode(),
        'PHONE_HASH_KEY': secrets.token_urlsafe(32),
        **conf['extra'],
    }
    for key, value in values.items():
        text, n = re.subn(rf'^#?\s*{key}=.*$', f'{key}={value}', text, count=1, flags=re.M)
        if n == 0:
            text += f'\n{key}={value}\n'
    text += f'\n# ---- 로컬 리허설 전용 ----\nCADDY_HTTP_PORT={http_port}\nCADDY_HTTPS_PORT={https_port}\n'
    target.write_text(text, encoding='utf-8', newline='\n')
    try:
        target.chmod(0o600)
    except OSError:
        pass
    print(f'만듦: {target}')
    return target


if __name__ == '__main__':
    args = sys.argv[1:] or ['staging']
    build(args[0], force='--force' in args)
