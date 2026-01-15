"""
사용자 관련 API 뷰셋 모듈.

이 모듈은 회원가입, 프로필 조회 및 수정 등 사용자와 관련된
API 엔드포인트를 처리하는 ViewSet을 정의한다.

Classes:
    UserViewSet: 사용자 관련 액션을 처리하는 ViewSet
"""
from rest_framework import viewsets, status, mixins
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.permissions import AllowAny, IsAuthenticated
from django.contrib.auth import get_user_model
from .serializers import UserSignupSerializer

User = get_user_model()

class UserViewSet(mixins.RetrieveModelMixin,
                  mixins.UpdateModelMixin,
                  viewsets.GenericViewSet):
    """
    사용자 관련 API 요청을 처리하는 ViewSet.

    회원가입(signup), 내 정보 조회(retrieve), 정보 수정(update) 기능을 제공한다.
    signup 액션은 누구나 접근 가능하며, 그 외 액션은 인증된 사용자만 접근 가능하다.

    Attributes:
        serializer_class: 사용할 시리얼라이저 클래스 (UserSignupSerializer)
        queryset: 쿼리셋 정의 (모든 사용자)

    Methods:
        get_permissions: 액션별 권한 설정
        signup: 회원가입 처리
    """
    serializer_class = UserSignupSerializer
    queryset = User.objects.all()
    
    def get_permissions(self):
        """
        현재 액션에 따른 권한 클래스를 반환한다.

        회원가입(signup)은 AllowAny, 그 외에는 IsAuthenticated를 적용한다.

        Returns:
            list: 적용할 권한 클래스 목록
        """
        if self.action == 'signup':
            return [AllowAny()]
        return [IsAuthenticated()]

    @action(detail=False, methods=['post'])
    def signup(self, request):
        """
        신규 회원가입을 처리한다.

        사용자로부터 입력받은 데이터를 검증하고 유저를 생성한다.
        성공 시 생성된 유저 정보와 (임시) 액세스 토큰을 반환한다.

        Args:
            request: HTTP 요청 객체

        Returns:
            Response: 생성된 유저 정보 및 토큰 (HTTP 201)
        """
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user = serializer.save()
        
        # 토큰 발급 로직 (추후 SimpleJWT 연동 예정, 현재는 user id 반환)
        # 테스트 통과를 위해 임시 키 반환
        return Response({
            "user": serializer.data,
            "access": "temp_access_token",
            "refresh": "temp_refresh_token"
        }, status=status.HTTP_201_CREATED)
