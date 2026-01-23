import os
import tempfile
import subprocess
from django.core.files.uploadedfile import UploadedFile

class AudioProcessor:
    @staticmethod
    def save_temp_file(audio_file: UploadedFile, suffix: str = ".webm") -> str:
        """업로드된 파일을 임시 파일로 저장"""
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp:
            for chunk in audio_file.chunks():
                temp.write(chunk)
            return temp.name

    @staticmethod
    def convert_to_wav(input_path: str) -> str:
        """ffmpeg를 사용하여 오디오 파일을 wav(16kHz, mono, pcm_s16le)로 변환"""
        try:
            wav_path = tempfile.mktemp(suffix='.wav')
            command = [
                'ffmpeg', '-i', input_path,
                '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', '-y', wav_path
            ]
            subprocess.run(
                command, 
                check=True, 
                stderr=subprocess.DEVNULL, 
                stdout=subprocess.DEVNULL
            )
            return wav_path
        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"FFmpeg conversion failed: {str(e)}")

    @staticmethod
    def cleanup(*paths):
        """임시 파일 삭제"""
        for path in paths:
            if path and os.path.exists(path):
                try:
                    os.unlink(path)
                except Exception:
                    pass
