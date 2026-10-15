"""
로컬 데모 백엔드 DB에서 한 사용자의 서버 상태를 JSON으로 출력한다(통합 E2E의 서버 쪽 확인용, 읽기 전용).

run_local_backend.ps1 이 쓰는 것과 같은 DB(backend/db.sqlite3, DJANGO_DEBUG=true)를 연다.

    backend\.venv\Scripts\python.exe scripts\e2e\server_state.py --phone 01055501234
    backend\.venv\Scripts\python.exe scripts\e2e\server_state.py --user-id <uuid>
"""
import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'backend'


def setup():
    os.environ.setdefault('PYTHONUTF8', '1')
    os.environ['DJANGO_DEBUG'] = 'true'
    os.environ.setdefault('DJANGO_SQLITE_PATH', str(BACKEND / 'db.sqlite3'))
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
    sys.path.insert(0, str(BACKEND))
    os.chdir(BACKEND)
    import django
    django.setup()


def state(phone=None, user_id=None):
    from apps.consents.models import ConsentRecord
    from apps.health.models import HealthProfile
    from apps.logs.models import ExerciseSession
    from apps.users.models import User, UserAuthProvider
    from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken

    if user_id:
        user = User.objects.filter(id=user_id).first()
    else:
        users = list(User.objects.filter(phone_number=phone))
        if len(users) > 1:
            return {'error': f'같은 전화번호 사용자 {len(users)}명'}
        user = users[0] if users else None
    if user is None:
        return {'found': False}
    health = HealthProfile.objects.filter(user=user).first()
    sessions = []
    for s in ExerciseSession.objects.filter(user=user).order_by('started_at'):
        items = list(s.items.order_by('sequence_no'))
        sessions.append({
            'client_uuid': str(s.client_uuid) if s.client_uuid else None,
            'mode': s.mode, 'exercise_name': s.exercise_name, 'status': s.status,
            'playlist_id': str(s.playlist_id) if s.playlist_id else None,
            'duration_ms': s.duration_ms, 'is_valid': s.is_valid, 'abnormal_end_reason': s.abnormal_end_reason,
            'items': [{'seq': i.sequence_no, 'exercise': i.exercise.name if hasattr(i.exercise, 'name') else str(i.exercise_id),
                       'duration_ms': i.duration_ms, 'skipped': i.is_skipped, 'skip_reason': i.skip_reason,
                       'rest_sec': i.rest_sec} for i in items],
        })
    outstanding = OutstandingToken.objects.filter(user=user)
    return {
        'found': True,
        'user_id': str(user.id),
        'is_active': user.is_active, 'is_deleted': user.is_deleted,
        'deleted_at': user.deleted_at.isoformat() if user.deleted_at else None,
        'last_login': user.last_login.isoformat() if user.last_login else None,
        'username': user.username,
        'pii': {
            'name': user.name, 'phone_number': user.phone_number, 'phone_hash': user.phone_hash,
            'firebase_uid': user.firebase_uid, 'birthdate': user.birthdate.isoformat() if user.birthdate else None,
            'gender': user.gender, 'height_cm': user.height_cm, 'weight_kg': user.weight_kg,
            'goal': user.goal, 'target_kcal': user.target_kcal, 'email': user.email,
        },
        'is_profile_completed': user.is_profile_completed,
        'consents': [{'type': c.type, 'version': c.version, 'agreed': c.agreed, 'method': c.method,
                      'withdrawn': c.withdrawn_at is not None, 'screen_id': c.screen_id,
                      'app_version': c.app_version, 'voice_confirmed': c.voice_confirmed}
                     for c in ConsentRecord.objects.filter(user=user).order_by('recorded_at', 'id')],
        'health': None if health is None else {
            'diseases': health.diseases, 'allergen_codes': health.allergen_codes, 'other_allergies': health.other_allergies},
        'exercise_sessions': sessions,
        'auth_providers': UserAuthProvider.objects.filter(user=user).count(),
        'tokens': {'outstanding': outstanding.count(),
                   'blacklisted': BlacklistedToken.objects.filter(token__in=outstanding).count()},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--phone')
    parser.add_argument('--user-id')
    args = parser.parse_args()
    setup()
    print(json.dumps(state(args.phone, args.user_id), ensure_ascii=False, indent=1, default=str))


if __name__ == '__main__':
    main()
