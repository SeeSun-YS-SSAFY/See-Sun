from rest_framework.views import APIView
from rest_framework import viewsets
from rest_framework.permissions import AllowAny, IsAuthenticated
from django.contrib.auth import get_user_model
from .serializers import (
    UserSignupSerializer, SignupSerializer, UserProfileSerializer,
    UserProfileCompletionSerializer, UserProfileUpdateSerializer,
    LoginSerializer
)
from rest_framework.response import Response
from rest_framework import status
from rest_framework.exceptions import ValidationError, AuthenticationFailed
from drf_spectacular.utils import extend_schema, OpenApiExample, OpenApiResponse
from rest_framework_simplejwt.tokens import RefreshToken
from .containers import Container

User = get_user_model()


class SignupView(APIView):
    """
    회원가입 API (BE_V1_AUTH_001)
    """
    permission_classes = [AllowAny]
    
    @extend_schema(
        summary="회원가입",
        description="이름, 전화번호, PIN 번호로 회원가입을 진행합니다.",
        request=SignupSerializer,
        responses={
            201: OpenApiResponse(
                response={
                    'type': 'object',
                    'properties': {
                        'user_id': {'type': 'string', 'format': 'uuid'},
                        'name': {'type': 'string'},
                        'phone_number_masked': {'type': 'string'},
                        'tts_message': {'type': 'string'}
                    }
                },
                examples=[
                    OpenApiExample(
                        'Success',
                        value={
                            'user_id': '550e8400-e29b-41d4-a716-446655440000',
                            'name': '홍길동',
                            'phone_number_masked': '010-****-1234',
                            'tts_message': '회원가입이 완료되었습니다. 로그인 화면으로 이동합니다.'
                        }
                    )
                ]
            ),
            400: OpenApiResponse(
                response={
                    'type': 'object',
                    'properties': {
                        'errors': {
                            'type': 'object',
                            'additionalProperties': {'type': 'array', 'items': {'type': 'string'}}
                        }
                    }
                },
                examples=[
                    OpenApiExample(
                        'Validation Error',
                        value={
                            'errors': {
                                'name': ['이름을 입력해주세요.'],
                                'phone_number': ['올바른 전화번호 형식이 아닙니다.'],
                                'pin_number': ['PIN 번호는 4자리 숫자여야 합니다.']
                            }
                        }
                    )
                ]
            ),
            409: OpenApiResponse(
                response={
                    'type': 'object',
                    'properties': {
                        'error': {'type': 'string'},
                        'tts_message': {'type': 'string'}
                    }
                },
                examples=[
                    OpenApiExample(
                        'Phone Number Already Exists',
                        value={
                            'error': 'PHONE_NUMBER_ALREADY_EXISTS',
                            'tts_message': '이미 가입된 전화번호입니다.'
                        }
                    )
                ]
            )
        },
        tags=['Auth']
    )
    def post(self, request):
        """회원가입 처리"""
        serializer = SignupSerializer(data=request.data)
        
        if not serializer.is_valid():
            return Response(
                {'errors': serializer.errors},
                status=status.HTTP_400_BAD_REQUEST
            )
        
        try:
            user = serializer.save()
            
            # 전화번호 마스킹 (010-****-1234)
            phone = user.phone_number
            phone_masked = f"{phone[:3]}-****-{phone[-4:]}"
            
            return Response({
                'user_id': str(user.id),
                'name': user.name,
                'phone_number_masked': phone_masked,
                'tts_message': '회원가입이 완료되었습니다. 로그인 화면으로 이동합니다.'
            }, status=status.HTTP_201_CREATED)
            
        except Exception as e:
            return Response({
                'error': 'SIGNUP_FAILED',
                'tts_message': '회원가입 중 오류가 발생했습니다. 다시 시도해 주세요.'
            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)


class LoginView(APIView):
    """
    PIN 기반 로그인 API (BE_V1_AUTH_002)
    """
    permission_classes = [AllowAny]
    
    @extend_schema(
        summary="로그인",
        description="전화번호와 PIN 번호로 로그인합니다.",
        request=LoginSerializer,
        responses={
            200: OpenApiResponse(
                response={
                    'type': 'object',
                    'properties': {
                        'access_token': {'type': 'string'},
                        'refresh_token': {'type': 'string'},
                        'expires_in': {'type': 'integer'},
                        'user': {
                            'type': 'object',
                            'properties': {
                                'user_id': {'type': 'string', 'format': 'uuid'},
                                'display_name': {'type': 'string'},
                                'profile_completed': {'type': 'boolean'}
                            }
                        },
                        'tts_message': {'type': 'string'}
                    }
                },
                examples=[
                    OpenApiExample(
                        'Success',
                        value={
                            'access_token': 'eyJ0eXAiOiJKV1QiLCJhbGc...',
                            'refresh_token': 'eyJ0eXAiOiJKV1QiLCJhbGc...',
                            'expires_in': 900,
                            'user': {
                                'user_id': '550e8400-e29b-41d4-a716-446655440000',
                                'display_name': '홍길동',
                                'profile_completed': True
                            },
                            'tts_message': '로그인이 완료되었습니다. 메인 페이지로 이동합니다.'
                        }
                    )
                ]
            ),
            401: OpenApiResponse(
                response={
                    'type': 'object',
                    'properties': {
                        'error': {'type': 'string'},
                        'tts_message': {'type': 'string'}
                    }
                },
                examples=[
                    OpenApiExample(
                        'Invalid Credentials',
                        value={
                            'error': 'INVALID_CREDENTIALS',
                            'tts_message': '로그인이 실패했습니다. 전화번호 또는 PIN 번호를 확인해주세요.'
                        }
                    )
                ]
            )
        },
        tags=['Auth']
    )
    def post(self, request):
        """로그인 처리"""
        serializer = LoginSerializer(data=request.data)
        
        if not serializer.is_valid():
            return Response(
                {'errors': serializer.errors},
                status=status.HTTP_400_BAD_REQUEST
            )
        
        phone_number = serializer.validated_data['phone_number']
        pin_number = serializer.validated_data['pin_number']
        device_hash = serializer.validated_data.get('device_hash', '')
        
        # 1. 사용자 조회
        try:
            user = User.objects.get(phone_number=phone_number)
        except User.DoesNotExist:
            return Response({
                'error': 'INVALID_CREDENTIALS',
                'tts_message': '로그인이 실패했습니다. 전화번호 또는 PIN 번호를 확인해주세요.'
            }, status=status.HTTP_401_UNAUTHORIZED)
        
        # 2. PIN 검증
        if not user.pin_hash or not check_password(pin_number, user.pin_hash):
            return Response({
                'error': 'INVALID_CREDENTIALS',
                'tts_message': '로그인이 실패했습니다. 전화번호 또는 PIN 번호를 확인해주세요.'
            }, status=status.HTTP_401_UNAUTHORIZED)
        
        # 3. JWT 토큰 발급
        refresh = RefreshToken.for_user(user)
        access_token = str(refresh.access_token)
        refresh_token = str(refresh)
        
        # 4. profile_completed 확인
        profile_completed = all([
            user.birthdate,
            user.gender,
            user.height_cm,
            user.weight_kg
        ])
        
        # 5. TTS 메시지 결정
        if profile_completed:
            tts_message = '로그인이 완료되었습니다. 메인 페이지로 이동합니다.'
        else:
            tts_message = '로그인이 완료되었습니다. 정확한 가이드를 위해 사용자 데이터 수집 화면으로 전환됩니다.'
        
        return Response({
            'access_token': access_token,
            'refresh_token': refresh_token,
            'expires_in': 900,  # 15분
            'user': {
                'user_id': str(user.id),
                'display_name': user.name or user.username,
                'profile_completed': profile_completed
            },
            'tts_message': tts_message
        }, status=status.HTTP_200_OK)


class GoogleLoginView(APIView):
    """
    Google OAuth2 로그인 API VIEW
    """
    
    @extend_schema(
        summary="Google 소셜 로그인",
        description="Google OAuth2 Code를 받아 JWT 토큰을 발급합니다. 'code'는 필수이며, 테스트 환경에 따라 'redirect_uri'를 설정할 수 있습니다.",
        request={
            'application/json': {
                'type': 'object',
                'properties': {
                    'code': {'type': 'string', 'description': 'Google Authorization Code'},
                    'device_hash': {'type': 'string', 'description': '기기 식별 해시 (선택)'},
                    'redirect_uri': {'type': 'string', 'description': 'Code 발급에 사용된 Redirect URI (기본값: postmessage)'}
                },
                'required': ['code']
            }
        },
        responses={
            200: {
                'type': 'object',
                'properties': {
                    'access_token': {'type': 'string'},
                    'refresh_token': {'type': 'string'},
                    'expires_in': {'type': 'integer'},
                    'is_new_user': {'type': 'boolean'},
                    'user': {
                        'type': 'object',
                        'properties': {
                            'user_id': {'type': 'string'},
                            'display_name': {'type': 'string'},
                            'email': {'type': 'string'},
                            'profile_completed': {'type': 'boolean'}
                        }
                    },
                    'tts_message': {'type': 'string'}
                },
                'example': {
                    "access_token": "jwt_token_example",
                    "refresh_token": "jwt_token_example",
                    "expires_in": 900,
                    "is_new_user": True,
                     "user": {
                        "user_id": "uuid-string",
                        "display_name": "홍길동",
                        "email": "user@gmail.com",
                        "profile_completed": False
                      },
                    "tts_message": "Google 로그인이 완료되었습니다."
                }
            },
            401: {
                'type': 'object',
                'properties': {
                    'error': {'type': 'string'},
                    'tts_message': {'type': 'string'}
                },
                'example': {
                    "error": "OAUTH_FAILED",
                    "tts_message": "Google 계정 인증에 실패했습니다. 다시 시도해 주세요."
                }
            }
        }
    )
    def post(self, request):
        code = request.data.get('code')
        device_hash = request.data.get('device_hash')
        redirect_uri = request.data.get('redirect_uri', 'postmessage')
        
        if not code:
            raise ValidationError({'code': 'This field is required.'})
            
        try:
            service = Container.get_social_login_service()
            tokens = service.execute(
                provider_name='GOOGLE', 
                code=code, 
                device_hash=device_hash,
                redirect_uri=redirect_uri
            )
            
            return Response({
                'access_token': tokens.access_token,
                'refresh_token': tokens.refresh_token,
                'expires_in': tokens.expires_in,
                'is_new_user': tokens.is_new_user,
                'user': {
                    'user_id': tokens.user.user_id,
                    'display_name': tokens.user.display_name,
                    'email': tokens.user.email,
                    'profile_completed': tokens.user.profile_completed
                },
                'tts_message': 'Google 로그인이 완료되었습니다.'
            }, status=status.HTTP_200_OK)
            
        except AuthenticationFailed as e:
            # 명세서에 맞춘 401 에러 응답
            return Response({
                "error": "OAUTH_FAILED",
                "tts_message": str(e.detail) if hasattr(e, 'detail') else "Google 계정 인증에 실패했습니다."
            }, status=status.HTTP_401_UNAUTHORIZED)
            
        except Exception as e:
            # 500 등 기타 에러
            raise e

# -------------------------------------------------------------

class UserProfileView(APIView):
    """
    내 프로필 조회 API
    GET /api/v1/users/profile/
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="내 프로필 조회",
        description="현재 로그인된 사용자의 프로필 정보를 조회합니다.",
        responses={200: UserProfileSerializer},
        tags=['Profile']
    )
    def get(self, request):
        serializer = UserProfileSerializer(request.user)
        return Response(serializer.data, status=status.HTTP_200_OK)

# -------------------------------------------------------------

class UserProfileCompletionView(APIView):
    """
    프로필 필수 정보 입력(완성) API
    PUT /api/v1/users/profile/completion/
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="프로필 필수 정보 입력 (완성)",
        description="회원가입 후 키, 몸무게, 성별, 생년월일 등 필수 정보를 입력하여 프로필을 완성합니다.",
        request=UserProfileCompletionSerializer,
        responses={200: UserProfileCompletionSerializer},
        tags=['Profile']
    )
    def put(self, request):
        serializer = UserProfileCompletionSerializer(request.user, data=request.data)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -------------------------------------------------------------

class UserProfileUpdateView(APIView):
    """
    프로필 정보 수정 API
    PATCH /api/v1/users/profile/edit/
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="프로필 정보 수정",
        description="프로필 정보를 부분적으로 수정합니다.",
        request=UserProfileUpdateSerializer,
        responses={200: UserProfileUpdateSerializer},
        tags=['Profile']
    )
    def patch(self, request):
        serializer = UserProfileUpdateSerializer(request.user, data=request.data, partial=True)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -------------------------------------------------------------