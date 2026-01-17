"""
사용자 관련 데이터 시리얼라이저 모듈.
"""
from rest_framework import serializers
from django.contrib.auth import get_user_model
from django.contrib.auth.hashers import make_password
import re

User = get_user_model()

class SignupSerializer(serializers.Serializer):
    """
    PIN 기반 회원가입 시리얼라이저 (BE_V1_AUTH_001)
    """
    name = serializers.CharField(
        max_length=255,
        required=True,
        error_messages={'required': '이름을 입력해주세요.'}
    )
    phone_number = serializers.CharField(
        max_length=20,
        required=True,
        error_messages={'required': '전화번호를 입력해주세요.'}
    )
    pin_number = serializers.CharField(
        max_length=4,
        min_length=4,
        write_only=True,
        required=True,
        error_messages={
            'required': 'PIN 번호를 입력해주세요.',
            'min_length': 'PIN 번호는 4자리 숫자여야 합니다.',
            'max_length': 'PIN 번호는 4자리 숫자여야 합니다.'
        }
    )
    
    def validate_name(self, value):
        """이름 유효성 검사"""
        if not value or not value.strip():
            raise serializers.ValidationError('이름을 입력해주세요.')
        return value.strip()
    
    def validate_phone_number(self, value):
        """전화번호 유효성 검사"""
        # 숫자만 허용 (하이픈 제거)
        phone = re.sub(r'[^0-9]', '', value)
        
        if len(phone) != 11:
            raise serializers.ValidationError('올바른 전화번호 형식이 아닙니다.')
        
        # 중복 검사
        if User.objects.filter(phone_number=phone).exists():
            raise serializers.ValidationError('이미 가입된 전화번호입니다.')
        
        return phone
    
    def validate_pin_number(self, value):
        """PIN 번호 유효성 검사"""
        if not value.isdigit():
            raise serializers.ValidationError('PIN 번호는 4자리 숫자여야 합니다.')
        return value
    
    def create(self, validated_data):
        """회원가입 처리"""
        phone_number = validated_data['phone_number']
        name = validated_data['name']
        pin_number = validated_data['pin_number']
        
        # PIN 해싱
        pin_hash = make_password(pin_number)
        
        # 사용자 생성
        user = User.objects.create(
            username=phone_number,  # username을 phone_number로 사용
            phone_number=phone_number,
            name=name,
            pin_hash=pin_hash
        )
        
        return user

# -------------------------------------------------------------

class LoginSerializer(serializers.Serializer):
    """
    PIN 기반 로그인 시리얼라이저 (BE_V1_AUTH_002)
    """
    phone_number = serializers.CharField(
        max_length=20,
        required=True,
        error_messages={'required': '전화번호를 입력해주세요.'}
    )
    pin_number = serializers.CharField(
        max_length=4,
        min_length=4,
        write_only=True,
        required=True,
        error_messages={
            'required': 'PIN 번호를 입력해주세요.',
            'min_length': 'PIN 번호는 4자리여야 합니다.',
            'max_length': 'PIN 번호는 4자리여야 합니다.'
        }
    )
    device_hash = serializers.CharField(
        max_length=255,
        required=False,
        allow_blank=True
    )
    
    def validate_phone_number(self, value):
        """전화번호 정규화"""
        phone = re.sub(r'[^0-9]', '', value)
        return phone
    
    def validate_pin_number(self, value):
        """PIN 번호 검증"""
        if not value.isdigit():
            raise serializers.ValidationError('PIN 번호는 숫자만 가능합니다.')
        return value


class LogoutSerializer(serializers.Serializer):
    """
    로그아웃 시리얼라이저 (BE_V1_AUTH_005)
    """
    refresh_token = serializers.CharField(
        required=True,
        error_messages={'required': 'Refresh Token이 필요합니다.'}
    )
    
    def validate_refresh_token(self, value):
        """Refresh Token 유효성 검사"""
        if not value or not value.strip():
            raise serializers.ValidationError('Refresh Token이 필요합니다.')
        return value


class UserDeleteSerializer(serializers.Serializer):
    """
    회원탈퇴 시리얼라이저 (BE_V1_AUTH_006)
    """
    confirmation = serializers.BooleanField(
        required=True,
        error_messages={'required': '탈퇴 확인이 필요합니다.'}
    )
    
    def validate_confirmation(self, value):
        """탈퇴 확인 검증"""
        if not value:
            raise serializers.ValidationError('탈퇴를 진행하려면 확인이 필요합니다.')
        return value


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

# ----------------------------------------------------------------------
# 사용자 프로필 조회 시리얼라이저

class UserProfileSerializer(serializers.ModelSerializer):
    """
    사용자 프로필 조회 시리얼라이저.
    민감한 정보(비밀번호 등)를 제외한 사용자 정보를 반환한다.
    """
    class Meta:
        model = User
        fields = (
            'id', 'username', 'name', 'phone_number',
            'birthdate', 'gender', 'height_cm', 'weight_kg',
            'is_profile_completed'
        )
        read_only_fields = fields

# -------------------------------------------------------------

class UserProfileCompletionSerializer(serializers.ModelSerializer):
    """
    프로필 필수 정보 입력(완성) 시리얼라이저.
    회원가입 후 초기 프로필 설정 시 사용된다.
    """
    class Meta:
        model = User
        fields = ('height_cm', 'weight_kg', 'gender', 'birthdate')
        extra_kwargs = {
            'height_cm': {'required': True},
            'weight_kg': {'required': True},
            'gender': {'required': True},
            'birthdate': {'required': True},
        }

    def update(self, instance, validated_data):
        instance = super().update(instance, validated_data)
        instance.is_profile_completed = True
        instance.save()
        return instance

# -------------------------------------------------------------

class UserProfileUpdateSerializer(serializers.ModelSerializer):
    """
    프로필 정보 수정 시리얼라이저.
    원하는 필드만 선택적으로 수정할 수 있다.
    """
    class Meta:
        model = User
        fields = ('name', 'height_cm', 'weight_kg', 'gender', 'birthdate')
        extra_kwargs = {
            'name': {'required': False},
            'height_cm': {'required': False},
            'weight_kg': {'required': False},
            'gender': {'required': False},
            'birthdate': {'required': False},
        }

# -------------------------------------------------------------