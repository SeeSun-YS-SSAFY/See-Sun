"""
자주 하는 운동(B5)·루틴 세션 시작(B6)·STT 경로 별칭(B7) 회귀 테스트
"""
import uuid
from unittest.mock import patch

from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from rest_framework import status
from rest_framework.test import APITestCase

from apps.exercises.models import ExerciseCategory, Exercise
from apps.logs.models import ExerciseSession

User = get_user_model()


class FrequentExerciseAPITest(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user(username='u1', password='pw')
        self.other = User.objects.create_user(username='u2', password='pw')
        self.client.force_authenticate(self.user)
        category = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
        self.squat = Exercise.objects.create(exercise_name='스쿼트', category=category, is_active=True)
        self.lunge = Exercise.objects.create(exercise_name='런지', category=category, is_active=True)

    def _session(self, user, name, valid=True, status_='COMPLETED'):
        return ExerciseSession.objects.create(
            user=user, exercise_name=name, is_valid=valid, status=status_, mode='MANUAL'
        )

    def test_많이_한_순서로_반환하고_무효·진행중·타인_세션은_제외(self):
        for _ in range(3):
            self._session(self.user, '런지')
        self._session(self.user, '스쿼트')
        self._session(self.user, '스쿼트', valid=False)
        self._session(self.user, '스쿼트', status_='IN_PROGRESS')
        for _ in range(5):
            self._session(self.other, '스쿼트')

        for url in ('/api/v1/exercises/frequent', '/api/v1/exercises/frequent/'):
            response = self.client.get(url)
            self.assertEqual(response.status_code, status.HTTP_200_OK)
            names = [(e['exercise_name'], e['count']) for e in response.data['exercises']]
            self.assertEqual(names, [('런지', 3), ('스쿼트', 1)])
            self.assertEqual(response.data['exercises'][0]['category_name'], '근력 운동')

    def test_기록이_없으면_빈_목록(self):
        response = self.client.get('/api/v1/exercises/frequent/')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data['exercises'], [])


class SessionStartRoutineTest(APITestCase):
    URL = '/api/v1/log/session/start/'

    def setUp(self):
        self.user = User.objects.create_user(username='u1', password='pw')
        self.client.force_authenticate(self.user)

    def test_루틴에서_플레이리스트가_아닌_ID가_와도_운동이름이_있으면_시작(self):
        response = self.client.post(self.URL, {'playlist_id': str(uuid.uuid4()), 'exercise_name': '스쿼트'}, format='json')
        self.assertEqual(response.status_code, status.HTTP_201_CREATED, response.data)

    def test_단일_운동은_exercise_id를_함께_보내도_시작(self):
        response = self.client.post(self.URL, {'exercise_id': str(uuid.uuid4()), 'exercise_name': '스쿼트'}, format='json')
        self.assertEqual(response.status_code, status.HTTP_201_CREATED, response.data)

    def test_운동이름_없이_잘못된_플레이리스트면_400(self):
        response = self.client.post(self.URL, {'playlist_id': str(uuid.uuid4())}, format='json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)


class STTModeAliasTest(APITestCase):
    def setUp(self):
        self.client.force_authenticate(User.objects.create_user(username='u1', password='pw'))

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes', return_value=(b'a', 16000, 'WEBM_OPUS'))
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe', return_value='다음')
    @patch('apps.stt.services.gemini_service.GeminiService.parse_full_command', return_value={'action': 'NEXT'})
    def test_full_command_하이픈_경로도_허용(self, *_):
        audio = SimpleUploadedFile('a.webm', b'x', content_type='audio/webm')
        response = self.client.post('/api/v1/stt/full-command/', {'audio': audio}, format='multipart')
        self.assertEqual(response.status_code, status.HTTP_200_OK, response.data)
        self.assertEqual(response.data['mode'], 'full_command')
