"""
관리자 화면 2단계 인증(TOTP, 처리방침 11절) — /admin/ 과 /ops/ 공통

로그인 순서(직원 계정):
  1) 비밀번호(/admin/login/, 실패 5회 15분 잠금 — 기존 그대로)
  2) 2단계 인증을 아직 등록하지 않았으면: [첫 로그인 비밀번호 변경] → QR 등록 → 코드 1개 확인 → 복구 코드 10개(한 번만 보임)
     등록했으면: 코드 확인(또는 복구 코드) → [비밀번호 변경이 걸려 있으면 변경]
  3) 확인 표시는 세션에만 둔다. 로그인할 때마다 지우므로 매번 코드를 받는다(user_logged_in 신호).
- 코드: 30초 TOTP 6자리(Google OTP·Google Authenticator), 앞뒤 1구간 허용, 이미 쓴 구간은 다시 받지 않는다.
- 실패 잠금: 비밀번호 실패와 코드 실패를 같은 카운터로 센다(LOGIN_MAX_FAILURES회, LOGIN_LOCKOUT_SECONDS, 계정·IP 각각).
  코드까지 맞아야 카운터를 지운다.
- 기록: 성공 LOGIN(detail otp:*), 실패 LOGIN_FAILED(otp:fail), 잠금 LOGIN_LOCKED(otp:locked), 등록·비밀번호 변경 CHANGE.
- 휴대폰을 잃으면 서버 셸에서 ``manage.py ops_reset_otp <계정>`` (다음 로그인 때 다시 등록).
"""
import hashlib
import hmac
import secrets
import time

import pyotp
from django.conf import settings
from django.db import transaction
from django.utils import timezone
from django.utils.http import url_has_allowed_host_and_scheme

from apps.core.audit import client_ip, clear_login_failures, is_login_locked, record, register_login_failure

SESSION_KEY = '_ops_otp_verified'
INTERVAL = 30
DIGITS = 6
RECOVERY_COUNT = 10
RECOVERY_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'  # 헷갈리는 글자(i·l·o·0·1) 뺌

STEP_PASSWORD = 'password'
STEP_SETUP = 'setup'
STEP_VERIFY = 'verify'


# ---- 상태 ------------------------------------------------------------------------------------------
def security_for(user, create=True):
    from .models import AdminSecurity
    if create:
        return AdminSecurity.objects.get_or_create(user=user)[0]
    return AdminSecurity.objects.filter(user=user).first()


def is_enrolled(security):
    return bool(security and security.otp_confirmed_at and security.otp_secret)


def _marker(user, security):
    return f'{user.pk}:{security.otp_confirmed_at.timestamp():.6f}'


def is_verified(request, security=None):
    security = security if security is not None else security_for(request.user, create=False)
    if not is_enrolled(security):
        return False
    return request.session.get(SESSION_KEY) == _marker(request.user, security)


def mark_verified(request, security):
    request.session[SESSION_KEY] = _marker(request.user, security)


AUTH_AT_KEY = '_ops_auth_at'
LAST_SEEN_KEY = '_ops_last_seen'


def clear_verified(sender=None, request=None, **kwargs):
    """user_logged_in 신호: 새로 로그인하면 2단계 확인을 다시 받고, 세션 시간(절대·유휴)을 새로 잰다"""
    if request is not None and hasattr(request, 'session'):
        request.session.pop(SESSION_KEY, None)
        now = time.time()
        request.session[AUTH_AT_KEY] = now
        request.session[LAST_SEEN_KEY] = now


def required_step(request):
    """직원 계정이 지금 거쳐야 할 단계(없으면 None)"""
    user = request.user
    if not (user.is_authenticated and user.is_active and user.is_staff):
        return None
    security = security_for(user, create=False)
    if not is_enrolled(security):
        if security and security.must_change_password:
            return STEP_PASSWORD
        return STEP_SETUP
    if not is_verified(request, security):
        return STEP_VERIFY
    if security.must_change_password:
        return STEP_PASSWORD
    return None


def safe_next(request, value, default='/ops/'):
    if value and url_has_allowed_host_and_scheme(value, allowed_hosts={request.get_host()},
                                                 require_https=request.is_secure()):
        return value
    return default


