from rest_framework.views import APIView
from rest_framework import viewsets
from rest_framework.permissions import AllowAny, IsAuthenticated
from django.contrib.auth import get_user_model
from .serializers import UserSignupSerializer, SignupSerializer
from rest_framework.response import Response
from rest_framework import status
from rest_framework.exceptions import ValidationError, AuthenticationFailed
from drf_spectacular.utils import extend_schema, OpenApiExample, OpenApiResponse
from .containers import Container


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

class UserViewSet(viewsets.ModelViewSet):
    """
    사용자 ViewSet
    """
    queryset = get_user_model().objects.all()
    serializer_class = UserSignupSerializer
    permission_classes = [IsAuthenticated] # 기본적으로 인증 필요
