"""
운동 관련 API 뷰셋 모듈.

이 모듈은 운동 목록 조회, 루틴(Playlist) 관리, 운동 세션(Session) 관리 등
주요 기능에 대한 ViewSet을 정의한다.
"""
from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated, AllowAny
from .models import Exercise, Playlist, ExerciseSession
from .serializers import (
    ExerciseSerializer, PlaylistSerializer, ExerciseSessionSerializer,
    ExerciseCategorySerializer, ExerciseSimpleSerializer, ExerciseDetailSerializer,
    PlaylistCreateSerializer
)
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from drf_spectacular.utils import extend_schema, OpenApiResponse, OpenApiExample
from .models import Exercise, Playlist, ExerciseSession, ExerciseCategory

class ExerciseViewSet(viewsets.ReadOnlyModelViewSet):
    """
    운동 정보 조회용 ViewSet.
    누구나 접근 가능하며, 카테고리별 필터링을 지원한다.
    """
    queryset = Exercise.objects.all().select_related('category').prefetch_related('media_contents')
    serializer_class = ExerciseSerializer
    permission_classes = [AllowAny] 

    def get_queryset(self):
        queryset = super().get_queryset()
        category_id = self.request.query_params.get('category_id')
        if category_id:
            queryset = queryset.filter(category_id=category_id)
        return queryset

# -----------------------------------------------------------------------

class SessionViewSet(viewsets.ModelViewSet):
    """
    운동 세션(기록) 관리용 ViewSet.
    """
    serializer_class = ExerciseSessionSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return ExerciseSession.objects.filter(user=self.request.user).order_by('-started_at')

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

# -----------------------------------------------------------------------------------------------
class ExerciseCategoryListView(APIView):
    """
    운동 카테고리 목록 조회 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="운동 카테고리 목록 조회",
        description="모든 운동 카테고리 목록을 반환합니다.",
        responses={
            200: OpenApiResponse(
                response=ExerciseCategorySerializer(many=True),
                examples=[
                    OpenApiExample(
                        'Success',
                        value=[
                            {
                                "category_id": "1",
                                "display_name": "근력 운동"
                            },
                            {
                                "category_id": "2",
                                "display_name": "유산소 운동"
                            }
                        ]
                    )
                ]
            )
        },
        tags=['Exercises']
    )
    def get(self, request):
        categories = ExerciseCategory.objects.all()
        serializer = ExerciseCategorySerializer(categories, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)

# ----------------------------------------------------------------------------

class ExerciseListByCategoryView(APIView):
    """
    특정 카테고리의 운동 목록 조회 API (Custom Response)
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="카테고리별 운동 목록 조회",
        description="특정 카테고리 ID를 받아 해당 카테고리 정보와 운동 목록(픽토그램 포함)을 반환합니다.",
        responses={
            200: OpenApiResponse(
                description="Success",
                examples=[
                    OpenApiExample(
                        'Success',
                        value={
                            "category_id": "1",
                            "category_name": "근력 운동",
                            "exercises": [
                                {
                                    "exercise_id": "uuid...",
                                    "exercise_name": "스쿼트",
                                    "pictogram_url": "https://example.com/squat.png"
                                }
                            ]
                        }
                    )
                ]
            ),
            404: OpenApiResponse(description="Category not found")
        },
        tags=['Exercises']
    )
    def get(self, request, category_id):
        try:
            category = ExerciseCategory.objects.get(category_id=category_id)
        except ExerciseCategory.DoesNotExist:
            return Response({"error": "Category not found"}, status=status.HTTP_404_NOT_FOUND)

        exercises = Exercise.objects.filter(category=category, is_active=True).prefetch_related('media_contents')
        
        # Serialize exercises
        exercise_data = ExerciseSimpleSerializer(exercises, many=True).data
        
        response_data = {
            "category_id": category.category_id,
            "category_name": category.display_name,
            "exercises": exercise_data
        }
        
        return Response(response_data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------------------------------

class ExerciseDetailView(APIView):
    """
    운동 상세 정보 조회 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="운동 상세 정보 조회",
        description="운동 ID를 받아 상세 정보를 반환합니다.",
        responses={
            200: ExerciseDetailSerializer,
            404: OpenApiResponse(description="Exercise not found")
        },
        tags=['Exercises']
    )
    def get(self, request, exercise_id):
        try:
            exercise = Exercise.objects.prefetch_related('media_contents').select_related('category').get(exercise_id=exercise_id)
        except Exercise.DoesNotExist:
            return Response({"error": "Exercise not found"}, status=status.HTTP_404_NOT_FOUND)
        
        serializer = ExerciseDetailSerializer(exercise)
        return Response(serializer.data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------------------------------
# 운동 플레이리스트 생성 
class PlaylistCreateView(APIView):
    """
    운동 루틴(플레이리스트) 생성 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="운동 루틴 생성",
        description="제목과 운동 목록을 받아 새로운 루틴을 생성합니다.",
        request=PlaylistCreateSerializer,
        responses={201: PlaylistCreateSerializer},
        tags=['Exercises']
    )
    def post(self, request):
        serializer = PlaylistCreateSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            playlist = serializer.save()
            return Response(PlaylistSerializer(playlist).data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -----------------------------------------------------------------------
class PlaylistListView(APIView):
    """
    내 플레이리스트 목록 조회 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="내 플레이리스트 목록 조회",
        description="사용자가 생성한 모든 활성 플레이리스트 목록을 반환합니다.",
        responses={
            200: PlaylistSerializer(many=True),
        },
        tags=['Exercises']
    )
    def get(self, request):
        playlists = Playlist.objects.filter(user=request.user, status='ACTIVE')
        serializer = PlaylistSerializer(playlists, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------
# 플레이리스트 상세 조회 
class PlaylistDetailView(APIView):
    """
    플레이리스트(루틴) 상세 조회 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="플레이리스트 상세 조회",
        description="플레이리스트 ID를 받아 상세 정보(운동 목록 포함)를 반환합니다. 본인의 플레이리스트만 조회 가능합니다.",
        responses={
            200: PlaylistSerializer,
            404: OpenApiResponse(description="Playlist not found or permission denied")
        },
        tags=['Exercises']
    )
    def get(self, request, playlist_id):
        try:
            # 본인의 플레이리스트이면서 활성 상태인 것만 조회
            playlist = Playlist.objects.get(playlist_id=playlist_id, user=request.user, status='ACTIVE')
        except Playlist.DoesNotExist:
            return Response({"error": "Playlist not found"}, status=status.HTTP_404_NOT_FOUND)
            
        serializer = PlaylistSerializer(playlist)
        return Response(serializer.data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------