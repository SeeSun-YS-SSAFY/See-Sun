"""
사용자 관련 데이터 시리얼라이저 모듈.
"""
from rest_framework import serializers
from django.contrib.auth import get_user_model

User = get_user_model()

class UserSignupSerializer(serializers.ModelSerializer):
    """
    회원가입 요청 데이터를 처리하는 시리얼라이저.
    """
    password = serializers.CharField(write_only=True)

    class Meta:
        model = User
        fields = (
            'id', 'username', 'password', 'name', 'phone_number',
            'height_cm', 'weight_kg', 'gender', 'birthdate'
        )
        extra_kwargs = {
            'password': {'write_only': True},
            'phone_number': {'required': True}
        }

    def create(self, validated_data):
        """
        검증된 데이터로 사용자를 생성한다.
        """
        user = User.objects.create_user(
            username=validated_data['username'],
            password=validated_data['password'],
            name=validated_data.get('name'),
            phone_number=validated_data.get('phone_number'),
            height_cm=validated_data.get('height_cm'),
            weight_kg=validated_data.get('weight_kg'),
            gender=validated_data.get('gender'),
            birthdate=validated_data.get('birthdate')
        )
        return user
