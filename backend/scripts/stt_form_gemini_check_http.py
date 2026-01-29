"""
Form 모드 STT + Gemini 정규화 검증 스크립트 (HTTP)

목적:
- `testfiles/media`의 WebM 3종(몸무게/키/성별)을 이용해
  form 모드의 STT → Gemini 정규화가 정상 동작하는지 확인합니다.

주의:
- 이 스크립트는 실제 서버 엔드포인트를 호출합니다.
- Google STT / Gemini API 키 및 서버 설정이 필요합니다.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import requests


@dataclass(frozen=True)
class Case:
    name: str
    filename: str
    field: str
    expected: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True, help="예: http://localhost:8000 또는 https://staging.example.com")
    parser.add_argument(
        "--key",
        default="userinfo_stt",
        choices=["userinfo_stt", "audio"],
        help="multipart 파일 필드명(권장: userinfo_stt, 레거시: audio)",
    )
    parser.add_argument("--timeout", type=int, default=30, help="요청 타임아웃(초)")
    parser.add_argument(
        "--media-dir",
        default="",
        help="테스트 미디어 디렉토리(미지정 시 repo_root/testfiles/media 자동 탐색)",
    )
    return parser.parse_args()


def _guess_default_media_dir() -> Path:
    # backend/scripts/.. -> backend, backend/.. -> repo root
    repo_root = Path(__file__).resolve().parents[2]
    return repo_root / "testfiles" / "media"


def _only_digits(value: str) -> str:
    return "".join(ch for ch in value if ch.isdigit())


def _post_form(
    *,
    base_url: str,
    timeout: int,
    key: str,
    audio_path: Path,
    field: str,
) -> tuple[int, dict | None, str]:
    url = f"{base_url.rstrip('/')}/api/v1/stt/form/"
    data = {"field": field}

    with audio_path.open("rb") as f:
        files = {key: (audio_path.name, f, "audio/webm")}
        resp = requests.post(url, data=data, files=files, timeout=timeout)

    try:
        return resp.status_code, resp.json(), url
    except Exception:
        return resp.status_code, None, url


def main() -> int:
    args = parse_args()

    media_dir = Path(args.media_dir) if args.media_dir else _guess_default_media_dir()
    if not media_dir.exists():
        print("테스트 미디어 디렉토리를 찾을 수 없습니다.")
        print(f"경로: {media_dir}")
        return 2

    cases: list[Case] = [
        Case(name="몸무게", filename="76kg.webm", field="weight", expected="76"),
        Case(name="키", filename="176cm.webm", field="height", expected="176"),
        Case(name="성별", filename="male.webm", field="gender", expected="M"),
    ]

    print("Form 모드 STT+Gemini 검증을 시작합니다.")
    print(f"- base_url: {args.base_url}")
    print(f"- key: {args.key}")
    print(f"- media_dir: {media_dir}")
    print("")

    failed = 0
    for case in cases:
        audio_path = media_dir / case.filename
        if not audio_path.exists():
            print(f"[실패] 파일이 존재하지 않습니다: {audio_path}")
            failed += 1
            continue

        status_code, payload, url = _post_form(
            base_url=args.base_url,
            timeout=args.timeout,
            key=args.key,
            audio_path=audio_path,
            field=case.field,
        )

        if status_code != 200:
            print(f"[실패] {case.name} - HTTP 상태가 200이 아닙니다. status={status_code}")
            print(f"  URL: {url}")
            if payload is not None:
                print(f"  응답: {payload}")
            failed += 1
            continue

        if payload is None:
            print(f"[실패] {case.name} - 응답 JSON 파싱에 실패했습니다.")
            print(f"  URL: {url}")
            failed += 1
            continue

        normalized = payload.get("normalized")
        stt_raw = payload.get("stt_raw")

        if not stt_raw:
            print(f"[실패] {case.name} - STT 결과(stt_raw)가 비어있습니다.")
            print(f"  응답: {payload}")
            failed += 1
            continue

        if case.field in ("weight", "height"):
            digits = _only_digits(str(normalized or ""))
            if digits != case.expected:
                print(f"[실패] {case.name} - 정규화 값이 기대와 다릅니다.")
                print(f"  기대: {case.expected}")
                print(f"  실제: {normalized} (digits={digits})")
                print(f"  STT: {stt_raw}")
                failed += 1
                continue
        elif case.field == "gender":
            if str(normalized or "").strip().upper() != case.expected:
                print(f"[실패] {case.name} - 정규화 값이 기대와 다릅니다.")
                print(f"  기대: {case.expected}")
                print(f"  실제: {normalized}")
                print(f"  STT: {stt_raw}")
                failed += 1
                continue

        print(f"[성공] {case.name} - normalized={normalized}, stt_raw={stt_raw}")

    print("")
    if failed:
        print(f"검증 실패: {failed}건")
        return 1

    print("검증 성공: 모든 케이스가 통과했습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

