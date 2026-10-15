"""법적 검토 D-13: 행별 출처명 저장, 이용 조건이 다른 원 출처(농진청) 기본 제외"""
import csv
import io

import pytest
from django.core.management import call_command

from apps.core.testing import SAMPLE_CSV, client_for, make_user
from apps.nutrition import importers
from apps.nutrition.models import Food


def _rows_with_rda():
    with open(SAMPLE_CSV, encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        headers = reader.fieldnames
    extra = dict(rows[0])
    extra.update({'식품코드': 'RDA-TEST-1', '식품명': '농진청시험음식', '출처명': '농촌진흥청(국가표준식품성분표)'})
    return headers, rows + [extra]


@pytest.mark.django_db
class TestFoodSourceName:
    def test_출처명을_저장하고_상세에_보여준다(self):
        call_command('import_mfds_foods', str(SAMPLE_CSV), stdout=io.StringIO())
        food = Food.objects.get(name='떡볶이')
        assert food.source_name == '식품의약품안전처'
        body = client_for(make_user()).get(f'/api/v1/foods/{food.id}/').json()
        assert body['source_name'] == '식품의약품안전처'

    def test_농진청_행은_기본_제외(self):
        headers, rows = _rows_with_rda()
        result = importers.import_rows(headers, iter(rows))
        assert result.excluded == 1 and not Food.objects.filter(source_code='RDA-TEST-1').exists()

    def test_명시하면_모두_적재(self):
        headers, rows = _rows_with_rda()
        result = importers.import_rows(headers, iter(rows), exclude_sources=())
        assert result.excluded == 0
        assert Food.objects.get(source_code='RDA-TEST-1').source_name.startswith('농촌진흥청')
