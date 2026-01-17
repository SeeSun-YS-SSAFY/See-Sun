"""
사용자 권한 관련 Permission Classes
"""
from rest_framework.permissions import BasePermission
from rest_framework.exceptions import PermissionDenied


class IsProfileCompleted(BasePermission):
    """
    프로필이 완성된 사용자만 접근 가능
    
    Usage:
        class WorkoutView(APIView):
            permission_classes = [IsAuthenticated, IsProfileCompleted]
    
    프로필 미완성 시 403 에러와 함께 리다이렉트 정보 반환
    """
    
    def has_permission(self, request, view):
        """
        프로필 완성 여부 확인
        
        Returns:
            bool: 프로필이 완성되었으면 True, 아니면 False
        
        Raises:
            PermissionDenied: 프로필이 완성되지 않은 경우
        """
        # 인증되지 않은 사용자는 IsAuthenticated에서 처리
        if not request.user.is_authenticated:
            return False
        
        # 프로필 관련 API는 항상 허용 (예외 경로)
        exempt_paths = [
            '/api/v1/users/profile/',
            '/api/v1/users/profile/completion/',
            '/api/v1/users/profile/edit/',
            '/api/v1/users/auth/',  # 인증 관련 API
        ]
        
        if any(request.path.startswith(path) for path in exempt_paths):
            return True
        
        # 프로필 완성 여부 확인
        if not request.user.is_profile_completed:
            raise PermissionDenied({
                'error': 'PROFILE_INCOMPLETE',
                'message': '프로필을 먼저 완성해주세요.',
                'tts_message': '정확한 가이드를 위해 추가 정보를 입력해야 합니다. 입력 화면으로 이동합니다.',
                'redirect_url': '/profile/completion'
            })
        
        return True
