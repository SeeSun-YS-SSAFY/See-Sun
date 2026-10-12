"""
식약처 식품영양성분 DB(음식) 적재기 (09 문서 4-4)

원천: 공공데이터포털 「전국통합식품영양성분정보(음식)표준데이터」(publicDataPk=15100070, 약 2만 건)
- CSV/XLSX(한글 헤더) 또는 JSON(영문 코드 헤더, 포털 다운로드 API 형식) 모두 읽는다.
- 헤더는 공백을 지우고 비교하므로 '영양성분함량 기준량'과 '영양성분함량기준량'을 같은 열로 본다.
- 영양값은 기준량(100g/100ml) 그대로 저장하고, 1회 제공량은 '식품중량'(없으면 '1인(회)분량 참고량')을 쓴다.
- 같은 (출처, 식품코드)는 갱신한다(여러 번 실행해도 결과가 같다).
- 음식명·대표식품명 키워드로 알레르기 '포함 가능성'(basis=KEYWORD)을 태깅한다.

국가표준식품성분표(농진청, 공공누리 2유형)는 상업 이용 금지라 쓰지 않는다(05 문서 L-08).
"""
import csv
import io
import json
import re
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

from django.db import transaction

from seesun_rules import get_tables

# 내부 필드 → 원천 헤더 후보(한글 CSV 헤더, 포털 JSON 코드)
COLUMN_ALIASES = {
    'source_code': ['식품코드', 'FOOD_CD'],
    'name': ['식품명', 'FOOD_NM'],
    'origin': ['식품기원명', 'FOOD_ORIGIN_NM'],
    'category': ['식품대분류명', 'FOOD_LV3_NM'],
    'representative_name': ['대표식품명', 'FOOD_LV4_NM'],
    'basis': ['영양성분함량기준량', 'NUT_CON_SRTR_QUA'],
    'kcal': ['에너지(kcal)', 'ENERC'],
    'protein_g': ['단백질(g)', 'PROT'],
    'fat_g': ['지방(g)', 'FATCE'],
    'carb_g': ['탄수화물(g)', 'CHOCDF'],
    'sugar_g': ['당류(g)', 'SUGAR'],
    'fiber_g': ['식이섬유(g)', 'FIBTG'],
    'sodium_mg': ['나트륨(mg)', 'NAT'],
    'sat_fat_g': ['포화지방산(g)', 'FASAT'],
    'trans_fat_g': ['트랜스지방산(g)', 'FATRN'],
    'food_size': ['식품중량', 'FOOD_SIZE'],
    'serving_ref': ['1인(회)분량참고량', 'SERV_SIZE'],
    'maker': ['업체명', 'REST_NM'],
    'data_date': ['데이터기준일자', 'CRTR_YMD'],
}
REQUIRED = ('source_code', 'name', 'basis', 'kcal')
METRIC_FIELDS = ('carb_ratio', 'protein_ratio', 'fat_ratio', 'unsat_fat_share', 'serving_sodium_mg',
                 'sugar_index_score', 'sugar_index_grade')
NUMERIC = ('kcal', 'protein_g', 'fat_g', 'carb_g', 'sugar_g', 'fiber_g', 'sodium_mg', 'sat_fat_g', 'trans_fat_g')
EMPTY = {'', '-', '해당없음', 'N/A', 'null', 'None'}
AMOUNT_RE = re.compile(r'^\s*([\d.,]+)\s*([a-zA-Zㄱ-힣]*)\s*$')


class ImportFormatError(Exception):
    pass


def _norm_header(text: str) -> str:
    return re.sub(r'\s+', '', str(text or '')).replace('﻿', '')


def build_header_map(headers):
    """원천 헤더 목록 → {내부 필드: 원천 헤더}"""
    normalized = {_norm_header(h): h for h in headers}
    mapping = {}
    for key, aliases in COLUMN_ALIASES.items():
        for alias in aliases:
            if _norm_header(alias) in normalized:
                mapping[key] = normalized[_norm_header(alias)]
                break
    missing = [k for k in REQUIRED if k not in mapping]
    if missing:
        raise ImportFormatError(f'필수 열이 없습니다: {missing}. 찾은 헤더: {list(headers)[:12]}…')
    return mapping


