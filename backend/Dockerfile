# Python 3.11 slim 이미지 사용
FROM python:3.11-slim

WORKDIR /app

# 시스템 의존성 설치 (PostgreSQL 클라이언트, ffmpeg 등)
# --fix-missing 플래그로 미러 일시 오류 대응
RUN apt-get update && \
    apt-get install -y --no-install-recommends --fix-missing \
    gcc \
    libpq-dev \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Python 의존성 설치
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN pip install --no-cache-dir psycopg2-binary

# 애플리케이션 코드 복사
COPY . .

# 포트 노출
EXPOSE 8000

# 기본 명령어 (docker-compose에서 override 가능)
CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"]
