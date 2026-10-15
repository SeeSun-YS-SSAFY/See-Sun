"""
운영 콘솔(시선 ops) 화면 — 서버 렌더링(템플릿), 직원 전용

1. 대시보드: 가입자·신규 가입·운동 세션(일별 개수)·7일 활성·앱 버전 분포(개수만)
2. 사용자: 목록·검색(전화번호 해시 조각·이름 가림), 상세(건강·식단·운동 기록 없음), 이용 중지·재개, 탈퇴 처리(User.withdraw)
3. 운동 콘텐츠: 운동·픽토그램·안내 음성 보기, 파일 교체(이전 파일 보존)·되돌리기
4. 앱 버전: 지금 latest.json, APK 게시(publish_apk.sh와 같은 형식), 이력
5. 약관·기록: 동의 문서(읽기 전용), 접속기록 조회(필터)
쓰기 작업은 모두 접속기록에 남긴다(apps.core.audit.record).
"""
from datetime import datetime, time, timedelta

from django.conf import settings
from django.contrib import messages
from django.core.paginator import Paginator
from django.db.models import Count, Prefetch, Q
from django.shortcuts import get_object_or_404, redirect, render
from django.urls import reverse
from django.utils import timezone

from . import apk, media_files, push, stats
from .access import actor, audit, ops_view
from apps.core.audit import client_ip
from .masking import mask_name, mask_username, phone_fragment, user_status

USER_FIELDS = ('id', 'username', 'name', 'phone_hash', 'date_joined', 'last_login', 'is_active', 'is_deleted',
               'is_staff', 'is_superuser', 'is_profile_completed', 'firebase_uid')
PAGE_SIZE = 50


def _row(u):
    code, label = user_status(u)
    return {
        'id': u['id'], 'short_id': str(u['id'])[:8], 'username': mask_username(u['username']),
        'name': mask_name(u['name']), 'phone': phone_fragment(u['phone_hash']), 'date_joined': u['date_joined'],
        'last_login': u['last_login'], 'status': code, 'status_label': label,
        'is_staff': u['is_staff'] or u['is_superuser'], 'profile_done': u['is_profile_completed'],
        'firebase': bool(u['firebase_uid']),
    }


def _users():
    from apps.users.models import User
    return User.objects.all()


# ---- 1. 대시보드 -----------------------------------------------------------------------------------
@ops_view()
def dashboard(request):
    return render(request, 'ops/dashboard.html', {'nav': 'dashboard', 's': stats.dashboard()})


# ---- 2. 사용자 ------------------------------------------------------------------------------------
def _search(qs, q):
    import uuid as uuidlib
    digits = ''.join(ch for ch in q if ch.isdigit())
    if digits and len(digits) >= 10 and len(digits) == len(q.replace('-', '').replace(' ', '')):
        return qs.filter(phone_number=digits)  # 해시 색인 정확 일치(복호화 없음)
    cond = Q(username__icontains=q) | Q(name__icontains=q)
    try:
        cond |= Q(pk=uuidlib.UUID(q))
    except ValueError:
        if len(q) >= 4 and all(c in '0123456789abcdef-' for c in q.lower()):
            cond |= Q(id__startswith=q.lower())
    return qs.filter(cond)


@ops_view()
def users(request):
    q = (request.GET.get('q') or '').strip()[:100]
    status = request.GET.get('status') or ''
    qs = _users()
    if q:
        qs = _search(qs, q)
    if status == 'active':
        qs = qs.filter(is_deleted=False, is_active=True)
    elif status == 'suspended':
        qs = qs.filter(is_deleted=False, is_active=False)
    elif status == 'withdrawn':
        qs = qs.filter(is_deleted=True)
    elif status == 'staff':
        qs = qs.filter(Q(is_staff=True) | Q(is_superuser=True))
    page = Paginator(qs.order_by('-date_joined').values(*USER_FIELDS), PAGE_SIZE).get_page(request.GET.get('page'))
    # 개인정보 목록 조회 기록(검색어 값은 남기지 않고 키 이름만)
    keys = sorted(k for k in request.GET.keys() if request.GET.get(k))
    audit(request, 'VIEW', target_type='users.user', detail=('params=' + ','.join(keys)) if keys else 'list')
    return render(request, 'ops/users.html', {
        'nav': 'users', 'page': page, 'rows': [_row(u) for u in page.object_list], 'q': q, 'status': status,
        'total': page.paginator.count,
    })


