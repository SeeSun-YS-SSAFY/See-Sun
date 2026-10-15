"""
관리자 로그인 단계 화면(/ops/account/): 첫 로그인 비밀번호 변경 → 2단계 인증 등록(QR) → 코드 확인
허용 IP·로그인·직원 확인은 운영 콘솔과 같고, 2단계 확인 자체는 건너뛴다(여기가 그 단계라서).
"""
from functools import wraps
from urllib.parse import urlencode

from django.conf import settings
from django.contrib.auth import login as auth_login
from django.contrib.auth.forms import AuthenticationForm
from django.contrib.auth.views import LogoutView, PasswordChangeView, redirect_to_login
from django.core.exceptions import ValidationError
from django.http import Http404
from django.shortcuts import redirect, render
from django.urls import reverse
from django.utils import timezone, translation

from apps.core.audit import client_ip, is_login_locked, record, register_login_failure

from . import twofactor
from .access import ip_allowed
from .middleware import STEP_URLS


def account_view(view):
    @wraps(view)
    def wrapped(request, *args, **kwargs):
        if not ip_allowed(client_ip(request)):
            raise Http404()
        user = request.user
        if not user.is_authenticated:
            return redirect_to_login(request.get_full_path(), reverse('ops:login'))
        if not (user.is_active and user.is_staff):
            return render(request, 'ops/forbidden.html', {'reason': '직원 계정만 들어올 수 있습니다.'}, status=403)
        with translation.override('ko'):  # 비밀번호 규칙 안내 등 Django 기본 문구를 한국어로
            return view(request, *args, **kwargs)
    wrapped.ops_protected = True
    return wrapped


def _next(request):
    return twofactor.safe_next(request, request.POST.get('next') or request.GET.get('next'))


def _go_on(request):
    """다음 단계가 남아 있으면 그쪽으로(가려던 곳 next를 넘김), 아니면 가려던 곳으로"""
    step = twofactor.required_step(request)
    if step:
        return redirect(f'{reverse(STEP_URLS[step])}?{urlencode({"next": _next(request)})}')
    return redirect(_next(request))


def _locked(request):
    twofactor.audit(request, 'LOGIN_LOCKED', 'otp:locked', status=429)
    return render(request, 'ops/account_locked.html', status=429)


class ForcedPasswordChangeView(PasswordChangeView):
    """Django 기본 비밀번호 변경(이전 비밀번호 확인·비밀번호 규칙 검사)을 콘솔 모양으로"""
    template_name = 'ops/account_password.html'
    success_url = '/ops/'  # 실제 이동은 form_valid의 _go_on(다음 단계 또는 next)

    def get_context_data(self, **kwargs):
        ctx = super().get_context_data(**kwargs)
        security = twofactor.security_for(self.request.user, create=False)
        ctx['forced'] = bool(security and security.must_change_password)
        ctx['next'] = _next(self.request)
        return ctx

    def form_valid(self, form):
        super().form_valid(form)  # 저장 + 세션 유지(update_session_auth_hash)
        security = twofactor.security_for(self.request.user)
        if security.must_change_password:
            security.must_change_password = False
            security.save(update_fields=['must_change_password', 'updated_at'])
        twofactor.audit(self.request, 'CHANGE', 'password_changed')
        return _go_on(self.request)


account_password = account_view(ForcedPasswordChangeView.as_view())


@account_view
def otp_setup(request):
    security = twofactor.security_for(request.user)
    if twofactor.is_enrolled(security):
        return _go_on(request)
    if security.must_change_password:
        return _go_on(request)
    if not security.otp_secret:
        security.otp_secret = twofactor.new_secret()
        security.save(update_fields=['otp_secret', 'updated_at'])
    error = None
    if request.method == 'POST':
        if twofactor.is_locked(request):
            return _locked(request)
        if twofactor.accept_totp(security, request.POST.get('code')):
            twofactor.reset_failures(request)
            security.otp_confirmed_at = timezone.now()
            security.save(update_fields=['otp_confirmed_at', 'updated_at'])
            twofactor.mark_verified(request, security)
            codes = twofactor.issue_recovery_codes(request.user)
            twofactor.audit(request, 'CHANGE', 'otp:enrolled')
            twofactor.audit(request, 'LOGIN', 'otp:ok')
            return render(request, 'ops/account_recovery.html', {'codes': codes, 'next': _next(request)})
        twofactor.register_failure(request)
        twofactor.audit(request, 'LOGIN_FAILED', 'otp:enroll_fail', status=400)
        error = '코드가 맞지 않습니다. 앱에 지금 보이는 6자리 숫자를 넣어 주세요(휴대폰 시간이 자동인지도 확인).'
    uri = twofactor.provisioning_uri(request.user, security.otp_secret)
    secret = security.otp_secret
    return render(request, 'ops/account_otp_setup.html', {
        'qr_svg': twofactor.qr_svg(uri), 'secret_groups': [secret[i:i + 4] for i in range(0, len(secret), 4)],
        'error': error, 'next': _next(request),
    }, status=400 if error else 200)


