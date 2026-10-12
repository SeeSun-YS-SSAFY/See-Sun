"""
기준 데이터 동기화: 동의 문서·알레르기 19종·대체 식단을 저장소의 JSON 파일 내용으로 갱신한다.
(첫 적재는 마이그레이션이 하고, JSON을 고친 뒤에는 이 명령을 실행한다. 여러 번 실행해도 같다.)

    python manage.py sync_reference_data
"""
from django.core.management.base import BaseCommand


class Command(BaseCommand):
    help = '동의 문서·알레르기 마스터·대체 식단을 data/*.json 기준으로 갱신합니다.'

    def handle(self, *args, **options):
        from apps.consents.services import load_documents
        from apps.health.services import load_allergens
        from apps.nutrition.services import load_alternative_diets

        load_documents()
        load_allergens()
        load_alternative_diets()
        self.stdout.write(self.style.SUCCESS('기준 데이터를 갱신했습니다.'))
