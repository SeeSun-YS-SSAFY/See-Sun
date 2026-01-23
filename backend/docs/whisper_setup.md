# Whisper.cpp 서버 설정 가이드

이 문서는 STT 기능에 필요한 whisper.cpp HTTP 서버 설정 방법을 안내합니다.

## 1. whisper.cpp 설치

```bash
# whisper.cpp 저장소 클론
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp

# 빌드
make

# 모델 다운로드 (ggml-tiny: 75MB)
bash ./models/download-ggml-model.sh tiny
```

## 2. HTTP 서버 실행

```bash
# 기본 실행 (포트 8080)
./server -m models/ggml-tiny.bin -p 8080 --language ko

# 백그라운드 실행 (추천)
nohup ./server -m models/ggml-tiny.bin -p 8080 --language ko > whisper.log 2>&1 &
```

## 3. 서버 동작 확인

```bash
# 테스트 WAV 파일로 확인
curl -F "file=@samples/jfk.wav" http://localhost:8080/inference

# 예상 응답
# {"text": " ...인식된 텍스트..."}
```

## 4. Django API 테스트

```bash
# Django 서버 실행
python manage.py runserver 8080

# webm 파일 전송 (Postman 또는 curl)
curl -X POST http://localhost:8080/api/v1/stt/transcribe \
  -F "file=@test.webm" \
  -F "mode=form"
```

## 5. 모델 업그레이드 (선택)

```bash
# base 모델 (142MB, 더 높은 정확도)
bash ./models/download-ggml-model.sh base
./server -m models/ggml-base.bin -p 8080 --language ko

# 한국어 fine-tune 모델 (Hugging Face에서 검색)
# wget https://huggingface.co/.../ggml-model-ko-base.bin
```

## 6. 트러블슈팅

### whisper.cpp 서버가 응답하지 않을 때
```bash
# 프로세스 확인
ps aux | grep server

# 포트 사용 확인
lsof -i:8080

# 로그 확인
tail -f whisper.log
```

### ffmpeg 없음 오류
```bash
# Ubuntu/Debian
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg

# Windows
# https://ffmpeg.org/download.html 에서 다운로드
```
