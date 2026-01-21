from django.urls import reverse
from rest_framework.test import APITestCase
from rest_framework import status
from django.contrib.auth import get_user_model
from apps.exercises.models import Playlist, Exercise, ExerciseCategory
from apps.logs.models import ExerciseSession

User = get_user_model()

class ExerciseSessionTests(APITestCase):
    def setUp(self):
        # Create User
        self.user = User.objects.create_user(username='testuser', password='password123')
        self.client.force_authenticate(user=self.user)
        
        # Create Exercise Category & Exercise
        self.category = ExerciseCategory.objects.create(category_id='cardio', display_name='Cardio')
        self.exercise = Exercise.objects.create(
            category=self.category,
            exercise_name='Push Up'
        )
        
        # Create Playlist
        self.playlist = Playlist.objects.create(
            user=self.user,
            title='My Workout',
            mode='CUSTOM'
        )
        
        # URLs
        self.start_url = reverse('logs:session_start')
        
    def test_start_session_simple(self):
        """플레이리스트 없이 이름만으로 세션 시작 테스트"""
        data = {'exercise_name': 'Free Workout'}
        response = self.client.post(self.start_url, data)
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.data['status'], 'IN_PROGRESS')
        self.assertEqual(response.data['exercise_name'], 'Free Workout')
        self.assertIn('session_id', response.data)
        
        # DB 확인
        session = ExerciseSession.objects.get(session_id=response.data['session_id'])
        self.assertEqual(session.user, self.user)
        self.assertIsNotNone(session.started_at)
        
    def test_start_session_with_playlist(self):
        """플레이리스트 ID로 세션 시작 테스트"""
        data = {'playlist_id': self.playlist.playlist_id}
        response = self.client.post(self.start_url, data)
        
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.data['playlist'], str(self.playlist.playlist_id))
        self.assertEqual(response.data['exercise_name'], self.playlist.title) # 이름 자동 설정 확인
        
    def test_end_session(self):
        """세션 종료 및 시간 기록 테스트"""
        # 1. 세션 시작
        start_response = self.client.post(self.start_url, {'exercise_name': 'Test Session'})
        session_id = start_response.data['session_id']
        
        # 2. 잠시 대기
        
        end_url = reverse('logs:session_end', kwargs={'session_id': session_id})
        end_response = self.client.post(end_url)
        
        self.assertEqual(end_response.status_code, status.HTTP_200_OK)
        self.assertEqual(end_response.data['status'], 'COMPLETED')
        self.assertIsNotNone(end_response.data['ended_at'])
        self.assertIsNotNone(end_response.data['duration_ms'])
        self.assertIn('duration_seconds', end_response.data) # Float field check
        
        # DB 확인
        session = ExerciseSession.objects.get(session_id=session_id)
        self.assertEqual(session.status, 'COMPLETED')
        self.assertIsNotNone(session.duration)

    def test_end_session_invalid_id(self):
        """존재하지 않는 세션 종료 시도"""
        import uuid
        random_id = uuid.uuid4()
        end_url = reverse('logs:session_end', kwargs={'session_id': random_id})
        response = self.client.post(end_url)
        
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)

    def test_start_session_unauthenticated(self):
        """로그인하지 않은 사용자의 접근 제한"""
        self.client.force_authenticate(user=None)
        response = self.client.post(self.start_url, {'exercise_name': 'Test'})
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_session_ping(self):
        """세션 Ping 테스트"""
        start_response = self.client.post(self.start_url, {'exercise_name': 'Test Ping'})
        session_id = start_response.data['session_id']
        
        ping_url = reverse('logs:session_ping', kwargs={'session_id': session_id})
        response = self.client.post(ping_url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('last_ping_at', response.data)
        
        # Check DB
        session = ExerciseSession.objects.get(session_id=session_id)
        self.assertIsNotNone(session.last_ping_at)

    def test_end_session_too_short(self):
        """10초 미만 운동은 is_valid=False"""
        start_response = self.client.post(self.start_url, {'exercise_name': 'Short Exercise'})
        session_id = start_response.data['session_id']
        
        # Immediately end (0 seconds execution time ideally, definitely < 10)
        end_url = reverse('logs:session_end', kwargs={'session_id': session_id})
        response = self.client.post(end_url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        # duration_seconds should be near 0
        self.assertLess(response.data['duration_seconds'], 10.0)
        # is_valid should be False
        self.assertFalse(response.data['is_valid'])
