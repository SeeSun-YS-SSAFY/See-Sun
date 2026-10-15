"""사용자 화면: 가림 처리, 건강정보 미노출, 접속기록, 이용 중지, 기존 탈퇴 절차"""
import re

import pytest
from django.urls import reverse

from apps.core.models import AccessLog
from apps.core.testing import make_user
from apps.users.models import FirebaseDeletionTask, User

from .helpers import login, make_staff

pytestmark = pytest.mark.django_db

PHONE = '01077770001'


@pytest.fixture
def member():
    return make_user(PHONE, sensitive=True, diseases=['DIABETES'], allergens=[], height_cm=187, weight_kg=93,
                     firebase_uid='fb-test-uid-0001')


@pytest.fixture
def admin_client(client):
    login(client, make_staff())
    return client


def _text(response):
    return response.content.decode('utf-8')


def test_list_masks_phone_and_name(admin_client, member):
    body = _text(admin_client.get(reverse('ops:users')))
    assert PHONE not in body and '7777' not in body
    assert '홍길동' not in body and '홍*동' in body
    assert f'#{member.phone_hash[:8]}' in body


def test_detail_has_no_health_or_body_data(admin_client, member):
    response = admin_client.get(reverse('ops:user_detail', args=[member.pk]))
    body = _text(response)
    assert response.status_code == 200
    for leaked in (PHONE, '홍길동', '당뇨', 'DIABETES', '고혈압', '93kg', 'fb-test-uid-0001'):
        assert leaked not in body, leaked
    # 키 187: 맨 '187'은 CSRF 토큰·UUID·해시 안에 우연히 나올 수 있어(CI 간헐 실패) 낱말로 떨어진 숫자만 본다
    assert not re.search(r'(?<![0-9A-Za-z])187(?![0-9])', body), '187'
    assert '홍*동' in body
    for key in ('height_cm', 'weight_kg', 'birthdate', 'gender', 'diseases', 'target_kcal', 'phone_number'):
        assert key not in response.context['u']


def test_pages_never_decrypt_phone(admin_client, member, monkeypatch):
    """목록·상세는 values()로 해시만 읽는다 → 전화번호 복호화 함수가 불리지 않아야 한다"""
    field = User._meta.get_field('phone_number')

    def boom(value, expression, connection):
        if value is not None:  # 직원 계정(전화번호 없음)을 읽는 것은 괜찮다
            raise AssertionError('전화번호를 복호화했습니다')
        return value

    monkeypatch.setattr(field, 'from_db_value', boom, raising=False)
    assert admin_client.get(reverse('ops:users')).status_code == 200
    assert admin_client.get(reverse('ops:user_detail', args=[member.pk])).status_code == 200


def test_phone_search_uses_hash_and_query_is_not_logged(admin_client, member):
    make_user('01077770002')
    response = admin_client.get(reverse('ops:users'), {'q': PHONE})
    assert [r['id'] for r in response.context['rows']] == [member.pk]
    log = AccessLog.objects.filter(action='VIEW', target_type='users.user').latest('at')
    assert PHONE not in log.detail and log.detail == 'params=q'


def test_uuid_prefix_and_status_filters(admin_client, member):
    response = admin_client.get(reverse('ops:users'), {'q': str(member.pk)[:8]})
    assert [r['id'] for r in response.context['rows']] == [member.pk]
    User.objects.filter(pk=member.pk).update(is_active=False)
    suspended = admin_client.get(reverse('ops:users'), {'status': 'suspended'})
    assert [r['id'] for r in suspended.context['rows']] == [member.pk]


def test_detail_view_is_audited_with_subject(admin_client, member):
    admin_client.get(reverse('ops:user_detail', args=[member.pk]))
    log = AccessLog.objects.get(action='VIEW', subject_id=str(member.pk))
    assert log.actor == 'ops-admin' and log.target_type == 'users.user'


def test_suspend_and_unsuspend(admin_client, member):
    admin_client.post(reverse('ops:user_suspend', args=[member.pk]))
    member.refresh_from_db()
    assert member.is_active is False and member.is_deleted is False
    assert AccessLog.objects.filter(action='CHANGE', subject_id=str(member.pk), detail='ops:suspend').exists()
    admin_client.post(reverse('ops:user_unsuspend', args=[member.pk]))
    member.refresh_from_db()
    assert member.is_active is True
    assert AccessLog.objects.filter(action='CHANGE', subject_id=str(member.pk), detail='ops:unsuspend').exists()


def test_suspended_user_cannot_use_api(admin_client, member):
    from apps.core.testing import client_for  # noqa: F401  (force_authenticate는 is_active를 건너뛰어 JWT로 확인)
    from rest_framework.test import APIClient
    from rest_framework_simplejwt.tokens import RefreshToken

    token = str(RefreshToken.for_user(member).access_token)
    admin_client.post(reverse('ops:user_suspend', args=[member.pk]))
    api = APIClient()
    api.credentials(HTTP_AUTHORIZATION=f'Bearer {token}')
    assert api.get('/api/v1/me/').status_code == 401


def test_withdraw_uses_existing_deletion_path(admin_client, member, monkeypatch):
    calls = []
    original = User.withdraw

    def spy(self):
        calls.append(self.pk)
        return original(self)

    monkeypatch.setattr(User, 'withdraw', spy)
    response = admin_client.post(reverse('ops:user_withdraw', args=[member.pk]), {'confirm': str(member.pk)[:8]})
    assert response.status_code == 302
    assert calls == [member.pk]
    member.refresh_from_db()
    assert member.is_deleted and not member.is_active and member.phone_number is None and member.name is None
    from apps.health.models import HealthProfile
    assert not HealthProfile.objects.filter(user_id=member.pk).exists()
    # Firebase 삭제는 기존 receiver가 처리 → 테스트 환경(서비스 계정 없음)에서는 재시도 대기열에 남는다
    assert FirebaseDeletionTask.objects.filter(firebase_uid='fb-test-uid-0001').exists()
    log = AccessLog.objects.get(action='DELETE', subject_id=str(member.pk), status=200)
    assert 'User.withdraw' in log.detail


def test_withdraw_requires_typed_confirmation(admin_client, member):
    admin_client.post(reverse('ops:user_withdraw', args=[member.pk]), {'confirm': 'wrong'})
    member.refresh_from_db()
    assert not member.is_deleted
    assert AccessLog.objects.filter(action='DELETE', status=409, subject_id=str(member.pk)).exists()


def test_staff_accounts_are_not_touched(admin_client):
    other = make_staff('other-admin', superuser=False)
    admin_client.post(reverse('ops:user_withdraw', args=[other.pk]), {'confirm': str(other.pk)[:8]})
    admin_client.post(reverse('ops:user_suspend', args=[other.pk]))
    other.refresh_from_db()
    assert other.is_active and not other.is_deleted


def test_no_raw_delete(admin_client, member):
    """탈퇴 처리 뒤에도 행은 남는다(비식별) — 원시 delete()로 지우지 않는다"""
    admin_client.post(reverse('ops:user_withdraw', args=[member.pk]), {'confirm': str(member.pk)[:8]})
    assert User.objects.filter(pk=member.pk, is_deleted=True).exists()
