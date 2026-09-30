"""
배포 후 STT Sanity Check 스크립트 (HTTP)

목적:
- Dev/Staging 배포 직후, 실제 Google STT 통신이 정상인지 1회 확인합니다.

사용 예시(Windows PowerShell):
- .\.venv\Scripts\python backend\scripts\stt_sanity_check_http.py --base-url https://staging.example.com --mode stt --file .\hello.webm --key userinfo_stt

주의:
- 이 스크립트는 업로드 파일을 그대로 전송합니다.
- 실제 테스트 파일은 "안녕하세요" 음성이 포함된 WebM(Opus) 파일을 권장합니다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True, help="예: https://staging.example.com")
    parser.add_argument("--mode", default="stt", help="form|listen|command|full_command|stt")
    parser.add_argument("--file", required=True, help="업로드할 오디오 파일 경로")
    parser.add_argument(
        "--key",
        default="userinfo_stt",
        choices=["userinfo_stt", "audio"],
        help="multipart 파일 필드명(권장: userinfo_stt, 레거시: audio)",
    )
    parser.add_argument("--field", default="", help="form 모드일 때 field 값(예: height)")
    parser.add_argument("--timeout", type=int, default=30, help="요청 타임아웃(초)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    base_url = args.base_url.rstrip("/")
    url = f"{base_url}/api/v1/stt/{args.mode}/"

    file_path = Path(args.file)
    if not file_path.exists():
        print("오디오 파일이 존재하지 않습니다.")
        print(f"경로: {file_path}")
        return 2

    data = {}
    if args.field:
        data["field"] = args.field

    with file_path.open("rb") as f:
        files = {args.key: (file_path.name, f, "audio/webm")}
        try:
            resp = requests.post(url, data=data, files=files, timeout=args.timeout)
        except Exception as e:
            print("요청에 실패했습니다.")
            print(f"에러: {e}")
            return 3

    print("요청 URL:", url)
    print("HTTP 상태:", resp.status_code)
    try:
        print("응답 JSON:", resp.json())
    except Exception:
        print("응답 TEXT:", resp.text[:2000])

    # 기대치(간단 체크):
    # - 200 응답에서 stt_raw 또는 message에 텍스트가 포함되어야 함
    if resp.status_code >= 500:
        print("서버 에러(5xx)로 보입니다. 서버 로그를 확인해주세요.")
        return 4

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