@account_view
def otp_verify(request):
    security = twofactor.security_for(request.user, create=False)
    if not twofactor.is_enrolled(security):
        return _go_on(request)
    if twofactor.is_verified(request, security):
        return _go_on(request)
    error = None
    if request.method == 'POST':
        if twofactor.is_locked(request):
            return _locked(request)
        code = request.POST.get('code', '')
        if twofactor.accept_totp(security, code):
            detail = 'otp:ok'
        elif twofactor.use_recovery_code(request.user, code):
            detail = f'otp:recovery left={twofactor.remaining_recovery_codes(request.user)}'
        else:
            detail = None
        if detail:
            twofactor.reset_failures(request)
            twofactor.mark_verified(request, security)
            twofactor.audit(request, 'LOGIN', detail)
            return _go_on(request)
        twofactor.register_failure(request)
        twofactor.audit(request, 'LOGIN_FAILED', 'otp:fail', status=400)
        if twofactor.is_locked(request):
            return _locked(request)
        error = '코드가 맞지 않거나 이미 쓴 코드입니다.'
    return render(request, 'ops/account_otp_verify.html', {
        'error': error, 'next': _next(request), 'left': twofactor.remaining_recovery_codes(request.user),
        'issuer': settings.OPS_OTP_ISSUER,
    }, status=400 if error else 200)


# ---- 콘솔 로그인·로그아웃(/ops/login/, /ops/logout/) --------------------------------------------------
GENERIC_LOGIN_ERROR = '계정 이름 또는 비밀번호가 맞지 않습니다.'


class OpsAuthenticationForm(AuthenticationForm):
    """계정이 없음·비밀번호 틀림·직원 아님·비활성을 모두 같은 문구로(계정 유무를 드러내지 않음)"""
    error_messages = {'invalid_login': GENERIC_LOGIN_ERROR, 'inactive': GENERIC_LOGIN_ERROR}

    def confirm_login_allowed(self, user):
        if not (user.is_active and user.is_staff):
            record('LOGIN_FAILED', actor=user.get_username(), ip=client_ip(self.request), path=self.request.path,
                   detail='not_staff')
            raise ValidationError(GENERIC_LOGIN_ERROR, code='invalid_login')


def ops_login(request):
    """관리자 로그인(apps.core.audit.admin_login)과 같은 잠금·기록. 허용 IP는 OPS_ALLOWED_IPS."""
    if not ip_allowed(client_ip(request)):
        raise Http404()
    next_url = _next(request)
    with translation.override('ko'):
        if request.user.is_authenticated and request.user.is_staff and request.method == 'GET':
            return redirect(next_url)  # 남은 단계는 미들웨어가 안내
        username = (request.POST.get('username') or '')[:150]
        if request.method == 'POST' and is_login_locked(username, client_ip(request)):
            record('LOGIN_LOCKED', actor=username, ip=client_ip(request), path=request.path, status=429)
            return render(request, 'ops/account_locked.html', status=429)
        form = OpsAuthenticationForm(request, data=request.POST if request.method == 'POST' else None)
        if request.method == 'POST':
            if form.is_valid():
                auth_login(request, form.get_user())  # 접속기록 LOGIN(IP 포함)은 user_logged_in 신호가 남긴다
                return redirect(next_url)
            register_login_failure(username, client_ip(request))  # 실패 기록은 user_login_failed 신호
        return render(request, 'ops/login.html', {'form': form, 'next': next_url},
                      status=400 if request.method == 'POST' else 200)


ops_login.ops_protected = True


class OpsLogoutView(LogoutView):
    http_method_names = ['post', 'options']
    next_page = '/ops/login/'


ops_logout = OpsLogoutView.as_view()
ops_logout.ops_protected = True
