"""connectedDebugAndroidTest 결과(XML) 요약: 모듈별 시험 수·실패 수와 실패 이유 첫 줄.

실행: python -X utf8 scripts/e2e/connected_summary.py [android 폴더]
"""

import re
import sys
from pathlib import Path


def main(root: Path):
    total = fails = 0
    for xml in sorted(root.glob('**/build/outputs/androidTest-results/connected/debug/TEST-*.xml')):
        x = xml.read_text(encoding='utf-8')
        m = re.search(r'tests="(\d+)" failures="(\d+)" errors="(\d+)"', x)
        if not m:
            continue
        n, f, e = map(int, m.groups())
        total += n
        fails += f + e
        module = xml.relative_to(root).parts[:-6]
        print(f"{'/'.join(module)}\t{n}\t실패 {f + e}")
        for t in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<failure[^>]*>([^\n]*)\n?([^\n]*)', x):
            print(f"  ✗ {t.group(2).split('.')[-1]}.{t.group(1)} | {(t.group(3) + ' ' + t.group(4))[:400]}")
    print(f"합계\t{total}\t실패 {fails}")


if __name__ == '__main__':
    main(Path(sys.argv[1] if len(sys.argv) > 1 else 'android'))
