"""대시보드(개수만)와 약관·기록 화면"""
from datetime import timedelta

import pytest
from django.urls import reverse
from django.utils import timezone

from apps.consents.models import ConsentRecord
from apps.core.audit import record
from apps.core.testing import make_user
from apps.logs.models import ExerciseSession
from apps.users.models import User

from .helpers import login, make_staff

pytestmark = pytest.mark.django_db


@pytest.fixture
def admin_client(client):
    login(client, make_staff())
    return client


def test_dashboard_counts(admin_client):
    a = make_user('01011110001', sensitive=True, diseases=['HYPERTENSION'])
    b = make_user('01011110002')
    c = make_user('01011110003')
    User.objects.filter(pk=c.pk).update(is_active=False)
    old = make_user('01011110004')
    User.objects.filter(pk=old.pk).update(date_joined=timezone.now() - timedelta(days=40), last_login=None)
    now = timezone.now()
    User.objects.filter(pk=a.pk).update(last_login=now)
    ExerciseSession.objects.create(user=b, mode='MANUAL', started_at=now - timedelta(days=1))
    ExerciseSession.objects.create(user=b, mode='MANUAL', started_at=now - timedelta(days=2))
    ConsentRecord.objects.filter(user=a).update(app_version='0.3.0')
    ConsentRecord.objects.filter(user=b).update(app_version='0.2.0')

    response = admin_client.get(reverse('ops:dashboard'))
    s = response.context['s']
    assert s['totals'] == {'total': 4, 'active_accounts': 3, 'suspended': 1, 'withdrawn': 0}  # 직원 제외
    assert s['active_7d'] == 2  # a(접속), b(운동)
    assert s['signups_sum'] == 3 and len(s['signups']) == 14
    assert s['sessions_sum'] == 2 and s['sessions_7d'] == 2
    assert {v['version'] for v in s['versions']} == {'0.3.0', '0.2.0'}
    body = response.content.decode()
    assert '고혈압' not in body and 'HYPERTENSION' not in body and '01011110001' not in body


def test_dashboard_without_version_data_omits_section(admin_client):
    make_user('01011110005')
    body = admin_client.get(reverse('ops:dashboard')).content.decode()
    assert '앱 버전 분포' not in body


def test_records_lists_consent_docs_and_filters_logs(admin_client):
    make_user('01011110006')
    record('COMMAND', actor='deployer', path='manage.py shell')
    record('VIEW', actor='someone', target_type='users.user')
    response = admin_client.get(reverse('ops:records'), {'action': 'COMMAND'})
    body = response.content.decode()
    assert response.status_code == 200
    assert '이용약관' in body
    assert [l.action for l in response.context['page']] == ['COMMAND']
    bad_date = admin_client.get(reverse('ops:records'), {'since': 'not-a-date'})
    assert bad_date.status_code == 200
    since = admin_client.get(reverse('ops:records'), {'since': timezone.localdate().isoformat(), 'actor': 'deploy'})
    assert [l.actor for l in since.context['page']] == ['deployer']
