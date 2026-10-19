"""
계획 v8 ①: 배포(deploy.sh)가 식약처 음식 DB를 적재하고 기대 개수와 같은지 검증한다.

- 번들은 원본 JSON을 gzip(seed/mfds_food_*.json.gz)으로 보내고, 기대 개수는 seed/EXPECTED_FOOD_COUNT.txt에 적는다.
- `--print-count`: 적재하면 생길 음식 수(농진청 행 제외·같은 식품코드는 하나)만 출력 — 번들 만들 때 기대 개수를 정한다.
- `--expect-count N`: 적재 뒤 식약처 음식 수가 N이 아니면 실패(종료 코드 1).
- `--verify-only --expect-count N`: 적재 없이 개수만 검사(deploy.sh가 다시 실행될 때).
"""
import csv
import gzip
import io
import json
from pathlib import Path

import pytest
from django.core.management import CommandError, call_command

from apps.core.testing import SAMPLE_CSV
from apps.nutrition import importers
from apps.nutrition.models import Food

REPO = Path(__file__).resolve().parents[4]
DEPLOY_SH = REPO / 'deploy' / 'provisional' / 'deploy.sh'
MAKE_BUNDLE_SH = REPO / 'deploy' / 'provisional' / 'make_bundle.sh'


def _sample_rows():
    with open(SAMPLE_CSV, encoding='utf-8-sig', newline='') as fh:
        reader = csv.DictReader(fh)
        return reader.fieldnames, list(reader)


def _portal_json_gz(tmp_path, *, extra_rows=()):
    """포털 JSON 형식(영문 코드 헤더) 3행 + 농진청 1행 + 같은 코드 중복 1행 → gzip."""
    rows = [
        {'FOOD_CD': f'D900-{i}', 'FOOD_NM': name, 'NUT_CON_SRTR_QUA': '100g', 'ENERC': '150', 'CHOCDF': '30',
         'SUGAR': '2', 'PROT': '5', 'FATCE': '3', 'NAT': '300', 'FOOD_SIZE': '200g', 'FOOD_LV3_NM': '밥류',
         'SRC_NM': '식품의약품안전처', 'CRTR_YMD': '2026-09-29'}
        for i, name in enumerate(['시험비빔밥', '시험김치찌개', '시험떡볶이'])
    ]
    rows.append(dict(rows[0], FOOD_CD='RDA-1', FOOD_NM='농진청시험', SRC_NM='농촌진흥청(국가표준식품성분표)'))
    rows.append(dict(rows[1], FOOD_NM='시험김치찌개(갱신)'))  # 같은 식품코드 → 하나로
    rows.extend(extra_rows)
    path = tmp_path / 'mfds_food_20261017.json.gz'
    path.write_bytes(gzip.compress(json.dumps(rows, ensure_ascii=False).encode('utf-8')))
    return path


@pytest.mark.django_db
class TestExpectedFoodCount:
    def test_gzip_JSON을_읽는다(self, tmp_path):
        path = _portal_json_gz(tmp_path)
        result = importers.import_file(path)
        assert (result.created, result.excluded) == (3, 1)
        assert Food.objects.get(source_code='D900-1').name == '시험김치찌개(갱신)'

    def test_print_count는_저장하지_않고_기대_개수만_출력(self, tmp_path):
        out = io.StringIO()
        call_command('import_mfds_foods', str(_portal_json_gz(tmp_path)), '--print-count', stdout=out)
        assert out.getvalue().strip() == '3'
        assert Food.objects.count() == 0

    def test_expect_count가_맞으면_통과하고_다시_실행해도_같다(self, tmp_path):
        path = _portal_json_gz(tmp_path)
        out = io.StringIO()
        call_command('import_mfds_foods', str(path), '--expect-count', '3', stdout=out)
        call_command('import_mfds_foods', str(path), '--expect-count', '3', stdout=out)
        assert '식약처 음식 3건 = 기대값' in out.getvalue()
        assert importers.stored_count() == 3

    def test_expect_count가_다르면_실패(self, tmp_path):
        with pytest.raises(CommandError, match='기대 4건, 실제 3건'):
            call_command('import_mfds_foods', str(_portal_json_gz(tmp_path)), '--expect-count', '4', stdout=io.StringIO())

    def test_verify_only는_적재하지_않고_개수만_본다(self, tmp_path):
        with pytest.raises(CommandError, match='실제 0건'):
            call_command('import_mfds_foods', '--verify-only', '--expect-count', '3', stdout=io.StringIO())
        call_command('import_mfds_foods', str(SAMPLE_CSV), stdout=io.StringIO())
        out = io.StringIO()
        call_command('import_mfds_foods', '--verify-only', '--expect-count', '23', stdout=out)
        assert '23건 = 기대값' in out.getvalue()
        with pytest.raises(CommandError):
            call_command('import_mfds_foods', '--verify-only')  # 기대 개수 없이는 쓰지 않는다

    def test_샘플_CSV_기대_개수는_23(self):
        out = io.StringIO()
        call_command('import_mfds_foods', str(SAMPLE_CSV), '--print-count', stdout=out)
        assert out.getvalue().strip() == '23'

    def test_사용자_등록_음식은_세지_않는다(self, tmp_path):
        call_command('import_mfds_foods', str(_portal_json_gz(tmp_path)), stdout=io.StringIO())
        Food.objects.create(source='USER', source_code='u-1', name='내 음식', search_name='내음식',
                            basis_amount=100, basis_unit='g', kcal=100)
        assert importers.stored_count() == 3


class TestDeployScript:
    """배포 스크립트가 음식 DB 단계를 갖고 있다(서버에서 돌리는 것은 코디네이터 — 여기서는 내용만 확인)."""

    def test_deploy_sh가_음식_DB를_적재하고_기대_개수를_검증한다(self):
        text = DEPLOY_SH.read_text(encoding='utf-8')
        assert 'seed/mfds_food_*.json.gz' in text and 'seed/EXPECTED_FOOD_COUNT.txt' in text
        assert 'import_mfds_foods /tmp/mfds_food.json.gz --expect-count "$FOOD_EXPECT"' in text
        assert 'import_mfds_foods --verify-only --expect-count "$FOOD_EXPECT"' in text
        # 콘텐츠 시드 뒤, 콘텐츠 검증 앞
        assert text.index('# ---- 4. 미디어') < text.index('# ---- 4b. 식약처 음식 DB') < text.index('# ---- 5. 검증')

    def test_번들_스크립트가_기대_개수를_적는다(self):
        text = MAKE_BUNDLE_SH.read_text(encoding='utf-8')
        assert '--print-count' in text and 'EXPECTED_FOOD_COUNT.txt' in text
