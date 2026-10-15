"""
앱 업데이트(APK) 게시 — deploy/provisional/publish_apk.sh 와 같은 결과를 서버 안에서 만든다

폴더: settings.APP_UPDATE_DIR(= media 볼륨의 app/, Caddy가 /media/app/ 으로 서빙)
  seesun-prov-<versionCode>.apk   APK
  manifest-<versionCode>.json     그 버전의 매니페스트
  latest.json                     지금 공개 매니페스트(앱이 시작할 때·6시간마다 읽는다)
매니페스트 형식(키 순서·들여쓰기 2칸·끝 줄바꿈까지 스크립트와 같게):
  {"versionCode", "versionName", "minVersionCode", "apkUrl", "sha256", "sizeBytes", "notes", "publishedAt"}
순서: 임시 이름으로 받기(sha256·크기 계산) → APK 확인(zip·서명·패키지·versionCode·versionName) → 이름 바꾸기 →
  manifest-<code>.json 원자적 저장 → latest.json 원자적 교체(같은 폴더 임시 파일 → os.replace) → 최신 + 이전 3개만 남기고 정리.
지금 공개된 versionCode 이하로는 게시하지 않는다(설치된 앱은 낮은 버전으로 내려가지 않는다).
"""
import hashlib
import json
import os
import re
import struct
import time
import zipfile
from datetime import datetime, timezone as dt_timezone
from pathlib import Path

from django.conf import settings

APK_RE = re.compile(r'^seesun-prov-(\d+)\.apk$')
MANIFEST_RE = re.compile(r'^manifest-(\d+)\.json$')
LOCK_NAME = '.ops-publish.lock'
LOCK_STALE_SECONDS = 15 * 60

ANDROID_ATTR_IDS = {0x0101021b: 'versionCode', 0x0101021c: 'versionName'}


class PublishError(Exception):
    pass


def app_dir() -> Path:
    return Path(settings.APP_UPDATE_DIR)


# ---- 매니페스트 읽기 --------------------------------------------------------------------------------
def read_latest():
    path = app_dir() / 'latest.json'
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {'_error': 'latest.json을 읽지 못했습니다(JSON 형식 오류).'}


def history_on_disk():
    """서버에 남은 manifest-<code>.json 목록(새 버전부터). APK 파일이 있는지도 함께."""
    folder = app_dir()
    if not folder.exists():
        return []
    rows = []
    for entry in folder.iterdir():
        m = MANIFEST_RE.match(entry.name)
        if not m:
            continue
        try:
            data = json.loads(entry.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            data = {}
        code = int(m.group(1))
        rows.append({'code': code, 'manifest': data, 'apk_present': (folder / f'seesun-prov-{code}.apk').exists()})
    return sorted(rows, key=lambda r: r['code'], reverse=True)


def manifest_json(manifest: dict) -> str:
    """publish_apk.sh 의 heredoc과 같은 모양: 2칸 들여쓰기, 한글 그대로, 끝 줄바꿈"""
    return json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'


def build_manifest(*, code, name, min_code, apk_url, sha256, size, notes, published_at=None):
    published_at = published_at or datetime.now(dt_timezone.utc)
    return {
        'versionCode': int(code),
        'versionName': name,
        'minVersionCode': int(min_code),
        'apkUrl': apk_url,
        'sha256': sha256,
        'sizeBytes': int(size),
        'notes': notes,
        'publishedAt': published_at.strftime('%Y-%m-%dT%H:%M:%SZ'),
    }


def base_url(request=None):
    if settings.APP_UPDATE_BASE_URL:
        return settings.APP_UPDATE_BASE_URL
    latest = read_latest() or {}
    apk_url = latest.get('apkUrl') if isinstance(latest, dict) else None
    if apk_url and '/' in apk_url:
        return apk_url.rsplit('/', 1)[0]
    domain = os.environ.get('API_DOMAIN', '').strip()
    if domain and domain != 'localhost':
        return f'https://{domain}/media/app'
    if request is not None:
        return request.build_absolute_uri(f"{settings.MEDIA_URL.rstrip('/')}/app")
    return f"{settings.MEDIA_URL.rstrip('/')}/app"


# ---- APK 확인 --------------------------------------------------------------------------------------
def _string_pool(data, offset):
    _, header_size, _, count, _, flags, strings_start, _ = struct.unpack_from('<HHIIIIII', data, offset)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f'<{count}I', data, offset + header_size)
    base = offset + strings_start
    strings = []
    for rel in offsets:
        pos = base + rel
        if utf8:
            n = data[pos]
            pos += 2 if n & 0x80 else 1
            length = data[pos]
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            strings.append(data[pos:pos + length].decode('utf-8', errors='replace'))
        else:
            length = struct.unpack_from('<H', data, pos)[0]
            pos += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack_from('<H', data, pos)[0]
                pos += 2
            strings.append(data[pos:pos + length * 2].decode('utf-16-le', errors='replace'))
    return strings


