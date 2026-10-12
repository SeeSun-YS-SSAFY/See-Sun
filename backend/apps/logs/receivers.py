from django.dispatch import receiver

from apps.users.models import account_withdrawn


@receiver(account_withdrawn)
def delete_exercise_records(sender, user, **kwargs):
    """탈퇴 시 운동 기록·나만의 루틴 파기(05 문서 §2 서비스 기록)"""
    from apps.exercises.models import Playlist

    from .models import ExerciseSession

    ExerciseSession.objects.filter(user=user).delete()
    Playlist.objects.filter(user=user).delete()
