"""
법적 검토 보완(구현_보완_요청.md) 회귀 테스트 — S-01 관리자 로그인 잠금, S-02 개인정보 접속기록
"""
from datetime import timedelta

import pytest
from django.core.management import call_command
from django.db import models
from django.test import Client, override_settings
from django.utils import timezone

from apps.core.models import AccessLog, AccessLogImmutable
from apps.users.models import User

LOGIN = '/admin/login/'


@pytest.fixture
def staff(db):
    user = User.objects.create_user(username='opsadmin', password='Correct-Horse-9', is_staff=True, is_superuser=True)
    return user


def _login(client, password, username='opsadmin', ip='203.0.113.7'):
    return client.post(LOGIN, {'username': username, 'password': password, 'next': '/admin/'},
                       HTTP_X_FORWARDED_FOR=ip)


@pytest.mark.django_db
class TestAdminLoginLockout:
    @override_settings(LOGIN_MAX_FAILURES=5, LOGIN_LOCKOUT_SECONDS=900)
    def test_5회_실패하면_맞는_비밀번호도_429(self, staff):
        c = Client()
        for _ in range(5):
            assert _login(c, 'wrong').status_code == 200  # 로그인 화면 다시(오류 표시)
        r = _login(c, 'Correct-Horse-9')
        assert r.status_code == 429
        assert AccessLog.objects.filter(action='LOGIN_LOCKED', actor='opsadmin').exists()
        assert AccessLog.objects.filter(action='LOGIN_FAILED').count() == 5

    @override_settings(LOGIN_MAX_FAILURES=5)
    def test_다른_IP에서도_같은_계정은_잠김(self, staff):
        for i in range(5):
            _login(Client(), 'wrong', ip=f'198.51.100.{i}')
        assert _login(Client(), 'Correct-Horse-9', ip='192.0.2.1').status_code == 429

    def test_성공하면_기록되고_카운터_초기화(self, staff):
        c = Client()
        _login(c, 'wrong')
        r = _login(c, 'Correct-Horse-9')
        assert r.status_code == 302
        log = AccessLog.objects.get(action='LOGIN')
        assert log.actor == 'opsadmin' and log.ip == '203.0.113.7' and log.actor_id == str(staff.pk)


@pytest.mark.django_db
class TestAccessLog:
    def test_관리자_화면_조회를_기록(self, staff):
        c = Client()
        c.force_login(staff)
        r = c.get('/admin/core/accesslog/', {'q': '01012345678'}, HTTP_X_FORWARDED_FOR='203.0.113.9')
        assert r.status_code == 200
        log = AccessLog.objects.filter(action='VIEW', target_type='core.accesslog').latest('at')
        assert log.actor == 'opsadmin' and log.ip == '203.0.113.9' and log.path == '/admin/core/accesslog/'
        # 검색어(개인정보일 수 있음)는 값 없이 키만
        assert log.detail == 'params=q' and '0101234' not in log.detail

    def test_관리자_첫_화면은_기록하지_않음(self, staff):
        c = Client()
        c.force_login(staff)
        c.get('/admin/')
        assert not AccessLog.objects.filter(action='VIEW').exists()

    def test_정보주체_ID를_남긴다(self, staff):
        from apps.core.audit import _subject_for
        from apps.diet.models import FavoriteFood
        member = User.objects.create_user(username='member', password='x')
        assert _subject_for(User, [str(member.pk)]) == str(member.pk)
        assert _subject_for(FavoriteFood, ['999']) == ''

    def test_수정·삭제_불가(self, db):
        log = AccessLog.objects.create(action='VIEW', actor='a')
        with pytest.raises(AccessLogImmutable):
            log.save()
        with pytest.raises(AccessLogImmutable):
            log.delete()
        with pytest.raises(AccessLogImmutable):
            AccessLog.objects.all().delete()
        with pytest.raises(AccessLogImmutable):
            AccessLog.objects.update(actor='b')

    def test_감사_대상_명령은_기록(self, db, monkeypatch):
        monkeypatch.setenv('SEESUN_OPERATOR', 'kim-ops')
        call_command('purge_access_logs', dry_run=True)
        log = AccessLog.objects.get(action='COMMAND')
        assert log.actor == 'kim-ops' and log.path == 'manage.py purge_access_logs' and 'dry_run' in log.detail

    def test_2년_지난_기록만_파기(self, db):
        old = AccessLog.objects.create(action='VIEW', actor='old')
        new = AccessLog.objects.create(action='VIEW', actor='new')
        models.QuerySet.update(AccessLog.objects.filter(pk=old.pk), at=timezone.now() - timedelta(days=731))
        call_command('purge_access_logs')
        assert not AccessLog.objects.filter(pk=old.pk).exists()
        assert AccessLog.objects.filter(pk=new.pk).exists()

    @override_settings(ACCESS_LOG_RETENTION_DAYS=730)
    def test_보관_기간은_최소_2년(self):
        import importlib
        import os

        import config.settings as s
        os.environ['ACCESS_LOG_RETENTION_DAYS'] = '30'
        try:
            importlib.reload(s)
            assert s.ACCESS_LOG_RETENTION_DAYS == 730
        finally:
            os.environ.pop('ACCESS_LOG_RETENTION_DAYS')
            importlib.reload(s)
