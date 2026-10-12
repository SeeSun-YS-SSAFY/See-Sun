"""
See:Sun 산식 모듈 (02 문서 NFR-07, DSCR-06 — "목표 칼로리·식단 점수·당류 지표 계산은 별도 모듈")

- Django에 의존하지 않는 순수 함수만 둔다(앱의 ``core:domain`` 모듈과 같은 명세).
- 기준값은 ``tables_v1.py`` 데이터에만 있고, ``get_tables()`` 로 교체할 수 있다
  (환경변수 ``SEESUN_RULES_TABLES=seesun_rules.tables_v2`` 등).
- 테스트 벡터: ``seesun_rules/vectors/*.json`` (Android 단위 테스트에서도 같은 파일을 쓴다).
"""
import importlib
import os
from functools import lru_cache

DEFAULT_TABLES = 'seesun_rules.tables_v1'


@lru_cache(maxsize=4)
def _load(module_path: str):
    return importlib.import_module(module_path)


def get_tables(module_path: str | None = None):
    return _load(module_path or os.environ.get('SEESUN_RULES_TABLES', DEFAULT_TABLES))
