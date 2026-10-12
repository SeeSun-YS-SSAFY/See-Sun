"""
동의 게이트 권한

- HasRequiredConsents: 필수 동의(TOS·PRIVACY 현재 버전)가 없으면 403 CONSENT_REQUIRED
- HasSensitiveConsent: 민감정보 동의가 없으면 403 SENSITIVE_CONSENT_REQUIRED (건강정보 API)
"""
from rest_framework.permissions import BasePermission, IsAuthenticated

from apps.core.responses import CodedPermissionDenied

from . import services


class HasRequiredConsents(BasePermission):
    def has_permission(self, request, view):
        if not (request.user and request.user.is_authenticated):
            return False
        if services.missing_required(request.user):
            raise CodedPermissionDenied('CONSENT_REQUIRED', '필수 약관에 먼저 동의해 주세요.')
        return True


class HasSensitiveConsent(BasePermission):
    def has_permission(self, request, view):
        if not (request.user and request.user.is_authenticated):
            return False
        if not services.has_sensitive_consent(request.user):
            raise CodedPermissionDenied('SENSITIVE_CONSENT_REQUIRED',
                                        '건강정보 처리에 동의하면 이 기능을 쓸 수 있어요. 설정에서 동의할 수 있습니다.')
        return True


DATA_API_PERMISSIONS = [IsAuthenticated, HasRequiredConsents]
HEALTH_API_PERMISSIONS = [IsAuthenticated, HasRequiredConsents, HasSensitiveConsent]
