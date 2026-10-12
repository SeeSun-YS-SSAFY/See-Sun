"""
미디어 URL 생성 (09 문서 2-9, 03 문서 §7)

DB에는 CDN 상대키(예: ``pictograms/strength/squat_1.png``) 또는 1차 형식의 ``/media/...`` 경로가 저장된다.
- MEDIA_CDN_BASE_URL(예: Cloudflare R2 커스텀 도메인)이 설정되면 ``<CDN>/<상대키>`` 절대 주소를 돌려준다.
- 설정이 없으면 1차와 같이 ``MEDIA_URL`` 기준 상대 경로(``/media/...``)를 돌려준다(로컬 개발).
- 이미 http(s) 절대 주소면 그대로 둔다.
"""
from django.conf import settings


def media_key(value: str | None) -> str | None:
    """저장값에서 CDN 상대키를 뽑는다. ``/media/a/b.mp3`` → ``a/b.mp3``"""
    if not value:
        return None
    if value.startswith(('http://', 'https://')):
        return None
    key = value
    media_prefix = (settings.MEDIA_URL or '/media/')
    if key.startswith(media_prefix):
        key = key[len(media_prefix):]
    return key.lstrip('/')


def media_url(value: str | None) -> str | None:
    if not value:
        return None
    if value.startswith(('http://', 'https://')):
        return value
    key = media_key(value)
    base = (getattr(settings, 'MEDIA_CDN_BASE_URL', '') or '').rstrip('/')
    if base:
        return f'{base}/{key}'
    return f"{(settings.MEDIA_URL or '/media/').rstrip('/')}/{key}"
