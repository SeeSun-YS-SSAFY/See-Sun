"""
사용자 모델 정의 모듈.

이 모듈은 서비스의 사용자(User)를 정의하고 관리한다.
Django의 AbstractUser를 확장하여 추가 프로필 정보를 포함한다.

Classes:
    User: 사용자 모델
"""
from django.contrib.auth.models import AbstractUser
from django.db import models

class User(AbstractUser):
    """
    사용자 정보를 저장하는 모델.

    Django의 AbstractUser를 상속받아 기본 인증 필드 외에
    신체 정보(키, 몸무게)와 운동 목적 등의 프로필 정보를 확장하여 저장한다.

    Attributes:
        height (float): 키 (cm)
        weight (float): 몸무게 (kg)
        exercise_purpose (str): 운동 목적
        gender (str): 성별 (M/F)
        birth_year (int): 생년 (YYYY)
    """
    
    first_name = None
    last_name = None
    
    # Profile Fields
    height = models.FloatField(null=True, blank=True, verbose_name='키')
    weight = models.FloatField(null=True, blank=True, verbose_name='몸무게')
    exercise_purpose = models.CharField(
        max_length=50, 
        null=True, 
        blank=True, 
        verbose_name='운동 목적'
    )
    gender = models.CharField(
        max_length=10, 
        choices=[('M', '남성'), ('F', '여성')], 
        null=True, 
        blank=True, 
        verbose_name='성별'
    )
    birth_year = models.IntegerField(null=True, blank=True, verbose_name='생년')

    class Meta:
        db_table = 'users'
        verbose_name = '사용자'
        verbose_name_plural = '사용자 목록'

    def __str__(self):
        """
        사용자 모델의 문자열 표현을 반환한다.

        Returns:
            str: 사용자명 (username)
        """
        return self.username

