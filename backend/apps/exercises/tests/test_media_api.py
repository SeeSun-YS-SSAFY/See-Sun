from django.urls import reverse
from rest_framework import status
from rest_framework.test import APITestCase, APIClient
from django.contrib.auth import get_user_model
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia, Playlist, PlaylistItem

User = get_user_model()

class ExerciseMediaAPITest(APITestCase):
    def setUp(self):
        # 1. User & Auth
        self.user = User.objects.create_user(username='mediatester', password='password')
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # 2. Category & Exercise
        self.category = ExerciseCategory.objects.create(category_id="1", display_name="테스트 카테고리")
        self.exercise = Exercise.objects.create(
            category=self.category,
            exercise_name="테스트 운동",
            exercise_description="설명",
            exercise_guide_text="가이드"
        )

        # 3. Media Data Setup
        # Pictograms
        self.pic1 = ExerciseMedia.objects.create(
            exercise=self.exercise, media_type='PICTOGRAM', url='/media/pic1.jpg'
        )
        self.pic2 = ExerciseMedia.objects.create(
            exercise=self.exercise, media_type='PICTOGRAM', url='/media/pic2.jpg'
        )
        
        # Audios
        self.audio1 = ExerciseMedia.objects.create(
            exercise=self.exercise, media_type='GUIDE_AUDIO', 
            url='/media/audio1.mp3', s3_key='first_description'
        )
        self.audio2 = ExerciseMedia.objects.create(
            exercise=self.exercise, media_type='GUIDE_AUDIO', 
            url='/media/audio2.mp3', s3_key='main_form'
        )

    def test_exercise_detail_media_fields(self):
        """운동 상세 조회 시 audios, pictograms 필드 확인"""
        url = reverse('exercises:exercise_detail', args=[self.exercise.exercise_id])
        response = self.client.get(url)

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        
        data = response.data
        
        # Pictograms Check
        self.assertIn('pictograms', data)
        self.assertEqual(len(data['pictograms']), 2)
        self.assertIn('/media/pic1.jpg', data['pictograms'])
        
        # Audios Check
        self.assertIn('audios', data)
        self.assertEqual(len(data['audios']), 2)
        # Check structure: {'type': '...', 'url': '...'}
        types = [a['type'] for a in data['audios']]
        self.assertIn('first_description', types)
        self.assertIn('main_form', types)

        print("\n[테스트] 운동 상세 미디어 필드 검증: 성공 (PASS)")

    def test_playlist_item_media_fields(self):
        """플레이리스트 항목 조회 시 미디어 필드 포함 여부 확인"""
        # Create Playlist
        playlist = Playlist.objects.create(user=self.user, title="My Routine", status="ACTIVE")
        item = PlaylistItem.objects.create(
            playlist=playlist, 
            exercise=self.exercise, 
            sequence_no=1,
            set_count=3,
            reps_count=10,
            duration_sec=0,
            rest_sec=60
        )
        
        url = reverse('exercises:playlist_detail', args=[playlist.playlist_id])
        response = self.client.get(url)
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        items = response.data['items']
        self.assertTrue(len(items) > 0)
        
        target_item = items[0]
        self.assertEqual(target_item['exercise_id'], str(self.exercise.exercise_id))
        
        # Check Media Fields in Playlist Item
        self.assertIn('pictograms', target_item)
        self.assertIn('audios', target_item)
        
        self.assertEqual(len(target_item['pictograms']), 2)
        self.assertEqual(len(target_item['audios']), 2)
        
        print("\n[테스트] 플레이리스트 항목 미디어 필드 검증: 성공 (PASS)")
