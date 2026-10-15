"""
시선 Android 통합 E2E(실서버) 실행기: 단계별 기기 시험 → 화면 사진 수집 → 서버 DB 확인.

준비(README §16 데모 환경과 같다):
  1) 로컬 백엔드:  powershell -ExecutionPolicy Bypass -File scripts\\demo\\run_local_backend.ps1 -Reset -Port 8010 -DevicePort 8000
  2) APK 설치:     cd android; .\\gradlew.bat :app:installDebug :app:installDebugAndroidTest
  3) 실행:         backend\\.venv\\Scripts\\python.exe scripts\\e2e\\run_integrated_e2e.py --port 8010 --out <폴더>

단계(RealServerJourneyTest의 메서드):
  a 온보딩  b 운동  c 마이페이지 키 수정  d 동의 철회·재동의·데이터 초기화  e 로그아웃·재로그인  f 회원 탈퇴
단계마다 서버 상태(scripts/e2e/server_state.py)를 확인하고 결과를 <out>/results.json 에 쓴다.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PY = ROOT / 'backend' / '.venv' / 'Scripts' / 'python.exe'
APP = 'kr.seesun.app.debug'
RUNNER = f'{APP}.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'kr.seesun.app.RealServerJourneyTest'
DEVICE_SHOTS = '/data/local/tmp/seesun-e2e'
PHASES = {
    'a': ('a_온보딩', '온보딩(연령·안내·동의·인증·프로필·건강·완료)'),
    'b': ('b_운동', '메인 → 운동(안전 안내·한 가지 운동·루틴 휴식·주간 기록)'),
    'c': ('c_마이페이지_키수정', '마이페이지 키 수정 → 목표 열량 재계산'),
    'd': ('d_동의철회_재동의_데이터초기화', '민감정보 동의 철회·다시 동의·건강 정보 저장·데이터 초기화(건강만)'),
    'e': ('e_로그아웃_재로그인', '로그아웃 → 기존 회원 재로그인'),
    'f': ('f_회원탈퇴', '회원 탈퇴 → 서버 개인정보 파기'),
}


def adb_path():
    home = os.environ.get('ANDROID_HOME') or str(Path.home() / 'AppData/Local/Android/Sdk')
    return str(Path(home) / 'platform-tools' / 'adb.exe')


ADB = adb_path()


def adb(*args, check=True, timeout=900):
    res = subprocess.run([ADB, *args], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f'adb {" ".join(args)} 실패: {res.stderr.strip()}')
    return res.stdout


def server_state(phone=None, user_id=None):
    cmd = [str(PY), str(ROOT / 'scripts' / 'e2e' / 'server_state.py')]
    cmd += ['--user-id', user_id] if user_id else ['--phone', phone]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', env={**os.environ, 'PYTHONUTF8': '1'})
    return json.loads(out.stdout)


def run_phase(key, phone):
    method = PHASES[key][0]
    adb('logcat', '-c')
    started = time.time()
    out = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'realServer', 'true', '-e', 'phone', phone,
              '-e', 'class', f'{CLASS}#{method}', RUNNER, check=False)
    elapsed = round(time.time() - started, 1)
    ok = 'OK (1 test)' in out
    failure = None
    if not ok:
        m = re.search(r'INSTRUMENTATION_STATUS: stack=(.+?)(?:INSTRUMENTATION_STATUS: |\Z)', out, re.S)
        failure = (m.group(1) if m else out)[-1500:]
    log = adb('logcat', '-d', '-s', 'SeeSunE2E:I', check=False)
    facts = dict(re.findall(r'FACT (\w+)=(\S*)', log))
    shots = re.findall(r'SHOT (\S+)', log)
    return {'ok': ok, 'elapsed_s': elapsed, 'facts': facts, 'shots': shots, 'failure': failure}


def checks_for(key, st, facts, prev):
    """단계별 서버 확인. (이름, 통과, 근거)"""
    c = []
    add = lambda name, ok, detail='': c.append({'check': name, 'ok': bool(ok), 'detail': str(detail)})
    if key == 'a':
        p = st.get('pii', {})
        add('사용자 생성(전화번호 해시로 조회)', st.get('found'), st.get('user_id'))
        add('프로필 저장(이름·생년월일·성별·키·몸무게·목표)',
            (p.get('name'), p.get('birthdate'), p.get('gender'), p.get('height_cm'), p.get('weight_kg'), p.get('goal'))
            == ('김시선', '1956-03-15', 'F', 158, 56, 'MAINTAIN'), p)
        add('완료 화면 목표 열량 = 서버 target_kcal', str(p.get('target_kcal')) == facts.get('onboarding_target_kcal'),
            f"화면 {facts.get('onboarding_target_kcal')} / 서버 {p.get('target_kcal')}")
        agreed = {x['type'] for x in st.get('consents', []) if x['agreed'] and not x['withdrawn']}
        add('필수 동의(TOS·PRIVACY)·민감정보·국외이전 고지 기록', {'TOS', 'PRIVACY', 'SENSITIVE', 'OVERSEAS'} <= agreed, sorted(agreed))
        add('동의 입증 정보(앱 버전·화면 ID)', all(x['app_version'] and x['screen_id'] for x in st.get('consents', [])),
            [(x['type'], x['method'], x['screen_id'], x['app_version']) for x in st.get('consents', [])])
        h = st.get('health') or {}
        add('건강 정보(질환 2·알레르기 2·목표)', len(h.get('diseases', [])) == 2 and len(h.get('allergen_codes', [])) == 2, h)
    elif key == 'b':
        s = st.get('exercise_sessions', [])
        manual = [x for x in s if x['mode'] == 'MANUAL']
        routine = [x for x in s if x['mode'] == 'CURRICULUM']
        add('한 가지 운동 기록 서버 저장(COMPLETED·10초 이상)',
            any(x['status'] == 'COMPLETED' and (x['duration_ms'] or 0) >= 10_000 for x in manual),
            [(x['exercise_name'], x['status'], x['duration_ms']) for x in manual])
        add('루틴 기록 서버 저장(CURRICULUM·8항목·첫 운동 완료·나머지 건너뜀)',
            any(len(x['items']) == 8 and not x['items'][0]['skipped'] for x in routine),
            [(x['exercise_name'], x['status'], [(i['seq'], i['skipped'], i['skip_reason']) for i in x['items']]) for x in routine])
        add('기본 루틴은 이름만·mode=CURRICULUM(B6 설계: 나만의 루틴만 playlist_id)',
            all(x['exercise_name'] == '근력 운동 루틴' and not x['playlist_id'] for x in routine) and routine,
            [(x['exercise_name'], x['playlist_id']) for x in routine])
        add('client_uuid 멱등 키', all(x['client_uuid'] for x in s), len(s))
        add('주간 기록 화면 횟수 ≥ 1', facts.get('weekly_count', '0').isdigit() and int(facts['weekly_count']) >= 1,
            f"화면 {facts.get('weekly_count')}회 / 서버 세션 {len(s)}건")
    elif key == 'c':
        p, old = st.get('pii', {}), (prev or {}).get('pii', {})
        add('키 170 저장', p.get('height_cm') == 170, p.get('height_cm'))
        add('목표 열량 재계산(키 158→170)', p.get('target_kcal') and p.get('target_kcal') != old.get('target_kcal'),
            f"{old.get('target_kcal')} → {p.get('target_kcal')}")
        add('마이페이지 표시 = 서버 target_kcal', str(p.get('target_kcal')) == facts.get('mypage_target_kcal_after_height'),
            f"화면 {facts.get('mypage_target_kcal_after_height')} / 서버 {p.get('target_kcal')}")
    elif key == 'd':
        sens = [x for x in st.get('consents', []) if x['type'] == 'SENSITIVE']
        add('민감정보 철회 기록(withdrawn) 후 다시 동의 기록', any(x['withdrawn'] for x in sens) and sens[-1]['agreed']
            and not sens[-1]['withdrawn'], [(x['agreed'], x['withdrawn'], x['screen_id']) for x in sens])
        h = st.get('health')
        add('데이터 초기화(건강만) 후 서버 건강 정보 비어 있음', h is None or not any(h.values()), h)
        add('계정은 남음(초기화는 탈퇴 아님)', st.get('is_active') and not st.get('is_deleted'))
        add('운동 기록은 그대로(건강만 초기화)', len(st.get('exercise_sessions', [])) == len((prev or {}).get('exercise_sessions', [])),
            len(st.get('exercise_sessions', [])))
    elif key == 'e':
        add('같은 계정으로 재로그인(새 사용자 생성 없음)', st.get('user_id') == (prev or {}).get('user_id'), st.get('user_id'))
        add('로그아웃한 갱신 토큰은 폐기(blacklist)', st['tokens']['blacklisted'] >= 1, st['tokens'])
    elif key == 'f':
        p = st.get('pii', {})
        add('탈퇴 표시(is_deleted·비활성)', st.get('is_deleted') and not st.get('is_active'), st.get('deleted_at'))
        add('개인정보 필드 모두 비움(이름·전화·해시·UID·생년월일·성별·키·몸무게·목표·열량)', not any(p.values()), p)
        add('동의 기록 삭제', not st.get('consents'), len(st.get('consents', [])))
        add('건강 정보 삭제', st.get('health') is None, st.get('health'))
        add('운동 기록 삭제', not st.get('exercise_sessions'), len(st.get('exercise_sessions', [])))
        add('인증 공급자 연결 삭제', st.get('auth_providers') == 0, st.get('auth_providers'))
        add('발급 토큰 전부 폐기', st['tokens']['outstanding'] == st['tokens']['blacklisted'], st['tokens'])
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8000, help='PC 백엔드 포트(기기 쪽은 8000)')
    ap.add_argument('--phone', default='01055501234')
    ap.add_argument('--phases', default='abcdef')
    ap.add_argument('--out', required=True)
    ap.add_argument('--keep-app-data', action='store_true', help='a 단계 전에 앱 데이터를 지우지 않는다')
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/healthz/', timeout=5) as r:
        assert r.status == 200, '백엔드가 켜져 있지 않아요'
    adb('reverse', 'tcp:8000', f'tcp:{args.port}')
    if 'a' in args.phases and not args.keep_app_data:
        before = server_state(phone=args.phone)
        if before.get('found'):
            sys.exit(f'전화번호 {args.phone}는 이미 서버에 있어요. 다른 번호(--phone)를 쓰세요.')
        adb('shell', 'pm', 'clear', APP)
        adb('shell', 'rm', '-rf', DEVICE_SHOTS)
    adb('shell', 'pm', 'grant', APP, 'android.permission.RECORD_AUDIO', check=False)
    adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS', check=False)

    results_path = out / 'results.json'
    results = json.loads(results_path.read_text(encoding='utf-8')) if results_path.exists() else {'phases': {}}
    prev = results.get('last_state')
    user_id = results.get('user_id')
    for key in args.phases:
        name, desc = PHASES[key]
        print(f'== {key} {desc}', flush=True)
        r = run_phase(key, args.phone)
        st = server_state(user_id=user_id) if (key == 'f' and user_id) else server_state(phone=args.phone)
        if st.get('found') and not user_id:
            user_id = st['user_id']
        (out / 'server').mkdir(exist_ok=True)
        (out / 'server' / f'{key}_{name}.json').write_text(json.dumps(st, ensure_ascii=False, indent=1), encoding='utf-8')
        r['server_checks'] = checks_for(key, st, r['facts'], prev) if st.get('found') else [
            {'check': '서버 사용자', 'ok': False, 'detail': st}]
        r['description'] = desc
        results['phases'][key] = r
        prev = st
        adb('pull', f'{DEVICE_SHOTS}/.', str(out), check=False)
        status = '통과' if r['ok'] and all(x['ok'] for x in r['server_checks']) else '실패'
        print(f'   기기 {"OK" if r["ok"] else "FAIL"} {r["elapsed_s"]}s · 서버 확인 '
              f'{sum(x["ok"] for x in r["server_checks"])}/{len(r["server_checks"])} → {status}', flush=True)
        for x in r['server_checks']:
            if not x['ok']:
                print('     ✗', x['check'], str(x['detail'])[:300])
        if r['failure']:
            print('     기기 실패:', r['failure'][:800])
        results.update(last_state=st, user_id=user_id)
        results_path.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding='utf-8')
        if not r['ok']:
            break


if __name__ == '__main__':
    main()
