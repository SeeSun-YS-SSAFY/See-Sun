from urllib.parse import unquote
import requests
from django.conf import settings
from rest_framework.exceptions import AuthenticationFailed
from .interfaces import ISocialAuthProvider
from .dtos import SocialUserDTO

class GoogleAuthProvider(ISocialAuthProvider):
    """
    Google OAuth2 통신 구현체
    """
    
    def exchange_code_for_token(self, code: str, redirect_uri: str = 'postmessage') -> str:
        # settings에 TOKEN_URI가 없다면 기본값 사용
        token_uri = getattr(settings, 'GOOGLE_TOKEN_URI', 'https://oauth2.googleapis.com/token')
        
        # URL Encoded된 코드가 들어올 경우를 대비해 디코딩 (%2F -> /)
        decoded_code = unquote(code)
        
        data = {
            'client_id': settings.GOOGLE_CLIENT_ID,
            'client_secret': settings.GOOGLE_CLIENT_SECRET,
            'code': decoded_code,
            'grant_type': 'authorization_code',
            'redirect_uri': redirect_uri,
        }
        
        response = requests.post(token_uri, data=data)
        
        if not response.ok:
            # 디버깅을 위해 에러 내용 로깅 필요 (실제 운영 시에는 logger 사용)
            error_details = response.json()
            print(f"Google Token Exchange Error: {error_details}")
            raise AuthenticationFailed(f"Google server could not validate the code. Details: {error_details.get('error_description')}")
            
        return response.json().get('access_token')
    
    def get_user_info(self, access_token: str) -> SocialUserDTO:
        user_info_uri = getattr(settings, 'GOOGLE_USER_INFO_URI', 'https://www.googleapis.com/oauth2/v3/userinfo')
        
        response = requests.get(
            user_info_uri,
            headers={'Authorization': f'Bearer {access_token}'}
        )
        
        if not response.ok:
            raise AuthenticationFailed('Failed to get user info from Google.')
            
        data = response.json()
        
        return SocialUserDTO(
            email=data.get('email'),
            name=data.get('name'),
            provider='GOOGLE',
            provider_subject=data.get('sub')
        )
