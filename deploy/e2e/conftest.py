"""
배포 리허설 E2E·QA 공통 설정

대상
- staging 스택(기본 https://localhost:28443): 운영 이미지 + settings_staging(가짜 Firebase 토큰만 허용)
- 운영 구성 스택(기본 https://localhost:18443): 실제 운영 설정 그대로(가짜 토큰 거부 확인용)

환경변수
- SEESUN_E2E_BASE / SEESUN_PROD_BASE : 위 주소 바꾸기
- SEESUN_E2E_CA      : Caddy 로컬 CA 인증서 경로(run.sh가 컨테이너에서 꺼내 둔다). 없으면 TLS 검증 생략
- SEESUN_STAGING_ENV : staging.env 경로(만료 토큰을 진짜 서명으로 만들 때 SECRET_KEY를 읽는다)
- SEESUN_STAGING_PROJECT / SEESUN_PROD_PROJECT : compose 프로젝트 이름(DB 직접 확인용)

개인정보 표지: 테스트가 쓰는 이름·전화번호는 results/pii_markers.json에 모아 로그 검사(ops_checks.sh)에 쓴다.
"""
import json
import os
import random
import ssl
import subprocess
import uuid
from datetime import date
from pathlib import Path

import httpx
import pytest

HERE = Path(__file__).resolve().parent
RESULTS = HERE / 'results'
RESULTS.mkdir(exist_ok=True)

BASE = os.environ.get('SEESUN_E2E_BASE', 'https://localhost:28443')
PROD_BASE = os.environ.get('SEESUN_PROD_BASE', 'https://localhost:18443')
CA = os.environ.get('SEESUN_E2E_CA') or str(RESULTS / 'caddy-root.crt')
VERIFY = ssl.create_default_context(cafile=CA) if Path(CA).exists() else False
# 운영 구성 스택은 Caddy 로컬 CA가 따로다
PROD_CA = os.environ.get('SEESUN_PROD_CA') or str(RESULTS / 'caddy-root-prod.crt')
PROD_VERIFY = ssl.create_default_context(cafile=PROD_CA) if Path(PROD_CA).exists() else False
STAGING_ENV = Path(os.environ.get('SEESUN_STAGING_ENV', HERE / 'staging.env'))
STAGING_PROJECT = os.environ.get('SEESUN_STAGING_PROJECT', 'seesun-staging')
PROD_PROJECT = os.environ.get('SEESUN_PROD_PROJECT', 'seesun-rehearsal')

# 로그에 남으면 안 되는 값(테스트 이름·전화번호). 이름은 눈에 띄는 표지를 붙인다
PII_NAME = '홍길동시험'
_PII = {'names': {PII_NAME}, 'phones': set()}


def _save_markers():
    path = RESULTS / 'pii_markers.json'
    try:
        old = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        old = {}
    data = {'names': sorted(set(old.get('names', [])) | _PII['names']),
            'phones': sorted(set(old.get('phones', [])) | _PII['phones']), 'bodies': old.get('bodies', [])}
    (RESULTS / 'pii_markers.json').write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding='utf-8')


def new_phone() -> str:
    phone = '+8210' + ''.join(random.choice('0123456789') for _ in range(8))
    _PII['phones'].add(phone)
    _PII['phones'].add('0' + phone[3:])  # 국내 형식 010…
    _save_markers()
    return phone


def years_ago(years: int, days: int = 0) -> str:
    today = date.today()
    try:
        d = today.replace(year=today.year - years)
    except ValueError:  # 2월 29일
        d = today.replace(year=today.year - years, day=28)
    return date.fromordinal(d.toordinal() + days).isoformat()


def client(base: str = BASE) -> httpx.Client:
    verify = PROD_VERIFY if base == PROD_BASE else VERIFY
    return httpx.Client(base_url=base, verify=verify, timeout=30, follow_redirects=False)


