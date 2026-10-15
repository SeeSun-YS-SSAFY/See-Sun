"""
접속기록 남기기(법적 검토 S-02)·관리자 로그인 잠금(S-01)

- record(): 접속기록 1건. 기록 실패가 업무를 막지 않도록 예외는 로그로만 남긴다.
- AdminAccessLogMiddleware: /admin/ 의 모델 화면 요청을 (조회·추가·수정·삭제·일괄 작업)으로 기록
- 로그인·로그인 실패·로그아웃 신호 기록
- 감사 대상 관리 명령 실행 기록(BaseCommand.execute 감싸기, CoreConfig.ready에서 설치)
- admin_login: 실패 5회(LOGIN_MAX_FAILURES)면 15분(LOGIN_LOCKOUT_SECONDS) 잠금 — IP·계정 이름 각각
"""
import getpass
import logging
import os

from django.conf import settings
from django.core.cache import cache
from django.http import HttpResponse

logger = logging.getLogger(__name__)

# 실행하면 접속기록을 남기는 관리 명령(개인정보를 보거나 바꿀 수 있는 것)
AUDITED_COMMANDS = {
    'shell', 'dbshell', 'dumpdata', 'loaddata', 'changepassword', 'createsuperuser', 'flush',
    'rotate_field_keys', 'purge_inactive_users', 'purge_withdrawn_users', 'purge_access_logs',
    'retry_firebase_deletions', 'import_mfds_foods', 'sync_reference_data',
    'ops_bootstrap_admin', 'ops_reset_otp', 'ops_push_app_update',
}
ADMIN_VIEW_SUFFIXES = {
    'changelist': 'VIEW', 'change': 'VIEW', 'add': 'VIEW', 'delete': 'VIEW', 'history': 'VIEW',
}
POST_ACTIONS = {'change': 'CHANGE', 'add': 'ADD', 'delete': 'DELETE', 'changelist': 'BULK_ACTION'}


def client_ip(request) -> str | None:
    """Caddy가 넘긴 X-Forwarded-For의 마지막 값(Caddy가 직접 본 주소), 없으면 REMOTE_ADDR"""
    forwarded = request.META.get('HTTP_X_FORWARDED_FOR', '')
    if forwarded:
        return forwarded.split(',')[-1].strip() or None
    return request.META.get('REMOTE_ADDR') or None


def record(action, *, actor='', actor_id='', ip=None, target_type='', target_id='', subject_id='', path='',
           status=None, detail=''):
    from django.db import transaction

    from .models import AccessLog
    try:
        with transaction.atomic():  # 실패해도 바깥 트랜잭션을 깨지 않도록 저장점 안에서
            AccessLog.objects.create(
                action=action, actor=(actor or '')[:150], actor_id=str(actor_id or '')[:64], ip=ip or None,
                target_type=target_type[:100], target_id=str(target_id or '')[:300],
                subject_id=str(subject_id or '')[:64], path=(path or '')[:300], status=status,
                detail=(detail or '')[:300],
            )
    except Exception:  # noqa: BLE001 — 기록 실패가 관리 업무를 막지 않게(운영자가 로그로 확인)
        logger.exception('접속기록 저장 실패: action=%s', action)


def _actor(user):
    if user is not None and getattr(user, 'is_authenticated', False):
        return user.get_username(), user.pk
    return '', ''


# ---- 관리자 화면 ---------------------------------------------------------------------------------
def _admin_target(match):
    """admin URL 이름(users_user_change) → (모델, 화면 종류)"""
    from django.contrib import admin

    name = match.url_name or ''
    for model in admin.site._registry:
        prefix = f'{model._meta.app_label}_{model._meta.model_name}_'
        if name.startswith(prefix):
            return model, name[len(prefix):]
    return None, None


def _subject_for(model, object_ids):
    """정보주체(User) ID: User 자체이거나 user 필드가 있는 모델이면"""
    if model is None or not object_ids:
        return ''
    if model._meta.label == settings.AUTH_USER_MODEL:
        return ','.join(object_ids)
    if any(f.name == 'user' for f in model._meta.get_fields()):
        try:
            ids = model._default_manager.filter(pk__in=object_ids).values_list('user_id', flat=True)
            return ','.join(sorted({str(i) for i in ids if i}))
        except Exception:  # noqa: BLE001
            return ''
    return ''


class AdminAccessLogMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.prefix = '/' + getattr(settings, 'ADMIN_URL_PREFIX', 'admin/').lstrip('/')

    def __call__(self, request):
        response = self.get_response(request)
        if not request.path.startswith(self.prefix):
            return response
        user = getattr(request, 'user', None)
        if not (user is not None and user.is_authenticated and user.is_staff):
            return response
        match = getattr(request, 'resolver_match', None)
        if match is None:
            return response
        model, view = _admin_target(match)
        if model is None:
            return response  # 첫 화면·앱 목록·jsi18n 등은 개인정보 화면이 아님
        object_id = match.kwargs.get('object_id')
        object_ids = [str(object_id)] if object_id else []
        if request.method == 'POST':
            action = POST_ACTIONS.get(view, 'CHANGE')
            if view == 'changelist':
                object_ids = request.POST.getlist('_selected_action')
                if not request.POST.get('action'):
                    action = 'CHANGE'  # 목록 화면 일괄 편집(list_editable)
        else:
            action = ADMIN_VIEW_SUFFIXES.get(view, 'VIEW')
        # 검색어 등 쿼리 값은 개인정보일 수 있어 키 이름만 남긴다
        keys = sorted(set(request.GET.keys()) | ({f"action={request.POST.get('action')}"} if request.POST.get('action') else set()))
        name, pk = _actor(user)
        record(action, actor=name, actor_id=pk, ip=client_ip(request), target_type=model._meta.label_lower,
               target_id=','.join(object_ids), subject_id=_subject_for(model, object_ids), path=request.path,
               status=response.status_code, detail=('params=' + ','.join(keys)) if keys else '')
        return response


def on_logged_in(sender, request, user, **kwargs):
    if request is None:
        return
    record('LOGIN', actor=user.get_username(), actor_id=user.pk, ip=client_ip(request), path=request.path)


def on_logged_out(sender, request, user, **kwargs):
    if request is None or user is None:
        return
    record('LOGOUT', actor=user.get_username(), actor_id=user.pk, ip=client_ip(request), path=request.path)


def on_login_failed(sender, credentials, request=None, **kwargs):
    if request is None:
        return
    username = str((credentials or {}).get('username', ''))[:150]
    record('LOGIN_FAILED', actor=username, ip=client_ip(request), path=request.path)


# ---- 관리자 로그인 잠금(S-01) ------------------------------------------------------------------------
# 비밀번호 단계와 2단계 인증(코드) 단계가 같은 카운터를 쓴다(계정·IP 각각). 비밀번호가 맞아도 카운터를 지우지 않고,
# 2단계 코드까지 맞아야 지운다 — 다시 로그인하는 것으로 코드 실패 횟수를 되돌릴 수 없게.
def login_lock_keys(username, ip):
    username = (username or '').strip().lower()[:150]
    return [f'admin-login-fail:ip:{ip}', f'admin-login-fail:user:{username}']


def is_login_locked(username, ip):
    return any((cache.get(k) or 0) >= settings.LOGIN_MAX_FAILURES for k in login_lock_keys(username, ip))


def register_login_failure(username, ip):
    for key in login_lock_keys(username, ip):
        try:
            cache.incr(key)
        except ValueError:
            cache.set(key, 1, settings.LOGIN_LOCKOUT_SECONDS)


def clear_login_failures(username, ip):
    cache.delete_many(login_lock_keys(username, ip))


def admin_login(request, extra_context=None):
    from django.contrib import admin

    lockout = settings.LOGIN_LOCKOUT_SECONDS
    username = request.POST.get('username') if request.method == 'POST' else None
    if request.method == 'POST' and is_login_locked(username, client_ip(request)):
        record('LOGIN_LOCKED', actor=(username or '')[:150], ip=client_ip(request), path=request.path, status=429)
        return HttpResponse(f'로그인 실패가 {settings.LOGIN_MAX_FAILURES}회 넘어 {lockout // 60}분 동안 잠겼습니다.',
                            status=429, content_type='text/plain; charset=utf-8')
    response = admin.site.login(request, extra_context)
    if request.method == 'POST' and not (request.user.is_authenticated and response.status_code in (301, 302, 303)):
        register_login_failure(username, client_ip(request))
    return response


# ---- 관리 명령 ------------------------------------------------------------------------------------
def operator_name():
    """명령을 실행한 사람: SEESUN_OPERATOR(docker exec -e) > SUDO_USER > OS 사용자"""
    for env in ('SEESUN_OPERATOR', 'SUDO_USER'):
        if os.environ.get(env):
            return os.environ[env]
    try:
        return getpass.getuser()
    except Exception:  # noqa: BLE001
        return 'unknown'


def install_command_audit():
    from django.core.management.base import BaseCommand

    if getattr(BaseCommand.execute, '_seesun_audited', False):
        return
    original = BaseCommand.execute

    def execute(self, *args, **options):
        name = self.__class__.__module__.rsplit('.', 1)[-1]
        if name in AUDITED_COMMANDS:
            # 인자 값(파일 경로·사용자 입력)은 남기지 않고 옵션 이름만
            flags = sorted(k for k, v in options.items() if v not in (None, False, [], '') and k not in (
                'verbosity', 'settings', 'pythonpath', 'traceback', 'no_color', 'force_color', 'skip_checks'))
            record('COMMAND', actor=operator_name(), path=f'manage.py {name}', detail='options=' + ','.join(flags))
        return original(self, *args, **options)

    execute._seesun_audited = True
    BaseCommand.execute = execute
