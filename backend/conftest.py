import pytest
from django.core.cache import cache


@pytest.fixture(autouse=True)
def _clear_cache():
    """요청 제한·로그인 잠금 카운터가 테스트 간에 이어지지 않도록 초기화"""
    cache.clear()
    yield
    cache.clear()
