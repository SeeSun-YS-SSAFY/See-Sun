from .interfaces import ISocialAuthProvider, IUserRepository
from .infrastructure import GoogleAuthProvider
from .repositories import DjangoUserRepository
from .services import SocialLoginUseCase

class Container:
    """
    Dependency Injection Container
    """
    
    @staticmethod
    def get_social_auth_provider() -> ISocialAuthProvider:
        return GoogleAuthProvider()
        
    @staticmethod
    def get_user_repository() -> IUserRepository:
        return DjangoUserRepository()
        
    @staticmethod
    def get_social_login_service() -> SocialLoginUseCase:
        return SocialLoginUseCase(
            auth_provider=Container.get_social_auth_provider(),
            repository=Container.get_user_repository()
        )
