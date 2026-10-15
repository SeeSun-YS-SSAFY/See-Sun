"""
대시보드 집계 — 사람별 기록이 아니라 개수만 돌려준다(건강·식단·운동 기록 내용은 읽지 않는다)
"""
from collections import Counter
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.conf import settings
from django.db.models import Count, Q
from django.db.models.functions import TruncDate
from django.utils import timezone

DAYS = 14


def _local_today():
    return timezone.localtime(timezone.now(), ZoneInfo(settings.TIME_ZONE)).date()


def _daily(qs, field, days=DAYS):
    tz = ZoneInfo(settings.TIME_ZONE)
    today = _local_today()
    start = today - timedelta(days=days - 1)
    since = datetime.combine(start, time.min, tzinfo=tz)
    rows = (qs.filter(**{f'{field}__gte': since}).annotate(day=TruncDate(field, tzinfo=tz))
            .values('day').annotate(n=Count('pk')).order_by('day'))
    counts = {r['day']: r['n'] for r in rows}
    series = [{'day': start + timedelta(days=i), 'n': counts.get(start + timedelta(days=i), 0)} for i in range(days)]
    peak = max((s['n'] for s in series), default=0)
    for s in series:
        s['pct'] = round(100 * s['n'] / peak) if peak else 0
    return series


def members():
    from apps.users.models import User
    return User.objects.filter(is_staff=False, is_superuser=False)


def dashboard():
    from apps.consents.models import ConsentRecord
    from apps.logs.models import ExerciseSession
    from apps.users.models import FirebaseDeletionTask

    now = timezone.now()
    week_ago = now - timedelta(days=7)
    people = members()
    totals = people.aggregate(
        total=Count('pk', filter=Q(is_deleted=False)),
        active_accounts=Count('pk', filter=Q(is_deleted=False, is_active=True)),
        suspended=Count('pk', filter=Q(is_deleted=False, is_active=False)),
        withdrawn=Count('pk', filter=Q(is_deleted=True)),
    )
    session_users = (ExerciseSession.objects.filter(started_at__gte=week_ago, user__is_staff=False)
                     .values_list('user_id', flat=True).distinct())
    active_7d = people.filter(is_deleted=False).filter(
        Q(last_login__gte=week_ago) | Q(pk__in=session_users)).count()
    signups = _daily(people, 'date_joined')
    sessions = _daily(ExerciseSession.objects.filter(user__is_staff=False), 'started_at')

    # 앱 버전: 사람마다 가장 최근 동의 기록의 app_version(앱이 보낸 값). 값이 없으면 화면에서 뺀다.
    latest_version = {}
    for user_id, version in (ConsentRecord.objects.filter(user__is_deleted=False, user__is_staff=False)
                             .exclude(app_version='').order_by('user_id', 'recorded_at', 'id')
                             .values_list('user_id', 'app_version')):
        latest_version[user_id] = version
    versions = Counter(latest_version.values()).most_common(8)
    version_total = sum(n for _, n in versions)

    return {
        'totals': totals,
        'active_7d': active_7d,
        'signups': signups,
        'signups_sum': sum(s['n'] for s in signups),
        'signups_peak': max(s['n'] for s in signups),
        'sessions_peak': max(s['n'] for s in sessions),
        'sessions': sessions,
        'sessions_sum': sum(s['n'] for s in sessions),
        'sessions_7d': ExerciseSession.objects.filter(started_at__gte=week_ago, user__is_staff=False).count(),
        'versions': [{'version': v, 'n': n, 'pct': round(100 * n / version_total) if version_total else 0}
                     for v, n in versions],
        'firebase_pending': FirebaseDeletionTask.objects.count(),
    }
