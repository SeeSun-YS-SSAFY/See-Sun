"""
식약처 식품영양성분 DB(음식) 적재 명령

사용 예
    python manage.py import_mfds_foods 전국통합식품영양성분정보_음식_표준데이터.csv
    python manage.py import_mfds_foods data.xlsx --sheet Sheet1
    python manage.py import_mfds_foods --download --save-json /tmp/mfds_food.json   # 포털에서 직접 받기
    python manage.py import_mfds_foods apps/nutrition/fixtures/mfds_food_sample.csv  # 저장소 샘플(23건)

같은 파일을 여러 번 실행해도 결과가 같다(식품코드 기준 갱신).
"""
import json
from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from apps.nutrition import importers


class Command(BaseCommand):
    help = '식약처 식품영양성분 DB(음식) CSV/XLSX/JSON을 음식 테이블에 적재합니다.'

    def add_arguments(self, parser):
        parser.add_argument('path', nargs='?', help='CSV·XLSX·JSON 파일 경로')
        parser.add_argument('--download', action='store_true', help='공공데이터포털에서 직접 내려받아 적재(로그인 불필요)')
        parser.add_argument('--save-json', help='--download 결과를 이 경로에 저장')
        parser.add_argument('--encoding', help='CSV 인코딩(기본: utf-8-sig → cp949 순서로 시도)')
        parser.add_argument('--sheet', help='XLSX 시트 이름(기본: 첫 시트)')
        parser.add_argument('--limit', type=int, help='앞에서부터 N행만')
        parser.add_argument('--dry-run', action='store_true', help='파싱만 하고 저장하지 않음')
        parser.add_argument('--exclude-source', action='append', default=None,
                            help=f'출처명에 이 글자가 있는 행 제외(여러 번 가능, 기본 {importers.DEFAULT_EXCLUDED_SOURCES})')
        parser.add_argument('--include-all-sources', action='store_true',
                            help='출처 제외 없이 모두 적재(이용 조건을 확인한 경우만)')

    def handle(self, *args, **opts):
        if opts['download']:
            rows = importers.download_portal_rows()
            if opts['save_json']:
                Path(opts['save_json']).write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
            headers = list(rows[0].keys()) if rows else []
            runner = lambda **kw: importers.import_rows(headers, iter(rows), **kw)  # noqa: E731
        elif opts['path']:
            path = Path(opts['path'])
            if not path.exists():
                raise CommandError(f'파일이 없습니다: {path}')
            runner = lambda **kw: importers.import_file(path, encoding=opts['encoding'], sheet=opts['sheet'], **kw)  # noqa: E731
        else:
            raise CommandError('파일 경로 또는 --download 를 지정하세요.')
        try:
            excluded = () if opts['include_all_sources'] else (
                tuple(opts['exclude_source']) if opts['exclude_source'] else importers.DEFAULT_EXCLUDED_SOURCES)
            result = runner(limit=opts['limit'], dry_run=opts['dry_run'], exclude_sources=excluded)
        except importers.ImportFormatError as exc:
            raise CommandError(str(exc)) from exc
        prefix = '[dry-run] ' if opts['dry_run'] else ''
        self.stdout.write(self.style.SUCCESS(
            f'{prefix}신규 {result.created}건, 갱신 {result.updated}건, 건너뜀 {result.skipped}건, '
            f'출처 제외 {result.excluded}건, '
            f'알레르기 키워드 태그 {result.allergen_tags}건'))
