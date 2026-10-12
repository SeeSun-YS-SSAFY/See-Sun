"""알레르기 19종 적재(data/allergens.json). 이후 변경은 manage.py sync_reference_data"""
from django.db import migrations


def seed(apps, schema_editor):
    from apps.health.services import load_allergens
    load_allergens(apps.get_model('health', 'Allergen'))


class Migration(migrations.Migration):
    dependencies = [('health', '0001_initial')]
    operations = [migrations.RunPython(seed, migrations.RunPython.noop)]