def parse_manifest_axml(data: bytes) -> dict:
    """바이너리 AndroidManifest.xml에서 <manifest>의 package·versionCode·versionName만 읽는다"""
    if len(data) < 8 or struct.unpack_from('<H', data, 0)[0] != 0x0003:
        raise PublishError('AndroidManifest.xml 형식이 아닙니다.')
    strings, res_ids = [], []
    pos = struct.unpack_from('<H', data, 2)[0]
    while pos + 8 <= len(data):
        chunk_type, header_size, size = struct.unpack_from('<HHI', data, pos)
        if size < 8:
            break
        if chunk_type == 0x0001:
            strings = _string_pool(data, pos)
        elif chunk_type == 0x0180:
            res_ids = list(struct.unpack_from(f'<{(size - header_size) // 4}I', data, pos + header_size))
        elif chunk_type == 0x0102:
            name_idx = struct.unpack_from('<I', data, pos + 20)[0]
            if strings[name_idx] != 'manifest':
                raise PublishError('AndroidManifest.xml의 첫 요소가 manifest가 아닙니다.')
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, pos + 24)
            result = {}
            for i in range(attr_count):
                a = pos + 16 + attr_start + i * attr_size
                _ns, a_name, raw, _sz, _res0, dtype, value = struct.unpack_from('<IIIHBBI', data, a)
                key = strings[a_name] if a_name < len(strings) else ''
                if a_name < len(res_ids) and res_ids[a_name] in ANDROID_ATTR_IDS:
                    key = ANDROID_ATTR_IDS[res_ids[a_name]]
                if key not in ('package', 'versionCode', 'versionName'):
                    continue
                if dtype == 0x03:
                    result[key] = strings[raw if raw != 0xFFFFFFFF else value]
                elif dtype in (0x10, 0x11):
                    result[key] = value
                elif raw != 0xFFFFFFFF:
                    result[key] = strings[raw]
            return result
        pos += size
    raise PublishError('AndroidManifest.xml에서 manifest 요소를 찾지 못했습니다.')


def _has_v2_signing_block(path: Path) -> bool:
    """APK Signing Block(v2·v3 서명): 중앙 디렉터리 바로 앞에 'APK Sig Block 42'"""
    size = path.stat().st_size
    with path.open('rb') as fh:
        tail_len = min(size, 65536 + 22)
        fh.seek(size - tail_len)
        tail = fh.read()
        eocd = tail.rfind(b'PK\x05\x06')
        if eocd < 0:
            return False
        cd_offset = struct.unpack_from('<I', tail, eocd + 16)[0]
        if cd_offset < 16:
            return False
        fh.seek(cd_offset - 16)
        return fh.read(16) == b'APK Sig Block 42'


