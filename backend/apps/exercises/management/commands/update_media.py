from django.core.management.base import BaseCommand
from apps.exercises.models import Exercise, ExerciseMedia
from django.conf import settings
import os
import shutil
from gtts import gTTS
import uuid

class Command(BaseCommand):
    help = 'Update media: migrate pictograms and generate TTS audio'

    NAME_MAPPING = {
        # 근력
        "맨몸 스쿼트": "bodyweight_squat",
        "플랭크": "plank",
        "밴드 풀 어파트": "band_pull_apart",
        "푸쉬업": "push_up",
        "런지": "lunge",
        "힙 브릿지": "hip_bridge",
        "버피테스트": "burpee_test",
        "레그레이즈": "leg_raise",
        # 유산소
        "조깅": "jogging",
        "스텝박스를 활용한 운동": "step_box",
        "요가 - 우르드바하스타사나": "urdhva_hastasana",
        "점핑잭": "jumping_jack",
        "암워킹": "arm_walking",
        "스탠스잭": "stance_jack",
        "마운틴 클라이머": "mountain_climber",
        # 유연성
        "햄스트링 스트레칭": "hamstring_stretch",
        "나비자세": "butterfly_pose",
        "벽에 다리 올리기": "legs_up_wall",
        "고양이-소 자세": "cat_cow",
        "다리 스트레칭": "leg_stretch",
        "허리 비틀기": "waist_twist",
        "트렁크 트위스트 (Lying trunk twist)": "trunk_twist",
        # 균형
        "타이치 운동": "taichi",
        "명상 및 호흡 운동": "meditation_breathing",
        "버드 도그 홀드": "bird_dog_hold",
        "T 밸런스 자세": "t_balance",
        "체중 이동": "weight_shift",
        "하이 런지 자세": "high_lunge",
        "발끝 합장 자세": "feet_together",
    }
    
    CATEGORY_FOLDER_MAP = {
        '1': 'strength',
        '2': 'aerobic',
        '3': 'flexibilty',
        '4': 'balance'
    }

    def handle(self, *args, **options):
        self.update_pictograms()
        self.generate_tts()

    def get_english_name(self, exercise_name):
        # 1. Direct match
        if exercise_name in self.NAME_MAPPING:
            return self.NAME_MAPPING[exercise_name]
        
        # 2. Substring match
        for kor, eng in self.NAME_MAPPING.items():
            if kor.split(' (')[0] in exercise_name:
                return eng
        
        return None

    def update_pictograms(self):
        self.stdout.write("--- Updating Pictograms ---")
        
        exercises = Exercise.objects.all()
        source_root = settings.BASE_DIR / 'apps' / 'exercises' / 'pictogram'
        dest_root = settings.MEDIA_ROOT / 'exercises' / 'pictograms'
        
        if not os.path.exists(dest_root):
            os.makedirs(dest_root)

        count = 0 
        for exercise in exercises:
            ExerciseMedia.objects.filter(exercise=exercise, media_type='PICTOGRAM').delete()

            target_keyword = self.get_english_name(exercise.exercise_name)
            
            if not target_keyword:
                self.stdout.write(self.style.WARNING(f"Skipping Pictogram: No mapping for {exercise.exercise_name}"))
                continue

            folder_name = self.CATEGORY_FOLDER_MAP.get(str(exercise.category.category_id))
            if not folder_name: continue

            source_dir = source_root / folder_name
            if not os.path.exists(source_dir): continue

            matched_files = []
            for f in sorted(os.listdir(source_dir)):
                if target_keyword in f and (f.endswith('.jpg') or f.endswith('.png')):
                    matched_files.append(f)

            for filename in matched_files:
                cat_dest_dir = dest_root / folder_name
                if not os.path.exists(cat_dest_dir):
                    os.makedirs(cat_dest_dir)
                
                shutil.copy2(source_dir / filename, cat_dest_dir / filename)
                
                media_url = f"{settings.MEDIA_URL}exercises/pictograms/{folder_name}/{filename}"
                
                ExerciseMedia.objects.create(
                    exercise=exercise,
                    media_type='PICTOGRAM',
                    locale='ko-KR',
                    url=media_url
                )
            count += 1
            if matched_files:
                self.stdout.write(f"  [{exercise.exercise_name}] Linked {len(matched_files)} pictograms.")
        
        self.stdout.write(self.style.SUCCESS(f"Pictogram Update Complete. Processed {count} exercises."))


    def generate_tts(self):
        self.stdout.write("--- Generating TTS Audio ---")
        
        exercises = Exercise.objects.all()
        tts_root = settings.MEDIA_ROOT / 'exercises' / 'audio'

        TARGET_FIELDS = [
            'exercise_description',
            'first_description', 
            'main_form', 
            'form_description',
            'stay_form', 
            'fixed_form', 
            'exercise_guide_text' # User called it 'exercise_guide'
        ]

        count = 0
        for exercise in exercises:
            ExerciseMedia.objects.filter(exercise=exercise, media_type='GUIDE_AUDIO').delete()
            
            # Determine folder name: Use English Name
            eng_name = self.get_english_name(exercise.exercise_name)
            if not eng_name:
                self.stdout.write(self.style.WARNING(f"Skipping TTS: No English mapping for {exercise.exercise_name}"))
                continue
                
            ex_audio_dir = tts_root / eng_name
            if not os.path.exists(ex_audio_dir):
                os.makedirs(ex_audio_dir)

            current_ex_generated = 0
            
            for field in TARGET_FIELDS:
                text = getattr(exercise, field)
                if not text or text.lower() == 'x' or text.strip() == '':
                    continue

                try:
                    filename = f"{field}.mp3"
                    file_path = ex_audio_dir / filename
                    
                    tts = gTTS(text=text, lang='ko')
                    tts.save(str(file_path))
                    
                    # URL: /media/exercises/audio/{english_name}/{field}.mp3
                    media_url = f"{settings.MEDIA_URL}exercises/audio/{eng_name}/{filename}"
                    
                    ExerciseMedia.objects.create(
                        exercise=exercise,
                        media_type='GUIDE_AUDIO',
                        locale='ko-KR',
                        url=media_url,
                        s3_key=field
                    )
                    current_ex_generated += 1
                except Exception as e:
                    self.stdout.write(self.style.ERROR(f"TTS Error for {exercise.exercise_name} ({field}): {e}"))

            if current_ex_generated > 0:
                count += 1
                self.stdout.write(f"  [{exercise.exercise_name}] Generated {current_ex_generated} audio files.")

        self.stdout.write(self.style.SUCCESS(f"TTS Generation Complete. Processed {count} exercises."))
