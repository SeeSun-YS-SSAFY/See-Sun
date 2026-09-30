from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase, APIClient
from django.contrib.auth import get_user_model
from apps.exercises.models import ExerciseCategory, Exercise, ExerciseMedia

User = get_user_model()

class ExerciseListByCategoryAPITest(APITestCase):
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
            is_active=True
        )

        # 픽토그램 미디어 생성
        ExerciseMedia.objects.create(
            exercise=self.exercise,
            media_type='PICTOGRAM',
            url='https://example.com/squat_pictogram.png',
            s3_key='squat_pictogram.png'
        )

        # URL
        self.url = reverse('exercises:exercise_list_by_category', kwargs={'category_id': self.category.category_id})

    def test_get_exercises_by_category_success(self):
        """
        정상적인 카테고리별 운동 목록 조회 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 정상 조회")
        
        response = self.client.get(self.url)

        # 1. 상태 코드 200 확인
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print("  - 상태 코드 200 OK 검증 완료")

        # 2. 응답 구조 확인 (category_id, category_name, exercises)
        self.assertEqual(str(response.data['category_id']), str(self.category.category_id))
        self.assertEqual(response.data['category_name'], self.category.display_name)
        self.assertTrue('exercises' in response.data)
        print("  - 응답 데이터 구조 검증 완료")

        # 3. 운동 목록 내용 확인
        exercises = response.data['exercises']
        print(f"\n  [조회된 운동 목록 (총 {len(exercises)}개)]")
        for idx, ex in enumerate(exercises, 1):
            print(f"    {idx}. {ex['exercise_name']} (ID: {ex['exercise_id']})")
            print(f"       - Pictogram: {ex['pictogram_url']}")

        self.assertEqual(len(exercises), 1)
        self.assertEqual(str(exercises[0]['exercise_id']), str(self.exercise.exercise_id))
        self.assertEqual(exercises[0]['exercise_name'], self.exercise.exercise_name)
        self.assertEqual(exercises[0]['pictogram_url'], 'https://example.com/squat_pictogram.png')
        print("  - 운동 데이터 및 픽토그램 URL 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")

    def test_get_exercises_by_category_not_found(self):
        """
        존재하지 않는 카테고리 ID로 조회 시 404 에러 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 존재하지 않는 카테고리 조회")
        
        url = reverse('exercises:exercise_list_by_category', kwargs={'category_id': '9999'})
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
