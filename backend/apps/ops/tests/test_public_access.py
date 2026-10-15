"""
운영 콘솔을 어느 IP에서나(OPS_ALLOWED_IPS=any) 열 때의 보호 — 2026-10-15 소유자 결정 msg 993

/ops/ 는 비밀번호 + 2단계 인증으로만 막고, 장고 기본 관리자 화면(/admin/)은 허용 IP에서만.
"""
import time
import types

import pyotp
import pytest
from django.test import Client, override_settings
from django.urls import reverse

from apps.core.models import AccessLog
from apps.core.testing import make_user, staff_login
from apps.ops import twofactor
from apps.users.models import User

pytestmark = pytest.mark.django_db

OUTSIDE = '203.0.113.9'
PW = 'Console-Login-Pass-58'


def outside_client(**kwargs):
    return Client(REMOTE_ADDR=OUTSIDE, **kwargs)


def enrolled_staff(username='ops-public', superuser=True):
    user = User.objects.create_user(username=username, password=PW, is_staff=True, is_superuser=superuser)
    security = twofactor.security_for(user)
    security.otp_secret = pyotp.random_base32()
    from django.utils import timezone
    security.otp_confirmed_at = timezone.now()
    security.save()
    return user


def current_code(user, offset_steps=0):
    secret = twofactor.security_for(user).otp_secret
    return pyotp.TOTP(secret).generate_otp(int(time.time()) // 30 + offset_steps)


# ---- 어느 IP에서나 ------------------------------------------------------------------------------------
@override_settings(OPS_ALLOWED_IPS='any', ADMIN_ALLOWED_IPS='private_ranges')
def test_console_and_login_reachable_from_any_ip():
    c = outside_client()
    r = c.get('/ops/')
    assert r.status_code == 302 and r['Location'].startswith('/ops/login/?next=')
    assert c.get('/ops/login/').status_code == 200

    user = enrolled_staff()
    r = c.post('/ops/login/', {'username': 'ops-public', 'password': PW, 'next': '/ops/users/'})
    assert r.status_code == 302 and r['Location'] == '/ops/users/'
    r = c.get('/ops/users/')
    assert r['Location'].startswith(reverse('ops:account_otp_verify'))
    assert c.get(r['Location']).status_code == 200  # 단계 화면도 밖에서 열린다
    r = c.post(reverse('ops:account_otp_verify'), {'code': current_code(user), 'next': '/ops/users/'})
    assert r.status_code == 302 and r['Location'] == '/ops/users/'
    assert c.get('/ops/users/').status_code == 200
    login = AccessLog.objects.filter(action='LOGIN', actor='ops-public').order_by('at')
    assert [l.ip for l in login] == [OUTSIDE, OUTSIDE]  # 비밀번호 단계·코드 단계 모두 IP와 함께
    assert login.last().detail == 'otp:ok'


@override_settings(OPS_ALLOWED_IPS='any', ADMIN_ALLOWED_IPS='private_ranges')
def test_raw_admin_stays_ip_restricted():
    c = outside_client()
    for path in ('/admin/', '/admin/login/', '/admin/core/accesslog/', '/admin/logout/'):
        assert c.get(path).status_code == 404, path
    # 2단계까지 마친 직원이어도 밖에서는 /admin/ 이 열리지 않는다
    staff_login(c, enrolled_staff())
    assert c.get('/admin/').status_code == 404
    assert c.get('/ops/').status_code == 200
    # 허용 IP(사설 대역·SSH 터널)에서는 그대로
    inside = Client(REMOTE_ADDR='127.0.0.1')
    assert inside.get('/admin/login/').status_code == 200
    assert Client().get('/admin/login/', HTTP_X_FORWARDED_FOR='172.18.0.1').status_code == 200


@override_settings(OPS_ALLOWED_IPS='', ADMIN_ALLOWED_IPS='private_ranges')
def test_empty_ops_list_means_no_access_but_default_setting_follows_admin_list():
    """설정이 빈 문자열이면 아무 IP도 허용하지 않는다(실수로 열리지 않게). 환경값을 비우면 settings.py가 ADMIN 목록을 쓴다."""
    assert outside_client().get('/ops/login/').status_code == 404
    from apps.ops.access import ip_allowed
    assert ip_allowed('203.0.113.9', 'any') and ip_allowed(None, 'ANY')
    assert not ip_allowed('203.0.113.9', 'private_ranges')


# ---- 로그인 오류 문구·기록 ---------------------------------------------------------------------------
@override_settings(OPS_ALLOWED_IPS='any')
def test_generic_error_does_not_reveal_accounts():
    enrolled_staff('real-staff')
    member = make_user('01088880001')
    member.set_password(PW)
    member.save()
    inactive = User.objects.create_user(username='off-staff', password=PW, is_staff=True, is_active=False)
    cases = [('nobody-here', PW), ('real-staff', 'wrong-password'), (member.username, PW), (inactive.username, PW)]
    bodies = set()
    for username, password in cases:
        r = outside_client().post('/ops/login/', {'username': username, 'password': password})
        assert r.status_code == 400
        body = r.content.decode()
        assert '계정 이름 또는 비밀번호가 맞지 않습니다.' in body
        bodies.add(body.split('role="alert">', 1)[1].split('</div>', 1)[0])
    assert len(bodies) == 1
    failed = AccessLog.objects.filter(action='LOGIN_FAILED')
    assert failed.count() == 4 and set(failed.values_list('ip', flat=True)) == {OUTSIDE}


# ---- 잠금: 비밀번호·코드 두 단계를 함께 센다 ----------------------------------------------------------
@override_settings(OPS_ALLOWED_IPS='any', LOGIN_MAX_FAILURES=5)
def test_lockout_counts_password_and_code_failures_together():
    user = enrolled_staff()
    c = outside_client()
    for _ in range(3):
        assert c.post('/ops/login/', {'username': 'ops-public', 'password': 'nope'}).status_code == 400
    assert c.post('/ops/login/', {'username': 'ops-public', 'password': PW}).status_code == 302
    assert c.post(reverse('ops:account_otp_verify'), {'code': '000000'}).status_code == 400
    assert c.post(reverse('ops:account_otp_verify'), {'code': '000000'}).status_code == 429  # 3 + 2 = 5
    assert c.post(reverse('ops:account_otp_verify'), {'code': current_code(user)}).status_code == 429
    # 다시 로그인해도 잠김(비밀번호 단계도 같은 카운터)
    fresh = outside_client()
    assert fresh.post('/ops/login/', {'username': 'ops-public', 'password': PW}).status_code == 429
    assert AccessLog.objects.filter(action='LOGIN_LOCKED', actor='ops-public').count() == 3


@override_settings(OPS_ALLOWED_IPS='any', LOGIN_MAX_FAILURES=5)
def test_relogin_does_not_reset_code_failures():
    enrolled_staff()
    c = outside_client()
    c.post('/ops/login/', {'username': 'ops-public', 'password': PW})
    for _ in range(4):
        c.post(reverse('ops:account_otp_verify'), {'code': '000000'})
    c.post('/ops/logout/')
    assert c.post('/ops/login/', {'username': 'ops-public', 'password': PW}).status_code == 302
    assert c.post(reverse('ops:account_otp_verify'), {'code': '000000'}).status_code == 429


@override_settings(OPS_ALLOWED_IPS='any', LOGIN_MAX_FAILURES=5)
def test_successful_code_clears_counter():
    user = enrolled_staff()
    c = outside_client()
    for _ in range(3):
        c.post('/ops/login/', {'username': 'ops-public', 'password': 'nope'})
    c.post('/ops/login/', {'username': 'ops-public', 'password': PW})
    assert c.post(reverse('ops:account_otp_verify'), {'code': current_code(user)}).status_code == 302
    c.post('/ops/logout/')
    for _ in range(4):
        assert c.post('/ops/login/', {'username': 'ops-public', 'password': 'nope'}).status_code == 400


# ---- 세션 ---------------------------------------------------------------------------------------------
def _age_session(client, key, seconds):
    session = client.session
    session[key] = time.time() - seconds
    session.save()


@pytest.mark.parametrize('key,seconds,reason', [
    (twofactor.LAST_SEEN_KEY, 31 * 60, 'session_idle'),
    (twofactor.AUTH_AT_KEY, 8 * 3600 + 5, 'session_max'),
])
def test_staff_session_times_out(client, key, seconds, reason):
    staff_login(client, enrolled_staff())
    assert client.get('/ops/').status_code == 200
    _age_session(client, key, seconds)
    r = client.get('/ops/users/')
    assert r.status_code == 302 and r['Location'] == '/ops/login/?next=/ops/users/'
    assert client.get('/ops/')['Location'].startswith('/ops/login/')  # 로그아웃됨
    assert AccessLog.objects.filter(action='LOGOUT', detail=reason).exists()


def test_activity_keeps_session_alive(client):
    staff_login(client, enrolled_staff())
    _age_session(client, twofactor.LAST_SEEN_KEY, 29 * 60)
    assert client.get('/ops/').status_code == 200
    assert time.time() - client.session[twofactor.LAST_SEEN_KEY] < 5


def test_admin_session_times_out_too(client):
    staff_login(client, enrolled_staff())
    _age_session(client, twofactor.LAST_SEEN_KEY, 31 * 60)
    r = client.get('/admin/')
    assert r.status_code == 302 and r['Location'].startswith('/admin/login/')


@override_settings(OPS_ALLOWED_IPS='any')
def test_cookies_are_secure_httponly_lax():
    enrolled_staff()
    c = outside_client()
    r = c.post('/ops/login/', {'username': 'ops-public', 'password': PW})
    cookie = r.cookies['sessionid']
    assert cookie['httponly'] and cookie['samesite'] == 'Lax' and cookie['secure']
    assert int(cookie['max-age']) == 8 * 3600


@override_settings(OPS_ALLOWED_IPS='any')
def test_csrf_is_enforced_on_console_forms():
    enrolled_staff()
    c = outside_client(enforce_csrf_checks=True)
    page = c.get('/ops/login/')
    assert 'csrfmiddlewaretoken' in page.content.decode()
    assert c.post('/ops/login/', {'username': 'ops-public', 'password': PW}).status_code == 403
    token = c.cookies['csrftoken'].value
    assert c.post('/ops/login/', {'username': 'ops-public', 'password': PW,
                                  'csrfmiddlewaretoken': token}).status_code == 302


# ---- 응답 헤더 --------------------------------------------------------------------------------------
@override_settings(OPS_ALLOWED_IPS='any')
def test_security_headers_on_console_pages(client):
    pages = [outside_client().get('/ops/login/'), outside_client().get('/ops/')]
    staff_login(client, enrolled_staff())
    pages += [client.get('/ops/'), client.get('/ops/users/'), client.get(reverse('ops:account_otp_verify'))]
    for r in pages:
        assert r['X-Frame-Options'] == 'DENY'
        assert 'no-store' in r['Cache-Control'] and 'private' in r['Cache-Control']
        assert r['X-Robots-Tag'] == 'noindex, nofollow'
    assert '<meta name="robots" content="noindex, nofollow">' in pages[0].content.decode()
    api = client.get('/healthz/')
    assert 'X-Robots-Tag' not in api


def test_logout_requires_post(client):
    staff_login(client, enrolled_staff())
    assert client.get('/ops/logout/').status_code == 405
    r = client.post('/ops/logout/')
    assert r.status_code == 302 and r['Location'] == '/ops/login/'
    assert client.get('/ops/')['Location'].startswith('/ops/login/')
    assert AccessLog.objects.filter(action='LOGOUT', actor='ops-public').exists()
