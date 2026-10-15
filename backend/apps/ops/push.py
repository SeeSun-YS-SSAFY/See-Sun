"""
새 앱 버전 알림 — FCM 토픽 푸시(데이터 메시지만, 2026-10-15 소유자 승인 msg 1011)

앱(자체 업데이트 빌드)은 토픽 ``settings.OPS_APP_UPDATE_TOPIC``(기본 ``seesun-app-updates``)을 구독하고, 이 메시지를 받으면
latest.json 확인과 같은 흐름(설치된 versionCode보다 높을 때만 「새 버전이 있어요」)을 바로 탄다.

메시지(모든 값은 문자열):
  topic   = seesun-app-updates
  data    = {"type": "app_update", "versionCode": "5", "versionName": "0.5.0",
             "apkUrl": "https://<도메인>/media/app/seesun-prov-5.apk", "sha256": "<64자 hex>"}
  android = priority high, ttl 7일(604800초), collapse_key "app_update"(기기에 못 간 알림은 최신 것 하나만)
알림 표시(notification)는 넣지 않는다 — 앱이 직접 판단해 창을 띄운다.

보내기 실패는 게시를 막지 않는다: 로그·접속기록(record)·AppPush 행에 남기고 화면에는 경고로 보여 준다.
같은 버전을 여러 번 보내도 안전하다(앱은 버전 비교 후 무시, collapse_key로 쌓이지 않음).
"""
import logging
from dataclasses import dataclass
from datetime import timedelta

from django.conf import settings

from apps.core.audit import record

logger = logging.getLogger(__name__)

TTL = timedelta(days=7)
COLLAPSE_KEY = 'app_update'
MESSAGE_TYPE = 'app_update'


@dataclass
class PushResult:
    ok: bool
    message_id: str = ''
    error: str = ''
    topic: str = ''


def topic():
    return settings.OPS_APP_UPDATE_TOPIC


def payload(manifest: dict) -> dict:
    """매니페스트(latest.json) → FCM data(문자열만)"""
    return {
        'type': MESSAGE_TYPE,
        'versionCode': str(int(manifest['versionCode'])),
        'versionName': str(manifest['versionName']),
        'apkUrl': str(manifest['apkUrl']),
        'sha256': str(manifest['sha256']),
    }


def build_message(manifest: dict):
    from firebase_admin import messaging
    return messaging.Message(
        topic=topic(),
        data=payload(manifest),
        android=messaging.AndroidConfig(priority='high', ttl=TTL, collapse_key=COLLAPSE_KEY),
    )


def _app():
    from apps.users.firebase import admin_app
    return admin_app()


def send_app_update(manifest: dict, *, actor: str, source: str, ip=None, path='', dry_run=False) -> PushResult:
    """보내고 결과를 남긴다. 어떤 예외도 밖으로 내보내지 않는다."""
    from .models import AppPush

    code = manifest.get('versionCode') if isinstance(manifest, dict) else None
    result = PushResult(ok=False, topic=topic())
    try:
        message = build_message(manifest)
        from firebase_admin import messaging
        result.message_id = str(messaging.send(message, dry_run=dry_run, app=_app()))
        result.ok = True
    except Exception as exc:  # noqa: BLE001 — 푸시 실패가 게시를 막으면 안 된다
        from apps.users.firebase import FirebaseNotConfigured
        if isinstance(exc, FirebaseNotConfigured):
            result.error = 'NOT_CONFIGURED(서비스 계정 FIREBASE_CREDENTIALS_FILE 없음)'
        else:
            result.error = f'{type(exc).__name__}: {str(exc)[:200]}'
        logger.error('앱 업데이트 푸시 실패(versionCode=%s): %s', code, result.error)
    try:
        if not dry_run and code is not None:
            AppPush.objects.create(version_code=int(code), topic=result.topic, message_id=result.message_id[:300],
                                   error=result.error[:300], source=source, actor=actor[:150])
        record('ADD', actor=actor, ip=ip, target_type='ops.apppush', target_id=code or '', path=path or f'push:{source}',
               status=200 if result.ok else 502,
               detail=(f'push:ok topic={result.topic} id={result.message_id}' if result.ok
                       else f'push:fail topic={result.topic} {result.error}')[:300])
    except Exception:  # noqa: BLE001
        logger.exception('푸시 기록 저장 실패')
    return result
