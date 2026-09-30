#!/usr/bin/env python3
"""
Docker 환경에서 Google Cloud STT/TTS/Gemini API 동작 검증 스크립트

사용법:
  docker exec -it seesun-backend python scripts/test_google_services.py

검증 항목:
  1. GOOGLE_APPLICATION_CREDENTIALS 환경 변수 확인
  2. 서비스 계정 JSON 파일 존재 여부
  3. Google Cloud STT 클라이언트 초기화 및 간단한 호출 테스트
  4. Google Cloud TTS 클라이언트 초기화 및 간단한 호출 테스트
  5. Gemini API 클라이언트 초기화 및 간단한 호출 테스트
"""

import os
import sys
import json
from pathlib import Path


def print_header(title: str):
    print("\n" + "=" * 60)
    print(f"  {title}")
    print("=" * 60)


def print_result(success: bool, message: str):
    status = "✅ 성공" if success else "❌ 실패"
    print(f"  {status}: {message}")


def check_env_credentials() -> tuple[bool, str]:
    """환경 변수 및 인증 파일 확인"""
    print_header("1. 환경 변수 및 인증 파일 확인")
    
    creds_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    print(f"  GOOGLE_APPLICATION_CREDENTIALS = {creds_path}")
    
    if not creds_path:
        print_result(False, "GOOGLE_APPLICATION_CREDENTIALS 환경 변수가 설정되지 않았습니다.")
        return False, ""
    
    if not Path(creds_path).exists():
        print_result(False, f"인증 파일이 존재하지 않습니다: {creds_path}")
        return False, creds_path
    
    # JSON 파일 파싱 및 프로젝트 정보 출력
    try:
        with open(creds_path, "r") as f:
            creds_data = json.load(f)
        
        print(f"  프로젝트 ID: {creds_data.get('project_id', 'N/A')}")
        print(f"  서비스 계정: {creds_data.get('client_email', 'N/A')}")
        print_result(True, "인증 파일이 정상적으로 로드되었습니다.")
        return True, creds_path
    except Exception as e:
        print_result(False, f"인증 파일 파싱 오류: {e}")
        return False, creds_path


def check_gemini_api_key() -> bool:
    """Gemini API 키 확인"""
    print_header("2. Gemini API 키 확인")
    
    gemini_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    
    if gemini_key:
        masked = gemini_key[:10] + "..." + gemini_key[-4:] if len(gemini_key) > 14 else "***"
        print(f"  GEMINI_API_KEY = {masked}")
        print_result(True, "Gemini API 키가 설정되어 있습니다.")
        return True
    else:
        print_result(False, "GEMINI_API_KEY 또는 GOOGLE_API_KEY가 설정되지 않았습니다.")
        return False


def test_google_stt() -> bool:
    """Google Cloud STT 테스트"""
    print_header("3. Google Cloud STT 테스트")
    
    try:
        from google.cloud import speech
        
        # 클라이언트 초기화
        client = speech.SpeechClient()
        print_result(True, "SpeechClient 초기화 성공")
        
        # 간단한 API 호출 테스트 (빈 오디오로 오류 없이 호출되는지 확인)
        # 실제 오디오 없이도 API 접근 권한을 확인할 수 있음
        try:
            # list_operations를 통해 API 접근 권한 확인 (오디오 없이 가능)
            # 대안: 짧은 무음 PCM으로 recognize 호출
            
            # 16kHz, 모노, 0.1초 무음 PCM (1600 samples = 3200 bytes)
            silent_audio = b"\x00" * 3200
            
            audio = speech.RecognitionAudio(content=silent_audio)
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=16000,
                language_code="ko-KR",
            )
            
            response = client.recognize(config=config, audio=audio)
            print(f"  API 응답: results={len(response.results)}개")
            print_result(True, "STT API 호출 성공 (무음 테스트)")
            return True
            
        except Exception as api_error:
            error_str = str(api_error)
            if "403" in error_str or "PERMISSION_DENIED" in error_str:
                print_result(False, f"API 권한 오류: {error_str[:100]}")
            elif "SERVICE_DISABLED" in error_str:
                print_result(False, "Speech-to-Text API가 비활성화되어 있습니다.")
            else:
                print_result(False, f"API 호출 오류: {error_str[:100]}")
            return False
            
    except ImportError:
        print_result(False, "google-cloud-speech 패키지가 설치되지 않았습니다.")
        return False
    except Exception as e:
        print_result(False, f"클라이언트 초기화 오류: {e}")
        return False


