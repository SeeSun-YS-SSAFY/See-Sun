"""
관리자 화면(/admin/)·운영 콘솔(/ops/) 보호 미들웨어

1. AdminIPRestrictionMiddleware: 장고 기본 관리자 화면(/admin/)은 허용 IP(ADMIN_ALLOWED_IPS)에서만 — Caddy와 같은 목록을
   장고에서도 한 번 더 확인(밖이면 404). 운영 콘솔(/ops/)은 OPS_ALLOWED_IPS(가운영 기본 any = IP 제한 없음)를 따른다.
2. OpsSessionSecurityMiddleware: 직원 세션 시간 제한(유휴 OPS_SESSION_IDLE_SECONDS, 로그인 후 OPS_SESSION_MAX_SECONDS)과
   응답 헤더(X-Frame-Options DENY, Cache-Control no-store, X-Robots-Tag noindex).
3. AdminTwoFactorMiddleware: 비밀번호로 로그인한 직원 계정이 코드 확인(또는 등록·첫 비밀번호 변경)을 마치기 전에는
   화면을 열지 못하게 단계 화면(/ops/account/...)으로 보낸다. 로그인·로그아웃·단계 화면 자체는 통과.
"""
import time
from urllib.parse import urlencode

from django.conf import settings
from django.contrib.auth import logout
from django.contrib.auth.views import redirect_to_login
from django.http import Http404
from django.shortcuts import redirect
from django.urls import reverse
from django.utils.cache import patch_cache_control

from apps.core.audit import client_ip, record

from . import twofactor

STEP_URLS = {
    twofactor.STEP_PASSWORD: 'ops:account_password',
    twofactor.STEP_SETUP: 'ops:account_otp_setup',
    twofactor.STEP_VERIFY: 'ops:account_otp_verify',
}
OPS_PREFIX = '/ops/'


def admin_prefix():
    return '/' + getattr(settings, 'ADMIN_URL_PREFIX', 'admin/').lstrip('/')


def gated_prefixes():
    return (admin_prefix(), OPS_PREFIX)


def login_paths():
    admin = admin_prefix()
    return (admin + 'login/', admin + 'logout/', '/ops/login/', '/ops/logout/')


def exempt_paths():
    return login_paths() + (admin_prefix() + 'jsi18n/', '/ops/account/')


def step_redirect(request, step):
    next_url = request.get_full_path() if request.method == 'GET' else '/ops/'
    return redirect(f'{reverse(STEP_URLS[step])}?{urlencode({"next": next_url})}')


def login_url_for(path):
    return reverse('admin_login') if path.startswith(admin_prefix()) else reverse('ops:login')


class AdminIPRestrictionMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        from .access import ip_allowed
        if request.path.startswith(admin_prefix()) and not ip_allowed(client_ip(request), settings.ADMIN_ALLOWED_IPS):
            raise Http404()
        return self.get_response(request)


class OpsSessionSecurityMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        path = request.path
        gated = path.startswith(gated_prefixes())
        if gated:
            expired = self._expire_staff_session(request)
            if expired is not None:
                return self._headers(expired)
        response = self.get_response(request)
        return self._headers(response) if gated else response

    @staticmethod
    def _expire_staff_session(request):
        user = getattr(request, 'user', None)
        if not (user is not None and user.is_authenticated and user.is_staff):
            return None
        session = request.session
        now = time.time()
        auth_at = session.get(twofactor.AUTH_AT_KEY)
        last_seen = session.get(twofactor.LAST_SEEN_KEY)
        reason = None
        if auth_at is None:
            session[twofactor.AUTH_AT_KEY] = now  # 이 기능 전에 만든 세션: 지금부터 잰다
        elif now - auth_at > settings.OPS_SESSION_MAX_SECONDS:
            reason = 'session_max'
        elif last_seen is not None and now - last_seen > settings.OPS_SESSION_IDLE_SECONDS:
            reason = 'session_idle'
        if reason:
            name, pk = user.get_username(), user.pk
            logout(request)
            record('LOGOUT', actor=name, actor_id=pk, ip=client_ip(request), path=request.path, detail=reason)
            if request.path.startswith(login_paths()):
                return None  # 로그인 화면은 그대로 보여 준다(이미 로그아웃됨)
            return redirect_to_login(request.get_full_path() if request.method == 'GET' else OPS_PREFIX,
                                     login_url_for(request.path))
        session[twofactor.LAST_SEEN_KEY] = now
        return None

    @staticmethod
    def _headers(response):
        response['X-Frame-Options'] = 'DENY'
        response['X-Robots-Tag'] = 'noindex, nofollow'
        response['Referrer-Policy'] = 'same-origin'
        patch_cache_control(response, no_store=True, no_cache=True, private=True, max_age=0)
        return response


class AdminTwoFactorMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        path = request.path
        if path.startswith(gated_prefixes()) and not path.startswith(exempt_paths()):
            step = twofactor.required_step(request)
            if step:
                return step_redirect(request, step)
        return self.get_response(request)
