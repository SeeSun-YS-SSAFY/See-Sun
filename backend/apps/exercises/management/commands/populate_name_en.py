"""
Gemini API를 사용하여 Exercise.name_en 필드를 자동으로 채우는 관리 명령어.

사용법:
    python manage.py populate_name_en
"""
from django.core.management.base import BaseCommand
from apps.exercises.models import Exercise
from django.conf import settings
import os
import time

class Command(BaseCommand):
    help = 'Gemini API를 사용하여 Exercise.name_en 필드를 자동으로 채웁니다.'

    # 기존 매핑 (Gemini API 호출 전 fallback)
    NAME_MAPPING = {
        "맨몸 스쿼트": "bodyweight_squat",
        "플랭크": "plank",
        "밴드 풀 어파트": "band_pull_apart",
        "푸쉬업": "push_up",
        "런지": "lunge",
        "힙 브릿지": "hip_bridge",
        "버피테스트": "burpee_test",
        "레그레이즈": "leg_raise",
        "조깅": "jogging",
        "스텝박스를 활용한 운동": "step_box",
        "요가 - 우르드바하스타사나": "urdhva_hastasana",
        "점핑잭": "jumping_jack",
        "암워킹": "arm_walking",
        "스탠스잭": "stance_jack",
        "마운틴 클라이머": "mountain_climber",
        "햄스트링 스트레칭": "hamstring_stretch",
        "나비자세": "butterfly_pose",
        "벽에 다리 올리기": "legs_up_wall",
        "고양이-소 자세": "cat_cow",
        "다리 스트레칭": "leg_stretch",
        "허리 비틀기": "waist_twist",
        "트렁크 트위스트 (Lying trunk twist)": "trunk_twist",
        "타이치 운동": "taichi",
        "명상 및 호흡 운동": "meditation_breathing",
        "버드 도그 홀드": "bird_dog_hold",
        "T 밸런스 자세": "t_balance",
        "체중 이동": "weight_shift",
        "하이 런지 자세": "high_lunge",
        "발끝 합장 자세": "feet_together",
    }

    def add_arguments(self, parser):
        parser.add_argument(
            '--use-gemini',
            action='store_true',
            help='Gemini API를 사용하여 매핑에 없는 운동명도 번역 (API 키 필요)'
        )

    def handle(self, *args, **options):
        use_gemini = options['use_gemini']
        
        if use_gemini:
            try:
                import google.generativeai as genai
            except ImportError:
                self.stdout.write(self.style.ERROR('google-generativeai 모듈이 설치되지 않았습니다. pip install google-generativeai'))
                return
            
            api_key = os.environ.get('GOOGLE_API_KEY') or os.environ.get('GEMINI_API_KEY') or getattr(settings, 'GOOGLE_API_KEY', None)
            if not api_key:
                self.stdout.write(self.style.ERROR('GOOGLE_API_KEY 또는 GEMINI_API_KEY가 설정되지 않았습니다.'))
                return
            genai.configure(api_key=api_key)
            model = genai.GenerativeModel('gemini-2.0-flash')
        else:
            model = None
        
        exercises = Exercise.objects.filter(name_en='')
        self.stdout.write(f"name_en이 비어있는 운동: {exercises.count()}개")
        
        updated = 0
        for exercise in exercises:
            korean_name = exercise.exercise_name
            
            # 1. 기존 매핑에서 찾기
            if korean_name in self.NAME_MAPPING:
                exercise.name_en = self.NAME_MAPPING[korean_name]
                exercise.save(update_fields=['name_en'])
                self.stdout.write(f"  [매핑] {korean_name} -> {exercise.name_en}")
                updated += 1
                continue
            
            # 2. Gemini API 사용 (옵션 활성화 시)
            if use_gemini:
                try:
                    prompt = f"""
다음 한글 운동 이름을 영문 스네이크케이스로 변환해주세요.
규칙:
- 소문자만 사용
- 단어는 밑줄(_)로 구분
- 간결하게 (최대 30자)
- 괄호 내용은 제외

입력: {korean_name}
출력 (영문 스네이크케이스만):"""
                    
                    response = model.generate_content(prompt)
                    english_name = response.text.strip().lower().replace(' ', '_')
                    english_name = ''.join(c for c in english_name if c.isalnum() or c == '_')
                    
                    exercise.name_en = english_name
                    exercise.save(update_fields=['name_en'])
                    self.stdout.write(f"  [Gemini] {korean_name} -> {exercise.name_en}")
                    updated += 1
                    
                    time.sleep(0.5)  # Rate limit 방지
                    
                except Exception as e:
                    self.stdout.write(self.style.ERROR(f"  [에러] {korean_name}: {e}"))
            else:
                self.stdout.write(self.style.WARNING(f"  [스킵] {korean_name} - 매핑 없음"))
        
        self.stdout.write(self.style.SUCCESS(f"\n완료! {updated}개 운동 업데이트됨."))
