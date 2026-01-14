"""
사용자 관련 데이터 시리얼라이저 모듈.

이 모듈은 사용자 가입, 프로필 수정 등 사용자 관련 데이터의 
변환 및 검증을 담당하는 시리얼라이저를 정의한다.

Classes:
    UserSignupSerializer: 회원가입용 시리얼라이저
"""
from rest_framework import serializers
from django.contrib.auth import get_user_model

User = get_user_model()

class UserSignupSerializer(serializers.ModelSerializer):
    """
    회원가입 요청 데이터를 처리하는 시리얼라이저.

    사용자명, 이메일, 비밀번호 및 프로필 정보를 받아 유효성을 검증하고
    새로운 사용자 인스턴스를 생성한다.

    Attributes:
        password: 쓰기 전용 비밀번호 필드

    Methods:
        create: 검증된 데이터로 사용자 생성
    """
    password = serializers.CharField(write_only=True)

    class Meta:
        model = User
        fields = (
            'id', 'username', 'password', 'email', 
            'height', 'weight', 'exercise_purpose', 
            'gender', 'birth_year'
        )
        extra_kwargs = {
            'password': {'write_only': True},
            'email': {'required': True}
        }

    def create(self, validated_data):
        """
        검증된 데이터로 사용자를 생성한다.

        Args:
            validated_data (dict): 유효성 검증을 통과한 데이터

        Returns:
            User: 생성된 사용자 인스턴스
        """
        user = User.objects.create_user(
            username=validated_data['username'],
            email=validated_data['email'],
            password=validated_data['password']
        )
        return user
