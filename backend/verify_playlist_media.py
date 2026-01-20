import os
import django
from rest_framework.test import APIRequestFactory

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
django.setup()

from apps.exercises.models import Exercise, Playlist, PlaylistItem
from apps.exercises.serializers import PlaylistItemSerializer
from django.contrib.auth import get_user_model

def test_playlist_item_media():
    User = get_user_model()
    # Create or get user
    user, _ = User.objects.get_or_create(username='verifier_pl', password='password')
    
    # Get an exercise (e.g. Leg Raise from previous test)
    # Using '레그레이즈' (mapped to leg_raise) if available, or just first one
    exercise = Exercise.objects.filter(exercise_name='레그레이즈').first()
    if not exercise:
        exercise = Exercise.objects.first()
    
    if not exercise:
        print("No exericse found.")
        return

    # Create dummy playlist
    playlist, _ = Playlist.objects.get_or_create(user=user, title="Media Test Playlist")
    
    # Create item
    item, created = PlaylistItem.objects.get_or_create(
        playlist=playlist,
        exercise=exercise,
        defaults={
            'sequence_no': 1,
            'set_count': 3,
            'reps_count': 10,
            'duration_sec': 0,
            'rest_sec': 60
        }
    )

    # Serialize
    serializer = PlaylistItemSerializer(item)
    data = serializer.data
    
    print(f"--- Testing PlaylistItem: {data['exercise_name']} ---")
    print("\n[Audios]")
    for audio in data.get('audios', []):
        print(f"  - Type: {audio['type']}, URL: {audio['url']}")

    print("\n[Pictograms]")
    for pic in data.get('pictograms', []):
        print(f"  - URL: {pic}")

if __name__ == "__main__":
    test_playlist_item_media()
