"""
운동 관련 API 뷰셋 모듈.

이 모듈은 운동 목록 조회, 루틴(Playlist) 관리, 운동 세션(Session) 관리 등
주요 기능에 대한 ViewSet을 정의한다.
"""
from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated, AllowAny
from .models import Exercise, Playlist
from .serializers import (
    ExerciseSerializer, PlaylistSerializer,
    ExerciseCategorySerializer, ExerciseSimpleSerializer, ExerciseDetailSerializer,
    ExerciseCategorySerializer, ExerciseSimpleSerializer, ExerciseDetailSerializer,
    ExerciseCategorySerializer, ExerciseSimpleSerializer, ExerciseDetailSerializer,
    PlaylistCreateSerializer, PlaylistUpdateSerializer, PlaylistItemAddSerializer,
    PlaylistItemUpdateSerializer
)
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from django.views.generic import TemplateView  # TemplateView 추가
from drf_spectacular.utils import extend_schema, OpenApiResponse, OpenApiExample
from .models import Exercise, Playlist, ExerciseCategory, PlaylistItem

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
            return Response({"error": "해당 카테고리를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)

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
            return Response({"error": "해당 운동 정보를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
        
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
            return Response({"error": "플레이리스트를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
            
        serializer = PlaylistSerializer(playlist)
        return Response(serializer.data, status=status.HTTP_200_OK)

    @extend_schema(
        summary="플레이리스트 기본 정보 수정",
        description="플레이리스트의 제목 등을 수정합니다. (운동 목록 수정 아님)",
        request=PlaylistUpdateSerializer,
        responses={
            200: PlaylistSerializer,
            404: OpenApiResponse(description="Playlist not found")
        },
        tags=['Exercises']
    )
    def patch(self, request, playlist_id):
        try:
            playlist = Playlist.objects.get(playlist_id=playlist_id, user=request.user, status='ACTIVE')
        except Playlist.DoesNotExist:
            return Response({"error": "플레이리스트를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
            
        serializer = PlaylistUpdateSerializer(playlist, data=request.data, partial=True)
        if serializer.is_valid():
            updated_playlist = serializer.save()
            # 응답은 전체 정보를 내려줌
            return Response(PlaylistSerializer(updated_playlist).data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    @extend_schema(
        summary="플레이리스트 삭제",
        description="플레이리스트를 삭제합니다.",
        responses={204: OpenApiResponse(description="Playlist deleted"), 404: OpenApiResponse(description="Playlist not found")},
        tags=['Exercises']
    )
    def delete(self, request, playlist_id):
        try:
            playlist = Playlist.objects.get(playlist_id=playlist_id, user=request.user)
        except Playlist.DoesNotExist:
            return Response({"error": "플레이리스트를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
            
        playlist.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

# -----------------------------------------------------------------------

class PlaylistItemAddView(APIView):
    """
    플레이리스트 운동 추가 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="플레이리스트에 운동 추가",
        description="특정 플레이리스트에 운동을 추가합니다.",
        request=PlaylistItemAddSerializer,
        responses={201: PlaylistSerializer, 404: OpenApiResponse(description="Playlist not found")},
        tags=['Exercises']
    )
    def post(self, request, playlist_id):
        try:
            playlist = Playlist.objects.get(playlist_id=playlist_id, user=request.user, status='ACTIVE')
        except Playlist.DoesNotExist:
            return Response({"error": "플레이리스트를 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)

        serializer = PlaylistItemAddSerializer(data=request.data, context={'playlist': playlist})
        if serializer.is_valid():
            serializer.save()
            # 추가 후 변경된 전체 플레이리스트 반환
            return Response(PlaylistSerializer(playlist).data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -----------------------------------------------------------------------

class PlaylistItemDetailView(APIView):
    """
    플레이리스트 항목 개별 삭제 및 수정 API
    """
    permission_classes = [IsAuthenticated]

    def get_object(self, playlist_id, item_id, user):
        try:
            # 본인의 활성 플레이리스트에 속한 아이템인지 확인
            item = PlaylistItem.objects.select_related('playlist').get(
                playlist_item_id=item_id,
                playlist__playlist_id=playlist_id,
                playlist__user=user,
                playlist__status='ACTIVE'
            )
            return item
        except PlaylistItem.DoesNotExist:
            return None

    @extend_schema(
        summary="플레이리스트 항목 삭제",
        description="플레이리스트에서 특정 운동 항목을 삭제합니다.",
        responses={200: PlaylistSerializer, 404: OpenApiResponse(description="Item not found")},
        tags=['Exercises']
    )
    def delete(self, request, playlist_id, item_id):
        item = self.get_object(playlist_id, item_id, request.user)
        if not item:
            return Response({"error": "해당 운동 항목을 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
        
        playlist = item.playlist
        item.delete()
        
        # 삭제 후 갱신된 플레이리스트 반환
        return Response(PlaylistSerializer(playlist).data, status=status.HTTP_200_OK)

    @extend_schema(
        summary="플레이리스트 항목 상세 수정",
        description="운동 항목의 세트 수, 횟수, 순서 등을 수정합니다.",
        request=PlaylistItemUpdateSerializer,
        responses={200: PlaylistSerializer, 404: OpenApiResponse(description="Item not found")},
        tags=['Exercises']
    )
    def patch(self, request, playlist_id, item_id):
        item = self.get_object(playlist_id, item_id, request.user)
        if not item:
            return Response({"error": "해당 운동 항목을 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
            
        serializer = PlaylistItemUpdateSerializer(item, data=request.data, partial=True)
        if serializer.is_valid():
            serializer.save()
            return Response(PlaylistSerializer(item.playlist).data, status=status.HTTP_200_OK)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -----------------------------------------------------------------------

class TTSTestView(TemplateView):
    """
    TTS 기능 테스트를 위한 임시 페이지 뷰
    """
    template_name = "exercises/tts_test.html"

# -----------------------------------------------------------------------

class GoogleTTSView(APIView):
    """
    Google Cloud TTS를 이용하여 텍스트를 오디오로 변환하여 반환하는 API
    """
    permission_classes = [IsAuthenticated]

    def post(self, request):
        text = request.data.get('text')
        if not text:
            return Response({"error": "변환할 텍스트가 필요합니다."}, status=status.HTTP_400_BAD_REQUEST)
        
        try:
            from .google_tts import GoogleTTSClient
            tts_client = GoogleTTSClient()
            audio_content = tts_client.synthesize_text(text)
            
            # 오디오 바이너리 스트리밍 반환
            from django.http import HttpResponse
            response = HttpResponse(audio_content, content_type="audio/mpeg")
            response['Content-Disposition'] = 'inline; filename="tts_output.mp3"'
            return response

        except Exception as e:
            return Response({"error": str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

# -----------------------------------------------------------------------

class PlaylistAudioView(APIView):
    """
    루틴 화면 진입 시 동적 TTS 오디오 생성 API
    
    - 운동 목록이 없으면: "{루틴이름} 화면입니다. 운동추가 버튼을 눌러 추가해주세요."
    - 운동 목록이 있으면: "{루틴이름} 화면입니다. 아래에 목록 중 운동을 선택해주세요."
    """
    permission_classes = [IsAuthenticated]

    def get(self, request, playlist_id):
        # 1. 루틴 조회
        playlist = Playlist.objects.filter(
            playlist_id=playlist_id,
            user=request.user
        ).first()
        
        if not playlist:
            return Response(
                {"error": "해당 루틴을 찾을 수 없습니다."},
                status=status.HTTP_404_NOT_FOUND
            )
        
        # 2. 운동 목록 유무 확인
        has_items = playlist.items.exists()
        
        # 3. 안내 텍스트 생성
        if has_items:
            text = f"{playlist.title} 화면입니다. 아래에 목록 중 운동을 선택해주세요."
        else:
            text = f"{playlist.title} 화면입니다. 운동추가 버튼을 눌러 추가해주세요."
        
        # 4. TTS 생성 (저장 안 함)
        try:
            from .google_tts import GoogleTTSClient
            tts_client = GoogleTTSClient()
            audio_content = tts_client.synthesize_text(text)
            
            from django.http import HttpResponse
            response = HttpResponse(audio_content, content_type="audio/mpeg")
            response['Content-Disposition'] = 'inline; filename="playlist_audio.mp3"'
            return response
            
        except Exception as e:
            return Response(
                {"error": f"TTS 생성 오류: {str(e)}"},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )

# -----------------------------------------------------------------------
