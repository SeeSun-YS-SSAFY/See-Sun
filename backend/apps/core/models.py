"""
개인정보취급자 접속기록(법적 검토 S-02, 안전성 확보조치 기준 제8조)

누가(actor), 언제(at), 어디서(ip), 어떤 정보주체(subject_id)의 무엇을(target_*, action) 했는지 남긴다.
- 대상: Django 관리자 화면(로그인·조회·추가·수정·삭제·일괄 작업), 감사 대상 관리 명령(shell·dbshell·
  dumpdata·키 교체·파기 명령 등). DB 직접 접속은 postgres 접속 로그(log_connections)로 보완한다(런북 §10).
- 추가 전용: 모델·쿼리셋에서 수정·삭제를 막고, PostgreSQL에서는 트리거가 UPDATE와 보관 기간(2년) 안의
  DELETE를 거부한다(마이그레이션 0002).
- 보관: 2년(ACCESS_LOG_RETENTION_DAYS, 최소 730일). 지난 행은 ``manage.py purge_access_logs`` (월 1회 cron).
- 행에는 정보주체 UUID만 남기고 이름·전화번호 같은 값은 넣지 않는다.
"""
from django.db import models


class AccessLogImmutable(Exception):
    """접속기록은 고치거나 지울 수 없다(보관 기간이 지난 행의 파기만 purge_expired로)"""


class AccessLogQuerySet(models.QuerySet):
    def update(self, **kwargs):
        raise AccessLogImmutable('접속기록은 수정할 수 없습니다.')

    def delete(self):
        raise AccessLogImmutable('접속기록은 삭제할 수 없습니다. 보관 기간이 지난 행은 purge_access_logs를 쓰세요.')

    def purge_expired(self, cutoff):
        """cutoff 이전 행만 파기(반환: 지운 건수)"""
        qs = self.filter(at__lt=cutoff)
        count, _ = models.QuerySet.delete(qs)
        return count


class AccessLog(models.Model):
    class Action(models.TextChoices):
        LOGIN = 'LOGIN', '로그인'
        LOGIN_FAILED = 'LOGIN_FAILED', '로그인 실패'
        LOGIN_LOCKED = 'LOGIN_LOCKED', '로그인 잠금'
        LOGOUT = 'LOGOUT', '로그아웃'
        VIEW = 'VIEW', '조회'
        ADD = 'ADD', '추가'
        CHANGE = 'CHANGE', '수정'
        DELETE = 'DELETE', '삭제'
        BULK_ACTION = 'BULK_ACTION', '일괄 작업'
        EXPORT = 'EXPORT', '내려받기'
        COMMAND = 'COMMAND', '관리 명령'

    at = models.DateTimeField(auto_now_add=True, db_index=True, verbose_name='일시')
    actor = models.CharField(max_length=150, verbose_name='취급자(계정)')
    actor_id = models.CharField(max_length=64, blank=True, verbose_name='취급자 ID')
    ip = models.GenericIPAddressField(null=True, blank=True, verbose_name='접속지(IP)')
    action = models.CharField(max_length=20, choices=Action.choices, verbose_name='수행 업무')
    target_type = models.CharField(max_length=100, blank=True, verbose_name='대상 종류')
    target_id = models.CharField(max_length=300, blank=True, verbose_name='대상 ID')
    subject_id = models.CharField(max_length=64, blank=True, db_index=True, verbose_name='정보주체 ID')
    path = models.CharField(max_length=300, blank=True, verbose_name='경로·명령')
    status = models.PositiveSmallIntegerField(null=True, blank=True, verbose_name='응답 코드')
    detail = models.CharField(max_length=300, blank=True, verbose_name='비고')

    objects = AccessLogQuerySet.as_manager()

    class Meta:
        db_table = 'access_logs'
        ordering = ['-at', '-id']
        verbose_name = '개인정보 접속기록'
        verbose_name_plural = '개인정보 접속기록'
        default_permissions = ('view',)

    def save(self, *args, **kwargs):
        if not self._state.adding:
            raise AccessLogImmutable('접속기록은 수정할 수 없습니다.')
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise AccessLogImmutable('접속기록은 삭제할 수 없습니다.')

    def __str__(self):
        return f'{self.at:%Y-%m-%d %H:%M} {self.actor} {self.action} {self.target_type}'
