"""앱 버전 게시: publish_apk.sh 와 같은 매니페스트 형식, versionCode 가드, APK 확인, 정리, 접속기록"""
import hashlib
import json
import re
from pathlib import Path

import pytest
from django.urls import reverse

from apps.core.models import AccessLog
from apps.ops import apk
from apps.ops.models import AppRelease

from .helpers import apk_upload, axml_manifest, fake_apk, login, make_staff

pytestmark = pytest.mark.django_db

BASE = 'https://seesun-prov.example.org/media/app'
REPO = Path(__file__).resolve().parents[4]


@pytest.fixture
def app_dir(settings, tmp_path):
    settings.APP_UPDATE_DIR = tmp_path / 'app'
    settings.APP_UPDATE_BASE_URL = BASE
    return tmp_path / 'app'


@pytest.fixture
def admin_client(client):
    login(client, make_staff())
    return client


def publish(client, code=5, name='0.5.0', notes='운동 화면을 고쳤어요.', min_code=0, **apk_kwargs):
    apk_kwargs.setdefault('version_code', code)
    apk_kwargs.setdefault('version_name', name)
    return client.post(reverse('ops:app_publish'), {
        'apk': apk_upload(**apk_kwargs), 'version_code': code, 'version_name': name, 'notes': notes,
        'min_version_code': min_code,
    })


def test_parser_reads_synthetic_manifest():
    assert apk.parse_manifest_axml(axml_manifest('kr.seesun.app', 7, '0.7.0')) == {
        'versionCode': 7, 'versionName': '0.7.0', 'package': 'kr.seesun.app'}


def test_publish_writes_script_compatible_manifest(admin_client, app_dir):
    response = publish(admin_client, notes='첫 줄 "따옴표"\n둘째 줄 \\ 역슬래시')
    assert response.status_code == 302
    data = fake_apk(version_code=5, version_name='0.5.0')
    apk_file = app_dir / 'seesun-prov-5.apk'
    assert apk_file.read_bytes() == data
    latest = (app_dir / 'latest.json').read_bytes()
    assert latest == (app_dir / 'manifest-5.json').read_bytes()
    text = latest.decode('utf-8')
    manifest = json.loads(text)
    assert list(manifest) == ['versionCode', 'versionName', 'minVersionCode', 'apkUrl', 'sha256', 'sizeBytes',
                              'notes', 'publishedAt']
    assert manifest['apkUrl'] == f'{BASE}/seesun-prov-5.apk'
    assert manifest['sha256'] == hashlib.sha256(data).hexdigest() and manifest['sizeBytes'] == len(data)
    assert manifest['notes'] == '첫 줄 "따옴표"\n둘째 줄 \\ 역슬래시'
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', manifest['publishedAt'])
    # publish_apk.sh heredoc과 같은 모양(2칸 들여쓰기, 숫자는 따옴표 없이, 한글 그대로, 끝 줄바꿈)
    expected = (
        '{\n'
        '  "versionCode": 5,\n'
        '  "versionName": "0.5.0",\n'
        '  "minVersionCode": 0,\n'
        f'  "apkUrl": "{BASE}/seesun-prov-5.apk",\n'
        f'  "sha256": "{manifest["sha256"]}",\n'
        f'  "sizeBytes": {len(data)},\n'
        '  "notes": "첫 줄 \\"따옴표\\"\\n둘째 줄 \\\\ 역슬래시",\n'
        f'  "publishedAt": "{manifest["publishedAt"]}"\n'
        '}\n'
    )
    assert text == expected
    assert not list(app_dir.glob('.*.tmp'))
    release = AppRelease.objects.get(version_code=5)
    assert release.actor == 'ops-admin' and release.sha256 == manifest['sha256']
    assert AccessLog.objects.filter(action='ADD', target_type='ops.apprelease', target_id='5', status=200).exists()


