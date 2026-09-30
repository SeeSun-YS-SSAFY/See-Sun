# syntax=docker/dockerfile:1
# 멀티 스테이지: deps → (dev | builder → runner)
#   - 운영: docker build -t seesun-frontend ./frontend   (기본 타겟 = runner)
#   - 개발: docker-compose.yml 에서 target: dev 사용 (next dev + 소스 볼륨 마운트)

# ---- 의존성 설치 ----
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci

# ---- 개발 서버 (docker-compose 용) ----
FROM deps AS dev
COPY . .
EXPOSE 3000
CMD ["npm", "run", "dev"]

# ---- 운영 빌드 ----
FROM deps AS builder
# NEXT_PUBLIC_* 값은 빌드 시점에 번들에 인라인되므로 build-arg 로 전달
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_API_MEDIA_URL
ARG NEXT_PUBLIC_API_CLIENT_ID
ARG NEXT_PUBLIC_GOOGLE_REDIRECT_URI
ARG NEXT_PUBLIC_API_MOCKING=disabled
# /media 프록시 대상 (next.config.js rewrites — 빌드 시 routes-manifest 에 고정됨)
ARG BACKEND_INTERNAL_URL=http://backend:8000
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL \
    NEXT_PUBLIC_API_MEDIA_URL=$NEXT_PUBLIC_API_MEDIA_URL \
    NEXT_PUBLIC_API_CLIENT_ID=$NEXT_PUBLIC_API_CLIENT_ID \
    NEXT_PUBLIC_GOOGLE_REDIRECT_URI=$NEXT_PUBLIC_GOOGLE_REDIRECT_URI \
    NEXT_PUBLIC_API_MOCKING=$NEXT_PUBLIC_API_MOCKING \
    BACKEND_INTERNAL_URL=$BACKEND_INTERNAL_URL \
    NEXT_TELEMETRY_DISABLED=1
COPY . .
RUN npm run build && npm prune --omit=dev

# ---- 운영 실행 ----
FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000
COPY --from=builder --chown=node:node /app/package*.json ./
COPY --from=builder --chown=node:node /app/node_modules ./node_modules
COPY --from=builder --chown=node:node /app/.next ./.next
COPY --from=builder --chown=node:node /app/public ./public
COPY --from=builder --chown=node:node /app/next.config.js ./next.config.js
USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
