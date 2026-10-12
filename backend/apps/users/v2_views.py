"""
2차(Android) 인증·내 정보 API

- POST /api/v1/auth/firebase/ : Firebase ID 토큰 → 사용자 찾기/만들기 → 앱 JWT
- GET/PATCH/DELETE /api/v1/me/ : 내 정보·수정(목표 칼로리 재계산)·탈퇴(전체 파기)
"""
import logging

from django.db import IntegrityError, transaction
from drf_spectacular.utils import extend_schema
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import AnonRateThrottle
from rest_framework.views import APIView
from rest_framework_simplejwt.tokens import RefreshToken

from apps.core import crypto
from apps.core.responses import error_response, validation_error

from . import firebase, profile
from .models import User
from .serializers import UserDeleteSerializer
from .v2_serializers import (
    FirebaseExchangeResponseSerializer, FirebaseExchangeSerializer, MeSerializer, MeUpdateSerializer,
)

logger = logging.getLogger(__name__)


class FirebaseRateThrottle(AnonRateThrottle):
    scope = 'firebase'


def onboarding_state(user):
    from apps.consents.services import has_sensitive_consent, missing_required
    return {
        'profile_completed': user.is_profile_completed,
        'consents_required': missing_required(user),
        'sensitive_consent': has_sensitive_consent(user),
    }


class FirebaseAuthView(APIView):
    permission_classes = [AllowAny]
    authentication_classes = []
    throttle_classes = [FirebaseRateThrottle]

    @extend_schema(
        summary='Firebase 전화번호 인증 → 앱 JWT', tags=['Auth'],
        request=FirebaseExchangeSerializer, responses={200: FirebaseExchangeResponseSerializer},
        description='오류: 400 VALIDATION_ERROR/BIRTHDATE_REQUIRED/PHONE_REQUIRED · 401 INVALID_ID_TOKEN · '
                    '403 AGE_RESTRICTED/ACCOUNT_DISABLED · 503 AUTH_NOT_CONFIGURED',
    )
    def post(self, request):
        serializer = FirebaseExchangeSerializer(data=request.data)
        if not serializer.is_valid():
            return validation_error(serializer.errors)
        data = serializer.validated_data
        try:
            identity = firebase.get_verifier().verify(data['id_token'])
        except firebase.FirebaseNotConfigured:
            return error_response('AUTH_NOT_CONFIGURED', '지금은 로그인할 수 없어요. 잠시 후 다시 시도해 주세요.', 503)
        except firebase.InvalidIdToken:
            return error_response('INVALID_ID_TOKEN', '전화번호 인증이 만료되었어요. 다시 인증해 주세요.', 401)

        phone = crypto.normalize_phone(identity.phone_number)
        user = User.objects.filter(firebase_uid=identity.uid).first()
        if user is None and phone:
            # 1차(PIN) 가입자 또는 기기 변경: 같은 전화번호 계정에 Firebase UID를 연결
            user = User.objects.filter(phone_number=phone).first()
        if user is not None and (not user.is_active or user.is_deleted):
            return error_response('ACCOUNT_DISABLED', '이용이 중지된 계정이에요.', 403)

        is_new = user is None
        if is_new:
            if not phone:
                return error_response('PHONE_REQUIRED', '전화번호 인증이 필요해요.', 400)
            birthdate = data.get('birthdate')
            if birthdate is None:
                return error_response('BIRTHDATE_REQUIRED', '생년월일을 먼저 입력해 주세요.', 400,
                                      errors={'birthdate': ['신규 가입에는 생년월일이 필요합니다.']})
            if birthdate > profile.local_today() or profile.is_under_min_age(birthdate):
                # 만 14세 미만: 가입 차단, 입력값은 저장하지 않는다(05 문서 §4 1번)
                return error_response('AGE_RESTRICTED', '만 14세 이상만 가입할 수 있어요.', 403)
            try:
                with transaction.atomic():
                    user = User(username=f'fb_{identity.uid}'[:150], firebase_uid=identity.uid,
                                phone_number=phone, birthdate=birthdate, name=data.get('name'))
                    user.set_unusable_password()
                    user.save()
            except IntegrityError:
                user = User.objects.get(firebase_uid=identity.uid)
                is_new = False
        else:
            changed = []
            if user.firebase_uid != identity.uid:
                user.firebase_uid = identity.uid
                changed.append('firebase_uid')
            if phone and user.phone_number != phone:
                user.phone_number = phone
                changed.append('phone_number')
            if data.get('name') and not user.name:
                user.name = data['name']
                changed.append('name')
            if changed:
                user.save(update_fields=changed)

        refresh = RefreshToken.for_user(user)
        return Response({
            'access_token': str(refresh.access_token),
            'refresh_token': str(refresh),
            'expires_in': 900,
            'is_new_user': is_new,
            'user': MeSerializer(user).data,
            'onboarding': onboarding_state(user),
            'tts_message': '전화번호 인증이 완료되었습니다.' if is_new else '로그인되었습니다.',
        })


class MeView(APIView):
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='내 정보', tags=['Me'], responses=MeSerializer)
    def get(self, request):
        return Response(MeSerializer(request.user).data)

    @extend_schema(summary='내 정보 수정', tags=['Me'], request=MeUpdateSerializer, responses=MeSerializer,
                   description='키·몸무게·성별·생년월일·목표가 바뀌면 목표 칼로리를 다시 계산합니다.')
    def patch(self, request):
        serializer = MeUpdateSerializer(request.user, data=request.data, partial=True)
        if not serializer.is_valid():
            codes = serializer.errors.get('birthdate', [])
            if any(getattr(c, 'code', '') == 'age_restricted' for c in codes):
                return error_response('AGE_RESTRICTED', '만 14세 이상만 이용할 수 있어요.', 403)
            return validation_error(serializer.errors)
        user = serializer.save()
        body = MeSerializer(user).data
        body['tts_message'] = '정보를 저장했어요.'
        return Response(body)

    @extend_schema(summary='회원 탈퇴(전체 파기)', tags=['Me'], request=UserDeleteSerializer, responses={200: None})
    def delete(self, request):
        serializer = UserDeleteSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('CONFIRMATION_REQUIRED', '탈퇴하려면 한 번 더 확인해 주세요.', 400,
                                  errors=serializer.errors)
        request.user.withdraw()
        return Response({'message': '회원탈퇴가 완료되었습니다.',
                         'tts_message': '회원 탈퇴가 완료되었어요. 그동안 시선을 이용해 주셔서 감사합니다.'},
                        status=status.HTTP_200_OK)