@ops_view()
def user_detail(request, user_id):
    from apps.consents.models import ConsentType
    from apps.consents.services import latest_records
    from apps.users.models import UserAuthProvider

    u = get_object_or_404(_users().values(*USER_FIELDS), pk=user_id)
    row = _row(u)
    stub = _users().model(pk=u['id'])
    consents = []
    records = latest_records(stub)
    for value, label in ConsentType.choices:
        rec = records.get(value)
        consents.append({'label': label, 'agreed': None if rec is None else (rec.agreed and not rec.withdrawn_at),
                         'version': rec.version if rec else '', 'at': rec.recorded_at if rec else None})
    providers = list(UserAuthProvider.objects.filter(user_id=u['id']).values_list('provider', flat=True))
    audit(request, 'VIEW', target_type='users.user', target_id=u['id'], subject_id=u['id'])
    return render(request, 'ops/user_detail.html', {
        'nav': 'users', 'u': row, 'consents': consents, 'providers': providers,
        'is_self': u['id'] == request.user.pk,
    })


def _member_or_error(request, user_id):
    from apps.users.models import User
    user = get_object_or_404(User, pk=user_id)
    if user.is_staff or user.is_superuser:
        return user, '직원 계정은 운영 콘솔에서 바꿀 수 없습니다(관리자 화면 /admin/ 에서).'
    if user.is_deleted:
        return user, '이미 탈퇴 처리된 계정입니다.'
    return user, None


@ops_view(write=True)
def user_set_active(request, user_id, active):
    from apps.users.models import User
    user, error = _member_or_error(request, user_id)
    target = reverse('ops:user_detail', args=[user_id])
    if error:
        audit(request, 'CHANGE', target_type='users.user', target_id=user_id, subject_id=user_id, status=409,
              detail='refused')
        messages.error(request, error)
        return redirect(target)
    User.objects.filter(pk=user.pk).update(is_active=active)  # phone 등 다른 필드는 건드리지 않는다
    audit(request, 'CHANGE', target_type='users.user', target_id=user.pk, subject_id=user.pk,
          detail='ops:unsuspend' if active else 'ops:suspend')
    messages.success(request, '이용을 다시 열었습니다.' if active else '이용을 중지했습니다. 앱 로그인과 API 사용이 막힙니다.')
    return redirect(target)


@ops_view(write=True)
def user_withdraw(request, user_id):
    """기존 탈퇴 절차(User.withdraw: 토큰 폐기·개인정보 파기·account_withdrawn 신호·Firebase 삭제 대기열) 그대로"""
    user, error = _member_or_error(request, user_id)
    target = reverse('ops:user_detail', args=[user_id])
    if not error and (request.POST.get('confirm') or '').strip() != str(user.pk)[:8]:
        error = '확인 칸에 사용자 ID 앞 8자를 정확히 넣어야 탈퇴 처리됩니다.'
    if error:
        audit(request, 'DELETE', target_type='users.user', target_id=user_id, subject_id=user_id, status=409,
              detail='refused')
        messages.error(request, error)
        return redirect(target)
    user.withdraw()
    audit(request, 'DELETE', target_type='users.user', target_id=user.pk, subject_id=user.pk,
          detail='ops:withdraw(User.withdraw)')
    messages.success(request, '탈퇴 처리했습니다. 개인정보는 파기됐고, Firebase 계정 삭제는 실패하면 대기열에서 다시 시도합니다.')
    return redirect(target)