def _clean(value):
    if value is None:
        return None
    text = str(value).strip()
    return None if text in EMPTY else text


def parse_decimal(value):
    text = _clean(value)
    if text is None:
        return None
    try:
        return Decimal(text.replace(',', ''))
    except InvalidOperation:
        return None


def parse_amount(value):
    """'100g' → (Decimal('100'), 'g'), '235.40ml' → (Decimal('235.40'), 'ml')"""
    text = _clean(value)
    if text is None:
        return None, ''
    m = AMOUNT_RE.match(text)
    if not m:
        return None, ''
    try:
        return Decimal(m.group(1).replace(',', '')), m.group(2).lower()
    except InvalidOperation:
        return None, ''


def parse_date(value):
    text = _clean(value)
    if not text:
        return None
    digits = re.sub(r'\D', '', text)
    if len(digits) != 8:
        return None
    try:
        return date(int(digits[:4]), int(digits[4:6]), int(digits[6:]))
    except ValueError:
        return None


def search_key(text: str) -> str:
    return re.sub(r'[\s_·,()\[\]]', '', text or '').lower()


def row_to_food_fields(row, header_map):
    get = lambda key: row.get(header_map[key]) if key in header_map else None  # noqa: E731
    basis_amount, basis_unit = parse_amount(get('basis'))
    serving_amount, serving_unit = parse_amount(get('food_size'))
    if serving_amount is None:
        serving_amount, serving_unit = parse_amount(get('serving_ref'))
    name = _clean(get('name'))
    code = _clean(get('source_code'))
    if not name or not code:
        return None
    category = _clean(get('category')) or ''
    rep = _clean(get('representative_name')) or ''
    fields = {
        'source_code': code,
        'name': name,
        'search_name': search_key(name),
        'representative_name': rep,
        'maker': _clean(get('maker')) or '',
        'category': category,
        'origin': _clean(get('origin')) or '',
        'is_beverage': category in get_tables().BEVERAGE_CATEGORIES,
        'basis_amount': basis_amount or Decimal('100'),
        'basis_unit': basis_unit or 'g',
        'serving_amount': serving_amount,
        'serving_unit': serving_unit,
        'data_date': parse_date(get('data_date')),
    }
    for key in NUMERIC:
        fields[key] = parse_decimal(get(key))
    return fields


def read_rows(path: Path, encoding: str | None = None, sheet: str | None = None):
    """파일 형식별로 dict 행을 돌려준다(헤더, 행 반복자)"""
    suffix = path.suffix.lower()
    if suffix == '.json':
        data = json.loads(path.read_text(encoding=encoding or 'utf-8'))
        if isinstance(data, dict):
            data = data.get('data') or data.get('items') or data.get('records') or []
        headers = list(data[0].keys()) if data else []
        return headers, iter(data)
    if suffix in ('.xlsx', '.xlsm'):
        from openpyxl import load_workbook
        wb = load_workbook(path, read_only=True, data_only=True)
        ws = wb[sheet] if sheet else wb.worksheets[0]
        rows = ws.iter_rows(values_only=True)
        headers = [str(h) if h is not None else '' for h in next(rows)]
        return headers, (dict(zip(headers, r)) for r in rows)
    raw = path.read_bytes()
    for enc in ([encoding] if encoding else ['utf-8-sig', 'cp949']):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise ImportFormatError('CSV 인코딩을 알 수 없습니다(--encoding 지정).')
    reader = csv.DictReader(io.StringIO(text))
    return reader.fieldnames or [], reader


@dataclass
class ImportResult:
    created: int = 0
    updated: int = 0
    skipped: int = 0
    allergen_tags: int = 0
    errors: list = field(default_factory=list)


