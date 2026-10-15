"""운영 콘솔 접근 통제: 로그인·직원·최고 관리자·허용 IP"""
import pytest
from django.urls import URLPattern, reverse

from apps.core.models import AccessLog
from apps.ops import urls as ops_urls
from apps.ops.access import ip_allowed
from apps.core.testing import make_user

from .helpers import login, make_staff

pytestmark = pytest.mark.django_db

READ_PAGES = ['ops:dashboard', 'ops:users', 'ops:exercises', 'ops:app_release', 'ops:records']


@pytest.mark.parametrize('name', READ_PAGES)
def test_anonymous_is_sent_to_admin_login(client, name):
    url = reverse(name)
    response = client.get(url)
    assert response.status_code == 302
    assert response['Location'].startswith('/ops/login/?next=')


@pytest.mark.parametrize('name', READ_PAGES)
def test_non_staff_user_is_refused(client, name):
    member = make_user('01055550001')
    login(client, member)
    assert client.get(reverse(name)).status_code == 403


@pytest.mark.parametrize('name', READ_PAGES)
def test_staff_reads_every_page(client, name):
    login(client, make_staff(superuser=False))
    assert client.get(reverse(name)).status_code == 200


def test_every_ops_url_is_protected():
    views = [p.callback for p in ops_urls.urlpatterns if isinstance(p, URLPattern)]
    assert views and all(getattr(v, 'ops_protected', False) for v in views)


def test_outside_ip_gets_404_even_when_logged_in(client):
    login(client, make_staff())
    assert client.get(reverse('ops:dashboard'), REMOTE_ADDR='203.0.113.9').status_code == 404
    # Caddy 뒤: X-Forwarded-For의 마지막 값(Caddy가 본 주소)으로 판단 — 앞에 끼운 위조 값은 무시
    spoofed = client.get(reverse('ops:dashboard'), HTTP_X_FORWARDED_FOR='127.0.0.1, 203.0.113.9')
    assert spoofed.status_code == 404
    tunnel = client.get(reverse('ops:dashboard'), HTTP_X_FORWARDED_FOR='172.18.0.1')
    assert tunnel.status_code == 200


def test_allowed_ip_list_follows_admin_setting(client, settings):
    settings.OPS_ALLOWED_IPS = 'private_ranges 203.0.113.10/32'
    login(client, make_staff())
    assert client.get(reverse('ops:dashboard'), REMOTE_ADDR='203.0.113.10').status_code == 200
    assert client.get(reverse('ops:dashboard'), REMOTE_ADDR='203.0.113.11').status_code == 404


def test_ip_parsing():
    assert ip_allowed('10.1.2.3', 'private_ranges')
    assert ip_allowed('::1', 'private_ranges')
    assert not ip_allowed('8.8.8.8', 'private_ranges')
    assert ip_allowed('8.8.8.8', 'private_ranges, 8.8.8.0/24')
    assert not ip_allowed('8.8.8.8', '<관리자 PC 공인 IP>/32')  # 자리표시가 남아 있으면 아무것도 열지 않는다
    assert not ip_allowed(None, 'private_ranges')


def test_staff_without_superuser_cannot_write(client):
    member = make_user('01055550002')
    login(client, make_staff(superuser=False))
    response = client.post(reverse('ops:user_suspend', args=[member.pk]))
    assert response.status_code == 403
    member.refresh_from_db()
    assert member.is_active
    assert AccessLog.objects.filter(action='CHANGE', status=403, detail='denied=not_superuser').exists()


def test_write_endpoints_reject_get(client):
    member = make_user('01055550003')
    login(client, make_staff())
    assert client.get(reverse('ops:user_withdraw', args=[member.pk])).status_code == 405
    member.refresh_from_db()
    assert not member.is_deleted


def test_console_login_has_admin_lockout(client, settings):
    """콘솔 로그인(/ops/login/)도 관리자 로그인과 같은 실패 잠금"""
    make_staff('lock-me')
    for _ in range(settings.LOGIN_MAX_FAILURES):
        client.post('/ops/login/', {'username': 'lock-me', 'password': 'wrong'})
    locked = client.post('/ops/login/', {'username': 'lock-me', 'password': 'pw-for-tests-only'})
    assert locked.status_code == 429