# ---- 3. 운동 콘텐츠 --------------------------------------------------------------------------------
def _media_rows(exercise):
    out = {'PICTOGRAM': [], 'GUIDE_AUDIO': []}
    for m in exercise.media_contents.all():
        if m.media_type in out:
            out[m.media_type].append(m)
    out['PICTOGRAM'].sort(key=lambda m: m.sequence)
    out['GUIDE_AUDIO'].sort(key=lambda m: m.sequence)
    return out


@ops_view()
def exercises(request):
    from apps.exercises.models import Exercise, ExerciseCategory
    category = request.GET.get('category') or ''
    qs = (Exercise.objects.select_related('category')
          .annotate(n_pic=Count('media_contents', filter=Q(media_contents__media_type='PICTOGRAM')),
                    n_audio=Count('media_contents', filter=Q(media_contents__media_type='GUIDE_AUDIO')))
          .order_by('category_id', 'exercise_name'))
    if category:
        qs = qs.filter(category_id=category)
    first_pic = {}
    from apps.exercises.models import ExerciseMedia
    for m in ExerciseMedia.objects.filter(media_type='PICTOGRAM', exercise__in=qs).order_by('exercise_id', 'sequence'):
        first_pic.setdefault(m.exercise_id, m.public_url)
    rows = [{'e': e, 'thumb': first_pic.get(e.pk)} for e in qs]
    return render(request, 'ops/exercises.html', {
        'nav': 'exercises', 'rows': rows, 'categories': ExerciseCategory.objects.order_by('category_id'),
        'category': category,
    })


@ops_view()
def exercise_detail(request, exercise_id):
    from apps.exercises.models import Exercise, ExerciseMedia
    from .models import MediaRevision
    exercise = get_object_or_404(
        Exercise.objects.select_related('category').prefetch_related(
            Prefetch('media_contents', queryset=ExerciseMedia.objects.all())), pk=exercise_id)
    media = _media_rows(exercise)
    revisions = MediaRevision.objects.filter(exercise_id=exercise.pk)[:50]
    return render(request, 'ops/exercise_detail.html', {
        'nav': 'exercises', 'e': exercise, 'pictograms': media['PICTOGRAM'], 'audios': media['GUIDE_AUDIO'],
        'revisions': revisions, 'cdn': bool(settings.MEDIA_CDN_BASE_URL),
        'image_limit_mb': settings.OPS_IMAGE_MAX_BYTES // (1024 * 1024),
        'audio_limit_mb': settings.OPS_AUDIO_MAX_BYTES // (1024 * 1024),
    })


@ops_view(write=True)
def media_replace(request, media_id):
    from apps.exercises.models import ExerciseMedia
    media = get_object_or_404(ExerciseMedia, pk=media_id, media_type__in=('PICTOGRAM', 'GUIDE_AUDIO'))
    target = reverse('ops:exercise_detail', args=[media.exercise_id])
    upload = request.FILES.get('file')
    try:
        if upload is None:
            raise media_files.MediaError('파일을 고르지 않았습니다.')
        revision = media_files.replace(media, upload, actor(request)[0])
    except media_files.MediaError as exc:
        audit(request, 'CHANGE', target_type='exercises.exercisemedia', target_id=media.pk, status=400,
              detail='ops:media_replace refused')
        messages.error(request, str(exc))
        return redirect(target)
    audit(request, 'CHANGE', target_type='exercises.exercisemedia', target_id=media.pk,
          detail=f'ops:media_replace rev={revision.pk} sha={revision.new_checksum[:12]}')
    messages.success(request, '파일을 바꿨습니다. 이전 파일은 그대로 남아 있어 「되돌리기」로 돌아갈 수 있습니다.')
    return redirect(target)


@ops_view(write=True)
def media_revert(request, revision_id):
    from .models import MediaRevision
    revision = get_object_or_404(MediaRevision, pk=revision_id)
    target = reverse('ops:exercise_detail', args=[revision.exercise_id])
    try:
        media_files.revert(revision, actor(request)[0])
    except media_files.MediaError as exc:
        audit(request, 'CHANGE', target_type='exercises.exercisemedia', target_id=revision.media_id or '',
              status=409, detail=f'ops:media_revert refused rev={revision.pk}')
        messages.error(request, str(exc))
        return redirect(target)
    audit(request, 'CHANGE', target_type='exercises.exercisemedia', target_id=revision.media_id,
          detail=f'ops:media_revert rev={revision.pk}')
    messages.success(request, '교체 전 파일로 되돌렸습니다.')
    return redirect(target)