def import_rows(headers, rows, *, source='MFDS', limit=None, dry_run=False, batch_size=1000) -> ImportResult:
    from apps.health.models import Allergen
    from apps.health.services import match_allergen_keywords

    from .models import AllergenBasis, Food, FoodAllergen

    header_map = build_header_map(headers)
    result = ImportResult()
    parsed = {}
    for index, row in enumerate(rows):
        if limit is not None and index >= limit:
            break
        fields = row_to_food_fields(row, header_map)
        if fields is None:
            result.skipped += 1
            continue
        parsed[fields['source_code']] = fields  # 같은 코드가 또 나오면 뒤 행 사용
    if dry_run:
        result.created = len(parsed)
        return result

    allergens = list(Allergen.objects.filter(is_active=True))
    allergen_by_code = {a.code: a for a in allergens}
    codes = list(parsed)
    with transaction.atomic():
        for start in range(0, len(codes), batch_size):
            chunk = codes[start:start + batch_size]
            existing = {f.source_code: f for f in Food.objects.filter(source=source, source_code__in=chunk)}
            to_create, to_update = [], []
            for code in chunk:
                fields = parsed[code]
                food = existing.get(code)
                if food is None:
                    food = Food(source=source, allergen_basis=AllergenBasis.KEYWORD, **fields)
                    to_create.append(food.apply_metrics())
                else:
                    for key, value in fields.items():
                        setattr(food, key, value)
                    to_update.append(food.apply_metrics())
            Food.objects.bulk_create(to_create)
            if to_update:
                Food.objects.bulk_update(
                    to_update, [k for k in parsed[chunk[0]] if k != 'source_code'] + list(METRIC_FIELDS), batch_size=200)
            result.created += len(to_create)
            result.updated += len(to_update)

            foods = Food.objects.filter(source=source, source_code__in=chunk)
            FoodAllergen.objects.filter(food__in=foods, basis='KEYWORD').delete()
            links = []
            for food in foods:
                for code_ in match_allergen_keywords(f'{food.name} {food.representative_name}', allergens):
                    links.append(FoodAllergen(food=food, allergen=allergen_by_code[code_],
                                              status='CONFIRMED', basis='KEYWORD'))
            FoodAllergen.objects.bulk_create(links, ignore_conflicts=True)
            result.allergen_tags += len(links)
    return result


def import_file(path, *, encoding=None, sheet=None, **kwargs) -> ImportResult:
    headers, rows = read_rows(Path(path), encoding=encoding, sheet=sheet)
    return import_rows(headers, rows, **kwargs)


# --- 공공데이터포털 다운로드(로그인 불필요, 포털 화면이 쓰는 JSON 경로) -------------------------
PORTAL_BASE = 'https://www.data.go.kr'
PORTAL_PK = '15100070'


def download_portal_rows(per_page=10000, timeout=180):  # pragma: no cover - 네트워크
    """포털 '다운로드' 버튼과 같은 JSON 경로로 전체 행을 받는다. 포털 화면이 바뀌면 동작하지 않을 수 있다."""
    import urllib.parse
    import urllib.request

    headers = {'X-Requested-With': 'XMLHttpRequest', 'User-Agent': 'Mozilla/5.0',
               'Referer': f'{PORTAL_BASE}/data/{PORTAL_PK}/standard.do'}

    def get_json(url):
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)

    meta = get_json(f'{PORTAL_BASE}/download/columList.json?pk={PORTAL_PK}&ext=json')
    table = meta['tableVO']
    total = int(meta['totalCount'])
    rows, page = [], 1
    while len(rows) < total:
        params = [('colNmList', c) for c in table['colNmList']] + [
            ('totalCount', total), ('svcTableNm', table['svcTableNm']), ('perPage', per_page), ('page', page)]
        chunk = get_json(f'{PORTAL_BASE}/download/standard.json?publicDataPk={PORTAL_PK}&'
                         + urllib.parse.urlencode(params))
        if not chunk:
            break
        rows.extend(chunk)
        page += 1
    return rows