def test_script_field_order_matches_publish_apk_sh():
    """publish_apk.sh 의 heredoc 키 순서가 바뀌면 이 테스트로 알 수 있게"""
    script = (REPO / 'deploy' / 'provisional' / 'publish_apk.sh').read_text(encoding='utf-8')
    heredoc = script.split('MANIFEST=$(cat <<EOF', 1)[1].split('\nEOF', 1)[0]
    keys = re.findall(r'"(\w+)":', heredoc)
    assert keys == list(apk.build_manifest(code=1, name='a', min_code=0, apk_url='u', sha256='s', size=1, notes='n'))


@pytest.mark.parametrize('code', [4, 5])
def test_version_code_must_increase(admin_client, app_dir, code):
    publish(admin_client, code=5)
    before = (app_dir / 'latest.json').read_bytes()
    publish(admin_client, code=code, name='0.4.0' if code == 4 else '0.5.0')
    assert (app_dir / 'latest.json').read_bytes() == before
    assert AppRelease.objects.count() == 1
    assert AccessLog.objects.filter(action='ADD', status=400, detail='ops:apk_publish refused').exists()


def test_guard_reads_manifest_written_by_script(admin_client, app_dir):
    """스크립트가 먼저 게시한 latest.json(versionCode 3)이 있으면 3 이하는 막는다"""
    app_dir.mkdir(parents=True)
    (app_dir / 'latest.json').write_text(json.dumps({'versionCode': 3, 'apkUrl': f'{BASE}/seesun-prov-3.apk'}),
                                         encoding='utf-8')
    publish(admin_client, code=3, name='0.3.0')
    assert not (app_dir / 'seesun-prov-3.apk').exists()
    publish(admin_client, code=4, name='0.4.0')
    assert json.loads((app_dir / 'latest.json').read_text(encoding='utf-8'))['versionCode'] == 4


@pytest.mark.parametrize('kwargs', [
    {'package': 'com.other.app'},
    {'version_code': 6},
    {'version_name': '9.9.9'},
    {'signed': False},
])
def test_apk_contents_must_match(admin_client, app_dir, kwargs):
    publish(admin_client, code=5, name='0.5.0', **kwargs)
    assert not (app_dir / 'latest.json').exists()
    assert not (app_dir / 'seesun-prov-5.apk').exists()
    assert not list(app_dir.glob('.*'))  # 임시 파일·잠금 파일도 남지 않음


def test_not_an_apk_is_refused(admin_client, app_dir):
    from django.core.files.uploadedfile import SimpleUploadedFile
    admin_client.post(reverse('ops:app_publish'), {
        'apk': SimpleUploadedFile('x.apk', b'hello'), 'version_code': 5, 'version_name': '0.5.0', 'notes': 'x'})
    assert not (app_dir / 'latest.json').exists()


def test_min_version_and_required_fields(admin_client, app_dir):
    publish(admin_client, code=5, min_code=6)
    publish(admin_client, code=5, notes='')
    assert not (app_dir / 'latest.json').exists()
    publish(admin_client, code=5, min_code=5)
    assert json.loads((app_dir / 'latest.json').read_text(encoding='utf-8'))['minVersionCode'] == 5


def test_prune_keeps_current_and_three_previous(admin_client, app_dir):
    for code in range(1, 7):
        publish(admin_client, code=code, name=f'0.{code}.0')
    apks = sorted(int(p.stem.rsplit('-', 1)[1]) for p in app_dir.glob('seesun-prov-*.apk'))
    manifests = sorted(int(p.stem.split('-')[1]) for p in app_dir.glob('manifest-*.json'))
    assert apks == manifests == [3, 4, 5, 6]
    assert AppRelease.objects.count() == 6  # 이력은 전부 남는다


def test_page_shows_current_manifest(admin_client, app_dir):
    publish(admin_client, code=5)
    body = admin_client.get(reverse('ops:app_release')).content.decode()
    assert 'seesun-prov-5.apk' in body and '공개 중' in body and 'value="6"' in body


def test_base_url_falls_back_to_current_manifest(settings, app_dir):
    settings.APP_UPDATE_BASE_URL = ''
    app_dir.mkdir(parents=True)
    (app_dir / 'latest.json').write_text(json.dumps({'versionCode': 3, 'apkUrl': 'https://x.example/media/app/seesun-prov-3.apk'}),
                                         encoding='utf-8')
    assert apk.base_url() == 'https://x.example/media/app'
