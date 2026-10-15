"""
접속기록 위·변조 방지(법적 검토 S-02): PostgreSQL 트리거가 UPDATE와 보관 기간(2년) 안의 DELETE를 거부한다.
(SQLite 테스트 DB에서는 건너뛴다. 모델·쿼리셋도 수정·삭제를 막는다)
"""
from django.db import migrations

FORWARD = """
CREATE OR REPLACE FUNCTION access_logs_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'access_logs is append-only';
    END IF;
    IF TG_OP = 'DELETE' AND OLD.at > now() - interval '730 days' THEN
        RAISE EXCEPTION 'access_logs rows must be kept for 2 years';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS access_logs_guard ON access_logs;
CREATE TRIGGER access_logs_guard BEFORE UPDATE OR DELETE ON access_logs
    FOR EACH ROW EXECUTE FUNCTION access_logs_guard();
"""
BACKWARD = """
DROP TRIGGER IF EXISTS access_logs_guard ON access_logs;
DROP FUNCTION IF EXISTS access_logs_guard();
"""


def forward(apps, schema_editor):
    if schema_editor.connection.vendor == 'postgresql':
        schema_editor.execute(FORWARD)


def backward(apps, schema_editor):
    if schema_editor.connection.vendor == 'postgresql':
        schema_editor.execute(BACKWARD)


class Migration(migrations.Migration):
    dependencies = [('core', '0001_access_log')]
    operations = [migrations.RunPython(forward, backward)]
