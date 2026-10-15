"""
운동 기록 2차 API (09 문서 2-10, 02 문서 EX-07·EX-10)

- POST /api/v1/exercise-sessions/bulk/ : 앱 오프라인 큐의 운동 세션을 client_uuid로 멱등 저장
- GET  /api/v1/exercise/weekly/        : 주간 운동 횟수·시간(마이페이지 [운동] 레포트)

유효 세션 규칙은 1차와 같다: 완료(COMPLETED)이고 10초 이상.
"""
from datetime import date, datetime, time, timedelta

from django.conf import settings
from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.throttling import UserRateThrottle
from rest_framework.views import APIView

from apps.consents.permissions import DATA_API_PERMISSIONS
from apps.core.dates import RANGE_MESSAGE, parse_api_date
from apps.core.responses import validation_error
from apps.exercises.models import Exercise, Playlist
from apps.users.profile import local_today
from seesun_rules import get_tables

from .models import ExerciseSession, ExerciseSessionItem

MIN_VALID_SECONDS = 10.0
CLOCK_SKEW = timedelta(minutes=5)
# 한 세션 최대 길이(항목 duration_ms 상한과 같다)
MAX_SESSION_DURATION = timedelta(hours=24)


class SyncRateThrottle(UserRateThrottle):
    scope = 'sync'


class SessionItemInputSerializer(serializers.Serializer):
    exercise_id = serializers.UUIDField()
    sequence_no = serializers.IntegerField(min_value=0, max_value=1000)
    started_at = serializers.DateTimeField(required=False, allow_null=True)
    ended_at = serializers.DateTimeField(required=False, allow_null=True)
    duration_ms = serializers.IntegerField(required=False, allow_null=True, min_value=0, max_value=24 * 3600 * 1000)
    is_skipped = serializers.BooleanField(required=False, default=False)
    skip_reason = serializers.ChoiceField(choices=ExerciseSessionItem.SKIP_REASON_CHOICES, required=False, allow_null=True)
    rest_sec = serializers.IntegerField(required=False, default=10, min_value=0, max_value=3600)


class SessionInputSerializer(serializers.Serializer):
    client_uuid = serializers.UUIDField()
    exercise_name = serializers.CharField(max_length=255, required=False, allow_blank=True)
    playlist_id = serializers.UUIDField(required=False, allow_null=True)
    mode = serializers.ChoiceField(choices=ExerciseSession.MODE_CHOICES, required=False, default='MANUAL')
    started_at = serializers.DateTimeField()
    ended_at = serializers.DateTimeField(required=False, allow_null=True)
    status = serializers.ChoiceField(choices=ExerciseSession.STATUS_CHOICES, required=False, default='COMPLETED')
    abnormal_end_reason = serializers.ChoiceField(choices=ExerciseSession.ABNORMAL_END_REASON_CHOICES,
                                                  required=False, allow_null=True)
    items = SessionItemInputSerializer(many=True, required=False, default=list, max_length=100)

    def validate(self, attrs):
        now = timezone.now()
        if attrs['started_at'] > now + CLOCK_SKEW:
            raise serializers.ValidationError({'started_at': '미래 시각입니다.'})
        ended = attrs.get('ended_at')
        if ended and ended < attrs['started_at']:
            raise serializers.ValidationError({'ended_at': '종료 시각이 시작보다 빠릅니다.'})
        if ended and ended - attrs['started_at'] > MAX_SESSION_DURATION:
            # duration_ms(IntegerField)는 약 24.8일까지만 담긴다 → 넘으면 DB integer out of range 500(배포 리허설 B-09)
            raise serializers.ValidationError({'ended_at': '운동 세션은 24시간을 넘을 수 없습니다.'})
        user = self.context['user']
        playlist_id = attrs.pop('playlist_id', None)
        attrs['playlist'] = None
        if playlist_id:
            attrs['playlist'] = Playlist.objects.filter(playlist_id=playlist_id, user=user).first()
        if not attrs.get('exercise_name'):
            if attrs['playlist'] is None:
                raise serializers.ValidationError({'exercise_name': 'exercise_name 또는 본인 playlist_id가 필요합니다.'})
            attrs['exercise_name'] = attrs['playlist'].title
        ids = {i['exercise_id'] for i in attrs['items']}
        found = set(Exercise.objects.filter(exercise_id__in=ids).values_list('exercise_id', flat=True))
        if ids - found:
            raise serializers.ValidationError({'items': f'없는 운동 ID: {sorted(map(str, ids - found))}'})
        return attrs


class SessionBulkRequestSerializer(serializers.Serializer):
    sessions = serializers.ListField(child=serializers.DictField(), allow_empty=False,
                                     help_text='SessionInput 목록(최대 50건)')


class SessionBulkResultSerializer(serializers.Serializer):
    client_uuid = serializers.CharField(allow_null=True)
    status = serializers.CharField(help_text='created|duplicate|invalid')
    session_id = serializers.CharField(allow_null=True)
    is_valid = serializers.BooleanField(required=False)
    errors = serializers.DictField(required=False)


class SessionBulkResponseSerializer(serializers.Serializer):
    results = SessionBulkResultSerializer(many=True)