def inspect_apk(path: Path) -> dict:
    if not zipfile.is_zipfile(path):
        raise PublishError('APK(zip) 파일이 아닙니다.')
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())
        if 'AndroidManifest.xml' not in names:
            raise PublishError('APK 안에 AndroidManifest.xml이 없습니다.')
        info = parse_manifest_axml(zf.read('AndroidManifest.xml'))
        v1 = any(n.startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC')) for n in names)
    info['signed'] = v1 or _has_v2_signing_block(path)
    return info


# ---- 게시 ------------------------------------------------------------------------------------------
class _Lock:
    def __init__(self, folder: Path):
        self.path = folder / LOCK_NAME
        self.fd = None

    def __enter__(self):
        try:
            if self.path.exists() and time.time() - self.path.stat().st_mtime > LOCK_STALE_SECONDS:
                self.path.unlink()
            self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise PublishError('다른 게시가 진행 중입니다. 잠시 후 다시 시도해 주세요.') from None
        return self

    def __exit__(self, *exc):
        os.close(self.fd)
        try:
            self.path.unlink()
        except OSError:
            pass


def _atomic_write(target: Path, text: str):
    tmp = target.with_name(f'.{target.name}.tmp')
    tmp.write_text(text, encoding='utf-8', newline='\n')
    os.chmod(tmp, 0o644)
    os.replace(tmp, target)


def current_version_code():
    latest = read_latest()
    if isinstance(latest, dict) and isinstance(latest.get('versionCode'), int):
        return latest['versionCode']
    return None


def publish(upload, *, code, name, notes, min_code=0, actor='', url_base):
    """upload: Django UploadedFile. 반환: 매니페스트(dict). 실패는 PublishError(파일은 남기지 않는다)."""
    from .models import AppRelease

    if min_code > code:
        raise PublishError(f'minVersionCode({min_code})가 versionCode({code})보다 큽니다.')
    if upload.size > settings.OPS_APK_MAX_BYTES:
        raise PublishError(f'APK가 너무 큽니다({upload.size // (1024 * 1024)}MB, 한도 '
                           f'{settings.OPS_APK_MAX_BYTES // (1024 * 1024)}MB).')
    folder = app_dir()
    folder.mkdir(parents=True, exist_ok=True)
    with _Lock(folder):
        current = current_version_code()
        if current is not None and code <= current:
            raise PublishError(f'versionCode {code}는 지금 공개된 {current} 이하입니다. 더 큰 번호로 빌드해 주세요.')
        if AppRelease.objects.filter(version_code=code).exists():
            raise PublishError(f'versionCode {code}는 이미 게시한 적이 있습니다.')

        file_name = f'seesun-prov-{code}.apk'
        tmp = folder / f'.{file_name}.tmp'
        digest, size = hashlib.sha256(), 0
        try:
            with tmp.open('wb') as out:
                for chunk in upload.chunks():
                    digest.update(chunk)
                    size += len(chunk)
                    out.write(chunk)
            info = inspect_apk(tmp)
            if not info.get('signed'):
                raise PublishError('서명되지 않은 APK입니다. 릴리스 키로 서명한 APK를 올려 주세요.')
            expected = {'package': settings.APP_UPDATE_PACKAGE, 'versionCode': code, 'versionName': name}
            found = {k: info.get(k) for k in expected}
            if found != expected:
                raise PublishError(f'APK의 패키지·버전({found["package"]}, {found["versionCode"]}, {found["versionName"]})이 '
                                   f'입력값({expected["package"]}, {code}, {name})과 다릅니다.')
            os.chmod(tmp, 0o644)
            os.replace(tmp, folder / file_name)
        finally:
            if tmp.exists():
                tmp.unlink()

        sha = digest.hexdigest()
        manifest = build_manifest(code=code, name=name, min_code=min_code, apk_url=f'{url_base}/{file_name}',
                                  sha256=sha, size=size, notes=notes)
        text = manifest_json(manifest)
        _atomic_write(folder / f'manifest-{code}.json', text)
        _atomic_write(folder / 'latest.json', text)
        pruned = prune(folder, code)
        AppRelease.objects.create(
            version_code=code, version_name=name, min_version_code=min_code, sha256=sha, size_bytes=size,
            apk_url=manifest['apkUrl'], notes=notes, actor=actor,
            published_at=datetime.strptime(manifest['publishedAt'], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=dt_timezone.utc),
        )
    manifest['_pruned'] = pruned
    return manifest


def prune(folder: Path, current_code: int, keep=None):
    """지금 버전과 이전 keep개(기본 3)만 남긴다(publish_apk.sh 3단계와 같음). 지운 versionCode 목록."""
    keep = settings.APP_UPDATE_KEEP_PREVIOUS if keep is None else keep
    codes = sorted(int(m.group(1)) for m in (APK_RE.match(p.name) for p in folder.iterdir()) if m)
    others = [c for c in codes if c != current_code]
    doomed = others[:-keep] if keep > 0 else others
    for old in doomed:
        for name in (f'seesun-prov-{old}.apk', f'manifest-{old}.json'):
            try:
                (folder / name).unlink()
            except FileNotFoundError:
                pass
    for stray in folder.glob('.*.tmp'):
        stray.unlink(missing_ok=True)
    return doomed
