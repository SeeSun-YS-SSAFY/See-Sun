"""새 앱 버전 알림(FCM 토픽 푸시) — firebase_admin.messaging.send 는 가짜로 바꿔 끼운다"""
import io
import json
from datetime import timedelta

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError
from django.urls import reverse
from firebase_admin import messaging

from apps.core.models import AccessLog
from apps.ops import push
from apps.ops.models import AppPush
from apps.users import firebase

from .helpers import apk_upload, login, make_staff

pytestmark = pytest.mark.django_db

BASE = 'https://seesun-prov.example.org/media/app'
MANIFEST = {'versionCode': 7, 'versionName': '0.7.0', 'minVersionCode': 0, 'apkUrl': f'{BASE}/seesun-prov-7.apk',
            'sha256': 'ab' * 32, 'sizeBytes': 10, 'notes': 'x', 'publishedAt': '2026-10-15T00:00:00Z'}


@pytest.fixture
def fcm(monkeypatch):
    """보낸 메시지를 모은다. fcm['fail']에 예외를 넣으면 실패를 흉내 낸다."""
    state = {'sent': [], 'fail': None}

    def fake_send(message, dry_run=False, app=None):
        if state['fail']:
            raise state['fail']
        state['sent'].append({'message': message, 'dry_run': dry_run, 'app': app})
        return f'projects/seesun-test/messages/{len(state["sent"])}'

    monkeypatch.setattr(messaging, 'send', fake_send)
    monkeypatch.setattr(push, '_app', lambda: 'APP')
    return state


@pytest.fixture
def app_dir(settings, tmp_path):
    settings.APP_UPDATE_DIR = tmp_path / 'app'
    settings.APP_UPDATE_BASE_URL = BASE
    return tmp_path / 'app'


@pytest.fixture
def admin_client(client):
    login(client, make_staff())
    return client


def write_latest(app_dir, manifest=MANIFEST):
    app_dir.mkdir(parents=True, exist_ok=True)
    (app_dir / 'latest.json').write_text(json.dumps(manifest), encoding='utf-8')


def test_message_shape_and_topic(fcm):
    result = push.send_app_update(MANIFEST, actor='tester', source='command')
    assert result.ok and result.message_id == 'projects/seesun-test/messages/1'
    sent = fcm['sent'][0]
    msg = sent['message']
    assert sent['app'] == 'APP' and sent['dry_run'] is False
    assert msg.topic == 'seesun-app-updates'
    assert msg.data == {'type': 'app_update', 'versionCode': '7', 'versionName': '0.7.0',
                        'apkUrl': f'{BASE}/seesun-prov-7.apk', 'sha256': 'ab' * 32}
    assert all(isinstance(v, str) for v in msg.data.values())
    assert msg.notification is None  # 데이터 메시지만
    assert msg.android.priority == 'high'
    assert msg.android.ttl == timedelta(days=7)
    assert msg.android.collapse_key == 'app_update'
    # FCM 형식으로 직렬화해도 같은지(firebase_admin 인코더)
    from firebase_admin import _messaging_encoder
    encoded = _messaging_encoder.MessageEncoder().default(msg)
    assert encoded['topic'] == 'seesun-app-updates' and encoded['android']['ttl'] == '604800s'
    assert encoded['android']['priority'] == 'high' and encoded['android']['collapse_key'] == 'app_update'


def test_topic_is_configurable(fcm, settings):
    settings.OPS_APP_UPDATE_TOPIC = 'seesun-app-updates-staging'
    push.send_app_update(MANIFEST, actor='tester', source='command')
    assert fcm['sent'][0]['message'].topic == 'seesun-app-updates-staging'


def test_success_is_recorded(fcm):
    push.send_app_update(MANIFEST, actor='tester', source='command', ip='127.0.0.1')
    row = AppPush.objects.get()
    assert row.ok and row.version_code == 7 and row.topic == 'seesun-app-updates' and row.source == 'command'
    log = AccessLog.objects.get(target_type='ops.apppush')
    assert log.action == 'ADD' and log.status == 200 and log.target_id == '7'
    assert log.detail == 'push:ok topic=seesun-app-updates id=projects/seesun-test/messages/1'


@pytest.mark.parametrize('error', [RuntimeError('fcm down'), firebase.FirebaseNotConfigured()])
def test_failure_is_tolerated_and_recorded(fcm, error):
    fcm['fail'] = error
    result = push.send_app_update(MANIFEST, actor='tester', source='resend')
    assert not result.ok and result.error
    row = AppPush.objects.get()
    assert not row.ok and row.error == result.error
    log = AccessLog.objects.get(target_type='ops.apppush')
    assert log.status == 502 and log.detail.startswith('push:fail topic=seesun-app-updates')


def test_without_service_account_it_reports_not_configured(settings):
    settings.FIREBASE_CREDENTIALS_FILE = ''
    result = push.send_app_update(MANIFEST, actor='tester', source='command')
    assert not result.ok and result.error.startswith('NOT_CONFIGURED')


