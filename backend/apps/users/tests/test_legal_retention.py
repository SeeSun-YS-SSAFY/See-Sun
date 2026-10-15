"""
법적 검토 보완 회귀 테스트 — D-05 Firebase 삭제 재시도 큐, D-06 동의 이력 보관, D-07 탈퇴 행 비식별·파기,
D-08 장기 미이용자 파기(last_login 갱신 포함)
"""
from datetime import timedelta

import pytest
from django.core.management import call_command
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from apps.consents.models import ConsentRecord
from apps.core.testing import client_for, make_user
from apps.users import firebase
from apps.users.models import FirebaseDeletionTask, User


class FlakyVerifier(firebase.FirebaseVerifier):
    def __init__(self, fail=True):
        self.fail, self.deleted = fail, []

    def verify(self, id_token):
        _, uid, phone = id_token.split(':', 2)
        return firebase.FirebaseIdentity(uid=uid, phone_number=phone)

    def delete_user(self, uid):
        if self.fail:
            raise firebase.FirebaseDeletionFailed('UnavailableError')
        self.deleted.append(uid)


@pytest.fixture
def flaky():
    v = FlakyVerifier()
    firebase.set_verifier_for_tests(v)
    yield v
    firebase.set_verifier_for_tests(None)


def _withdrawn_user(uid='uid-x', phone='01077778888'):
    user = make_user(phone)
    user.firebase_uid = uid
    user.save()
    user.withdraw()
    return User.objects.get(pk=user.pk)


@pytest.mark.django_db
class TestFirebaseDeletionQueue:
    def test_삭제_실패는_큐에_남고_재시도로_지워진다(self, flaky):
        _withdrawn_user('uid-q1')
        task = FirebaseDeletionTask.objects.get(firebase_uid='uid-q1')
        assert task.attempts == 1 and task.last_error == 'UnavailableError' and task.last_attempt_at
        with pytest.raises(SystemExit):
            call_command('retry_firebase_deletions')
        assert FirebaseDeletionTask.objects.get(firebase_uid='uid-q1').attempts == 2
        flaky.fail = False
        call_command('retry_firebase_deletions')
        assert not FirebaseDeletionTask.objects.exists() and flaky.deleted == ['uid-q1']

    @override_settings(FIREBASE_CREDENTIALS_FILE='', FIREBASE_PROJECT_ID='', FIREBASE_AUTH_FAKE=False)
    def test_자격증명이_없어도_실패로_기록(self):
        firebase.set_verifier_for_tests(None)
        _withdrawn_user('uid-q2')
        assert FirebaseDeletionTask.objects.get(firebase_uid='uid-q2').last_error == 'NOT_CONFIGURED'

    @override_settings(FIREBASE_CREDENTIALS_FILE='', FIREBASE_PROJECT_ID='p', FIREBASE_AUTH_FAKE=False)
    def test_프로젝트_ID만이면_서비스계정_없음으로_기록(self):
        firebase.set_verifier_for_tests(None)
        firebase._admin_verifier = None
        _withdrawn_user('uid-q3')
        assert FirebaseDeletionTask.objects.get(firebase_uid='uid-q3').last_error == 'NO_SERVICE_ACCOUNT'


@pytest.mark.django_db
class TestWithdrawnRow:
    def test_비식별_행에는_가입일·마지막_접속·탈퇴일만(self, flaky):
        user = make_user('01012121212', sensitive=True)
        user.email, user.is_staff = 'a@example.com', True
        user.last_login = timezone.now()
        user.save()
        user.withdraw()
        u = User.objects.get(pk=user.pk)
        assert u.date_joined and u.last_login and u.deleted_at
        assert (u.name, u.phone_number, u.phone_hash, u.firebase_uid, u.birthdate, u.gender, u.height_cm,
                u.weight_kg, u.goal, u.target_kcal, u.pin_hash, u.email) == (None,) * 11 + ('',)
        assert u.app_settings == {} and not u.is_staff and not u.is_superuser and not u.is_active
        assert not u.has_usable_password() and u.groups.count() == 0 and u.username == f'deleted_{u.pk}'

    def test_동의_이력은_보관_후_파기(self, flaky):
        user = make_user('01034343434', sensitive=True)
        user.withdraw()
        records = ConsentRecord.objects.filter(user=user)
        assert records.count() >= 3 and not records.filter(withdrawn_at__isnull=True, agreed=True).exists()
        call_command('purge_withdrawn_users')
        assert User.objects.filter(pk=user.pk).exists()  # 보관 기간 안
        User.objects.filter(pk=user.pk).update(deleted_at=timezone.now() - timedelta(days=3 * 365 + 1))
        call_command('purge_withdrawn_users')
        assert not User.objects.filter(pk=user.pk).exists()
        assert not ConsentRecord.objects.filter(user_id=user.pk).exists()

    def test_탈퇴_안_한_사용자는_파기하지_않음(self):
        user = make_user('01056565656')
        User.objects.filter(pk=user.pk).update(deleted_at=timezone.now() - timedelta(days=4000))
        call_command('purge_withdrawn_users')
        assert User.objects.filter(pk=user.pk).exists()


@pytest.mark.django_db
class TestInactivePurge:
    def test_2년_미접속_일반_사용자만_파기(self, flaky):
        old = make_user('01011110001')
        recent = make_user('01011110002')
        admin_user = make_user('01011110003')
        never = make_user('01011110004')
        long_ago = timezone.now() - timedelta(days=731)
        User.objects.filter(pk=old.pk).update(last_login=long_ago)
        User.objects.filter(pk=recent.pk).update(last_login=timezone.now() - timedelta(days=10), date_joined=long_ago)
        User.objects.filter(pk=admin_user.pk).update(last_login=long_ago, is_staff=True)
        User.objects.filter(pk=never.pk).update(last_login=None, date_joined=long_ago)
        call_command('purge_inactive_users', dry_run=True)
        assert not User.objects.filter(is_deleted=True).exists()
        call_command('purge_inactive_users')
        deleted = set(User.objects.filter(is_deleted=True).values_list('pk', flat=True))
        assert deleted == {old.pk, never.pk}

    def test_토큰_교환과_갱신이_last_login을_남긴다(self, flaky):
        api = APIClient()
        r = api.post('/api/v1/auth/firebase/', {'id_token': 'x:uid-ll:+821099998888', 'birthdate': '1990-01-01'},
                     format='json')
        user = User.objects.get(firebase_uid='uid-ll')
        assert user.last_login is not None
        User.objects.filter(pk=user.pk).update(last_login=timezone.now() - timedelta(days=400))
        r2 = api.post('/api/v1/auth/token/refresh/', {'refresh': r.data['refresh_token']}, format='json')
        assert r2.status_code == 200
        user.refresh_from_db()
        assert timezone.now() - user.last_login < timedelta(minutes=1)
