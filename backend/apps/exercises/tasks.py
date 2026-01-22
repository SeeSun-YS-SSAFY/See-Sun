"""
운동 오디오 병합 비동기 태스크

카테고리 조회 시 해당 카테고리의 모든 운동에 대해
개별 음성파일들을 하나로 병합하는 태스크.
"""
from celery import shared_task
from django.conf import settings
from pydub import AudioSegment
from pathlib import Path
import os


@shared_task
def merge_exercise_audios(category_id):
    """
    특정 카테고리의 모든 운동에 대해 오디오 파일을 병합합니다.
    
    Args:
        category_id: 카테고리 ID
    """
    from apps.exercises.models import Exercise, ExerciseMedia
    
    exercises = Exercise.objects.filter(
        category__category_id=category_id,
        is_active=True
    )
    
    merged_dir = settings.MEDIA_ROOT / 'exercises' / 'audio_merged'
    if not os.path.exists(merged_dir):
        os.makedirs(merged_dir)
    
    for exercise in exercises:
        merge_single_exercise_audio.delay(str(exercise.exercise_id))
    
    return f"카테고리 {category_id}의 {exercises.count()}개 운동 병합 태스크 시작"


@shared_task
def merge_single_exercise_audio(exercise_id):
    """
    단일 운동의 오디오 파일들을 하나로 병합합니다.
    
    Args:
        exercise_id: 운동 UUID (문자열)
    """
    from apps.exercises.models import Exercise, ExerciseMedia
    
    try:
        exercise = Exercise.objects.get(exercise_id=exercise_id)
    except Exercise.DoesNotExist:
        return f"운동 {exercise_id}를 찾을 수 없습니다."
    
    # 해당 운동의 모든 오디오 미디어 조회 (sequence 순서대로)
    audio_medias = ExerciseMedia.objects.filter(
        exercise=exercise,
        media_type='GUIDE_AUDIO'
    ).order_by('sequence')
    
    if not audio_medias.exists():
        return f"운동 {exercise.exercise_name}에 오디오가 없습니다."
    
    # 오디오 파일 병합
    combined = AudioSegment.empty()
    
    for media in audio_medias:
        # URL에서 파일 경로 추출
        # URL 형식: /media/exercises/audio/{name}/{field}.mp3
        file_path = settings.MEDIA_ROOT / media.url.lstrip('/media/')
        
        if os.path.exists(file_path):
            try:
                audio = AudioSegment.from_mp3(str(file_path))
                combined += audio
                # 각 오디오 사이에 0.5초 묵음 추가
                combined += AudioSegment.silent(duration=500)
            except Exception as e:
                print(f"오디오 로드 오류 ({file_path}): {e}")
    
    # 병합된 파일 저장
    merged_dir = settings.MEDIA_ROOT / 'exercises' / 'audio_merged'
    if not os.path.exists(merged_dir):
        os.makedirs(merged_dir)
    
    # 파일명: exercise의 name_en 사용
    filename = exercise.name_en if exercise.name_en else str(exercise.exercise_id)
    output_path = merged_dir / f"{filename}_merged.mp3"
    
    combined.export(str(output_path), format="mp3")
    
    return f"운동 {exercise.exercise_name} 병합 완료: {output_path}"