# ---- 콘솔 ----------------------------------------------------------------------------------------
def test_publish_sends_push_after_manifest(admin_client, app_dir, fcm):
    order = []
    original_send = messaging.send

    def check_manifest_first(message, dry_run=False, app=None):
        order.append(json.loads((app_dir / 'latest.json').read_text(encoding='utf-8'))['versionCode'])
        return original_send(message, dry_run=dry_run, app=app)

    messaging.send = check_manifest_first
    try:
        response = admin_client.post(reverse('ops:app_publish'), {
            'apk': apk_upload(version_code=5, version_name='0.5.0'), 'version_code': 5, 'version_name': '0.5.0',
            'notes': '알림 시험'}, follow=True)
    finally:
        messaging.send = original_send
    assert order == [5]  # latest.json을 쓴 뒤에 보냈다
    data = fcm['sent'][0]['message'].data
    assert data['versionCode'] == '5' and data['apkUrl'] == f'{BASE}/seesun-prov-5.apk'
    body = response.content.decode()
    assert '푸시 보냄 (메시지 ID projects/seesun-test/messages/1)' in body
    assert body.count('푸시 보냄') >= 3  # 알림 메시지, 지금 매니페스트 칸, 이력 행


def test_publish_succeeds_even_when_push_fails(admin_client, app_dir, fcm):
    fcm['fail'] = RuntimeError('fcm down')
    response = admin_client.post(reverse('ops:app_publish'), {
        'apk': apk_upload(version_code=5, version_name='0.5.0'), 'version_code': 5, 'version_name': '0.5.0',
        'notes': '알림 시험'}, follow=True)
    assert json.loads((app_dir / 'latest.json').read_text(encoding='utf-8'))['versionCode'] == 5
    body = response.content.decode()
    assert '게시했습니다' in body and '푸시 실패: RuntimeError: fcm down' in body
    assert 'msg warning' in body


def test_resend_button_requires_superuser(client, app_dir, fcm):
    write_latest(app_dir)
    login(client, make_staff('viewer', superuser=False))
    assert client.post(reverse('ops:app_push_resend')).status_code == 403
    assert not fcm['sent'] and not AppPush.objects.exists()
    assert AccessLog.objects.filter(status=403, detail='denied=not_superuser').exists()


def test_resend_sends_current_version_and_audits(admin_client, app_dir, fcm):
    write_latest(app_dir)
    assert admin_client.get(reverse('ops:app_push_resend')).status_code == 405
    r = admin_client.post(reverse('ops:app_push_resend'), follow=True)
    assert fcm['sent'][0]['message'].data['versionCode'] == '7'
    assert AppPush.objects.get().source == 'resend'
    log = AccessLog.objects.get(target_type='ops.apppush')
    assert log.actor == 'ops-admin' and log.path == '/ops/app/push/' and log.ip == '127.0.0.1'
    assert '업데이트 알림 다시 보내기' in r.content.decode()


def test_resend_without_manifest(admin_client, app_dir, fcm):
    r = admin_client.post(reverse('ops:app_push_resend'), follow=True)
    assert not fcm['sent'] and '보낼 알림이 없습니다' in r.content.decode()


# ---- 관리 명령 -----------------------------------------------------------------------------------
def test_command_sends_current_manifest_and_can_repeat(app_dir, fcm, monkeypatch):
    monkeypatch.setenv('SEESUN_OPERATOR', 'publish_apk.sh')
    write_latest(app_dir)
    for _ in range(2):
        out = io.StringIO()
        call_command('ops_push_app_update', stdout=out)
        assert '푸시 보냄: 토픽 seesun-app-updates, versionCode 7' in out.getvalue()
    assert len(fcm['sent']) == 2 and AppPush.objects.filter(source='command', actor='publish_apk.sh').count() == 2
    assert AccessLog.objects.filter(action='COMMAND', path='manage.py ops_push_app_update').count() == 2


def test_command_dry_run_does_not_record(app_dir, fcm):
    write_latest(app_dir)
    call_command('ops_push_app_update', dry_run=True, stdout=io.StringIO())
    assert fcm['sent'][0]['dry_run'] is True and not AppPush.objects.exists()


def test_command_failures(app_dir, fcm):
    with pytest.raises(CommandError, match='latest.json'):
        call_command('ops_push_app_update', stdout=io.StringIO())
    write_latest(app_dir)
    fcm['fail'] = RuntimeError('fcm down')
    with pytest.raises(CommandError, match='푸시 실패'):
        call_command('ops_push_app_update', stdout=io.StringIO())
    assert AppPush.objects.get().error


# ---- firebase.py: 서비스 계정 모드(FIREBASE_CREDENTIALS_FILE)에서의 토큰 검증 ----------------------------
def test_admin_app_requires_service_account(settings):
    settings.FIREBASE_CREDENTIALS_FILE = ''
    with pytest.raises(firebase.FirebaseNotConfigured):
        firebase.admin_app()
    settings.FIREBASE_CREDENTIALS_FILE = '/nonexistent/key.json'
    with pytest.raises(firebase.FirebaseNotConfigured):
        firebase.admin_app()


@pytest.mark.parametrize('error,expected', [
    ('user_not_found', firebase.InvalidIdToken),
    ('unavailable', firebase.FirebaseNotConfigured),
])
def test_verify_with_service_account_maps_revocation_check_errors(monkeypatch, error, expected):
    from firebase_admin import auth, exceptions
    verifier = firebase.FirebaseAdminVerifier(credentials_file='key.json', project_id='p', check_revoked=True)
    monkeypatch.setattr(verifier, '_get_app', lambda: 'APP')
    seen = {}

    def fake_verify(token, app=None, check_revoked=False):
        seen['check_revoked'] = check_revoked
        if error == 'user_not_found':
            raise auth.UserNotFoundError('gone')
        raise exceptions.UnavailableError('down')

    monkeypatch.setattr(auth, 'verify_id_token', fake_verify)
    with pytest.raises(expected):
        verifier.verify('token')
    assert seen['check_revoked'] is True  # 서비스 계정이 있으면 폐기 확인을 켠다
