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

    print(f"=== 총 {users.count()}명의 유저가 조회되었습니다 ===\n")

    for user in users:
        # 토큰 생성
        refresh = RefreshToken.for_user(user)
        access_token = str(refresh.access_token)

        print("=" * 60)
        print(f"ID     : {user.id}")
        print(f"Name   : {user.name}")
        print(f"Phone  : {user.phone_number}")
        print(f"Active : {user.is_active}")
        print("-" * 60)
        print("Access Token:")
        print(access_token)
        print("=" * 60)
        print("\n")

if __name__ == "__main__":
    get_token()
