from dataclasses import dataclass
from typing import Optional

@dataclass(frozen=True)
class SocialUserDTO:
    """
    Social Authentication Provider로부터 받은 사용자 정보 DTO
    """
    email: str
    name: Optional[str] = None
    provider: str = 'GOOGLE'
    provider_subject: str = '' # Google's sub (unique id)

@dataclass(frozen=True)
class UserDetailDTO:
    """
    로그인/회원가입 후 반환할 사용자 상세 정보
    """
    user_id: str
    display_name: str
    email: str
    profile_completed: bool

@dataclass(frozen=True)
class AuthTokensDTO:
    """
    로그인 성공 후 반환할 JWT 토큰 DTO
    """
    access_token: str
    refresh_token: str
    user: UserDetailDTO
    expires_in: int = 900
    is_new_user: bool = False
