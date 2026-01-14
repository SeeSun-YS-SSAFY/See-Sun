"""
운동 관련 데이터 모델 정의 모듈.

이 모듈은 운동 카테고리, 개별 운동, 운동 단계 및 사용자의 운동 기록 로그를 정의한다.

Classes:
    ExerciseCategory: 운동 카테고리 (근력, 유산소 등)
    Exercise: 개별 운동 정보
    ExerciseStep: 운동별 진행 단계 (TTS/VUI)
    ExerciseLog: 사용자 운동 수행 기록
"""
from django.db import models

class ExerciseCategory(models.Model):
    """
    운동의 대분류를 관리하는 모델.

    Attributes:
        code (str): 카테고리 고유 코드 (예: STR, CRD)
        name (str): 카테고리 표시 이름 (예: 근력 운동, 유산소)
    """
    code = models.CharField(max_length=20, unique=True, verbose_name="카테고리 코드")
    name = models.CharField(max_length=50, verbose_name="카테고리명")
    
    class Meta:
        db_table = 'exercise_categories'
        verbose_name = '운동 카테고리'
        verbose_name_plural = '운동 카테고리 목록'

    def __str__(self):
        """
        카테고리의 문자열 표현을 반환한다.

        Returns:
            str: 카테고리 이름
        """
        return self.name

class Exercise(models.Model):
    """
    개별 운동 정보를 담는 모델.

    운동의 이름, 난이도, 설명 및 소속 카테고리 정보를 저장한다.
    
    Attributes:
        category: 소속된 운동 카테고리 (ForeignKey)
        name (str): 운동 이름
        difficulty (str): 운동 난이도 (Easy/Medium/Hard)
        description (str): 운동에 대한 상세 설명
        created_at (datetime): 생성 일시
        updated_at (datetime): 수정 일시
    """
    DIFFICULTY_CHOICES = [
        ('Easy', '쉬움'),
        ('Medium', '보통'),
        ('Hard', '어려움'),
    ]

    category = models.ForeignKey(
        ExerciseCategory, 
        on_delete=models.CASCADE, 
        related_name='exercises',
        verbose_name="카테고리"
    )
    name = models.CharField(max_length=100, verbose_name="운동명")
    difficulty = models.CharField(
        max_length=10, 
        choices=DIFFICULTY_CHOICES, 
        verbose_name="난이도"
    )
    description = models.TextField(blank=True, verbose_name="설명")
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")
    updated_at = models.DateTimeField(auto_now=True, verbose_name="수정일")

    class Meta:
        db_table = 'exercises'
        verbose_name = '운동'
        verbose_name_plural = '운동 목록'

    def __str__(self):
        """
        운동 모델의 문자열 표현을 반환한다.

        Returns:
            str: 운동 이름
        """
        return self.name

class ExerciseStep(models.Model):
    """
    운동의 진행 단계를 정의하는 모델.

    각 운동을 구성하는 세부 동작이나 가이드를 순서대로 저장한다.
    
    Attributes:
        exercise: 해당 단계가 속한 운동 (ForeignKey)
        sequence (int): 단계 순서
        script (str): 음성 안내 스크립트
        type (str): 가이드 타입 (TTS/VUI)
    """
    TYPE_CHOICES = [
        ('TTS', '음성 가이드'),
        ('VUI', 'VUI 가이드'),
    ]

    exercise = models.ForeignKey(
        Exercise, 
        on_delete=models.CASCADE, 
        related_name='steps',
        verbose_name="운동"
    )
    sequence = models.IntegerField(verbose_name="순서")
    script = models.TextField(verbose_name="스크립트 (TTS)")
    type = models.CharField(
        max_length=10, 
        choices=TYPE_CHOICES, 
        default='TTS',
        verbose_name="가이드 타입"
    )

    class Meta:
        db_table = 'exercise_steps'
        verbose_name = '운동 단계'
        verbose_name_plural = '운동 단계 목록'
        ordering = ['sequence']

    def __str__(self):
        """
        운동 단계의 문자열 표현을 반환한다.

        Returns:
            str: {운동명} - Step {순서} 형식의 문자열
        """
        return f"{self.exercise.name} - Step {self.sequence}"

class ExerciseLog(models.Model):
    """
    사용자의 운동 수행 기록을 저장하는 모델.

    누가, 언제, 어떤 운동을, 얼마 동안, 몇 회 수행했는지 기록한다.
    
    Attributes:
        user: 운동을 수행한 사용자 (ForeignKey)
        exercise: 수행한 운동 (ForeignKey)
        start_time (datetime): 운동 시작 시간
        end_time (datetime): 운동 종료 시간
        duration (int): 운동 소요 시간 (초 단위)
        count (int): 운동 수행 횟수
        created_at (datetime): 기록 생성 일시
    """
    from django.conf import settings # 순환 참조 방지
    
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL, 
        on_delete=models.CASCADE, 
        related_name='exercise_logs',
        verbose_name="사용자"
    )
    exercise = models.ForeignKey(
        Exercise, 
        on_delete=models.CASCADE, 
        related_name='logs',
        verbose_name="운동"
    )
    start_time = models.DateTimeField(verbose_name="시작 시간")
    end_time = models.DateTimeField(verbose_name="종료 시간")
    duration = models.IntegerField(verbose_name="소요 시간 (초)")
    count = models.IntegerField(verbose_name="횟수", default=0)
    created_at = models.DateTimeField(auto_now_add=True, verbose_name="생성일")

    class Meta:
        db_table = 'exercise_logs'
        verbose_name = '운동 기록'
        verbose_name_plural = '운동 기록 목록'
        ordering = ['-start_time']

    def __str__(self):
        """
        운동 기록의 문자열 표현을 반환한다.

        Returns:
            str: {사용자} - {운동명} ({날짜}) 형식의 문자열
        """
        return f"{self.user} - {self.exercise} ({self.created_at.date()})"
