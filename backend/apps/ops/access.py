"""
운영 콘솔 접근 통제(법적 검토 S-01·S-02)

- 허용 IP: OPS_ALLOWED_IPS. 가운영은 any(IP 제한 없음 — 2026-10-15 소유자 결정 msg 993, 대신 비밀번호 + 2단계 인증).
  값을 비우면 ADMIN_ALLOWED_IPS와 같은 목록(Caddy remote_ip 형식, private_ranges 가능)으로 제한하고, 밖이면 404.
  장고 기본 관리자 화면(/admin/)은 늘 ADMIN_ALLOWED_IPS로 제한한다(Caddy + apps.ops.middleware).
- 로그인: 콘솔 전용 /ops/login/ (관리자 로그인과 같은 실패 잠금·접속기록, 계정 유무를 드러내지 않는 같은 오류 문구)
  그다음 2단계 인증(TOTP, apps.ops.twofactor) — 첫 로그인이면 비밀번호 변경·등록부터.
- 권한: 조회는 직원(is_staff), 바꾸는 작업(이용 중지·탈퇴 처리·파일 교체·앱 게시)은 최고 관리자(is_superuser)만.
- 개인정보 화면 조회와 모든 쓰기 작업은 접속기록(apps.core.audit.record)에 남긴다. 검색어 같은 값은 남기지 않는다.
"""
import ipaddress
import logging
from functools import wraps

from django.conf import settings
from django.contrib.auth.views import redirect_to_login
from django.http import Http404
from django.shortcuts import render
from django.urls import reverse

from apps.core.audit import client_ip, record

logger = logging.getLogger(__name__)

# Caddy private_ranges 와 같은 대역
PRIVATE_RANGES = ('192.168.0.0/16', '172.16.0.0/12', '10.0.0.0/8', '127.0.0.1/8', 'fd00::/8', '::1')


def allowed_networks(spec=None):
    spec = settings.OPS_ALLOWED_IPS if spec is None else spec
    networks = []
    for token in spec.replace(',', ' ').split():
        values = PRIVATE_RANGES if token == 'private_ranges' else (token,)
        for value in values:
            try:
                networks.append(ipaddress.ip_network(value, strict=False))
            except ValueError:
                logger.warning('운영 콘솔 허용 IP 형식이 아닙니다(무시): %r', value[:60])
    return networks


def ip_allowed(ip, spec=None):
    """spec(Caddy remote_ip 형식). 'any'면 IP를 보지 않는다(운영 콘솔을 인증만으로 여는 경우, 결정 2026-10-15 msg 993)."""
    spec = settings.OPS_ALLOWED_IPS if spec is None else spec
    if spec.strip().lower() == 'any':
        return True
    if not ip:
        return False
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if getattr(addr, 'ipv4_mapped', None):
        addr = addr.ipv4_mapped
    return any(addr.version == net.version and addr in net for net in allowed_networks(spec))


def actor(request):
    user = request.user
    return user.get_username(), user.pk


def audit(request, action, *, target_type='', target_id='', subject_id='', status=200, detail=''):
    name, pk = actor(request)
    record(action, actor=name, actor_id=pk, ip=client_ip(request), target_type=target_type, target_id=target_id,
           subject_id=subject_id, path=request.path, status=status, detail=detail)


def ops_view(write=False):
    """운영 콘솔 화면 데코레이터: IP → 로그인 → 직원(쓰기면 최고 관리자) 순서로 확인"""
    def decorator(view):
        @wraps(view)
        def wrapped(request, *args, **kwargs):
            if not ip_allowed(client_ip(request)):
                raise Http404()
            user = request.user
            if not user.is_authenticated:
                return redirect_to_login(request.get_full_path(), reverse('ops:login'))
            if not (user.is_active and user.is_staff):
                return render(request, 'ops/forbidden.html', {'reason': '직원 계정만 들어올 수 있습니다.'}, status=403)
            from . import twofactor
            from .middleware import step_redirect
            step = twofactor.required_step(request)  # 미들웨어가 먼저 막지만 화면마다 한 번 더
            if step:
                return step_redirect(request, step)
            if write:
                if request.method != 'POST':
                    return render(request, 'ops/forbidden.html', {'reason': '잘못된 요청입니다.'}, status=405)
                if not user.is_superuser:
                    audit(request, 'CHANGE', status=403, detail='denied=not_superuser')
                    return render(request, 'ops/forbidden.html',
                                  {'reason': '이 작업은 최고 관리자 계정만 할 수 있습니다.'}, status=403)
            return view(request, *args, **kwargs)
        wrapped.ops_protected = True
        return wrapped
    return decorator