def test_google_tts() -> bool:
    """Google Cloud TTS 테스트"""
    print_header("4. Google Cloud TTS 테스트")
    
    try:
        from google.cloud import texttospeech
        
        # 클라이언트 초기화
        client = texttospeech.TextToSpeechClient()
        print_result(True, "TextToSpeechClient 초기화 성공")
        
        # 간단한 텍스트로 TTS 테스트
        try:
            input_text = texttospeech.SynthesisInput(text="테스트")
            voice = texttospeech.VoiceSelectionParams(
                language_code="ko-KR",
                name="ko-KR-Neural2-A"
            )
            audio_config = texttospeech.AudioConfig(
                audio_encoding=texttospeech.AudioEncoding.MP3
            )
            
            response = client.synthesize_speech(
                request={"input": input_text, "voice": voice, "audio_config": audio_config}
            )
            
            audio_size = len(response.audio_content)
            print(f"  생성된 오디오 크기: {audio_size} bytes")
            
            if audio_size > 0:
                print_result(True, "TTS API 호출 성공")
                return True
            else:
                print_result(False, "TTS 응답이 비어있습니다.")
                return False
                
        except Exception as api_error:
            error_str = str(api_error)
            if "403" in error_str or "PERMISSION_DENIED" in error_str:
                print_result(False, f"API 권한 오류: {error_str[:100]}")
            elif "SERVICE_DISABLED" in error_str:
                print_result(False, "Text-to-Speech API가 비활성화되어 있습니다.")
            else:
                print_result(False, f"API 호출 오류: {error_str[:100]}")
            return False
            
    except ImportError:
        print_result(False, "google-cloud-texttospeech 패키지가 설치되지 않았습니다.")
        return False
    except Exception as e:
        print_result(False, f"클라이언트 초기화 오류: {e}")
        return False


def test_gemini_api() -> bool:
    """Gemini API 테스트"""
    print_header("5. Gemini API 테스트")
    
    try:
        from google import genai
        
        api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
        
        if not api_key:
            print_result(False, "Gemini API 키가 설정되지 않았습니다.")
            return False
        
        # 클라이언트 초기화
        client = genai.Client(api_key=api_key)
        print_result(True, "Gemini Client 초기화 성공")
        
        # 간단한 프롬프트로 테스트
        try:
            response = client.models.generate_content(
                model="gemini-2.0-flash",
                contents="1+1은?"
            )
            
            result_text = response.text.strip()
            print(f"  응답: {result_text[:50]}...")
            print_result(True, "Gemini API 호출 성공")
            return True
            
        except Exception as api_error:
            error_str = str(api_error)
            if "API_KEY_INVALID" in error_str:
                print_result(False, "API 키가 유효하지 않습니다.")
            elif "QUOTA_EXCEEDED" in error_str:
                print_result(False, "API 할당량이 초과되었습니다.")
            else:
                print_result(False, f"API 호출 오류: {error_str[:100]}")
            return False
            
    except ImportError:
        print_result(False, "google-genai 패키지가 설치되지 않았습니다.")
        return False
    except Exception as e:
        print_result(False, f"클라이언트 초기화 오류: {e}")
        return False


def main() -> int:
    print("\n" + "=" * 60)
    print("  Google Cloud STT/TTS/Gemini 서비스 검증")
    print("=" * 60)
    
    results = []
    
    # 1. 환경 변수 확인
    env_ok, creds_path = check_env_credentials()
    results.append(("환경 변수/인증 파일", env_ok))
    
    # 2. Gemini API 키 확인
    gemini_key_ok = check_gemini_api_key()
    results.append(("Gemini API 키", gemini_key_ok))
    
    # 3. STT 테스트
    stt_ok = test_google_stt()
    results.append(("Google Cloud STT", stt_ok))
    
    # 4. TTS 테스트
    tts_ok = test_google_tts()
    results.append(("Google Cloud TTS", tts_ok))
    
    # 5. Gemini 테스트
    gemini_ok = test_gemini_api()
    results.append(("Gemini API", gemini_ok))
    
    # 결과 요약
    print_header("결과 요약")
    
    all_passed = True
    for name, passed in results:
        status = "✅" if passed else "❌"
        print(f"  {status} {name}")
        if not passed:
            all_passed = False
    
    print("")
    if all_passed:
        print("  🎉 모든 서비스가 정상 동작합니다!")
        return 0
    else:
        print("  ⚠️  일부 서비스에 문제가 있습니다. 위 오류를 확인하세요.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
