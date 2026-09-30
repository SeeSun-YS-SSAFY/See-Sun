from django.core.management.base import BaseCommand
from apps.exercises.models import Exercise, ExerciseMedia
from django.conf import settings
import os
import shutil

class Command(BaseCommand):
    help = 'Update pictogram URLs to local static files based on Korean name mapping'

    def handle(self, *args, **options):
        # 1. 한글 운동명 <-> 영어 키워드 (파일명 일부) 매핑 테이블
        # 키: DB의 exercise_name (한글/영어 혼용될 수 있으므로 주의)
        # 값: 파일명에 포함된 영어 식별자
        NAME_MAPPING = {
            # 근력 (Strength) - Category 1
            "맨몸 스쿼트": "bodyweight_squat",
            "플랭크": "plank",
            "밴드 풀 어파트": "band_pull_apart",
            "푸쉬업": "push_up",  # 혹은 "푸쉬업" 확인 필요 -> fixture 확인 결과 "팔굽혀펴기"가 아니라 "푸쉬업"일 수도 있음
            "런지": "lunge",
            "힙 브릿지": "hip_bridge",
            "버피테스트": "burpee_test",
            "레그레이즈": "leg_raise",

            # 유산소 (Aerobic) - Category 2
            "조깅": "jogging",
            "스텝박스를 활용한 운동": "step_box",
            "요가 - 우르드바하스타아사나": "urdhva_hastasana",
            "점핑잭": "jumping_jack",
            "암워킹": "arm_walking",
            "스탠스잭": "stance_jack",
            "마운틴 클라이머": "mountain_climber",
            
            # 유연성 (Flexibility) - Category 3
            "햄스트링 스트레칭": "hamstring_stretch",
            "나비자세": "butterfly_pose",
            "벽에 다리 올리기": "legs_up_wall",
            "고양이-소 자세": "cat_cow",
            "다리 스트레칭": "leg_stretch",  # 추측, 파일명: leg_stretch
            "허리 비틀기": "waist_twist",
            "트렁크 트위스트 (Lying trunk twist)": "trunk_twist", # DB 이름이 길 경우 포함 키워드로 찾기 개선 필요

            # 균형 (Balance) - Category 4
            "타이치 운동": "taichi",
            "명상 및 호흡 운동": "meditation_breathing",
            "버드 도그 홀드": "bird_dog_hold",
            "T 밸런스 자세": "t_balance",
            "체중 이동": "weight_shift",
            "하이 런지 자세": "high_lunge",
            "발끝 합장 자세": "feet_together", # 파일명: feet_together
        }

        # DB 이름과 매핑이 정확하지 않을 수 있으므로, 보완 로직 필요
        # 예: "트렁크 트위스트 (Lying trunk twist)" -> "trunk_twist"
        
        exercises = Exercise.objects.all()
        base_static_url = f"http://localhost:8000/static/"
        pictogram_root = settings.STATICFILES_DIRS[0]

        updated_count = 0
        
        # 카테고리 폴더 이름 매핑 (Category ID -> Folder Name)
        CATEGORY_FOLDER_MAP = {
            '1': 'strength',
            '2': 'aerobic',
            '3': 'flexibilty', # 오타 주의: 폴더명이 flexibilty 임
            '4': 'balance'
        }

        for exercise in exercises:
            exercise_name = exercise.exercise_name
            
            # 매핑 키워드 찾기
            target_keyword = None
            if exercise_name in NAME_MAPPING:
                target_keyword = NAME_MAPPING[exercise_name]
            else:
                # 정확히 일치하지 않으면 포함 여부로 확인 (예: "트렁크 트위스트" 포함)
                for kor_name, eng_key in NAME_MAPPING.items():
                    if kor_name.split(' (')[0] in exercise_name: # 괄호 앞부분만 비교 등
                        target_keyword = eng_key
                        break
            
            if not target_keyword:
                self.stdout.write(self.style.WARNING(f"Skipping {exercise_name}: No mapping found."))
                continue

            category_id = str(exercise.category.category_id)
            if category_id not in CATEGORY_FOLDER_MAP:
                self.stdout.write(self.style.WARNING(f"Unknown category ID {category_id} for {exercise_name}"))
                continue
                
            folder_name = CATEGORY_FOLDER_MAP[category_id]
            category_dir = os.path.join(pictogram_root, folder_name)
            
            if not os.path.exists(category_dir):
                self.stdout.write(self.style.ERROR(f"Directory not found: {category_dir}"))
                continue

            # 해당 키워드를 포함하는 파일 모두 찾기
            # 예: "push_up" -> "strength04_push_up_01.jpg", "strength04_push_up_02.jpg" ...
            matched_files = []
            for f in sorted(os.listdir(category_dir)):
                if target_keyword in f and (f.endswith('.jpg') or f.endswith('.png')):
                    matched_files.append(f)
            
            if not matched_files:
                self.stdout.write(self.style.WARNING(f"No files found for {exercise_name} (keyword: {target_keyword}) in {folder_name}"))
                continue

            # 기존 픽토그램 데이터 삭제 (중복 방지 및 갱신)
            ExerciseMedia.objects.filter(exercise=exercise, media_type='PICTOGRAM').delete()

            # 파일 생성
            for filename in matched_files:
                new_url = f"{base_static_url}{folder_name}/{filename}"
                
                # s3_key는 로컬 서빙이므로 비워둠
                ExerciseMedia.objects.create(
                    exercise=exercise,
                    media_type='PICTOGRAM',
                    locale='ko-KR',
                    url=new_url,
                    # s3_key is omitted
                )
                
            updated_count += 1
            self.stdout.write(self.style.SUCCESS(f"Updated {exercise_name}: Added {len(matched_files)} pictograms."))

        self.stdout.write(self.style.SUCCESS(f"Successfully processed {updated_count} exercises."))