# ---- 4. 앱 버전 -----------------------------------------------------------------------------------
@ops_view()
def app_release(request):
    from .models import AppPush, AppRelease
    latest = apk.read_latest()
    current = apk.current_version_code()
    last_push = {}
    for row in AppPush.objects.order_by('version_code', 'created_at', 'id'):
        last_push[row.version_code] = row  # 버전마다 가장 최근 보낸 기록
    disk = apk.history_on_disk()
    for d in disk:
        d['push'] = last_push.get(d['code'])
    releases = list(AppRelease.objects.all()[:50])
    for r in releases:
        r.push = last_push.get(r.version_code)
    raw_path = apk.app_dir() / 'latest.json'
    latest_raw = raw_path.read_text(encoding='utf-8', errors='replace') if raw_path.exists() else ''
    return render(request, 'ops/app_release.html', {
        'nav': 'app', 'latest': latest, 'latest_raw': latest_raw, 'latest_error': (latest or {}).get('_error') if isinstance(latest, dict) else None,
        'next_code': (current or 0) + 1, 'disk': disk, 'releases': releases,
        'base_url': apk.base_url(request), 'package': settings.APP_UPDATE_PACKAGE,
        'max_mb': settings.OPS_APK_MAX_BYTES // (1024 * 1024),
        'pushes': AppPush.objects.all()[:20], 'last_push': last_push, 'push_topic': push.topic(),
        'push_payload': _safe_payload(latest), 'current_push': last_push.get(current),
    })


def _safe_payload(latest):
    try:
        return push.payload(latest) if isinstance(latest, dict) and 'versionCode' in latest else None
    except (KeyError, TypeError, ValueError):
        return None


def _int(value, name):
    try:
        number = int(str(value).strip())
    except (TypeError, ValueError):
        raise apk.PublishError(f'{name}는 숫자여야 합니다.') from None
    if number < 0:
        raise apk.PublishError(f'{name}는 0 이상이어야 합니다.')
    return number


@ops_view(write=True)
def app_publish(request):
    target = reverse('ops:app_release')
    try:
        upload = request.FILES.get('apk')
        if upload is None:
            raise apk.PublishError('APK 파일을 고르지 않았습니다.')
        code = _int(request.POST.get('version_code'), 'versionCode')
        min_code = _int(request.POST.get('min_version_code') or 0, 'minVersionCode')
        name = (request.POST.get('version_name') or '').strip()
        notes = (request.POST.get('notes') or '').strip().replace('\r\n', '\n')
        if not name or len(name) > 64:
            raise apk.PublishError('versionName을 넣어 주세요(64자 이내).')
        if not notes:
            raise apk.PublishError('바뀐 점(한국어)을 넣어 주세요. 앱의 업데이트 창에 그대로 보입니다.')
        if len(notes) > 1000:
            raise apk.PublishError('바뀐 점은 1000자 이내로 써 주세요.')
        manifest = apk.publish(upload, code=code, name=name, notes=notes, min_code=min_code,
                               actor=actor(request)[0], url_base=apk.base_url(request))
    except apk.PublishError as exc:
        audit(request, 'ADD', target_type='ops.apprelease', target_id=request.POST.get('version_code', '')[:20],
              status=400, detail='ops:apk_publish refused')
        messages.error(request, str(exc))
        return redirect(target)
    audit(request, 'ADD', target_type='ops.apprelease', target_id=manifest['versionCode'],
          detail=f"ops:apk_publish sha={manifest['sha256'][:12]} pruned={','.join(map(str, manifest['_pruned'])) or '-'}")
    messages.success(request, f"versionCode {manifest['versionCode']}({manifest['versionName']})을 게시했습니다.")
    _push_and_report(request, manifest, 'publish')
    return redirect(target)


