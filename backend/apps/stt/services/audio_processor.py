import os
import tempfile
import subprocess
import io
import wave
from django.core.files.uploadedfile import UploadedFile

class AudioProcessor:
    @staticmethod
    def read_to_bytes(audio_file: UploadedFile) -> bytes:
        """업로드 파일을 청크 단위로 읽어서 바이트로 반환합니다."""
        chunks = []
        for chunk in audio_file.chunks():
            chunks.append(chunk)
        return b"".join(chunks)

    @staticmethod
    def convert_webm_to_bytes(audio_file: UploadedFile) -> tuple[bytes, int, str]:
        """
        WebM 파일을 바이트로 반환합니다. (변환 스킵)

        반환값에 인코딩 문자열을 포함하여, View가 포맷을 알 필요가 없도록 결합도를 낮춥니다.
        """
        audio_bytes = AudioProcessor.read_to_bytes(audio_file)
        sample_rate = 16000
        return audio_bytes, sample_rate, "WEBM_OPUS"

    @staticmethod
    def pcm_to_wav_bytes(pcm_data: bytes, sample_rate: int = 16000) -> bytes:
        """
        PCM 데이터를 WAV 형식 바이트로 변환합니다.

        주의: WebSocket 스트리밍에서는 WAV 헤더를 붙이지 않고 raw PCM을 그대로 쓰는 편이 안전합니다.
        """
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(sample_rate)
            wf.writeframes(pcm_data)
        return wav_buffer.getvalue()

    @staticmethod
    def save_temp_file(audio_file: UploadedFile, suffix: str = ".webm") -> str:
        """업로드된 파일을 임시 파일로 저장"""
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp:
            for chunk in audio_file.chunks():
                temp.write(chunk)
            return temp.name

    @staticmethod
    def convert_to_wav(input_path: str) -> str:
        """ffmpeg를 사용하여 오디오 파일을 wav(16kHz, mono, pcm_s16le)로 변환합니다."""
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
            raise RuntimeError("FFmpeg 변환에 실패했습니다.") from e

    @staticmethod
    def cleanup(*paths):
        """임시 파일 삭제"""
        for path in paths:
            if path and os.path.exists(path):
                try:
                    os.unlink(path)
                except Exception:
                    pass
