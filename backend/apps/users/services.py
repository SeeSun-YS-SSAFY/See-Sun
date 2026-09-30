from typing import Optional
from .interfaces import ISocialAuthProvider, IUserRepository
from .dtos import AuthTokensDTO

class SocialLoginUseCase:
    """
    소셜 로그인 비즈니스 로직을 처리하는 유스케이스
    """
    
    def __init__(self, auth_provider: ISocialAuthProvider, repository: IUserRepository):
        self.auth_provider = auth_provider
        self.repository = repository
        
    def execute(self, provider_name: str, code: str, device_hash: Optional[str] = None, redirect_uri: str = 'postmessage') -> AuthTokensDTO:
        # 1. Exchange Code for Token
        provider_access_token = self.auth_provider.exchange_code_for_token(code=code, redirect_uri=redirect_uri)
        
        # 2. Get User Info from Provider
        social_user_dto = self.auth_provider.get_user_info(provider_access_token)
        
        # 3. Check if user exists
        user = self.repository.get_by_social_id(
            provider=social_user_dto.provider,
            provider_subject=social_user_dto.provider_subject
        )
        
        is_new_user = False
        if not user:
            # 4. Create User if not exists
            user = self.repository.create_social_user(social_user_dto)
            is_new_user = True
            
        # 5. Generate JWT Tokens
        tokens = self.repository.generate_tokens(user)
        
        # 새로운 객체를 생성하여 is_new_user 업데이트 반환 (frozen=True 이므로 copy & replace 대신 새로 생성)
        return AuthTokensDTO(
            access_token=tokens.access_token,
            refresh_token=tokens.refresh_token,
            user=tokens.user,
            is_new_user=is_new_user
        )
