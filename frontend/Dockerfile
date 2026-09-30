# Node.js 20 Alpine 이미지 사용
FROM node:20-alpine

WORKDIR /app

# 패키지 파일 복사 및 의존성 설치
COPY package*.json ./
RUN npm ci

# 소스 코드 복사
COPY . .

# 포트 노출
EXPOSE 3000

# Next.js 개발 서버 실행
CMD ["npm", "run", "dev"]
