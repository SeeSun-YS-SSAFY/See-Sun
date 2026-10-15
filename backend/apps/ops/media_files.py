"""
운동 픽토그램·안내 음성 파일 교체(되돌릴 수 있게)

- 이전 파일은 지우지도 덮어쓰지도 않는다. 새 파일은 ``exercises/ops/<미디어 ID 앞 12자>/<종류>-<시각>-<임의 6자>.<확장자>``
  (버전이 붙은 새 이름)로 두고, 미디어 행의 s3_key·url(·checksum)만 새 파일로 돌린다. 원본 폴더
  (exercises/pictograms/<분류>/)에 두지 않는 것은 1차 update_media가 파일 이름 끝 번호를 그림 순서로 읽기 때문이다.
- 주소가 바뀌므로 앱 캐시도 새 파일을 받는다(음성은 checksum이 ?v= 로 붙는다).
- 픽토그램의 보기용 사본(display_key)은 옛 그림이라 비운다 → 앱은 새 원본 그림을 쓴다. 되돌리면 다시 채운다.
- 형식은 파일 앞부분(매직 바이트)으로 확인한다: 그림 JPEG·PNG·WebP, 음성 MP3. 크기 한도는 설정값.
- CDN(MEDIA_CDN_BASE_URL)을 쓰는 환경에서는 서버 디스크에 둔 파일이 CDN에 없으므로 교체하지 않는다.
"""
import hashlib
import secrets
from pathlib import Path

from django.conf import settings
from django.db import transaction
from django.utils import timezone

IMAGE_TYPES = {'jpg': 'image/jpeg', 'png': 'image/png', 'webp': 'image/webp'}
AUDIO_TYPES = {'mp3': 'audio/mpeg'}


class MediaError(Exception):
    pass


def sniff(head: bytes):
    """(확장자, MIME) 또는 None"""
    if head[:3] == b'\xff\xd8\xff':
        return 'jpg', IMAGE_TYPES['jpg']
    if head[:8] == b'\x89PNG\r\n\x1a\n':
        return 'png', IMAGE_TYPES['png']
    if head[:4] == b'RIFF' and head[8:12] == b'WEBP':
        return 'webp', IMAGE_TYPES['webp']
    if head[:3] == b'ID3' or (len(head) > 1 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0):
        return 'mp3', AUDIO_TYPES['mp3']
    return None


def kind_of(media):
    return 'image' if media.media_type == 'PICTOGRAM' else 'audio'


def validate(upload, media):
    kind = kind_of(media)
    limit = settings.OPS_IMAGE_MAX_BYTES if kind == 'image' else settings.OPS_AUDIO_MAX_BYTES
    if upload.size == 0:
        raise MediaError('빈 파일입니다.')
    if upload.size > limit:
        raise MediaError(f'파일이 너무 큽니다({upload.size / 1024 / 1024:.1f}MB, 한도 {limit // (1024 * 1024)}MB).')
    upload.seek(0)
    head = upload.read(16)
    upload.seek(0)
    found = sniff(head)
    allowed = IMAGE_TYPES if kind == 'image' else AUDIO_TYPES
    if not found or found[0] not in allowed:
        names = ', '.join(e.upper() for e in allowed)
        raise MediaError(f'{"그림" if kind == "image" else "음성"} 파일 형식이 아닙니다. 쓸 수 있는 형식: {names}.')
    if kind == 'image':
        _check_image(upload)
    return found


def _check_image(upload):
    """Pillow가 있으면(개발 환경) 실제로 열리는 그림인지도 확인. 운영 이미지에는 Pillow가 없어 매직 바이트만 본다."""
    try:
        from PIL import Image
    except ImportError:
        return
    try:
        with Image.open(upload) as img:
            img.verify()
    except Exception:  # noqa: BLE001
        raise MediaError('그림 파일이 깨져 있어 열 수 없습니다.') from None
    finally:
        upload.seek(0)


def _ensure_local_media():
    if getattr(settings, 'MEDIA_CDN_BASE_URL', ''):
        raise MediaError('CDN(MEDIA_CDN_BASE_URL)을 쓰는 환경에서는 운영 콘솔로 파일을 바꿀 수 없습니다.')


def replace(media, upload, actor):
    from .models import MediaRevision

    _ensure_local_media()
    ext, content_type = validate(upload, media)
    stamp = f"{timezone.now():%Y%m%d-%H%M%S}-{secrets.token_hex(3)}"
    kind = 'pictogram' if media.media_type == 'PICTOGRAM' else 'audio'
    key = f'exercises/ops/{media.media_id.hex[:12]}/{kind}-{stamp}.{ext}'
    target = Path(settings.MEDIA_ROOT) / key
    if target.exists():
        raise MediaError('같은 시각에 올린 파일이 있습니다. 잠시 후 다시 시도해 주세요.')
    target.parent.mkdir(parents=True, exist_ok=True)
    digest, size = hashlib.sha256(), 0
    tmp = target.with_name(f'.{target.name}.tmp')
    with tmp.open('wb') as out:
        for chunk in upload.chunks():
            digest.update(chunk)
            size += len(chunk)
            out.write(chunk)
    tmp.chmod(0o644)
    tmp.replace(target)
    checksum = digest.hexdigest()
    try:
        with transaction.atomic():
            revision = MediaRevision.objects.create(
                media=media, exercise_id=media.exercise_id, media_type=media.media_type, sequence=media.sequence,
                section=media.section_name or '', old_s3_key=media.s3_key or '', old_url=media.url or '',
                old_checksum=media.checksum or '', old_display_key=media.display_key or '',
                old_duration_ms=media.duration_ms, new_key=key, new_checksum=checksum, size_bytes=size,
                content_type=content_type, actor=actor,
            )
            if not media.section and media.section_name:
                media.section = media.section_name  # 1차 형식(s3_key에 구간 이름)이면 구간 이름을 옮겨 둔다
            media.s3_key = key
            media.url = f'{settings.MEDIA_URL}{key}'
            media.checksum = checksum
            media.display_key = None
            if media.media_type != 'PICTOGRAM':
                media.duration_ms = None  # 새 녹음의 길이는 모른다(앱은 재생하며 길이를 잰다)
            media.save(update_fields=['section', 's3_key', 'url', 'checksum', 'display_key', 'duration_ms'])
    except Exception:
        target.unlink(missing_ok=True)
        raise
    return revision


def revert(revision, actor):
    """교체 전 파일로 되돌린다. 교체 뒤 같은 행을 또 바꿨으면 가장 최근 교체부터 되돌려야 한다."""
    media = revision.media
    if media is None:
        raise MediaError('미디어 행이 다시 만들어져(미디어 연결 명령) 되돌릴 대상이 없습니다.')
    if revision.reverted_at:
        raise MediaError('이미 되돌린 교체입니다.')
    if media.s3_key != revision.new_key:
        raise MediaError('이 교체 뒤에 파일이 다시 바뀌었습니다. 가장 최근 교체부터 되돌려 주세요.')
    with transaction.atomic():
        media.s3_key = revision.old_s3_key or None
        media.url = revision.old_url or None
        media.checksum = revision.old_checksum or None
        media.display_key = revision.old_display_key or None
        media.duration_ms = revision.old_duration_ms
        media.save(update_fields=['s3_key', 'url', 'checksum', 'display_key', 'duration_ms'])
        revision.reverted_at = timezone.now()
        revision.reverted_by = actor
        revision.save(update_fields=['reverted_at', 'reverted_by'])
    return media