def create_session(user, data):
    existing = ExerciseSession.objects.filter(user=user, client_uuid=data['client_uuid']).first()
    if existing:
        return existing, 'duplicate'
    items = data.pop('items', [])
    ended = data.get('ended_at')
    duration = (ended - data['started_at']) if ended else None
    seconds = duration.total_seconds() if duration is not None else None
    try:
        with transaction.atomic():
            session = ExerciseSession.objects.create(
                user=user, duration=duration, duration_seconds=seconds,
                duration_ms=int(seconds * 1000) if seconds is not None else None,
                is_valid=bool(seconds is not None and seconds >= MIN_VALID_SECONDS and data['status'] == 'COMPLETED'),
                **data,
            )
            ExerciseSessionItem.objects.bulk_create([
                ExerciseSessionItem(session=session, exercise_id=i['exercise_id'], sequence_no=i['sequence_no'],
                                    started_at=i.get('started_at'), ended_at=i.get('ended_at'),
                                    duration_ms=i.get('duration_ms'), is_skipped=i.get('is_skipped', False),
                                    skip_reason=i.get('skip_reason'), rest_sec=i.get('rest_sec', 10))
                for i in items
            ])
    except IntegrityError:
        return ExerciseSession.objects.get(user=user, client_uuid=data['client_uuid']), 'duplicate'
    return session, 'created'


class ExerciseSessionBulkView(APIView):
    permission_classes = DATA_API_PERMISSIONS
    throttle_classes = [SyncRateThrottle]

    @extend_schema(summary='운동 세션 일괄 업로드(멱등)', tags=['Exercise'], request=SessionBulkRequestSerializer,
                   responses=SessionBulkResponseSerializer,
                   description='sessions[] = {client_uuid, exercise_name|playlist_id, mode, started_at, ended_at, status, items[]}. '
                               '10초 미만이거나 완료되지 않은 세션은 is_valid=false로 저장합니다.')
    def post(self, request):
        sessions = request.data.get('sessions') if isinstance(request.data, dict) else None
        if not isinstance(sessions, list) or not sessions:
            return validation_error({'sessions': ['세션 목록이 필요합니다.']})
        if len(sessions) > settings.BULK_MAX_ITEMS:
            return validation_error({'sessions': [f'한 번에 최대 {settings.BULK_MAX_ITEMS}건까지 보낼 수 있습니다.']})
        results = []
        for raw in sessions:
            client_uuid = raw.get('client_uuid') if isinstance(raw, dict) else None
            serializer = SessionInputSerializer(data=raw, context={'user': request.user})
            if not serializer.is_valid():
                results.append({'client_uuid': client_uuid, 'status': 'invalid', 'session_id': None,
                                'errors': serializer.errors})
                continue
            session, state = create_session(request.user, dict(serializer.validated_data))
            results.append({'client_uuid': str(session.client_uuid), 'status': state,
                            'session_id': str(session.session_id), 'is_valid': session.is_valid})
        return Response({'results': results})


class WeeklyExerciseSerializer(serializers.Serializer):
    week_start = serializers.DateField()
    week_end = serializers.DateField()
    session_count = serializers.IntegerField()
    total_seconds = serializers.IntegerField()
    total_minutes = serializers.IntegerField()
    days = serializers.ListField(child=serializers.DictField())
    tts_message = serializers.CharField()


def weekly_summary(user, any_day: date):
    start = any_day - timedelta(days=any_day.weekday())
    tz = timezone.get_current_timezone()
    begin = timezone.make_aware(datetime.combine(start, time.min), tz)
    end = begin + timedelta(days=7)
    sessions = ExerciseSession.objects.filter(user=user, started_at__gte=begin, started_at__lt=end,
                                              status='COMPLETED', is_valid=True)
    names = get_tables().REPORT['weekday_names']
    days = {start + timedelta(days=i): {'count': 0, 'seconds': 0.0} for i in range(7)}
    for s in sessions:
        day = timezone.localtime(s.started_at, tz).date()
        seconds = s.duration_seconds
        if seconds is None and s.ended_at:
            seconds = (s.ended_at - s.started_at).total_seconds()
        days[day]['count'] += 1
        days[day]['seconds'] += seconds or 0
    total_seconds = int(sum(d['seconds'] for d in days.values()))
    count = sum(d['count'] for d in days.values())
    # 반올림은 0.5에서 올린다(앱 요일별 분 표시와 같게). round()는 은행가 반올림이라 30초를 0분으로 읽었다(통합 E2E BUG-06).
    minutes = (total_seconds + 30) // 60
    label = '이번 주' if start == local_today() - timedelta(days=local_today().weekday()) else f'{start.month}월 {start.day}일 주'
    tts = f'{label} 운동 {count}회, 총 {minutes}분이에요.' if count else f'{label}에는 운동 기록이 없어요.'
    return {
        'week_start': start, 'week_end': start + timedelta(days=6), 'session_count': count,
        'total_seconds': total_seconds, 'total_minutes': minutes,
        'days': [{'date': d.isoformat(), 'weekday': names[d.weekday()], 'session_count': v['count'],
                  'seconds': int(v['seconds'])} for d, v in days.items()],
        'tts_message': tts,
    }


class WeeklyExerciseView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='주간 운동 리포트', tags=['Exercise'], responses=WeeklyExerciseSerializer,
                   parameters=[OpenApiParameter('from', str, description='YYYY-MM-DD(그 주 월요일로 맞춤), 기본 이번 주')])
    def get(self, request):
        day = parse_api_date(request.query_params.get('from'), local_today())
        if day is None:
            return validation_error({'from': ['YYYY-MM-DD 형식이어야 합니다.', RANGE_MESSAGE]})
        return Response(WeeklyExerciseSerializer(weekly_summary(request.user, day)).data)