def _push_and_report(request, manifest, source):
    result = push.send_app_update(manifest, actor=actor(request)[0], source=source, ip=client_ip(request),
                                  path=request.path)
    if result.ok:
        messages.success(request, f'푸시 보냄 (메시지 ID {result.message_id}) — 토픽 {result.topic}을 구독한 앱에 바로 알림이 갑니다.')
    else:
        messages.warning(request, f'푸시 실패: {result.error}. 게시는 끝났고, 앱은 다음 실행 때(또는 6시간 안에) '
                                  '스스로 확인합니다. 「업데이트 알림 다시 보내기」로 다시 시도할 수 있습니다.')
    return result


@ops_view(write=True)
def app_push_resend(request):
    """지금 공개된 버전(latest.json)의 업데이트 알림을 다시 보낸다(최고 관리자, 접속기록)"""
    target = reverse('ops:app_release')
    latest = apk.read_latest()
    if not isinstance(latest, dict) or not isinstance(latest.get('versionCode'), int):
        audit(request, 'ADD', target_type='ops.apppush', status=409, detail='push:resend refused(no latest.json)')
        messages.error(request, '공개된 버전(latest.json)이 없어 보낼 알림이 없습니다.')
        return redirect(target)
    _push_and_report(request, latest, 'resend')
    return redirect(target)


# ---- 5. 약관·기록 ---------------------------------------------------------------------------------
@ops_view()
def records(request):
    from apps.consents.models import ConsentDocument, ConsentRecord
    from apps.core.models import AccessLog

    docs = ConsentDocument.objects.all()
    # 항목별 지금 동의 중인 인원(사람·항목마다 가장 최근 기록 기준, 개수만)
    last = {}
    for user_id, kind, ok, withdrawn in (ConsentRecord.objects.filter(user__is_deleted=False)
                                         .order_by('recorded_at', 'id')
                                         .values_list('user_id', 'type', 'agreed', 'withdrawn_at')):
        last[(user_id, kind)] = ok and withdrawn is None
    agreed = {}
    for (_, kind), ok in last.items():
        agreed[kind] = agreed.get(kind, 0) + (1 if ok else 0)
    logs = AccessLog.objects.all()
    f = {k: (request.GET.get(k) or '').strip()[:150] for k in ('action', 'actor', 'target_type', 'since', 'until', 'subject')}
    if f['action']:
        logs = logs.filter(action=f['action'])
    if f['actor']:
        logs = logs.filter(actor__icontains=f['actor'])
    if f['target_type']:
        logs = logs.filter(target_type=f['target_type'])
    if f['subject']:
        logs = logs.filter(subject_id__startswith=f['subject'].lower())
    tz = timezone.get_current_timezone()
    for key, op in (('since', 'gte'), ('until', 'lt')):
        if f[key]:
            try:
                day = datetime.strptime(f[key], '%Y-%m-%d').date()
                if key == 'until':
                    day += timedelta(days=1)
                logs = logs.filter(**{f'at__{op}': datetime.combine(day, time.min, tzinfo=tz)})
            except ValueError:
                messages.error(request, '날짜는 2026-10-15 형식으로 넣어 주세요.')
    page = Paginator(logs, PAGE_SIZE).get_page(request.GET.get('page'))
    target_types = (AccessLog.objects.exclude(target_type='').values_list('target_type', flat=True)
                    .distinct().order_by('target_type'))
    keys = sorted(k for k in request.GET.keys() if request.GET.get(k))
    audit(request, 'VIEW', target_type='core.accesslog', detail=('params=' + ','.join(keys)) if keys else 'list')
    query = request.GET.copy()
    query.pop('page', None)
    return render(request, 'ops/records.html', {
        'nav': 'records', 'docs': [{'d': d, 'agreed': agreed.get(d.type, 0)} for d in docs], 'page': page,
        'f': f, 'actions': AccessLog.Action.choices, 'target_types': list(target_types), 'query': query.urlencode(),
    })
