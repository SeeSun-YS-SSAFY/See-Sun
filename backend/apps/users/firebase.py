"""
Firebase ID 토큰 검증 (09 문서 2-5)

검증기는 인터페이스(FirebaseVerifier) 뒤에 둔다.
- FirebaseAdminVerifier: firebase-admin SDK. FIREBASE_CREDENTIALS_FILE(서비스 계정 JSON) 또는
  FIREBASE_PROJECT_ID가 있을 때 사용한다.
- FakeFirebaseVerifier: 로컬 개발 전용. 토큰 형식 ``fake:<uid>:<전화번호>``.
  FIREBASE_AUTH_FAKE=true 이고 DEBUG=True일 때만 쓸 수 있다. DEBUG=False면 설정 로드 단계
  (settings.py)와 여기(get_verifier)에서 모두 거부한다.
  예외: 배포 리허설 E2E용 staging 설정(``deploy/e2e/settings_staging.py``, 운영 이미지에 없음, 실행 시
  볼륨으로만 넣는다)이 ``SEESUN_STAGING_FAKE_AUTH = True`` 를 직접 정의한 경우. 운영 설정(settings.py)은
  이 값을 환경변수로 읽지 않으므로 운영 컨테이너에서는 켤 방법이 없다.

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


class FirebaseDeletionFailed(Exception):
    """Firebase 사용자 삭제 실패(서비스 계정 없음 포함) → 재시도 큐(FirebaseDeletionTask, 법적 검토 D-05)"""


class FirebaseVerifier:
    def verify(self, id_token: str) -> FirebaseIdentity:  # pragma: no cover - 인터페이스
        raise NotImplementedError

    def delete_user(self, uid: str) -> None:
        """탈퇴 시 Firebase 사용자 삭제(지원하지 않으면 아무것도 하지 않음)"""


def fake_verifier_allowed() -> bool:
    """가짜 검증기 허용: 로컬 개발(DEBUG) 또는 staging 설정 모듈이 직접 켠 경우만"""
    return bool(settings.DEBUG or getattr(settings, 'SEESUN_STAGING_FAKE_AUTH', False) is True)


class FakeFirebaseVerifier(FirebaseVerifier):
    """``fake:<uid>:<전화번호>`` 형식만 받는 개발용 검증기"""

    PREFIX = 'fake:'

    def __init__(self):
        if not fake_verifier_allowed():
            raise ImproperlyConfigured('가짜 Firebase 검증기는 DEBUG=True에서만 쓸 수 있습니다.')

    def verify(self, id_token: str) -> FirebaseIdentity:
        if not fake_verifier_allowed():
            raise ImproperlyConfigured('가짜 Firebase 검증기는 DEBUG=True에서만 쓸 수 있습니다.')
        if not isinstance(id_token, str) or not id_token.startswith(self.PREFIX):
            raise InvalidIdToken('fake 토큰 형식이 아닙니다.')
        parts = id_token[len(self.PREFIX):].split(':', 1)
        if len(parts) != 2 or not parts[0]:
            raise InvalidIdToken('fake 토큰 형식이 아닙니다.')
        return FirebaseIdentity(uid=parts[0], phone_number=parts[1] or None)


def _anonymous_credential_class():
    from firebase_admin import credentials
    from google.auth.credentials import AnonymousCredentials

    class AnonymousCredential(credentials.Base):
        def get_credential(self):
            return AnonymousCredentials()

    return AnonymousCredential


def _AnonymousCredential():  # noqa: N802 — 지연 import용 팩토리
    return _anonymous_credential_class()()


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
            if self.credentials_file:
                cred = credentials.Certificate(self.credentials_file)
            else:
                # 프로젝트 ID만 있는 모드: 서명 검증(공개 인증서)에는 자격 증명이 필요 없다.
                # cred=None이면 firebase-admin이 Google 기본 자격 증명(ADC)을 찾다가 실패해 500이 났다(배포 리허설 B-01).
                cred = _AnonymousCredential()
            self._app = firebase_admin.initialize_app(cred, options=options, name=self.APP_NAME)
        return self._app

    def verify(self, id_token: str) -> FirebaseIdentity:
        from firebase_admin import auth
        from firebase_admin.exceptions import FirebaseError

        # 폐기 확인(check_revoked)은 서비스 계정 자격 증명이 있어야 한다
        check_revoked = self.check_revoked and bool(self.credentials_file)
        try:
            claims = auth.verify_id_token(id_token, app=self._get_app(), check_revoked=check_revoked)
        except auth.CertificateFetchError as exc:
            # Google 공개 인증서를 못 받음(일시 장애): 토큰 문제가 아니므로 401(재인증 유도) 대신 503
            logger.error('Firebase 공개 인증서 조회 실패: %s', type(exc).__name__)
            raise FirebaseNotConfigured() from exc
        except (ValueError, auth.InvalidIdTokenError, auth.ExpiredIdTokenError,
                auth.RevokedIdTokenError, auth.UserDisabledError, auth.UserNotFoundError) as exc:
            # UserNotFoundError: 폐기 확인(check_revoked, 서비스 계정이 있을 때만)에서 Firebase 사용자가 이미 지워진 경우
            raise InvalidIdToken(type(exc).__name__) from exc
        except FirebaseError as exc:
            # 서비스 계정 모드의 폐기 확인은 Firebase Auth API를 부른다 → 일시 장애·권한 오류는 503(토큰 문제 아님)
            logger.error('Firebase 토큰 폐기 확인 실패: %s', type(exc).__name__)
            raise FirebaseNotConfigured() from exc
        return FirebaseIdentity(uid=claims['uid'], phone_number=claims.get('phone_number'))

    def delete_user(self, uid: str) -> None:
        """성공(이미 없음 포함)이면 반환, 실패하면 FirebaseDeletionFailed(호출 측이 재시도 큐에 남긴다)"""
        if not self.credentials_file:
            raise FirebaseDeletionFailed('NO_SERVICE_ACCOUNT')
        from firebase_admin import auth
        try:
            auth.delete_user(uid, app=self._get_app())
        except auth.UserNotFoundError:
            pass
        except Exception as exc:  # noqa: BLE001
            raise FirebaseDeletionFailed(type(exc).__name__) from exc


_override: FirebaseVerifier | None = None
_admin_verifier: FirebaseAdminVerifier | None = None


def set_verifier_for_tests(verifier: FirebaseVerifier | None) -> None:
    global _override
    _override = verifier


def _credentials_file() -> str:
    credentials_file = settings.FIREBASE_CREDENTIALS_FILE
    if credentials_file and not os.path.exists(credentials_file):
        # 경로만 있고 파일이 없으면(마운트 누락) 프로젝트 ID만으로 검증하거나 미설정으로 처리
        logger.error('FIREBASE_CREDENTIALS_FILE 경로에 파일이 없습니다.')
        return ''
    return credentials_file


def _shared_admin_verifier(credentials_file: str) -> FirebaseAdminVerifier:
    global _admin_verifier
    if (_admin_verifier is None or _admin_verifier.credentials_file != credentials_file
            or _admin_verifier.project_id != settings.FIREBASE_PROJECT_ID):
        _admin_verifier = FirebaseAdminVerifier(
            credentials_file=credentials_file,
            project_id=settings.FIREBASE_PROJECT_ID,
            check_revoked=settings.FIREBASE_CHECK_REVOKED,
        )
    return _admin_verifier


def get_verifier() -> FirebaseVerifier:
    if _override is not None:
        return _override
    if settings.FIREBASE_AUTH_FAKE:
        return FakeFirebaseVerifier()
    credentials_file = _credentials_file()
    if credentials_file or settings.FIREBASE_PROJECT_ID:
        return _shared_admin_verifier(credentials_file)
    raise FirebaseNotConfigured()


def admin_app():
    """
    서비스 계정이 필요한 기능(FCM 푸시 등)용 firebase_admin 앱. 토큰 검증기와 같은 'seesun' 앱을 같이 써서
    두 번 초기화하지 않는다. 서비스 계정(FIREBASE_CREDENTIALS_FILE)이 없으면 FirebaseNotConfigured.
    """
    credentials_file = _credentials_file()
    if not credentials_file:
        raise FirebaseNotConfigured()
    return _shared_admin_verifier(credentials_file)._get_app()
