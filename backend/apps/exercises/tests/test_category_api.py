from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase, APIClient
from django.contrib.auth import get_user_model
from apps.exercises.models import ExerciseCategory

User = get_user_model()

class ExerciseCategoryAPITest(APITestCase):
    def setUp(self):
        # 테스트 유저 생성 및 인증
        self.user = User.objects.create_user(
            username='testuser',
            password='testpassword',
            name='테스트유저',
            phone_number='01012345678'
        )
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # 테스트용 카테고리 데이터 생성
        self.category1 = ExerciseCategory.objects.create(
            category_id="1",
            display_name="근력 운동"
        )
        self.category2 = ExerciseCategory.objects.create(
            category_id="2",
            display_name="유산소 운동"
        )

        # URL
        self.url = reverse('exercises:category_list')

    def test_get_exercise_categories(self):
        """
        운동 카테고리 목록 조회 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 운동 카테고리 목록 조회")
        response = self.client.get(self.url)

        # 1. 상태 코드 200 OK 확인
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print("  - 상태 코드 200 OK 검증 완료")

        # 2. 데이터 개수 확인
        self.assertEqual(len(response.data), 2)
        print("  - 데이터 개수 2개 검증 완료")

        # 3. 데이터 내용 확인 (순서는 보장되지 않을 수 있으므로 포함 여부 확인)
        expected_data = [
            {'category_id': '1', 'display_name': '근력 운동'},
            {'category_id': '2', 'display_name': '유산소 운동'}
        ]
        
        # response.data의 순서가 다를 수 있으므로 정렬하여 비교하거나 하나씩 확인
        # 여기서는 category_id 기준으로 정렬하여 비교
        response_data_sorted = sorted(response.data, key=lambda x: x['category_id'])
        expected_data_sorted = sorted(expected_data, key=lambda x: x['category_id'])
        
        print(f"\n  [조회된 카테고리 목록]")
        for cat in response_data_sorted:
            print(f"    - ID: {cat['category_id']}, Name: {cat['display_name']}")
        
        self.assertEqual(response_data_sorted, expected_data_sorted)
        print("  - 데이터 내용 일치 검증 완료")
        print(f"[{self._testMethodName}] 테스트 성공\n")

    def test_get_categories_unauthenticated(self):
        """
        인증되지 않은 사용자의 접근 시 401 Unauthorized 확인
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 비로그인 접근 제한")
        self.client.logout()  # 로그아웃 (인증 해제)
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        print("  - 상태 코드 401 Unauthorized 검증 완료")
        print(f"[{self._testMethodName}] 테스트 성공\n")
