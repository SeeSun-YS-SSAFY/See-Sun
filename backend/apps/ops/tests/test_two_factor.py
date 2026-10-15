"""관리자 화면 2단계 인증(TOTP)·첫 로그인 비밀번호 변경·관리자 만들기 명령"""
import io
import logging
import time
import types

import pyotp
import pytest
from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import Client
from django.urls import reverse

from apps.core.models import AccessLog
from apps.core.testing import make_user
from apps.ops import twofactor
from apps.ops.models import AdminSecurity, RecoveryCode
from apps.users.models import User

pytestmark = pytest.mark.django_db

TEMP_PW = 'Temp-Bootstrap-Pass-73'
NEW_PW = 'Fresh-Console-Pass-91'


@pytest.fixture
def clock(monkeypatch):
    """TOTP 시계(30초 구간)를 테스트에서 움직인다"""
    state = {'now': 1_800_000_000.0}
    monkeypatch.setattr(twofactor, 'time', types.SimpleNamespace(time=lambda: state['now']))
    return state


def code_for(user, clock, offset=0):
    secret = AdminSecurity.objects.get(user=user).otp_secret
    return pyotp.TOTP(secret).generate_otp(int(clock['now'] + offset) // 30)


def bootstrap(monkeypatch, username='ops-admin', password=TEMP_PW):
    monkeypatch.setenv('OPS_ADMIN_PASSWORD', password)
    out, err = io.StringIO(), io.StringIO()
    call_command('ops_bootstrap_admin', username, stdout=out, stderr=err)
    return out.getvalue(), err.getvalue()


def password_login(client, username='ops-admin', password=TEMP_PW, next_url='/ops/'):
    return client.post(f'/admin/login/?next={next_url}', {'username': username, 'password': password,
                                                          'next': next_url})


def enroll(client, user, clock):
    """비밀번호 변경까지 끝난 세션에서 등록을 마치고 복구 코드를 돌려준다"""
    client.get(reverse('ops:account_otp_setup'))
    response = client.post(reverse('ops:account_otp_setup'), {'code': code_for(user, clock), 'next': '/ops/'})
    assert response.status_code == 200
    return response.context['codes']


def test_full_first_login_flow(client, monkeypatch, clock):
    bootstrap(monkeypatch)
    user = User.objects.get(username='ops-admin')
    assert user.is_superuser and user.is_staff and user.admin_security.must_change_password

    response = password_login(client)
    assert response.status_code == 302 and response['Location'] == '/ops/'
    # 비밀번호 변경 전에는 /ops/ 도 /admin/ 도 열리지 않는다
    for path in ('/ops/', '/ops/users/', '/admin/', '/admin/core/accesslog/'):
        r = client.get(path)
        assert r.status_code == 302 and r['Location'].startswith(reverse('ops:account_password')), path
    # 등록 화면으로 바로 가도 비밀번호 변경부터
    assert client.get(reverse('ops:account_otp_setup'))['Location'].startswith(reverse('ops:account_password'))

    # 약한 비밀번호는 거부(Django 비밀번호 규칙)
    weak = client.post(reverse('ops:account_password'), {'old_password': TEMP_PW, 'new_password1': '12345678',
                                                         'new_password2': '12345678', 'next': '/ops/'})
    assert weak.status_code == 200
    user.admin_security.refresh_from_db()
    assert user.admin_security.must_change_password

    changed = client.post(reverse('ops:account_password'), {'old_password': TEMP_PW, 'new_password1': NEW_PW,
                                                            'new_password2': NEW_PW, 'next': '/ops/'})
    assert changed.status_code == 302 and changed['Location'].startswith(reverse('ops:account_otp_setup'))
    user.refresh_from_db()
    assert user.check_password(NEW_PW) and not user.admin_security.must_change_password
    assert AccessLog.objects.filter(action='CHANGE', detail='password_changed', actor='ops-admin').exists()

    # 아직 등록 전: 콘솔은 등록 화면으로
    assert client.get('/ops/')['Location'].startswith(reverse('ops:account_otp_setup'))
    page = client.get(reverse('ops:account_otp_setup'))
    body = page.content.decode()
    assert page.status_code == 200 and '<svg' in body and '<script src="http' not in body  # QR은 서버에서 그린 SVG
    assert client.post(reverse('ops:account_otp_setup'), {'code': '000000'}).status_code == 400
    codes = enroll(client, user, clock)
    assert len(codes) == 10 and len(set(codes)) == 10
    # 복구 코드는 해시로만 저장
    stored = ' '.join(RecoveryCode.objects.filter(user=user).values_list('code_hash', flat=True))
    assert all(c.replace('-', '') not in stored for c in codes)
    assert AccessLog.objects.filter(action='CHANGE', detail='otp:enrolled').exists()

    assert client.get('/ops/').status_code == 200
    assert client.get('/admin/').status_code == 200


def _enrolled_admin(client, monkeypatch, clock):
    bootstrap(monkeypatch)
    user = User.objects.get(username='ops-admin')
    password_login(client)
    client.post(reverse('ops:account_password'), {'old_password': TEMP_PW, 'new_password1': NEW_PW,
                                                  'new_password2': NEW_PW})
    codes = enroll(client, user, clock)
    client.post('/admin/logout/')
    return user, codes


def test_code_required_on_every_login_and_no_replay(monkeypatch, clock):
    client = Client()
    user, _ = _enrolled_admin(client, monkeypatch, clock)
    used = code_for(user, clock)  # 등록 때 쓴 구간의 코드

    password_login(client, password=NEW_PW)
    r = client.get('/admin/')
    assert r.status_code == 302 and r['Location'].startswith(reverse('ops:account_otp_verify'))
    assert client.get('/ops/')['Location'].startswith(reverse('ops:account_otp_verify'))
    # 같은 구간의 코드는 다시 받지 않는다
    assert client.post(reverse('ops:account_otp_verify'), {'code': used, 'next': '/ops/'}).status_code == 400
    clock['now'] += 30
    ok = client.post(reverse('ops:account_otp_verify'), {'code': code_for(user, clock), 'next': '/ops/users/'})
    assert ok.status_code == 302 and ok['Location'] == '/ops/users/'
    assert client.get('/ops/').status_code == 200 and client.get('/admin/').status_code == 200
    assert AccessLog.objects.filter(action='LOGIN', detail='otp:ok', actor='ops-admin').exists()
    assert AccessLog.objects.filter(action='LOGIN_FAILED', detail='otp:fail').exists()

    # 다시 로그인하면 또 코드를 받는다
    client.post('/admin/logout/')
    password_login(client, password=NEW_PW)
    assert client.get('/ops/')['Location'].startswith(reverse('ops:account_otp_verify'))


def test_recovery_code_is_single_use(monkeypatch, clock):
    client = Client()
    user, codes = _enrolled_admin(client, monkeypatch, clock)
    password_login(client, password=NEW_PW)
    assert client.post(reverse('ops:account_otp_verify'), {'code': codes[0].upper()}).status_code == 302
    assert client.get('/ops/').status_code == 200
    assert AccessLog.objects.filter(action='LOGIN', detail='otp:recovery left=9').exists()
    client.post('/admin/logout/')

    password_login(client, password=NEW_PW)
    assert client.post(reverse('ops:account_otp_verify'), {'code': codes[0]}).status_code == 400
    assert client.post(reverse('ops:account_otp_verify'), {'code': codes[1]}).status_code == 302
    assert RecoveryCode.objects.filter(user=user, used_at__isnull=False).count() == 2


def test_otp_failures_lock_out(monkeypatch, clock, settings):
    settings.LOGIN_MAX_FAILURES = 5
    client = Client()
    user, _ = _enrolled_admin(client, monkeypatch, clock)
    password_login(client, password=NEW_PW)
    clock['now'] += 60
    for i in range(4):
        assert client.post(reverse('ops:account_otp_verify'), {'code': '000000'}).status_code == 400
    assert client.post(reverse('ops:account_otp_verify'), {'code': '000000'}).status_code == 429
    # 잠긴 뒤에는 맞는 코드도 받지 않는다
    assert client.post(reverse('ops:account_otp_verify'), {'code': code_for(user, clock)}).status_code == 429
    assert client.get('/ops/').status_code == 302
    assert AccessLog.objects.filter(action='LOGIN_LOCKED', detail='otp:locked', actor='ops-admin').exists()
    assert AccessLog.objects.filter(action='LOGIN_FAILED', detail='otp:fail').count() == 5


def test_reset_otp_command_forces_reenrollment(monkeypatch, clock):
    client = Client()
    user, _ = _enrolled_admin(client, monkeypatch, clock)
    password_login(client, password=NEW_PW)
    clock['now'] += 30
    client.post(reverse('ops:account_otp_verify'), {'code': code_for(user, clock)})
    assert client.get('/ops/').status_code == 200
    call_command('ops_reset_otp', 'ops-admin', stdout=io.StringIO())
    assert not RecoveryCode.objects.filter(user=user).exists()
    # 이미 확인된 세션도 다시 등록해야 한다
    assert client.get('/ops/')['Location'].startswith(reverse('ops:account_otp_setup'))
    assert AccessLog.objects.filter(path='manage.py ops_reset_otp').exists()
    with pytest.raises(CommandError):
        call_command('ops_reset_otp', 'nobody', stdout=io.StringIO())


def test_staff_session_without_otp_is_gated_on_admin_and_ops(client):
    staff = User.objects.create_user(username='plain-staff', password=NEW_PW, is_staff=True, is_superuser=True)
    client.force_login(staff)
    for path in ('/admin/', '/admin/core/accesslog/', '/admin/users/firebasedeletiontask/', '/ops/', '/ops/app/'):
        r = client.get(path)
        assert r.status_code == 302 and r['Location'].startswith(reverse('ops:account_otp_setup')), path
    # 쓰기 요청도 막힌다
    member = make_user('01099990001')
    assert client.post(reverse('ops:user_suspend', args=[member.pk])).status_code == 302
    member.refresh_from_db()
    assert member.is_active


def test_non_staff_and_api_are_not_affected(client):
    member = make_user('01099990002')
    client.force_login(member)
    assert client.get('/ops/').status_code == 403
    assert client.get('/healthz/').status_code == 200


def test_bootstrap_never_leaks_password(monkeypatch, caplog):
    caplog.set_level(logging.DEBUG)
    out, err = bootstrap(monkeypatch)
    assert TEMP_PW not in out and TEMP_PW not in err and TEMP_PW not in caplog.text
    assert 'ops-admin' in out
    for row in AccessLog.objects.all():
        assert TEMP_PW not in ' '.join(str(v) for v in (row.actor, row.path, row.detail, row.target_id))
    log = AccessLog.objects.get(action='COMMAND', path='manage.py ops_bootstrap_admin')
    assert set(log.detail.removeprefix('options=').split(',')) <= {'password_env', 'username', 'stdout', 'stderr'}
    user = User.objects.get(username='ops-admin')
    assert user.check_password(TEMP_PW) and TEMP_PW not in user.password


def test_bootstrap_does_not_accept_password_argument(monkeypatch):
    monkeypatch.delenv('OPS_ADMIN_PASSWORD', raising=False)
    with pytest.raises(CommandError) as exc:
        call_command('ops_bootstrap_admin', 'ops-admin', stdout=io.StringIO())
    assert 'OPS_ADMIN_PASSWORD' in str(exc.value)
    with pytest.raises((CommandError, TypeError)):
        call_command('ops_bootstrap_admin', 'ops-admin', password=TEMP_PW, stdout=io.StringIO())
    assert not User.objects.filter(username='ops-admin').exists()


def test_bootstrap_rejects_weak_password_without_echo(monkeypatch):
    weak = 'ops-admin1'
    monkeypatch.setenv('OPS_ADMIN_PASSWORD', weak)
    with pytest.raises(CommandError) as exc:
        call_command('ops_bootstrap_admin', 'ops-admin', stdout=io.StringIO())
    assert weak not in str(exc.value)


def test_bootstrap_refuses_app_member_and_keeps_otp_on_reset(monkeypatch, clock):
    make_user('01099990003')
    with pytest.raises(CommandError):
        bootstrap(monkeypatch, username='u_01099990003')
    client = Client()
    user, _ = _enrolled_admin(client, monkeypatch, clock)
    out, _ = bootstrap(monkeypatch, password='Another-Temp-Pass-55')
    user.refresh_from_db()
    assert user.check_password('Another-Temp-Pass-55') and user.admin_security.must_change_password
    assert user.admin_security.otp_confirmed_at is not None  # 2단계 등록은 그대로(ops_reset_otp로만 지움)
    # 등록된 계정이 비밀번호를 다시 받으면: 코드 확인 → 비밀번호 변경 순서
    password_login(client, password='Another-Temp-Pass-55')
    assert client.get('/ops/')['Location'].startswith(reverse('ops:account_otp_verify'))
    clock['now'] += 30
    r = client.post(reverse('ops:account_otp_verify'), {'code': code_for(user, clock), 'next': '/ops/'})
    assert r['Location'].startswith(reverse('ops:account_password'))


def test_totp_window_and_format():
    secret = pyotp.random_base32()
    now = time.time()
    totp = pyotp.TOTP(secret)
    assert twofactor.match_step(secret, totp.generate_otp(int(now) // 30 - 1), now=now) == int(now) // 30 - 1
    assert twofactor.match_step(secret, totp.generate_otp(int(now) // 30 - 3), now=now) is None
    assert twofactor.match_step(secret, 'abcdef', now=now) is None
    assert twofactor.match_step(secret, '', now=now) is None
