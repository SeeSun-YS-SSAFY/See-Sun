"""
THIRD_PARTY_NOTICES 생성기(법적 검토 L-01) — 운영 이미지에 실제로 설치된 파이썬 패키지 기준

    docker run --rm --entrypoint python seesun-backend:latest scripts/gen_third_party_notices.py > THIRD_PARTY_NOTICES

패키지마다 이름·버전·라이선스(메타데이터 License-Expression/License/분류자)·홈페이지와, 배포본에 들어 있는
라이선스·NOTICE 파일 전문을 적는다. 의존성이 바뀌면 이미지를 다시 빌드하고 이 명령으로 다시 만든다.
"""
import sys
from importlib import metadata

SKIP = {'pip', 'setuptools', 'wheel'}
LICENSE_HINTS = ('license', 'licence', 'copying', 'notice', 'authors')


def license_of(dist):
    meta = dist.metadata
    expr = meta.get('License-Expression')
    if expr:
        return expr
    classifiers = [c.split('::')[-1].strip() for c in meta.get_all('Classifier') or [] if c.startswith('License ::')]
    text = (meta.get('License') or '').strip()
    if classifiers:
        return ', '.join(classifiers)
    if text and len(text) < 120:
        return text
    return text.splitlines()[0][:120] + ' …' if text else 'UNKNOWN(배포본 라이선스 파일 참조)'


def license_files(dist):
    out = []
    for f in dist.files or []:
        name = f.name.lower()
        if any(h in name for h in LICENSE_HINTS) and not name.endswith(('.py', '.pyc')):
            try:
                out.append((str(f), f.locate().read_text(encoding='utf-8', errors='replace')))
            except (OSError, UnicodeDecodeError):
                continue
    return out


def main():
    dists = sorted({d.metadata['Name'].lower(): d for d in metadata.distributions()
                    if d.metadata['Name'] and d.metadata['Name'].lower() not in SKIP}.values(),
                   key=lambda d: d.metadata['Name'].lower())
    w = sys.stdout.write
    w('See:Sun(시선) 백엔드 — 제3자 소프트웨어 고지(THIRD_PARTY_NOTICES)\n')
    w('운영 Docker 이미지(backend/Dockerfile runtime 타깃)에 설치된 파이썬 패키지 목록과 라이선스 전문입니다.\n')
    w('생성: backend/scripts/gen_third_party_notices.py (의존성이 바뀌면 다시 생성)\n')
    w(f'Python {sys.version.split()[0]} · 패키지 {len(dists)}개\n\n')
    w('== 요약 ==\n')
    for d in dists:
        m = d.metadata
        w(f"{m['Name']} {d.version} — {license_of(d)}\n")
    w('\n== 패키지별 상세 ==\n')
    for d in dists:
        m = d.metadata
        home = m.get('Home-page') or next((u.split(',', 1)[-1].strip() for u in (m.get_all('Project-URL') or [])), '')
        w('\n' + '=' * 78 + '\n')
        w(f"{m['Name']} {d.version}\n라이선스: {license_of(d)}\n")
        if home:
            w(f'홈페이지: {home}\n')
        for path, text in license_files(d):
            w(f'\n--- {path} ---\n{text.rstrip()}\n')


if __name__ == '__main__':
    main()
