import os
import django
from django.conf import settings
from rest_framework.test import APIClient
from django.contrib.auth import get_user_model

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
django.setup()

from apps.exercises.models import Exercise, ExerciseCategory

def test_exercise_detail():
    User = get_user_model()
    user, _ = User.objects.get_or_create(username='test_verifier', password='password')
    
    # Get a random exercise
    exercise = Exercise.objects.first()
    if not exercise:
        print("No exercises found in DB.")
        return

    client = APIClient()
    client.force_authenticate(user=user)
    
    url = f"/api/v1/exercises/{exercise.exercise_id}/"
    response = client.get(url)
    
    print(f"Status: {response.status_code}")
    if response.status_code == 200:
        data = response.json()
        print(f"Exercise: {data.get('exercise_name')}")
        print("Audios:", data.get('audios')) # Check if this list exists and is populated
        print("Pictograms:", data.get('pictograms'))
    else:
        print(response.data)

if __name__ == "__main__":
    test_exercise_detail()
