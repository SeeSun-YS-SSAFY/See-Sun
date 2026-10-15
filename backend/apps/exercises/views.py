import logging
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
from django.conf import settings
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import extend_schema, OpenApiResponse, OpenApiExample, inline_serializer
from rest_framework import serializers as drf_serializers

AUDIO_RESPONSE = {(200, 'audio/mpeg'): OpenApiTypes.BINARY}
TEXT_REQUEST = inline_serializer('TTSTextRequest', fields={'text': drf_serializers.CharField()})
from .models import Exercise, Playlist, ExerciseCategory, PlaylistItem

logger = logging.getLogger(__name__)

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
        operation_id="exercises_category_exercises",
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
        
        # 2차: Celery 제거. 병합 음성은 운동 상세 음성 API가 요청 시 병합한다.
        return Response(response_data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------------------------------

class FrequentExerciseListView(APIView):
    """
    자주 하는 운동 목록 API (SETUP_002)
    본인의 완료된 유효 세션(10초 이상)을 운동 이름 기준으로 집계해 많이 한 순서로 반환
    """
    permission_classes = [IsAuthenticated]
    LIMIT = 10

    @extend_schema(summary="자주 하는 운동 목록", tags=['Exercises'], responses=inline_serializer(
        'FrequentExerciseList', fields={'exercises': drf_serializers.ListField(child=drf_serializers.DictField())}))
    def get(self, request):
        from django.db.models import Count, Max
        from apps.logs.models import ExerciseSession

        stats = (
            ExerciseSession.objects
            .filter(user=request.user, status='COMPLETED', is_valid=True)
            .exclude(exercise_name__isnull=True).exclude(exercise_name='')
            .values('exercise_name')
            .annotate(count=Count('session_id'), last_performed_at=Max('started_at'))
            .order_by('-count', '-last_performed_at')
        )
        stat_by_name = {row['exercise_name']: row for row in stats[:self.LIMIT * 3]}
        exercises = (
            Exercise.objects
            .filter(exercise_name__in=stat_by_name.keys(), is_active=True)
            .select_related('category')
            .prefetch_related('media_contents')
        )
        items = []
        for exercise, data in zip(exercises, ExerciseSimpleSerializer(exercises, many=True).data):
            row = stat_by_name[exercise.exercise_name]
            items.append({
                **data,
                'category_name': exercise.category.display_name,
                'count': row['count'],
                'last_performed_at': row['last_performed_at'],
            })
        items.sort(key=lambda x: (-x['count'], -(x['last_performed_at'].timestamp() if x['last_performed_at'] else 0)))
        return Response({'exercises': items[:self.LIMIT]}, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------

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

def _server_tts_unavailable():
    """서버 TTS 라이브러리가 없으면(운영 이미지, 법적 검토 L-02) 503 응답, 있으면 None"""
    import importlib.util
    try:
        found = importlib.util.find_spec('google.cloud.texttospeech') is not None
    except ModuleNotFoundError:
        found = False
    if found:
        return None
    return Response({"error": "TTS_UNAVAILABLE", "tts_message": "서버 음성 안내는 쓰지 않아요. 휴대폰 음성으로 안내합니다."},
                    status=status.HTTP_503_SERVICE_UNAVAILABLE)


class GoogleTTSView(APIView):
    """
    Google Cloud TTS를 이용하여 텍스트를 오디오로 변환하여 반환하는 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(summary="텍스트 음성 변환(1차)", tags=['Exercises'], request=TEXT_REQUEST, responses=AUDIO_RESPONSE)
    def post(self, request):
        text = request.data.get('text')
        if not text:
            return Response({"error": "변환할 텍스트가 필요합니다."}, status=status.HTTP_400_BAD_REQUEST)
        
        unavailable = _server_tts_unavailable()
        if unavailable:
            return unavailable
        try:
            from .google_tts import GoogleTTSClient
            tts_client = GoogleTTSClient()
            audio_content = tts_client.synthesize_text(text)
            
            # 오디오 바이너리 스트리밍 반환
            from django.http import HttpResponse
            response = HttpResponse(audio_content, content_type="audio/mpeg")
            response['Content-Disposition'] = 'inline; filename="tts_output.mp3"'
            return response

        except Exception:  # noqa: BLE001 — 예외 내용은 응답에 넣지 않는다(내부 정보 노출 방지)
            logger.exception('서버 TTS 실패')
            return Response({"error": "TTS_FAILED", "tts_message": "음성을 만들지 못했어요."},
                            status=status.HTTP_503_SERVICE_UNAVAILABLE)

# -----------------------------------------------------------------------

class PlaylistAudioView(APIView):
    """
    루틴 화면 진입 시 동적 TTS 오디오 생성 API
    
    - 운동 목록이 없으면: "{루틴이름} 화면입니다. 운동추가 버튼을 눌러 추가해주세요."
    - 운동 목록이 있으면: "{루틴이름} 화면입니다. 아래에 목록 중 운동을 선택해주세요."
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(summary="루틴 화면 안내 음성(1차)", tags=['Exercises'], responses=AUDIO_RESPONSE)
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
        unavailable = _server_tts_unavailable()
        if unavailable:
            return unavailable
        try:
            from .google_tts import GoogleTTSClient
            tts_client = GoogleTTSClient()
            audio_content = tts_client.synthesize_text(text)
            
            from django.http import HttpResponse
            response = HttpResponse(audio_content, content_type="audio/mpeg")
            response['Content-Disposition'] = 'inline; filename="playlist_audio.mp3"'
            return response
            
        except Exception:  # noqa: BLE001
            logger.exception('루틴 안내 TTS 실패')
            return Response({"error": "TTS_FAILED", "tts_message": "음성을 만들지 못했어요."},
                            status=status.HTTP_503_SERVICE_UNAVAILABLE)

# -----------------------------------------------------------------------

class ExerciseAudioView(APIView):
    """
    단일 운동의 병합된 오디오 파일 반환 API
    
    - 병합된 파일이 있으면 반환
    - 없으면 실시간 병합 후 반환
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(summary="운동 안내 병합 음성(1차)", tags=['Exercises'], responses=AUDIO_RESPONSE)
    def get(self, request, exercise_id):
        from django.http import HttpResponse
        import os
        
        # 1. 운동 조회
        exercise = Exercise.objects.filter(exercise_id=exercise_id).first()
        if not exercise:
            return Response(
                {"error": "해당 운동을 찾을 수 없습니다."},
                status=status.HTTP_404_NOT_FOUND
            )
        
        # 2. 병합된 파일 확인
        filename = exercise.name_en if exercise.name_en else str(exercise.exercise_id)
        merged_path = settings.MEDIA_ROOT / 'exercises' / 'audio_merged' / f"{filename}_merged.mp3"
        
        if os.path.exists(merged_path):
            # 병합된 파일 존재 → 바로 반환
            with open(merged_path, 'rb') as f:
                audio_content = f.read()
            response = HttpResponse(audio_content, content_type="audio/mpeg")
            response['Content-Disposition'] = f'inline; filename="{filename}_merged.mp3"'
            return response
        
        # 2-1. 파일명 정규화 (Space -> Underscore, Lowercase) 후 재시도
        # 예: "Push Up" -> "push_up"
        try:
            normalized_name = filename.lower().replace(' ', '_')
            normalized_filename = f"{normalized_name}_merged.mp3"
            normalized_path = settings.MEDIA_ROOT / 'exercises' / 'audio_merged' / normalized_filename
            
            if os.path.exists(normalized_path):
                with open(normalized_path, 'rb') as f:
                    audio_content = f.read()
                response = HttpResponse(audio_content, content_type="audio/mpeg")
                response['Content-Disposition'] = f'inline; filename="{normalized_filename}"'
                return response
        except Exception as e:
            print(f"파일명 정규화 조회 실패: {e}")
            pass
        
        # 3. 병합된 파일 없음 → 실시간 병합(pydub·ffmpeg 필요: requirements-media.txt, 운영 이미지에는 없음)
        try:
            from pydub import AudioSegment
            import imageio_ffmpeg
            AudioSegment.converter = imageio_ffmpeg.get_ffmpeg_exe()
        except ImportError:
            return Response({"error": "AUDIO_NOT_PREPARED", "tts_message": "이 운동의 안내 음성이 아직 준비되지 않았어요."},
                            status=status.HTTP_404_NOT_FOUND)
        from .models import ExerciseMedia
        
        audio_medias = ExerciseMedia.objects.filter(
            exercise=exercise,
            media_type='GUIDE_AUDIO'
        ).order_by('sequence')
        
        if not audio_medias.exists():
            return Response(
                {"error": "해당 운동에 오디오가 없습니다."},
                status=status.HTTP_404_NOT_FOUND
            )
        
        combined = AudioSegment.empty()
        
        for media in audio_medias:
            # lstrip('/media/') 버그 수정 -> replace 사용
            relative_path = media.url.replace('/media/', '', 1)
            file_path = settings.MEDIA_ROOT / relative_path
            
            if os.path.exists(file_path):
                try:
                    # pydub.AudioSegment.from_mp3가 ffprobe를 필요로 하여 실패하는 경우 대비
                    # 직접 ffmpeg로 WAV 디코딩 후 로드
                    try:
                        audio = AudioSegment.from_mp3(str(file_path))
                    except Exception as e:
                        # ffmpeg 직접 사용
                        import subprocess
                        ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
                        cmd = [ffmpeg_exe, '-i', str(file_path), '-y', '-f', 'wav', '-']
                        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        stdout_data, stderr_data = process.communicate()
                        
                        if process.returncode != 0:
                            print(f"FFmpeg decode error ({file_path}): {stderr_data.decode('utf-8', errors='ignore')}")
                            raise e
                        
                        import io
                        audio = AudioSegment.from_wav(io.BytesIO(stdout_data))

                    combined += audio
                    combined += AudioSegment.silent(duration=500)
                except Exception as e:
                    print(f"오디오 로드 오류: {e}")
        
        # mp3로 export
        import io
        buffer = io.BytesIO()
        combined.export(buffer, format="mp3")
        buffer.seek(0)
        
        response = HttpResponse(buffer.read(), content_type="audio/mpeg")
        response['Content-Disposition'] = f'inline; filename="{filename}_merged.mp3"'
        return response

# -----------------------------------------------------------------------
