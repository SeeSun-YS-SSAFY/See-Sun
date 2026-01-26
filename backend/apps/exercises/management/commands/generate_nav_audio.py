"""
동적 네비게이션 TTS 오디오 생성 명령어

사용법:
    python manage.py generate_nav_audio [--use-gemini]

저장 위치:
    media/prefix/navigation/category/
    media/prefix/navigation/exercise/
    media/prefix/navigation/routine/
"""
from django.core.management.base import BaseCommand
from django.conf import settings
from apps.exercises.models import Exercise, ExerciseCategory, Playlist
from apps.exercises.google_tts import GoogleTTSClient
import os
import re
import time


class Command(BaseCommand):
    help = '동적 네비게이션 TTS 오디오 생성 (카테고리, 운동, 루틴)'

    # 카테고리 영문 매핑 (DB display_name -> 영문 파일명)
    CATEGORY_NAME_MAPPING = {
        '근력 운동': 'strength',
        '유산소 운동': 'aerobic',
        '유연성 운동': 'flexibility',
        '균형 운동': 'balance',
        # 띄어쓰기 없는 버전도 대비
        '근력운동': 'strength',
        '유산소운동': 'aerobic',
        '유연성운동': 'flexibility',
        '균형운동': 'balance',
    }

    def add_arguments(self, parser):
        parser.add_argument(
            '--use-gemini',
            action='store_true',
            help='Gemini API를 사용하여 파일명 생성 (API 키 필요)'
        )
        parser.add_argument(
            '--category-only',
            action='store_true',
            help='카테고리 오디오만 생성'
        )
        parser.add_argument(
            '--exercise-only',
            action='store_true',
            help='운동 오디오만 생성'
        )
        parser.add_argument(
            '--routine-only',
            action='store_true',
            help='루틴 오디오만 생성'
        )

    def handle(self, *args, **options):
        use_gemini = options['use_gemini']
        
        # Gemini API 설정
        if use_gemini:
            try:
                import google.generativeai as genai
                api_key = os.environ.get('GOOGLE_API_KEY') or os.environ.get('GEMINI_API_KEY') or getattr(settings, 'GOOGLE_API_KEY', None)
                if not api_key:
                    self.stdout.write(self.style.ERROR('GOOGLE_API_KEY 또는 GEMINI_API_KEY가 설정되지 않았습니다.'))
                    return
                genai.configure(api_key=api_key)
                self.gemini_model = genai.GenerativeModel('gemini-2.0-flash')
            except ImportError:
                self.stdout.write(self.style.ERROR('google-generativeai 모듈이 설치되지 않았습니다.'))
                return
        else:
            self.gemini_model = None

        # 기본 디렉토리 생성
        base_dir = settings.MEDIA_ROOT / 'prefix' / 'navigation'
        for sub in ['category', 'exercise', 'routine']:
            path = base_dir / sub
            if not os.path.exists(path):
                os.makedirs(path)

        # 선택적 실행
        run_all = not (options['category_only'] or options['exercise_only'] or options['routine_only'])
        
        if run_all or options['category_only']:
            self.generate_category_audio(base_dir / 'category')
        
        if run_all or options['exercise_only']:
            self.generate_exercise_audio(base_dir / 'exercise')
        
        if run_all or options['routine_only']:
            self.generate_routine_audio(base_dir / 'routine')

        self.stdout.write(self.style.SUCCESS('네비게이션 오디오 생성 완료!'))

    def get_english_filename(self, korean_text, fallback_mapping=None):
        """
        한글 텍스트를 영문 파일명으로 변환
        우선순위: 1. fallback_mapping -> 2. Gemini API -> 3. 기본 변환
        """
        # 1. 매핑에서 찾기
        if fallback_mapping and korean_text in fallback_mapping:
            return fallback_mapping[korean_text]
        
        # 2. Gemini API 사용
        if self.gemini_model:
            try:
                prompt = f"""
다음 한글 텍스트를 영문 스네이크케이스 파일명으로 변환해주세요.
규칙:
- 소문자만 사용
- 단어는 밑줄(_)로 구분
- 확장자 제외
- 간결하게 (최대 30자)

입력: {korean_text}
출력 (영문 스네이크케이스만):"""
                
                response = self.gemini_model.generate_content(prompt)
                result = response.text.strip().lower().replace(' ', '_')
                result = re.sub(r'[^a-z0-9_]', '', result)
                time.sleep(0.3)  # Rate limit 방지
                return result
            except Exception as e:
                self.stdout.write(self.style.WARNING(f"Gemini API 오류: {e}"))
        
        # 3. 기본 변환 (한글 제거 후 남은 것 사용)
        result = re.sub(r'[^a-zA-Z0-9]', '_', korean_text)
        return result.lower().strip('_') or 'unnamed'

    def generate_tts(self, text, filepath):
        """Google Cloud TTS로 오디오 생성 (여성 목소리: ko-KR-Neural2-A)"""
        try:
            tts_client = GoogleTTSClient()
            audio_content = tts_client.synthesize_text(
                text=text,
                language_code="ko-KR",
                name="ko-KR-Neural2-A",  # 여성 목소리
                speaking_rate=1.0
            )
            
            with open(filepath, 'wb') as f:
                f.write(audio_content)
            return True
        except Exception as e:
            self.stdout.write(self.style.ERROR(f"TTS 오류: {e}"))
            return False

    def generate_category_audio(self, output_dir):
        """카테고리 오디오 생성"""
        self.stdout.write("--- 카테고리 오디오 생성 ---")
        
        categories = ExerciseCategory.objects.all()
        count = 0
        
        for category in categories:
            display_name = category.display_name
            text = f"{display_name} 목록입니다"
            
            # 파일명 생성
            filename = self.get_english_filename(display_name, self.CATEGORY_NAME_MAPPING)
            filepath = output_dir / f"{filename}.mp3"
            
            if self.generate_tts(text, filepath):
                count += 1
                self.stdout.write(f"  [카테고리] {display_name} -> {filename}.mp3")
        
        self.stdout.write(self.style.SUCCESS(f"카테고리 오디오 {count}개 생성 완료"))

    def generate_exercise_audio(self, output_dir):
        """운동 오디오 생성"""
        self.stdout.write("--- 운동 오디오 생성 ---")
        
        exercises = Exercise.objects.filter(is_active=True)
        count = 0
        
        for exercise in exercises:
            exercise_name = exercise.exercise_name
            text = f"{exercise_name} 운동입니다"
            
            # 파일명: name_en 우선 사용
            if exercise.name_en:
                filename = exercise.name_en
            else:
                filename = self.get_english_filename(exercise_name)
            
            filepath = output_dir / f"{filename}.mp3"
            
            if self.generate_tts(text, filepath):
                count += 1
                self.stdout.write(f"  [운동] {exercise_name} -> {filename}.mp3")
        
        self.stdout.write(self.style.SUCCESS(f"운동 오디오 {count}개 생성 완료"))

    def generate_routine_audio(self, output_dir):
        """루틴 오디오 생성"""
        self.stdout.write("--- 루틴 오디오 생성 ---")
        
        playlists = Playlist.objects.filter(status='ACTIVE')
        count = 0
        
        for playlist in playlists:
            title = playlist.title
            text = f"{title} 화면입니다. 목록 중 운동을 선택해주세요"
            
            # 파일명 생성
            filename = self.get_english_filename(title)
            filepath = output_dir / f"{filename}.mp3"
            
            if self.generate_tts(text, filepath):
                count += 1
                self.stdout.write(f"  [루틴] {title} -> {filename}.mp3")
        
        self.stdout.write(self.style.SUCCESS(f"루틴 오디오 {count}개 생성 완료"))
