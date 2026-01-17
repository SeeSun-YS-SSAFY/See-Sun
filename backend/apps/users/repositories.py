from django.contrib.auth import get_user_model
from rest_framework_simplejwt.tokens import RefreshToken
from .interfaces import IUserRepository
from .dtos import SocialUserDTO, AuthTokensDTO, UserDetailDTO
from .models import UserAuthProvider

User = get_user_model()

class DjangoUserRepository(IUserRepository):
    """
    Django ORM을 사용하는 User Repository 구현체
    """
    
    def get_by_social_id(self, provider: str, provider_subject: str) -> object:
        try:
            auth_provider = UserAuthProvider.objects.get(
                provider=provider,
                provider_subject=provider_subject
            )
            return auth_provider.user
        except UserAuthProvider.DoesNotExist:
            return None
            
    def create_social_user(self, social_user: SocialUserDTO) -> object:
        user = None
        if social_user.email:
            user = User.objects.filter(email=social_user.email).first()
            
        if not user:
            username = social_user.email
            if not username:
               import uuid
               username = f"user_{uuid.uuid4()}"

            user = User.objects.create(
                username=username,
                email=social_user.email,
                name=social_user.name
            )
            
        UserAuthProvider.objects.create(
            user=user,
            provider=social_user.provider,
            provider_subject=social_user.provider_subject,
            provider_email=social_user.email
        )
        
        return user

    def generate_tokens(self, user: object) -> AuthTokensDTO:
        refresh = RefreshToken.for_user(user)
        
        user_dto = UserDetailDTO(
            user_id=str(user.id),
            display_name=user.name if user.name else "",
            email=user.email,
            profile_completed=user.is_profile_completed
        )
        
        return AuthTokensDTO(
            access_token=str(refresh.access_token),
            refresh_token=str(refresh),
            user=user_dto
        )
