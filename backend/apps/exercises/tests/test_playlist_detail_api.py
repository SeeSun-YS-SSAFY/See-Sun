from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase
from apps.users.models import User
from apps.exercises.models import Exercise, ExerciseCategory, Playlist, PlaylistItem
import uuid
import json

class PlaylistDetailApiTests(APITestCase):
    def setUp(self):
        # 사용자 생성
        self.user = User.objects.create_user(
            username='testuser',
            password='testpassword',
            phone_number='01012345678'
        )
        self.other_user = User.objects.create_user(
            username='otheruser',
            password='otherpassword',
            phone_number='01098765432'
        )
        
        # 운동 카테고리 및 운동 생성
        self.category = ExerciseCategory.objects.create(
            category_id='strength',
            display_name='근력 운동'
        )
        self.exercise = Exercise.objects.create(
            category=self.category,
            exercise_name='스쿼트',
            exercise_description='하체 운동입니다.'
        )
        
        # 플레이리스트 생성 (본인)
        self.my_playlist = Playlist.objects.create(
            user=self.user,
            title='나의 루틴',
            mode='CUSTOM',
            status='ACTIVE'
        )
        self.my_playlist_item = PlaylistItem.objects.create(
            playlist=self.my_playlist,
            exercise=self.exercise,
            sequence_no=1,
            set_count=3,
            reps_count=10
        )
        
        # 플레이리스트 생성 (타인)
        self.other_playlist = Playlist.objects.create(
            user=self.other_user,
            title='타인의 루틴',
            mode='CUSTOM',
            status='ACTIVE'
        )
        
        # 로그인 및 토큰 설정
        self.client.force_authenticate(user=self.user)
        
        # URL 설정
        self.url_name = 'exercises:playlist_detail'

    def test_get_playlist_detail_success(self):
        """내 플레이리스트 상세 조회 성공 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 내 플레이리스트 상세 조회")
        
        url = reverse(self.url_name, kwargs={'playlist_id': self.my_playlist.playlist_id})
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print(f"  - 상태 코드 200 OK 검증 완료")
        
        self.assertEqual(response.data['playlist_id'], str(self.my_playlist.playlist_id))
        self.assertEqual(response.data['title'], '나의 루틴')
        print(f"  - 플레이리스트 정보(ID, 제목) 검증 완료")
        
        self.assertEqual(len(response.data['items']), 1)
        self.assertEqual(response.data['items'][0]['exercise']['exercise_name'], '스쿼트')
        print(f"  - 플레이리스트 아이템 및 운동 정보 검증 완료")
        
        print("\n[응답 데이터 확인]")
        print(json.dumps(response.data, indent=4, ensure_ascii=False))

        print(f"[{self._testMethodName}] 테스트 성공")

    def test_get_playlist_not_found(self):
        """존재하지 않는 플레이리스트 ID 조회 시 404 반환 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 존재하지 않는 플레이리스트 조회")
        
        random_uuid = uuid.uuid4()
        url = reverse(self.url_name, kwargs={'playlist_id': random_uuid})
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)
        print(f"  - 상태 코드 404 Not Found 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공")

    def test_get_other_user_playlist(self):
        """다른 사용자의 플레이리스트 조회 시 404 반환 테스트 (권한 없음)"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 타인의 플레이리스트 조회 시도")
        
        # 타인의 플레이리스트 ID로 요청
        url = reverse(self.url_name, kwargs={'playlist_id': self.other_playlist.playlist_id})
        response = self.client.get(url)
        
        # View 구현 상 본인 것이 아니면 DoesNotExist -> 404 Not Found
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)
        print(f"  - 상태 코드 404 Not Found (권한 없음) 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공")

    def test_unauthenticated_access(self):
        """인증되지 않은 사용자 접근 시 401 반환 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 비로그인 접근 시도")
        
        self.client.logout()
        url = reverse(self.url_name, kwargs={'playlist_id': self.my_playlist.playlist_id})
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        print(f"  - 상태 코드 401 Unauthorized 검증 완료")
        
        print(f"[{self._testMethodName}] 테스트 성공")
