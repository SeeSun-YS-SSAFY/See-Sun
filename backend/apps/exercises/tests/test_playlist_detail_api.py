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
        
        # 두 번째 아이템 추가
        exercise2 = Exercise.objects.create(
            category=self.category,
            exercise_name='런지',
            exercise_description='하체 운동'
        )
        PlaylistItem.objects.create(
            playlist=self.my_playlist,
            exercise=exercise2,
            sequence_no=2,
            set_count=3,
            reps_count=15
        )

        url = reverse(self.url_name, kwargs={'playlist_id': self.my_playlist.playlist_id})
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print(f"  - 상태 코드 200 OK 검증 완료")
        
        self.assertEqual(response.data['playlist_id'], str(self.my_playlist.playlist_id))
        self.assertEqual(response.data['title'], '나의 루틴')
        print(f"  - 플레이리스트 정보(ID, 제목) 검증 완료")
        
        self.assertEqual(len(response.data['items']), 2)
        self.assertEqual(response.data['items'][0]['exercise_name'], '스쿼트')
        self.assertEqual(response.data['items'][1]['exercise_name'], '런지')
        print(f"  - 플레이리스트 아이템(2개) 및 운동 정보 검증 완료")
        
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

    def test_playlist_items_ordering(self):
        """플레이리스트 항목이 sequence_no 순서대로 정렬되는지 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 항목 정렬 순서 검증")
        
        # 추가 아이템 생성 (sequence_no를 2로 설정)
        exercise2 = Exercise.objects.create(
            category=self.category, 
            exercise_name='런지',
            exercise_description='하체 운동'
        )
        PlaylistItem.objects.create(
            playlist=self.my_playlist,
            exercise=exercise2,
            sequence_no=2,
            set_count=3
        )
        
        url = reverse(self.url_name, kwargs={'playlist_id': self.my_playlist.playlist_id})
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        items = response.data['items']
        
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]['sequence_no'], 1)
        self.assertEqual(items[0]['exercise_name'], '스쿼트')
        self.assertEqual(items[1]['sequence_no'], 2)
        self.assertEqual(items[1]['exercise_name'], '런지')
        
        print(f"  - 항목 1: {items[0]['exercise_name']} (Seq: {items[0]['sequence_no']})")
        print(f"  - 항목 2: {items[1]['exercise_name']} (Seq: {items[1]['sequence_no']})")
        print(f"  - 항목이 sequence_no 순서(1 -> 2)로 정렬됨 확인")
        
        print(f"[{self._testMethodName}] 테스트 성공")

    def test_update_playlist_title(self):
        """플레이리스트 제목 수정(PATCH) 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 플레이리스트 제목 수정")
        
        url = reverse('exercises:playlist_detail', kwargs={'playlist_id': self.my_playlist.playlist_id})
        update_data = {"title": "수정된 제목"}
        
        response = self.client.patch(url, update_data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        print(f"  - 상태 코드 200 OK")
        self.assertEqual(response.data['title'], "수정된 제목")
        print(f"  - 제목 변경 확인: {response.data['title']}")
        
        # 다른 필드 변질 없는지 확인
        self.assertEqual(len(response.data['items']), 1)
        
        print("\n[수정 후 응답 데이터 확인]")
        print(json.dumps(response.data, indent=4, ensure_ascii=False))
        
        print(f"[{self._testMethodName}] 테스트 성공")

    def test_add_playlist_item(self):
        """플레이리스트 운동 추가(POST) 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 운동 추가")
        
        # 새 운동 생성
        exercise2 = Exercise.objects.create(category=self.category, exercise_name='런지', exercise_description='...')
        
        url = reverse('exercises:playlist_item_add', kwargs={'playlist_id': self.my_playlist.playlist_id})
        data = {
            "exercise_id": str(exercise2.exercise_id),
            "set_count": 5,
            "reps_count": 12
        }
        
        response = self.client.post(url, data, format='json')
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(len(response.data['items']), 2)
        print(f"  - 아이템 개수 2개로 증가 확인")
        self.assertEqual(response.data['items'][1]['exercise_name'], '런지')
        
        print("\n[추가 후 응답 데이터 확인]")
        print(json.dumps(response.data, indent=4, ensure_ascii=False))

        print(f"[{self._testMethodName}] 테스트 성공")

    def test_delete_playlist_item(self):
        """플레이리스트 항목 삭제(DELETE) 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 항목 삭제")
        
        item_id = self.my_playlist_item.playlist_item_id
        url = reverse('exercises:playlist_item_detail', kwargs={
            'playlist_id': self.my_playlist.playlist_id,
            'item_id': item_id
        })
        
        response = self.client.delete(url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(len(response.data['items']), 0)
        print(f"  - 아이템 삭제 후 0개 확인")
        
        print("\n[삭제 후 응답 데이터 확인]")
        print(json.dumps(response.data, indent=4, ensure_ascii=False))

        print(f"[{self._testMethodName}] 테스트 성공")

    def test_delete_playlist(self):
        """플레이리스트 삭제(DELETE) 테스트"""
        print(f"\n[{self._testMethodName}] 테스트 시작: 플레이리스트 삭제")
        
        # 삭제할 플레이리스트 생성
        playlist_to_delete = Playlist.objects.create(
            user=self.user,
            title='삭제할 루틴',
            mode='CUSTOM',
            status='ACTIVE'
        )
        
        url = reverse(self.url_name, kwargs={'playlist_id': playlist_to_delete.playlist_id})
        response = self.client.delete(url)
        
        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        print(f"  - 상태 코드 204 No Content 검증 완료")
        
        # DB에서 삭제 확인
        with self.assertRaises(Playlist.DoesNotExist):
            Playlist.objects.get(playlist_id=playlist_to_delete.playlist_id)
        print(f"  - DB에서 삭제됨 확인")
        
        print("\n[삭제 결과 확인]")
        print("삭제 성공 (HTTP 204 No Content) - 응답 본문 없음")
        
        print(f"[{self._testMethodName}] 테스트 성공")


