# syntax=docker/dockerfile:1
# See:Sun 백엔드 이미지(09 문서 2-11)
#   운영:  docker build -t seesun-backend --target runtime backend/
#   개발:  docker compose(루트 docker-compose.yml)가 --target dev 로 빌드
ARG PYTHON_VERSION=3.12

# ---- 공통 기반 -------------------------------------------------------------
FROM python:${PYTHON_VERSION}-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

# ---- 휠 빌드(운영 의존성만) -----------------------------------------------
FROM base AS builder
COPY requirements.txt ./
RUN pip wheel --wheel-dir /wheels -r requirements.txt

# ---- 개발: 테스트·1차 웹 데모 호환 패키지 포함, runserver ----------------------
FROM base AS dev
COPY requirements.txt requirements-dev.txt ./
RUN pip install -r requirements-dev.txt
COPY . .
EXPOSE 8000
CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"]

# ---- 운영: gunicorn, 비루트 사용자 --------------------------------------------
FROM base AS runtime
RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app
COPY --from=builder /wheels /wheels
RUN pip install --no-index --find-links=/wheels /wheels/* && rm -rf /wheels
COPY --chown=app:app . .
# 정적 파일(관리자 화면)은 빌드 때 모아 WhiteNoise가 서빙. 아래 값은 collectstatic 전용 더미(운영 값 아님)
RUN DJANGO_DEBUG=false DJANGO_SECRET_KEY=collectstatic-build-only \
    FIELD_ENCRYPTION_KEYS=build-only PHONE_HASH_KEY=build-only \
    python manage.py collectstatic --noinput \
 && chmod +x docker-entrypoint.sh \
 && mkdir -p /app/media && chown app:app /app/media
USER app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz/', timeout=4).status == 200 else 1)"
ENTRYPOINT ["./docker-entrypoint.sh"]
CMD ["gunicorn", "config.wsgi:application", "-c", "gunicorn.conf.py"]
