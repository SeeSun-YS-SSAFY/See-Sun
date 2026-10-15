"""
시선 Android 해상도·글꼴 매트릭스 실행기(에뮬레이터 전용): 조건마다 화면 크기·밀도·글꼴·회전을 바꾸고
feature 기기 시험(onboarding·main·mypage·exercise)을 조사 모드(-e fitSurvey true)로 돌려
단계 사진(PNG)·fit.tsv·measure.tsv(배치 실측, core:uitest LayoutAudit)를 <out>/<조건>/<모듈>/ 에 모은다.

전제: 시험 APK가 설치돼 있어야 한다.
  cd android; $env:ANDROID_SERIAL='emulator-5554'; .\\gradlew.bat :feature:onboarding:installDebugAndroidTest \\
      :feature:main:installDebugAndroidTest :feature:mypage:installDebugAndroidTest :feature:exercise:installDebugAndroidTest

실행:
  python scripts/e2e/run_layout_matrix.py --serial emulator-5554 --out <폴더> [--only a_360x640,f_font2.0]

안전장치: 화면 크기·밀도·글꼴·회전을 바꾸므로 시리얼이 emulator- 로 시작할 때만 돈다(실기기 금지).
끝나면(실패해도) wm size/density reset, font_scale 1.0, 자동 회전 원래 값으로 되돌린다.
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

MODULES = {
    'onboarding': 'kr.seesun.feature.onboarding.test/androidx.test.runner.AndroidJUnitRunner',
    'main': 'kr.seesun.feature.main.test/androidx.test.runner.AndroidJUnitRunner',
    'mypage': 'kr.seesun.feature.mypage.test/androidx.test.runner.AndroidJUnitRunner',
    'exercise': 'kr.seesun.feature.exercise.test/kr.seesun.feature.exercise.HiltTestRunner',
}

# 조건 이름: (물리 크기 px, 밀도 dpi, 글꼴 배율, 가로 회전) — dp = px * 160 / dpi
CONDITIONS = {
    'a_360x640': ('720x1280', 320, 1.0, False),
    'b_360x740': ('1080x2220', 480, 1.0, False),
    'c_412x915': ('1080x2400', 420, 1.0, False),
    'd_480x1000': ('960x2000', 320, 1.0, False),
    'e1_673x841_fold': ('1768x2208', 420, 1.0, False),
    'e2_840x1120_tablet': ('1680x2240', 320, 1.0, False),
    'e3_841x673_fold_land': ('1768x2208', 420, 1.0, True),
    'f1_360x740_font1.3': ('1080x2220', 480, 1.3, False),
    'f2_360x740_font2.0': ('1080x2220', 480, 2.0, False),
    'g_915x412_land': ('1080x2400', 420, 1.0, True),
}


def adb_path():
    home = os.environ.get('ANDROID_HOME') or str(Path.home() / 'AppData/Local/Android/Sdk')
    return str(Path(home) / 'platform-tools' / 'adb.exe')


class Device:
    def __init__(self, serial):
        if not serial.startswith('emulator-'):
            sys.exit(f'이 실행기는 에뮬레이터 전용입니다(받은 시리얼 {serial}). 실기기 설정은 바꾸지 않습니다.')
        self.serial = serial
        self.adb = adb_path()

    def run(self, *args, check=True, timeout=1800):
        res = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True, text=True,
                             encoding='utf-8', errors='replace', timeout=timeout,
                             env={**os.environ, 'MSYS_NO_PATHCONV': '1'})
        if check and res.returncode != 0:
            raise RuntimeError(f'adb {" ".join(args)} 실패: {res.stderr.strip()}')
        return res.stdout

    def sh(self, cmd, check=True):
        return self.run('shell', cmd, check=check)

    def apply(self, size, density, font, landscape):
        self.sh(f'wm size {size}')
        self.sh(f'wm density {density}')
        self.sh(f'settings put system font_scale {font}')
        if landscape:
            self.sh('settings put system accelerometer_rotation 0')
            self.sh('settings put system user_rotation 1')
            self.sh('wm user-rotation lock 1', check=False)
        else:
            # 세로는 자동 회전(에뮬레이터 센서는 세로)으로 둔다. 회전 잠금(0)에 큰 글꼴을 주면 Android 16 에뮬레이터가
            # 시험 화면을 띄울 때 user_rotation을 1로 바꿔 가로로 찍혔다(2026-10-13 f1·f2 첫 측정).
            self.sh('wm user-rotation free', check=False)
            self.sh('settings put system user_rotation 0')
            self.sh('settings put system accelerometer_rotation 1')
        time.sleep(3)
        self.font = font

    def restore(self):
        self.sh('wm size reset', check=False)
        self.sh('wm density reset', check=False)
        self.sh('settings put system font_scale 1.0', check=False)
        self.sh('settings put system user_rotation 0', check=False)
        self.sh('wm user-rotation free', check=False)
        self.sh('settings put system accelerometer_rotation 1', check=False)


def run_module(dev, module, runner, dest, extra):
    pkg = runner.split('/')[0]
    remote = f'/sdcard/Android/data/{pkg}/files/e2e-shots'
    dev.sh(f'rm -rf {remote}', check=False)
    started = time.time()
    # 시스템 글꼴 조건이면 기본 글꼴로 그리던 시험도 시스템 글꼴을 쓰게 한다(useSystemFont).
    font = ['-e', 'systemFont', 'true'] if getattr(dev, 'font', 1.0) != 1.0 else []
    args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'fitSurvey', 'true', *font, *extra, runner]
    out = dev.run(*args, check=False, timeout=3600)
    elapsed = round(time.time() - started)
    dest.mkdir(parents=True, exist_ok=True)
    (dest / 'instrument.txt').write_text(out, encoding='utf-8')
    dev.run('pull', remote + '/.', str(dest), check=False)
    ok = 'OK (' in out
    fails = out.count('INSTRUMENTATION_STATUS_CODE: -2')
    return ok, fails, elapsed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default='emulator-5554')
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', default='', help='쉼표로 조건 이름(접두어 가능)')
    ap.add_argument('--modules', default=','.join(MODULES))
    ap.add_argument('--class', dest='klass', default='', help='-e class 로 넘길 시험 클래스(선택)')
    args = ap.parse_args()
    dev = Device(args.serial)
    out = Path(args.out)
    wanted = [c for c in CONDITIONS if not args.only or any(c.startswith(o) for o in args.only.split(','))]
    extra = ['-e', 'class', args.klass] if args.klass else []
    try:
        for cond in wanted:
            size, density, font, land = CONDITIONS[cond]
            dev.apply(size, density, font, land)
            print(f'== {cond}: {size}@{density} 글꼴 {font} {"가로" if land else "세로"}', flush=True)
            for module in args.modules.split(','):
                ok, fails, sec = run_module(dev, module, MODULES[module], out / cond / module, extra)
                print(f'   {module}: {"OK" if ok else "FAIL"} 실패 {fails} · {sec}s', flush=True)
    finally:
        dev.restore()
        print('설정 원복(wm size/density reset, font 1.0, 회전 자동)', flush=True)


if __name__ == '__main__':
    main()
