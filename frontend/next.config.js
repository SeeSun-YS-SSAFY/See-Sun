// @ts-check

/** @type {import('next').NextConfig} */
const nextConfig = {
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "dummyimage.com",
      },
      {
        protocol: 'http',
        hostname: '127.0.0.1',
        port: '8000',
        pathname: '/media/**', // 특정 경로만 허용할 경우
      },
    ],
    unoptimized: true
  },
  async rewrites() {
    return [
      {
        source: '/media/:path*',
        // 서버 측 프록시 대상 (docker-compose 서비스명 기본값)
        destination: `${process.env.BACKEND_INTERNAL_URL || 'http://backend:8000'}/media/:path*`,
      },
    ];
  },
};

export default nextConfig;
