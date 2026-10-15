"""국외이전 고지 v2(2026-10-12, 고지 확인으로 전환·법정 항목 본문) 적재. 법적 검토 C-01·C-02."""
from django.db import migrations


def load(apps, schema_editor):
    from apps.consents.services import load_documents
    load_documents(apps.get_model('consents', 'ConsentDocument'))


class Migration(migrations.Migration):
    dependencies = [('consents', '0003_notice_kind_and_audit')]
    operations = [migrations.RunPython(load, migrations.RunPython.noop)]