class User:
    """가입한 테스트 사용자(토큰·헤더)"""

    def __init__(self, api, uid, phone, body):
        self.api, self.uid, self.phone = api, uid, phone
        self.body = body
        self.access = body['access_token']
        self.refresh = body['refresh_token']
        self.id = body['user']['id']

    @property
    def h(self):
        return {'Authorization': f'Bearer {self.access}'}

    def get(self, path, **kw):
        return self.api.get(path, headers={**self.h, **kw.pop('headers', {})}, **kw)

    def post(self, path, **kw):
        return self.api.post(path, headers={**self.h, **kw.pop('headers', {})}, **kw)

    def patch(self, path, **kw):
        return self.api.patch(path, headers={**self.h, **kw.pop('headers', {})}, **kw)

    def put(self, path, **kw):
        return self.api.put(path, headers={**self.h, **kw.pop('headers', {})}, **kw)

    def delete(self, path, **kw):
        return self.api.request('DELETE', path, headers={**self.h, **kw.pop('headers', {})}, **kw)


def exchange(api, uid, phone, birthdate=None, name=None):
    body = {'id_token': f'fake:{uid}:{phone}'}
    if birthdate:
        body['birthdate'] = birthdate
    if name:
        body['name'] = name
    return api.post('/api/v1/auth/firebase/', json=body)


def signup(api, birthdate='1990-05-01', name=PII_NAME) -> User:
    uid, phone = f'e2e-{uuid.uuid4().hex[:16]}', new_phone()
    r = exchange(api, uid, phone, birthdate, name)
    assert r.status_code == 200, r.text
    return User(api, uid, phone, r.json())


def doc_versions(api) -> dict:
    r = api.get('/api/v1/consents/documents/')
    assert r.status_code == 200, r.text
    return {d['type']: d['version'] for d in r.json()}


def agree(user: User, *types, agreed=True, method='TOUCH'):
    versions = doc_versions(user.api)
    items = [{'type': t, 'version': versions[t], 'agreed': agreed, 'method': method} for t in types]
    return user.post('/api/v1/me/consents/', json={'items': items})


def ready_user(api, sensitive=False, profile=True) -> User:
    """필수 동의(+선택: 민감정보)와 프로필까지 마친 사용자"""
    user = signup(api)
    r = agree(user, 'TOS', 'PRIVACY', *(['SENSITIVE'] if sensitive else []))
    assert r.status_code == 200, r.text
    if profile:
        r = user.patch('/api/v1/me/', json={'gender': 'M', 'height_cm': 176, 'weight_kg': 70, 'goal': 'LOSE_WEIGHT'})
        assert r.status_code == 200, r.text
    return user


def food_id(api_user: User, q='떡볶이') -> int:
    r = api_user.get('/api/v1/foods/search/', params={'q': q})
    assert r.status_code == 200 and r.json()['results'], r.text
    return r.json()['results'][0]['id']


def psql(sql: str, project: str = STAGING_PROJECT) -> str:
    """staging DB 직접 조회(파기 확인 등). docker가 없으면 건너뛴다"""
    cmd = ['docker', 'exec', f'{project}-postgres-1', 'psql', '-U', 'seesun', '-d', 'seesun', '-Atc', sql]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', timeout=30)
    except FileNotFoundError:
        pytest.skip('docker 없음')
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.strip()


def staging_secret_key() -> str | None:
    if not STAGING_ENV.exists():
        return None
    for line in STAGING_ENV.read_text(encoding='utf-8').splitlines():
        if line.startswith('DJANGO_SECRET_KEY='):
            return line.split('=', 1)[1]
    return None


@pytest.fixture(scope='session')
def api():
    with client(BASE) as c:
        yield c


@pytest.fixture(scope='session')
def prod():
    with client(PROD_BASE) as c:
        yield c


@pytest.fixture
def user(api):
    return ready_user(api)


@pytest.fixture
def sensitive_user(api):
    return ready_user(api, sensitive=True)