# ---- TOTP -----------------------------------------------------------------------------------------
def new_secret():
    return pyotp.random_base32()


def provisioning_uri(user, secret):
    return pyotp.TOTP(secret, interval=INTERVAL, digits=DIGITS).provisioning_uri(
        name=user.get_username(), issuer_name=settings.OPS_OTP_ISSUER)


def qr_svg(uri):
    """QR을 서버에서 SVG로(외부 서비스·CDN 없음)"""
    import qrcode
    import qrcode.image.svg
    img = qrcode.make(uri, image_factory=qrcode.image.svg.SvgPathImage, box_size=10, border=2)
    return img.to_string(encoding='unicode')


def match_step(secret, code, now=None, window=1):
    """맞는 30초 구간 번호, 없으면 None"""
    code = (code or '').strip().replace(' ', '')
    if len(code) != DIGITS or not code.isdigit():
        return None
    totp = pyotp.TOTP(secret, interval=INTERVAL, digits=DIGITS)
    current = int(now if now is not None else time.time()) // INTERVAL
    for step in (current, current - 1, current + 1)[: 1 + 2 * window]:
        if hmac.compare_digest(totp.generate_otp(step), code):
            return step
    return None


def accept_totp(security, code):
    """코드가 맞고 아직 쓰지 않은 구간이면 그 구간을 기록하고 True"""
    step = match_step(security.otp_secret, code)
    if step is None or (security.otp_last_step is not None and step <= security.otp_last_step):
        return False
    security.otp_last_step = step
    security.save(update_fields=['otp_last_step', 'updated_at'])
    return True


# ---- 복구 코드 -------------------------------------------------------------------------------------
def _normalize(code):
    return ''.join(ch for ch in (code or '').lower() if ch.isalnum())


def _hash(salt, code):
    return hashlib.sha256(f'{salt}:{_normalize(code)}'.encode()).hexdigest()


def issue_recovery_codes(user):
    """기존 코드를 지우고 10개를 새로 만든다. 평문은 돌려줄 때 한 번뿐."""
    from .models import RecoveryCode
    codes = []
    with transaction.atomic():
        RecoveryCode.objects.filter(user=user).delete()
        for _ in range(RECOVERY_COUNT):
            raw = ''.join(secrets.choice(RECOVERY_ALPHABET) for _ in range(10))
            salt = secrets.token_hex(16)
            RecoveryCode.objects.create(user=user, salt=salt, code_hash=_hash(salt, raw))
            codes.append(f'{raw[:5]}-{raw[5:]}')
    return codes


def use_recovery_code(user, code):
    from .models import RecoveryCode
    if len(_normalize(code)) != 10:
        return False
    with transaction.atomic():
        for row in RecoveryCode.objects.select_for_update().filter(user=user, used_at__isnull=True):
            if hmac.compare_digest(row.code_hash, _hash(row.salt, code)):
                row.used_at = timezone.now()
                row.save(update_fields=['used_at'])
                return True
    return False


def remaining_recovery_codes(user):
    from .models import RecoveryCode
    return RecoveryCode.objects.filter(user=user, used_at__isnull=True).count()


# ---- 실패 잠금·기록 --------------------------------------------------------------------------------
def is_locked(request):
    """비밀번호 단계와 같은 카운터(apps.core.audit.login_lock_keys)"""
    return is_login_locked(request.user.get_username(), client_ip(request))


def register_failure(request):
    register_login_failure(request.user.get_username(), client_ip(request))


def reset_failures(request):
    clear_login_failures(request.user.get_username(), client_ip(request))


def audit(request, action, detail, status=None):
    user = request.user
    record(action, actor=user.get_username(), actor_id=user.pk, ip=client_ip(request), target_type='ops.adminsecurity',
           target_id=user.pk, path=request.path, status=status, detail=detail)


# ---- 관리 명령에서 ----------------------------------------------------------------------------------
def reset_device(user):
    """2단계 인증을 지운다(휴대폰 분실). 이미 확인된 세션도 표시가 맞지 않게 되어 다시 등록·확인해야 한다."""
    from .models import RecoveryCode
    security = security_for(user)
    security.otp_secret = None
    security.otp_confirmed_at = None
    security.otp_last_step = None
    security.save()
    RecoveryCode.objects.filter(user=user).delete()
    return security
