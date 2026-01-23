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

# imageio-ffmpeg에서 ffmpeg 경로 가져오기
try:
    import imageio_ffmpeg
    AudioSegment.converter = imageio_ffmpeg.get_ffmpeg_exe()
except ImportError:
    pass  # imageio-ffmpeg가 없으면 시스템 ffmpeg 사용


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


def load_audio_without_ffprobe(file_path):
    """
    pydub.AudioSegment.from_mp3가 ffprobe를 필요로 하여 실패하는 경우를 대비해,
    ffmpeg를 직접 사용하여 WAV로 디코딩 후 로드하는 함수.
    """
    try:
        # 1. 시도: 기본 로드 (ffprobe가 있다면 성공)
        return AudioSegment.from_mp3(str(file_path))
    except Exception as e:
        # 2. 실패 시: ffmpeg 직접 사용하여 WAV로 변환 후 로드
        # print(f"기본 로드 실패, ffmpeg 직접 사용 시도: {e}")
        try:
            import subprocess
            import io
            import imageio_ffmpeg
            
            ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
            
            # -y: overwrite, -f wav: output format, -: output to stdout
            cmd = [ffmpeg_exe, '-i', str(file_path), '-y', '-f', 'wav', '-']
            
            # Windows에서 subprocess 실행 시 shell=False (기본값) 사용
            # [WinError 2] 방지를 위해 ffmpeg_exe가 절대 경로인지 확인 (imageio_ffmpeg는 절대경로 반환함)
            
            process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            stdout_data, stderr_data = process.communicate()
            
            if process.returncode != 0:
                print(f"FFmpeg decode error: {stderr_data.decode('utf-8', errors='ignore')}")
                raise e # 원래 에러 다시 발생
                
            return AudioSegment.from_wav(io.BytesIO(stdout_data))
            
        except Exception as ffmpeg_e:
            print(f"FFmpeg 직접 로드도 실패: {ffmpeg_e}")
            raise e  # 원래 에러 발생


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
        # 주의: lstrip은 문자 집합을 제거하므로 replace 사용
        relative_path = media.url.replace('/media/', '', 1)
        file_path = settings.MEDIA_ROOT / relative_path
        
        if os.path.exists(file_path):
            try:
                # ffprobe 문제 해결을 위한 헬퍼 함수 사용
                audio = load_audio_without_ffprobe(file_path)
                combined += audio
                # 각 오디오 사이에 0.5초 묵음 추가
                combined += AudioSegment.silent(duration=500)
            except Exception as e:
                print(f"오디오 로드 오류 ({file_path}): {e}")
        else:
            print(f"파일을 찾을 수 없음: {file_path}")
    
    # 병합된 파일 저장
    merged_dir = settings.MEDIA_ROOT / 'exercises' / 'audio_merged'
    if not os.path.exists(merged_dir):
        os.makedirs(merged_dir)
    
    # 파일명: exercise의 name_en 사용
    filename = exercise.name_en if exercise.name_en else str(exercise.exercise_id)
    output_path = merged_dir / f"{filename}_merged.mp3"
    
    combined.export(str(output_path), format="mp3")
    
    return f"운동 {exercise.exercise_name} 병합 완료: {output_path}"
