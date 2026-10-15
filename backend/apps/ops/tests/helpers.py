"""운영 콘솔 테스트 도우미: 직원 계정, 가짜 APK(바이너리 매니페스트), 작은 PNG·MP3"""
import io
import struct
import zipfile
import zlib

from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile


def make_staff(username='ops-admin', superuser=True):
    User = get_user_model()
    user = User.objects.create(username=username, is_staff=True, is_superuser=superuser)
    user.set_password('pw-for-tests-only')
    user.save()
    return user


def login(client, user):
    from apps.core.testing import staff_login
    return staff_login(client, user) if user.is_staff else (client.force_login(user) or client)


# ---- 가짜 APK -------------------------------------------------------------------------------------
def _string_pool(strings):
    count = len(strings)
    data = b''
    offsets = []
    for s in strings:
        offsets.append(len(data))
        encoded = s.encode('utf-16-le')
        data += struct.pack('<H', len(s)) + encoded + b'\x00\x00'
    while len(data) % 4:
        data += b'\x00'
    header_size = 28
    strings_start = header_size + 4 * count
    size = strings_start + len(data)
    return (struct.pack('<HHIIIIII', 0x0001, header_size, size, count, 0, 0, strings_start, 0)
            + struct.pack(f'<{count}I', *offsets) + data)


def axml_manifest(package='kr.seesun.app', version_code=5, version_name='0.5.0'):
    strings = ['versionCode', 'versionName', 'package', 'manifest', version_name, package]
    pool = _string_pool(strings)
    res_map = struct.pack('<HHI', 0x0180, 8, 8 + 8) + struct.pack('<II', 0x0101021b, 0x0101021c)
    android_ns = 0xFFFFFFFF
    attrs = [
        struct.pack('<IIIHBBI', android_ns, 0, 0xFFFFFFFF, 8, 0, 0x10, version_code),
        struct.pack('<IIIHBBI', android_ns, 1, 4, 8, 0, 0x03, 4),
        struct.pack('<IIIHBBI', 0xFFFFFFFF, 2, 5, 8, 0, 0x03, 5),
    ]
    body = struct.pack('<IIHHHHHH', 0xFFFFFFFF, 3, 20, 20, len(attrs), 0, 0, 0) + b''.join(attrs)
    element = struct.pack('<HHIII', 0x0102, 16, 16 + len(body), 1, 0xFFFFFFFF) + body
    payload = pool + res_map + element
    return struct.pack('<HHI', 0x0003, 8, 8 + len(payload)) + payload


def fake_apk(signed=True, **manifest):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as zf:
        zf.writestr('AndroidManifest.xml', axml_manifest(**manifest))
        zf.writestr('classes.dex', b'dex\n035\x00' + b'\x00' * 64)
        if signed:
            zf.writestr('META-INF/CERT.RSA', b'\x30\x82fake-signature-for-tests')
    return buf.getvalue()


def apk_upload(**kwargs):
    return SimpleUploadedFile('app.apk', fake_apk(**kwargs), content_type='application/vnd.android.package-archive')


# ---- 작은 미디어 파일 ------------------------------------------------------------------------------
def png_bytes():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
    ihdr = struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)
    raw = zlib.compress(b'\x00\xff\x00\x00')
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', raw) + chunk(b'IEND', b'')


def mp3_bytes():
    return b'ID3\x04\x00\x00\x00\x00\x00\x00' + b'\xff\xfb\x90\x64' + b'\x00' * 400
