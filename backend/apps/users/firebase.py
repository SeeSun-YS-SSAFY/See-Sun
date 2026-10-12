"""
Firebase ID 토큰 검증 (09 문서 2-5)

검증기는 인터페이스(FirebaseVerifier) 뒤에 둔다.
- FirebaseAdminVerifier: firebase-admin SDK. FIREBASE_CREDENTIALS_FILE(서비스 계정 JSON) 또는
  FIREBASE_PROJECT_ID가 있을 때 사용한다.
- FakeFirebaseVerifier: 로컬 개발 전용. 토큰 형식 ``fake:<uid>:<전화번호>``.
  FIREBASE_AUTH_FAKE=true 이고 DEBUG=True일 때만 쓸 수 있다. DEBUG=False면 설정 로드 단계
  (settings.py)와 여기(get_verifier)에서 모두 거부한다.

테스트는 ``set_verifier_for_tests()`` 로 검증기를 바꿔 끼운다.
"""
import logging
import os
from dataclasses import dataclass

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class FirebaseIdentity:
    uid: str
    phone_number: str | None


class InvalidIdToken(Exception):
    """서명·만료·폐기·발급 대상 등 검증 실패"""


class FirebaseNotConfigured(Exception):
    """운영 자격 증명이 없고 가짜 검증기도 꺼져 있음"""


class FirebaseVerifier:
    def verify(self, id_token: str) -> FirebaseIdentity:  # pragma: no cover - 인터페이스
        raise NotImplementedError

    def delete_user(self, uid: str) -> None:
        """탈퇴 시 Firebase 사용자 삭제(지원하지 않으면 아무것도 하지 않음)"""


class FakeFirebaseVerifier(FirebaseVerifier):
    """``fake:<uid>:<전화번호>`` 형식만 받는 개발용 검증기"""

    PREFIX = 'fake:'

    def __init__(self):
        if not settings.DEBUG:
            raise ImproperlyConfigured('가짜 Firebase 검증기는 DEBUG=True에서만 쓸 수 있습니다.')

    def verify(self, id_token: str) -> FirebaseIdentity:
        if not settings.DEBUG:
            raise ImproperlyConfigured('가짜 Firebase 검증기는 DEBUG=True에서만 쓸 수 있습니다.')
        if not isinstance(id_token, str) or not id_token.startswith(self.PREFIX):
            raise InvalidIdToken('fake 토큰 형식이 아닙니다.')
        parts = id_token[len(self.PREFIX):].split(':', 1)
        if len(parts) != 2 or not parts[0]:
            raise InvalidIdToken('fake 토큰 형식이 아닙니다.')
        return FirebaseIdentity(uid=parts[0], phone_number=parts[1] or None)


class FirebaseAdminVerifier(FirebaseVerifier):
    """firebase-admin SDK 검증기(앱 인스턴스는 지연 초기화)"""

    APP_NAME = 'seesun'

    def __init__(self, credentials_file: str = '', project_id: str = '', check_revoked: bool = True):
        self.credentials_file = credentials_file
        self.project_id = project_id
        self.check_revoked = check_revoked
        self._app = None

    def _get_app(self):
        if self._app is not None:
            return self._app
        import firebase_admin
        from firebase_admin import credentials

        try:
            self._app = firebase_admin.get_app(self.APP_NAME)
        except ValueError:
            options = {'projectId': self.project_id} if self.project_id else None
            cred = credentials.Certificate(self.credentials_file) if self.credentials_file else None
            self._app = firebase_admin.initialize_app(cred, options=options, name=self.APP_NAME)
        return self._app

    def verify(self, id_token: str) -> FirebaseIdentity:
        from firebase_admin import auth

        # 폐기 확인(check_revoked)은 서비스 계정 자격 증명이 있어야 한다
        check_revoked = self.check_revoked and bool(self.credentials_file)
        try:
            claims = auth.verify_id_token(id_token, app=self._get_app(), check_revoked=check_revoked)
        except (ValueError, auth.InvalidIdTokenError, auth.ExpiredIdTokenError,
                auth.RevokedIdTokenError, auth.UserDisabledError, auth.CertificateFetchError) as exc:
            raise InvalidIdToken(type(exc).__name__) from exc
        return FirebaseIdentity(uid=claims['uid'], phone_number=claims.get('phone_number'))

    def delete_user(self, uid: str) -> None:
        if not self.credentials_file:
            return
        from firebase_admin import auth
        try:
            auth.delete_user(uid, app=self._get_app())
        except auth.UserNotFoundError:
            pass
        except Exception:  # noqa: BLE001 — 탈퇴 자체는 막지 않고 운영자가 확인
            logger.exception('Firebase 사용자 삭제 실패')


_override: FirebaseVerifier | None = None
_admin_verifier: FirebaseAdminVerifier | None = None


def set_verifier_for_tests(verifier: FirebaseVerifier | None) -> None:
    global _override
    _override = verifier


def get_verifier() -> FirebaseVerifier:
    global _admin_verifier
    if _override is not None:
        return _override
    if settings.FIREBASE_AUTH_FAKE:
        return FakeFirebaseVerifier()
    credentials_file = settings.FIREBASE_CREDENTIALS_FILE
    if credentials_file and not os.path.exists(credentials_file):
        # 경로만 있고 파일이 없으면(마운트 누락) 프로젝트 ID만으로 검증하거나 미설정으로 처리
        logger.error('FIREBASE_CREDENTIALS_FILE 경로에 파일이 없습니다.')
        credentials_file = ''
    if credentials_file or settings.FIREBASE_PROJECT_ID:
        if _admin_verifier is None or _admin_verifier.credentials_file != credentials_file:
            _admin_verifier = FirebaseAdminVerifier(
                credentials_file=credentials_file,
                project_id=settings.FIREBASE_PROJECT_ID,
                check_revoked=settings.FIREBASE_CHECK_REVOKED,
            )
        return _admin_verifier
    raise FirebaseNotConfigured()
