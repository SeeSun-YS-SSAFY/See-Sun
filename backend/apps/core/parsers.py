"""
JSON 파서 — 깊게 중첩된 본문을 400으로

DRF JSONParser는 ValueError만 ParseError(400)로 바꾼다. ``[[[[…]]]]`` 처럼 수천 단계 중첩된 본문은
json 모듈에서 RecursionError가 나 그대로 500이 됐다(배포 리허설 B-12, QA Q-HTTP-04).
"""
from rest_framework.exceptions import ParseError
from rest_framework.parsers import JSONParser


class SafeJSONParser(JSONParser):
    def parse(self, stream, media_type=None, parser_context=None):
        try:
            return super().parse(stream, media_type, parser_context)
        except RecursionError as exc:
            raise ParseError('JSON 중첩이 너무 깊습니다.') from exc
