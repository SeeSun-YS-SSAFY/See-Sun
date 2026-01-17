from abc import ABC, abstractmethod
from typing import Optional
from .dtos import SocialUserDTO, AuthTokensDTO

class ISocialAuthProvider(ABC):
    """
    소셜 로그인 제공자(Google 등)와의 통신을 담당하는 인터페이스
    """
    
    @abstractmethod
    def exchange_code_for_token(self, code: str, redirect_uri: str = 'postmessage') -> str:
        """
        Authorization Code를 Access Token으로 교환합니다.
        
        Args:
            code (str): 클라이언트로부터 받은 Authorization Code
            redirect_uri (str): Code 발급 시 사용한 Redirect URI (기본값: postmessage)
            
        Returns:
            str: Provider Access Token
        """
        pass
    
    @abstractmethod
    def get_user_info(self, access_token: str) -> SocialUserDTO:
        """
        Access Token으로 사용자 정보를 조회합니다.
        
        Args:
            access_token (str): Provider Access Token
            
        Returns:
            SocialUserDTO: 사용자 정보
        """
        pass


class IUserRepository(ABC):
    """
    사용자 데이터 저장소(DB) 접근을 담당하는 인터페이스
    """
    
    @abstractmethod
    def get_by_social_id(self, provider: str, provider_subject: str) -> Optional[object]:
        pass
        
    @abstractmethod
    def create_social_user(self, social_user: SocialUserDTO) -> object:
        pass

    @abstractmethod
    def generate_tokens(self, user: object) -> AuthTokensDTO:
        pass
