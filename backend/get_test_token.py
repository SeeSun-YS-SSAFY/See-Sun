import os
import django

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
django.setup()

from django.contrib.auth import get_user_model
from rest_framework_simplejwt.tokens import RefreshToken

User = get_user_model()

def get_token():
    # 전체 유저 조회
    users = User.objects.all()
    
    if not users.exists():
        print("생성된 유저가 없습니다.")
        return

    print("=== 등록된 유저 목록 ===")
    for u in users:
        print(f"ID: {u.id}, Name: {u.name}, Phone: {u.phone_number}, Completed: {u.is_active}")
    print("========================")

    # 첫 번째 유저로 토큰 생성 (원하는 유저가 있다면 여기서 필터링 수정)
    target_user = users.first()
    print(f"\n선택된 유저: {target_user.name} ({target_user.phone_number})")

    # 토큰 생성
    refresh = RefreshToken.for_user(target_user)
    access_token = str(refresh.access_token)
    
    print("-" * 50)
    print("Access Token:")
    print(access_token)
    print("-" * 50)
    
    return access_token

if __name__ == "__main__":
    get_token()
