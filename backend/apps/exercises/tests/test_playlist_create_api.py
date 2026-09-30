from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase, APIClient
from django.contrib.auth import get_user_model
from apps.exercises.models import ExerciseCategory, Exercise, Playlist, PlaylistItem

User = get_user_model()

class PlaylistCreateAPITest(APITestCase):
    def setUp(self):
        # 유저 생성 및 인증
        self.user = User.objects.create_user(
            username='testuser',
            password='testpassword'
        )
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # 카테고리 및 운동 생성
        self.category = ExerciseCategory.objects.create(
            category_id="1",
            display_name="근력 운동"
        )
        self.exercise1 = Exercise.objects.create(
            exercise_name="스쿼트",
            category=self.category,
            is_active=True
        )
        self.exercise2 = Exercise.objects.create(
            exercise_name="런지",
            category=self.category,
            is_active=True
        )

        # URL
        self.url = reverse('exercises:playlist_create')

    def test_create_playlist_success(self):
        """
        정상적인 플레이리스트 생성 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 플레이리스트 생성")
        
        data = {
            "title": "나만의 하체 루틴",
            "items": [
                {
                    "exercise_id": str(self.exercise1.exercise_id),
                    "sequence_no": 1,
                    "set_count": 3,
                    "reps_count": 10
                },
                {
                    "exercise_id": str(self.exercise2.exercise_id),
                    "sequence_no": 2,
                    "set_count": 3,
                    "reps_count": 12
                }
            ]
        }

        response = self.client.post(self.url, data, format='json')

        # 1. 상태 코드 201 Created 확인
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        print("  - 상태 코드 201 Created 검증 완료")

        # 2. DB 저장 확인 (Playlist)
        playlist = Playlist.objects.get(title="나만의 하체 루틴")
        self.assertEqual(playlist.user, self.user)
        self.assertEqual(playlist.mode, 'CUSTOM')
        print(f"  - 플레이리스트 생성 확인: {playlist.title} (ID: {playlist.playlist_id})")

        # 3. DB 저장 확인 (PlaylistItems)
        items = PlaylistItem.objects.filter(playlist=playlist).order_by('sequence_no')
        self.assertEqual(items.count(), 2)
        
        print("\n  [생성된 루틴 아이템 목록]")
        for item in items:
            print(f"    {item.sequence_no}. {item.exercise.exercise_name} (Sets: {item.set_count}, Reps: {item.reps_count})")
            
        self.assertEqual(items[0].exercise, self.exercise1)
        self.assertEqual(items[0].set_count, 3)
        self.assertEqual(items[1].exercise, self.exercise2)
        self.assertEqual(items[1].reps_count, 12)
        print("  - 루틴 아이템 상세 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")

    def test_create_playlist_invalid_data(self):
        """
        필수 데이터 누락 시 에러 테스트
        """
        print(f"\n[{self._testMethodName}] 테스트 시작: 필수 데이터 누락")
        
        # items 없이 title만 전송
        data = {
            "title": "빈 루틴"
        }
        response = self.client.post(self.url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        print("  - 상태 코드 400 Bad Request 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공\n")
