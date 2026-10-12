"""
식약처 음식 DB 적재·음식 API 테스트

샘플: apps/nutrition/fixtures/mfds_food_sample.csv — 공공데이터포털
「전국통합식품영양성분정보(음식)표준데이터」에서 고른 실제 23행(헤더 원본 그대로)
"""
import csv
import io
import json

import pytest
from django.core.management import CommandError, call_command

from apps.core.testing import SAMPLE_CSV, client_for, food, load_sample_foods, make_user
from apps.nutrition import importers
from apps.nutrition.models import Food, FoodAllergen


def _sample_rows():
    with open(SAMPLE_CSV, encoding='utf-8-sig', newline='') as fh:
        reader = csv.DictReader(fh)
        return reader.fieldnames, list(reader)


@pytest.mark.django_db
class TestImport:
    def test_샘플_CSV_적재와_멱등(self):
        out = io.StringIO()
        call_command('import_mfds_foods', str(SAMPLE_CSV), stdout=out)
        assert '신규 23건' in out.getvalue()
        call_command('import_mfds_foods', str(SAMPLE_CSV), stdout=out)
        assert '갱신 23건' in out.getvalue()
        assert Food.objects.count() == 23

    def test_1회_제공량_환산과_음료_판정(self):
        load_sample_foods()
        tteok = Food.objects.get(source_code='D110-467000000-0001')
        assert tteok.name == '떡볶이' and tteok.basis_unit == 'g' and float(tteok.serving_amount) == 180
        assert tteok.per_serving()['kcal'] == pytest.approx(144 * 1.8)
        assert tteok.carb_ratio is not None and tteok.sugar_index_grade is not None
        drink = Food.objects.get(source_code='D220-738000000-0004')
        assert drink.is_beverage and drink.maker == '이디야' and drink.basis_unit == 'ml'
        assert drink.carb_g is None and drink.sugar_index_grade is None  # 탄수화물 값 없음 → 지표 산출 안 함

    def test_알레르기_키워드_태그(self):
        load_sample_foods()
        shrimp_rice = Food.objects.get(name='볶음밥_새우')
        codes = set(FoodAllergen.objects.filter(food=shrimp_rice).values_list('allergen__code', flat=True))
        assert 'SHRIMP' in codes
        assert FoodAllergen.objects.filter(food__name='현미밥').count() == 0

    def test_CP949_CSV(self, tmp_path):
        headers, rows = _sample_rows()
        path = tmp_path / 'mfds_cp949.csv'
        with open(path, 'w', encoding='cp949', newline='', errors='replace') as fh:
            writer = csv.DictWriter(fh, fieldnames=headers)
            writer.writeheader()
            writer.writerows(rows[:3])
        result = importers.import_file(path)
        assert result.created == 3

    def test_XLSX(self, tmp_path):
        from openpyxl import Workbook
        headers, rows = _sample_rows()
        wb = Workbook()
        ws = wb.active
        ws.append(headers)
        for row in rows[:5]:
            ws.append([row[h] for h in headers])
        path = tmp_path / 'mfds.xlsx'
        wb.save(path)
        assert importers.import_file(path).created == 5

    def test_포털_JSON_영문_코드_헤더(self, tmp_path):
        data = [{'FOOD_CD': 'D999-1', 'FOOD_NM': '테스트밥', 'NUT_CON_SRTR_QUA': '100g', 'ENERC': '150',
                 'CHOCDF': '33', 'SUGAR': '0.1', 'FIBTG': '', 'PROT': '3', 'FATCE': '0.5', 'NAT': '2',
                 'FOOD_SIZE': '210g', 'REST_NM': '해당없음', 'FOOD_LV3_NM': '밥류', 'CRTR_YMD': '2026-09-29'}]
        path = tmp_path / 'mfds.json'
        path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        assert importers.import_file(path).created == 1
        f = Food.objects.get(source_code='D999-1')
        assert f.maker == '' and f.fiber_g is None and str(f.data_date) == '2026-09-29'

    def test_필수_열이_없으면_오류(self, tmp_path):
        path = tmp_path / 'bad.csv'
        path.write_text('이름,열량\n밥,100\n', encoding='utf-8')
        with pytest.raises(CommandError):
            call_command('import_mfds_foods', str(path))

    def test_dry_run은_저장하지_않음(self):
        call_command('import_mfds_foods', str(SAMPLE_CSV), '--dry-run', stdout=io.StringIO())
        assert Food.objects.count() == 0

    def test_수치_파싱(self):
        assert importers.parse_amount('235.40ml') == (importers.Decimal('235.40'), 'ml')
        assert importers.parse_amount('해당없음') == (None, '')
        assert importers.parse_decimal('1,234.5') == importers.Decimal('1234.5')
        assert importers.parse_decimal('-') is None


@pytest.mark.django_db
class TestFoodAPI:
    def test_검색은_정확일치·일반메뉴_먼저(self):
        load_sample_foods()
        api = client_for(make_user())
        r = api.get('/api/v1/foods/search/', {'q': '떡볶이'})
        assert r.status_code == 200 and r.data['count'] >= 1
        assert r.data['results'][0]['name'] == '떡볶이'
        assert r.data['results'][0]['per_serving']['kcal'] == pytest.approx(259.2)
        assert api.get('/api/v1/foods/search/', {'q': '볶음밥'}).data['results'][0]['name'] == '볶음밥_새우'
        assert api.get('/api/v1/foods/search/', {'q': ''}).status_code == 400
        assert api.get('/api/v1/foods/search/', {'q': '없는음식이름'}).data['count'] == 0

    def test_상세는_사용자_알레르기_상태와_태그(self):
        load_sample_foods()
        user = make_user(sensitive=True, allergens=['SHRIMP', 'EGG'])
        f = food('볶음밥_새우')
        data = client_for(user).get(f'/api/v1/foods/{f.id}/').data
        statuses = {a['code']: a['status'] for a in data['allergens']}
        assert statuses['SHRIMP'] == 'CONFIRMED' and statuses['EGG'] == 'NONE'
        assert data['sugar_index']['computable'] is True
        assert data['per_basis']['kcal'] == float(f.kcal)
        assert data['is_favorite'] is False

    def test_바코드(self):
        load_sample_foods()
        f = food('현미밥')
        f.barcode = '8801234567890'
        f.save()
        api = client_for(make_user())
        assert api.get('/api/v1/foods/barcode/8801234567890/').data['id'] == f.id
        r = api.get('/api/v1/foods/barcode/0000/')
        assert r.status_code == 404 and r.data['error'] == 'FOOD_NOT_FOUND'
        assert api.get('/api/v1/foods/999999/').status_code == 404
