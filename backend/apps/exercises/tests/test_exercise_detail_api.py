from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase, APIClient
from django.contrib.auth import get_user_model
from apps.exercises.models import ExerciseCategory, Exercise, ExerciseMedia
import uuid

User = get_user_model()

class ExerciseDetailAPITest(APITestCase):
    def setUp(self):
        # 유저 생성 및 인증
        self.user = User.objects.create_user(
            username='testuser',
            password='testpassword'
        )
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # 카테고리 생성
        self.category = ExerciseCategory.objects.create(
            category_id="1",
            display_name="근력 운동"
        )

        # 운동 생성
        self.exercise = Exercise.objects.create(
            exercise_name="스쿼트",
            category=self.category,
            is_active=True,
            exercise_description="하체 근력 강화",
            first_description="다리를 어깨 너비로 벌립니다.",
            main_form="무릎을 굽히며 내려갑니다.",
            form_description="허리를 곧게 펴세요.",
            stay_form="잠시 유지합니다.",
            fixed_form="무릎이 발끝을 넘지 않도록 주의합니다.",
            exercise_guide_text="정확한 자세가 중요합니다."
        )

        # 픽토그램 미디어 생성
        ExerciseMedia.objects.create(
            exercise=self.exercise,
            media_type='PICTOGRAM',
            url='https://example.com/squat_pictogram.png',
            s3_key='squat_pictogram.png'
        )

        # URL
        self.url = reverse('exercises:exercise_detail', kwargs={'exercise_id': self.exercise.exercise_id})

    def test_get_exercise_detail_success(self):
        """
        정상적인 운동 상세 정보 조회 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 정상 상세 조회")
        
        response = self.client.get(self.url)

        # 1. 상태 코드 200 확인
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print("  - 상태 코드 200 OK 검증 완료")

        # 2. 상세 데이터 내용 확인
        data = response.data
        self.assertEqual(str(data['exercise_id']), str(self.exercise.exercise_id))
        self.assertEqual(data['exercise_name'], "스쿼트")
        self.assertEqual(data['category_name'], "근력 운동")
        self.assertEqual(data['exercise_description'], "하체 근력 강화")
        self.assertEqual(data['exercise_guide'], "정확한 자세가 중요합니다.")
        self.assertEqual(data['pictogram_url'], 'https://example.com/squat_pictogram.png')
        
        # 상세 폼 설명 필드 확인
        self.assertEqual(data['first_description'], "다리를 어깨 너비로 벌립니다.")
        self.assertEqual(data['main_form'], "무릎을 굽히며 내려갑니다.")
        
        print(f"\n  [조회된 운동 상세 정보]")
        print(f"    - ID: {data['exercise_id']}")
        print(f"    - 이름: {data['exercise_name']}")
        print(f"    - 카테고리: {data['category_name']}")
        print(f"    - 설명: {data['exercise_description']}")
        print(f"    - 가이드: {data['exercise_guide']}")
        print(f"    - 시작 자세: {data['first_description']}")
        print(f"    - 운동 동작: {data['main_form']}")
        print(f"    - 픽토그램 URL: {data['pictogram_url']}")

        print("  - 상세 데이터 필드 값 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")

    def test_get_exercise_detail_not_found(self):
        """
        존재하지 않는 운동 ID로 조회 시 404 에러 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 존재하지 않는 운동 조회")
        
        random_uuid = uuid.uuid4()
        url = reverse('exercises:exercise_detail', kwargs={'exercise_id': random_uuid})
        response = self.client.get(url)

        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)
        print("  - 상태 코드 404 Not Found 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")

    def test_unauthenticated_access(self):
        """
        비로그인 시 접근 제한 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 비로그인 접근 제한")
        
        self.client.logout()
        response = self.client.get(self.url)

        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        print("  - 상태 코드 401 Unauthorized 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")
