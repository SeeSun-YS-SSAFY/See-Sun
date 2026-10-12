"""알레르기 대체 식단 19종 적재(data/alternative_diets.json). 이후 변경은 manage.py sync_reference_data"""
from django.db import migrations


def seed(apps, schema_editor):
    from apps.nutrition.services import load_alternative_diets
    load_alternative_diets(apps.get_model('health', 'Allergen'), apps.get_model('nutrition', 'AlternativeDiet'))


class Migration(migrations.Migration):
    dependencies = [('nutrition', '0001_initial'), ('health', '0002_seed_allergens')]
    operations = [migrations.RunPython(seed, migrations.RunPython.noop)]
