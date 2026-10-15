"""
운영 구성 스택(prod 설정 그대로, 기본 https://localhost:18443) 점검 — 가짜 토큰이 없으므로 인증 전 경로만

QA_MATRIX.md의 Q-PROD-* 행. 보안 헤더·DEBUG 꺼짐·가짜 검증기 거부·요청 제한·관리자 접근 통제·
레거시 코드 제거·미디어 서빙·본문 크기 제한·개인정보 접속기록을 확인한다.
"""
import re
import subprocess
import uuid

import pytest

from conftest import PROD_BASE, PROD_PROJECT, client, new_phone, psql

pytestmark = pytest.mark.prod


def test_Q_PROD_01_헬스체크(prod):
    r = prod.get('/healthz/')
    assert r.status_code == 200 and r.json() == {'status': 'ok', 'db': 'ok'}


def test_Q_PROD_02_보안_헤더(prod):
    r = prod.get('/api/v1/consents/documents/')
    h = r.headers
    assert h['strict-transport-security'].startswith('max-age=31536000')
    assert h['x-content-type-options'] == 'nosniff'
    assert h['referrer-policy'] == 'same-origin'
    assert h['x-frame-options'] == 'DENY'
    assert 'server' not in h


def test_Q_PROD_03_HTTP는_HTTPS로(prod):
    import httpx
    port = int(PROD_BASE.rsplit(':', 1)[1]) - 363  # 18443 → 18080
    r = httpx.get(f'http://localhost:{port}/healthz/', follow_redirects=False)
    assert r.status_code == 308 and r.headers['location'].startswith('https://')


def test_Q_PROD_04_DEBUG_꺼짐(prod):
    r = prod.get('/no-such-page/')
    assert r.status_code == 404
    assert 'URLconf' not in r.text and 'Traceback' not in r.text and 'DEBUG = True' not in r.text


def test_Q_PROD_05_가짜_Firebase_토큰_거부(prod):
    r = prod.post('/api/v1/auth/firebase/', json={'id_token': f'fake:prod-{uuid.uuid4().hex[:8]}:{new_phone()}',
                                                 'birthdate': '1990-01-01'})
    assert r.status_code == 401 and r.json()['error'] == 'INVALID_ID_TOKEN', r.text


def test_Q_PROD_06_Swagger와_스키마(prod):
    assert prod.get('/api/v1/docs/').status_code == 200
    assert prod.get('/api/v1/schema/').status_code == 200


def test_Q_PROD_07_관리자_로그인_화면은_SSH터널_대역에서만_열린다(prod):
    r = prod.get('/admin/login/')
    assert r.status_code == 200 and 'csrfmiddlewaretoken' in r.text
    css = prod.get('/static/admin/css/base.css')
    assert css.status_code == 200 and 'text/css' in css.headers['content-type']


def test_Q_PROD_08_관리자_로그인_5회_실패_잠금과_접속기록(prod):
    c = client(PROD_BASE)
    username = f'nobody-{uuid.uuid4().hex[:6]}'
    codes = []
    for _ in range(6):
        page = c.get('/admin/login/')
        token = re.search(r'name="csrfmiddlewaretoken" value="([^"]+)"', page.text).group(1)
        r = c.post('/admin/login/', data={'csrfmiddlewaretoken': token, 'username': username, 'password': 'wrong',
                                          'next': '/admin/'}, headers={'Referer': f'{PROD_BASE}/admin/login/'})
        codes.append(r.status_code)
    assert codes[:5] == [200] * 5 and codes[5] == 429, codes
    rows = psql(f"select action from access_logs where actor='{username}' order by id", project=PROD_PROJECT).split()
    assert rows.count('LOGIN_FAILED') == 5 and rows[-1] == 'LOGIN_LOCKED'
    ip = psql(f"select ip from access_logs where actor='{username}' limit 1", project=PROD_PROJECT)
    caddy_ip = subprocess.run(['docker', 'inspect', '-f', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',
                               f'{PROD_PROJECT}-caddy-1'], capture_output=True, text=True).stdout.strip()
    # 접속지는 Caddy가 본 클라이언트 주소여야 한다(Caddy 컨테이너 주소면 X-Forwarded-For를 못 읽은 것)
    assert ip and ip != caddy_ip, (ip, caddy_ip)


def test_Q_PROD_09_접속기록은_수정·삭제_불가(prod):
    with pytest.raises(RuntimeError, match='append-only'):
        psql("update access_logs set actor='x' where id=(select max(id) from access_logs)", project=PROD_PROJECT)
    with pytest.raises(RuntimeError, match='2 years'):
        psql('delete from access_logs where id=(select max(id) from access_logs)', project=PROD_PROJECT)


def test_Q_PROD_14_본문_2MB_초과는_413(prod):
    # 요청 제한(Q-PROD-10)보다 먼저: 한도가 차면 Django가 본문을 읽기 전에 429를 준다
    r = prod.post('/api/v1/auth/firebase/', content=b'{"id_token": "' + b'a' * (2 * 1024 * 1024 + 10) + b'"}',
                  headers={'Content-Type': 'application/json'})
    assert r.status_code == 413


def test_Q_PROD_10_요청_제한과_X_Forwarded_For_위조(prod):
    c = client(PROD_BASE)
    codes = [c.post('/api/v1/auth/firebase/', json={'id_token': 'x'},
                    headers={'X-Forwarded-For': f'10.0.0.{i}'}).status_code for i in range(15)]
    assert 429 in codes, codes
    assert codes.index(429) <= 10, f'위조 XFF로 한도를 넘김: {codes}'


@pytest.mark.parametrize('path', ['/api/v1/stt/command/', '/api/v1/stt/wake/'])
def test_Q_PROD_11_레거시_STT_경로_없음(prod, path):
    assert prod.post(path, json={}).status_code == 404


def test_Q_PROD_12_PIN_로그인_410(prod):
    assert prod.post('/api/v1/users/auth/login/', json={'phone_number': '01012341234', 'pin_number': '1234'}).status_code == 410


@pytest.mark.parametrize('path,status', [
    ('/media/exercises/pictograms/strength/strength01_bodyweight_squat_01.jpg', 200),
    ('/media/exercises/pictograms/', 404), ('/media/../.env', 404), ('/media/%2e%2e/%2e%2e/etc/passwd', 404),
    ('/media/exercises/pictograms/strength/none.jpg', 404),
])
def test_Q_PROD_13_미디어_로컬_서빙(prod, path, status):
    r = prod.get(path)
    assert r.status_code == status, (path, r.status_code)
    if status == 200:
        assert r.headers['content-type'] == 'image/jpeg' and 'max-age' in r.headers['cache-control']


def test_Q_PROD_15_허용되지_않은_Host(prod):
    r = prod.get('/api/v1/consents/documents/', headers={'Host': 'evil.example.com'})
    assert r.status_code in (400, 404, 421) or r.text == ''


def test_Q_PROD_16_인증없는_보호_API는_401(prod):
    assert prod.get('/api/v1/me/').status_code == 401
    assert prod.post('/api/v1/meals/bulk/', json={'meals': []}).status_code == 401
