"""동의 문서 v1 적재(data/documents.json). 이후 변경은 manage.py sync_reference_data"""
from django.db import migrations


def seed(apps, schema_editor):
    from apps.consents.services import load_documents
    load_documents(apps.get_model('consents', 'ConsentDocument'))


class Migration(migrations.Migration):
    dependencies = [('consents', '0001_initial')]
    operations = [migrations.RunPython(seed, migrations.RunPython.noop)]
