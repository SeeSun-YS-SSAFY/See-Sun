"""
시선 Android 실서버 QA 매트릭스 실행기(RealServerQaTest + 호스트 쪽 행).

준비:
  1) 로컬 백엔드(8010):  scripts\\demo\\run_local_backend.ps1 -Port 8010 -DevicePort 8000
  2) 장애 주입 프록시:   backend\\.venv\\Scripts\\python.exe scripts\\e2e\\fault_proxy.py --listen 8020 --upstream 8010
  3) APK:                android> .\\gradlew.bat :app:installDebug :app:installDebugAndroidTest
  4) 실행:               backend\\.venv\\Scripts\\python.exe scripts\\e2e\\run_qa.py --out <폴더> --phone 01066600001

순서: q01 신규 가입 → q02 메인·운동 → q03 마이페이지·토큰 → [호스트: 이용약관 새 버전] q04 다시 동의 → [호스트: 되돌림]
      → [호스트: TalkBack 켬] q05 의미 검사 → [호스트: 프로세스 종료 행 P-01·P-02] → q06 탈퇴
      → [호스트: 프로세스 종료 P-03(온보딩 복원)] → [TalkBack 켬] q07 시작 화면 → TalkBack 끔.
결과: <out>/qa/qa_results.json (행 id·결과·근거), 화면 사진 <out>/qa/*.png, 서버 확인 <out>/qa/server_*.json
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_integrated_e2e import ADB, APP, CLASS, DEVICE_SHOTS, PY, ROOT, RUNNER, adb, server_state  # noqa: E402

QA_CLASS = 'kr.seesun.app.RealServerQaTest'
TALKBACK = 'com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'
ROWS = []


def record(row_id, title, ok, detail=''):
    ROWS.append({'id': row_id, 'title': title, 'result': 'PASS' if ok else 'FAIL', 'detail': str(detail)[:600], 'by': 'host'})
    print(f'   {row_id} {"PASS" if ok else "FAIL"} {title} {"" if ok else detail}', flush=True)


def run_method(method, phone, cls=QA_CLASS):
    adb('logcat', '-c')
    out = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'realServer', 'true', '-e', 'phone', phone,
              '-e', 'class', f'{cls}#{method}', RUNNER, check=False, timeout=1800)
    log = adb('logcat', '-d', '-s', 'SeeSunE2E:I', check=False)
    rows = re.findall(r'ROW (\S+) (PASS|FAIL) (\S+)(?: :: (.*))?', log)
    for rid, res, title, reason in rows:
        ROWS.append({'id': rid, 'title': title, 'result': res, 'detail': reason.strip(), 'by': method})
        print(f'   {rid} {res} {title} {reason[:300] if res == "FAIL" else ""}', flush=True)
    facts = dict(re.findall(r'FACT (\w+)=(\S*)', log))
    trees = re.findall(r'TREE (\S+)\n', log)
    ok = 'OK (1 test)' in out
    if not ok:
        m = re.search(r'stack=(.+?)(?:INSTRUMENTATION_STATUS|\Z)', out, re.S)
        print('   [메서드 중단]', (m.group(1) if m else out)[:900], flush=True)
    return {'ok': ok, 'facts': facts, 'rows': len(rows), 'trees': trees}


def django(code):
    script = ('import os,sys;sys.path.insert(0,"backend");os.chdir("backend");'
              'os.environ["DJANGO_DEBUG"]="true";os.environ.setdefault("DJANGO_SQLITE_PATH","db.sqlite3");'
              'os.environ.setdefault("DJANGO_SETTINGS_MODULE","config.settings");import django;django.setup();' + code)
    res = subprocess.run([str(PY), '-c', script], cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
    if res.returncode:
        raise RuntimeError(res.stderr[-800:])
    return res.stdout.strip()


def bump_tos():
    return django(
        'from apps.consents.models import ConsentDocument as D;from django.utils import timezone;'
        'cur=D.objects.filter(type="TOS",is_active=True).order_by("-effective_at").first();'
        'D.objects.update_or_create(type="TOS",version="2026-10-13-qa",defaults=dict(title=cur.title,summary=cur.summary,'
        'url=cur.url,required=True,kind=cur.kind,effective_at=timezone.localdate(),is_active=True));print("bumped")')


def restore_tos():
    return django('from apps.consents.models import ConsentDocument as D;'
                  'print(D.objects.filter(type="TOS",version="2026-10-13-qa").update(is_active=False))')


def talkback(on):
    if on:
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', TALKBACK)
        adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    else:
        adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services', check=False)
        adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '0')
    time.sleep(4)


# ── 호스트 UI 조작(uiautomator dump + input) ─────────────────────

def ui_nodes():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/ui.xml', check=False)
    xml = adb('exec-out', 'cat', '/data/local/tmp/ui.xml', check=False)
    nodes = []
    try:
        for n in ET.fromstring(xml).iter('node'):
            b = [int(x) for x in re.findall(r'\d+', n.get('bounds', ''))]
            nodes.append({'text': n.get('text', ''), 'desc': n.get('content-desc', ''),
                          'pkg': n.get('package', ''), 'center': ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2) if len(b) == 4 else None})
    except ET.ParseError:
        pass
    return nodes


def find(text, nodes=None, exact=False):
    """정확히 같은 글자(또는 설명)를 먼저, exact가 아니면 포함하는 것도 찾는다(화면 밖 Compose 노드는 dump에 없다)."""
    nodes = nodes or ui_nodes()
    for n in nodes:
        if text in (n['text'], n['desc']):
            return n
    if exact:
        return None
    for n in nodes:
        if text in n['text'] or text in n['desc']:
            return n
    return None


def wait_ui(text, timeout=20, exact=False):
    end = time.time() + timeout
    while time.time() < end:
        n = find(text, exact=exact)
        if n:
            return n
        time.sleep(1)
    return None


def need_ui(text, timeout=20):
    """화면에 꼭 있어야 하는 글자. 없으면 그 자리에서 실패시킨다(엉뚱한 버튼을 누르지 않게)."""
    n = wait_ui(text, timeout)
    if not n:
        raise RuntimeError(f'기대한 화면이 아님: "{text}" 없음')
    return n


def tap(text, timeout=20, exact=True):
    n = wait_ui(text, timeout, exact=exact)
    for _ in range(5):  # 화면 아래에 있으면 올려 가며 찾는다
        if n:
            break
        adb('shell', 'input', 'swipe', '540', '1800', '540', '900', '300')
        time.sleep(0.8)
        n = find(text, exact=exact)
    if not n:
        raise RuntimeError(f'화면에 "{text}" 없음')
    adb('shell', 'input', 'tap', str(n['center'][0]), str(n['center'][1]))
    time.sleep(1.2)


def scroll_tap(text, tries=6):
    for _ in range(tries):
        if find(text):
            return tap(text)
        adb('shell', 'input', 'swipe', '540', '1800', '540', '900', '300')
        time.sleep(0.8)
    raise RuntimeError(f'스크롤해도 "{text}" 없음')


def host_shot(name):
    adb('shell', 'screencap', '-p', f'{DEVICE_SHOTS}/qa/{name}.png')


def launch_app():
    adb('shell', 'monkey', '-p', APP, '-c', 'android.intent.category.LAUNCHER', '1', check=False)
    time.sleep(3)


def kill_in_background():
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1.5)
    adb('shell', 'am', 'kill', APP)
    time.sleep(1.5)
    alive = adb('shell', 'pidof', APP, check=False).strip()
    return not alive


def host_rows_signed_in(phone):
    # P-01 로그인 상태에서 백그라운드 프로세스 종료(am kill) → 다시 열면 메인(세션 유지)
    try:
        launch_app()
        need_ui('오늘 요약', 30)
        killed = kill_in_background()
        launch_app()
        ok = wait_ui('오늘 요약', 30) is not None
        host_shot('P-01_프로세스종료후_메인')
        record('P-01', '로그인상태_프로세스종료_복귀_메인유지', ok and killed, f'killed={killed}')
    except Exception as e:  # noqa: BLE001
        host_shot('P-01_FAIL')
        record('P-01', '로그인상태_프로세스종료_복귀_메인유지', False, e)
    # P-02 운동 중: am kill(저메모리 정리)로는 포그라운드 서비스 덕분에 죽지 않아야 하고(재생 유지),
    # P-03 실제로 죽으면(am crash) 다음 운동 화면 진입 때 ABORTED·APP_KILLED로 확정·전송돼야 한다.
    try:
        before = len(server_state(phone=phone).get('exercise_sessions', []))
        launch_app()
        need_ui('오늘 요약', 30)
        tap('운동')
        need_ui('운동 방식을 골라')
        tap('한 가지 운동')
        tap('전체 운동')
        tap('밴드 풀 어파트')
        if wait_ui('건강 확인 시작', 8, exact=True):  # 이 사용자의 첫 운동이면 안전 안내부터
            tap('건강 확인 시작')
            for _ in range(7):
                tap('아니요')
            tap('확인하고 운동하기')
        need_ui('상태: 재생 중', 40)
        time.sleep(17)  # 생존 신호(15초) 한 번 이상
        host_shot('P-02_운동중')
        survived = not kill_in_background()
        record('P-02', '운동중_am_kill_포그라운드서비스로_생존', survived, f'pid={adb("shell", "pidof", APP, check=False).strip()}')
        adb('shell', 'am', 'crash', APP, check=False)
        time.sleep(3)
        crashed = not adb('shell', 'pidof', APP, check=False).strip()
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # 혹시 뜬 오류 창 닫기
        launch_app()
        need_ui('오늘 요약', 30)
        tap('운동')
        need_ui('운동 방식을 골라')
        deadline = time.time() + 120
        sessions = []
        while time.time() < deadline:
            sessions = server_state(phone=phone).get('exercise_sessions', [])
            if len(sessions) > before:
                break
            time.sleep(5)
        new = sessions[before:] if len(sessions) > before else []
        ok = crashed and any(s['status'] == 'ABORTED' and s['abnormal_end_reason'] == 'APP_KILLED' for s in new)
        host_shot('P-03_비정상종료후_운동화면')
        record('P-03', '운동중_비정상종료_다음진입때_APP_KILLED_전송', ok,
               [f'crashed={crashed}'] + [(s['exercise_name'], s['status'], s['abnormal_end_reason'], s['duration_ms']) for s in new])
    except Exception as e:  # noqa: BLE001
        host_shot('P-02_FAIL')
        record('P-02', '운동중_프로세스종료_흐름', False, e)


def host_row_onboarding_restore():
    # P-03 온보딩 중(로그인 전) 백그라운드 프로세스 종료 → 같은 단계·값으로 복원(SavedStateHandle)
    try:
        adb('shell', 'am', 'force-stop', APP)
        launch_app()
        need_ui('시선 시작하기', 30)
        tap('처음 시작하기')
        tap('키보드로 직접 입력', timeout=5)
        adb('shell', 'input', 'text', '19700101')
        time.sleep(1)
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # 키보드 닫기
        tap('다음')
        tap('확인했어요')
        wait_ui('See:Sun 이용약관', 20)
        host_shot('P-04_종료전_이용약관')
        killed = kill_in_background()
        launch_app()
        ok = wait_ui('See:Sun 이용약관', 20) is not None
        host_shot('P-04_복원후')
        record('P-04', '온보딩중_프로세스종료_같은단계_복원', ok and killed, f'killed={killed}')
    except Exception as e:  # noqa: BLE001
        host_shot('P-04_FAIL')
        record('P-04', '온보딩중_프로세스종료_같은단계_복원', False, e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--proxy-port', type=int, default=8020)
    ap.add_argument('--phone', default='01066600001')
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', default='', help='쉼표로 고른 단계만(q01~q08, P=P1+P4). 기본은 q08을 뺀 전체 순서. 예: --only q01,q08')
    args = ap.parse_args()
    out = Path(args.out) / 'qa'
    out.mkdir(parents=True, exist_ok=True)
    only = set(filter(None, args.only.split(',')))
    want = lambda k: not only or k in only  # noqa: E731

    adb('reverse', 'tcp:8000', f'tcp:{args.proxy_port}')
    adb('shell', 'svc', 'wifi', 'enable')
    adb('shell', 'svc', 'data', 'enable')
    talkback(False)
    if want('q01'):
        if server_state(phone=args.phone).get('found'):
            sys.exit(f'{args.phone}는 이미 서버에 있어요. 다른 --phone을 쓰세요.')
        adb('shell', 'pm', 'clear', APP)
        adb('shell', 'rm', '-rf', f'{DEVICE_SHOTS}/qa')
    adb('shell', 'pm', 'grant', APP, 'android.permission.RECORD_AUDIO', check=False)
    adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS', check=False)

    meta = {}
    for key, method in [('q01', 'q01_온보딩_입력검증_네트워크'), ('q02', 'q02_메인_운동_네트워크_수명주기'),
                        ('q03', 'q03_마이페이지_설정_토큰')]:
        if want(key):
            print(f'== {method}', flush=True)
            meta[key] = run_method(method, args.phone)
    if want('q03'):
        st = server_state(phone=args.phone)
        (out / 'server_after_q01_q03.json').write_text(json.dumps(st, ensure_ascii=False, indent=1), encoding='utf-8')
        p = st.get('pii', {})
        record('SRV-01', '서버_신규가입_한명_민감정보미동의', st.get('found') and not any(
            c['type'] == 'SENSITIVE' and c['agreed'] and c['screen_id'].startswith('LGL') for c in st.get('consents', [])),
            [(c['type'], c['agreed'], c['screen_id']) for c in st.get('consents', [])])
        record('SRV-02', '서버_키수정_500뒤_재저장만_반영(180)', p.get('height_cm') == 180, p.get('height_cm'))
        if want('q02'):
          record('SRV-03', '서버_오프라인운동_재연결후_저장', any(
            s['exercise_name'] == '맨몸 스쿼트' and s['status'] == 'COMPLETED' for s in st.get('exercise_sessions', [])),
            [(s['exercise_name'], s['status']) for s in st.get('exercise_sessions', [])])
          record('SRV-04', '서버_두번누른_플랭크_1건만', sum(
            s['exercise_name'] == '플랭크' for s in st.get('exercise_sessions', [])) == 1,
            [s['exercise_name'] for s in st.get('exercise_sessions', [])])
        record('SRV-05', '서버_동의제출_시간초과_재시도_중복없음', len(
            [c for c in st.get('consents', []) if c['type'] == 'TOS']) == 1, [c['type'] for c in st.get('consents', [])])
    if want('q04'):
        print('== [호스트] 이용약관 새 버전', bump_tos(), flush=True)
        try:
            print('== q04_동의문서변경_다시동의', flush=True)
            meta['q04'] = run_method('q04_동의문서변경_다시동의', args.phone)
        finally:
            print('== [호스트] 이용약관 QA 버전 사용 중지', restore_tos(), flush=True)
    if want('q05'):
        talkback(True)
        try:
            print('== q05_TalkBack_의미검사', flush=True)
            meta['q05'] = run_method('q05_TalkBack_의미검사', args.phone)
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/ui_talkback.xml', check=False)
            (out / 'semantics_talkback_last_screen.xml').write_text(
                adb('exec-out', 'cat', '/data/local/tmp/ui_talkback.xml', check=False), encoding='utf-8')
        finally:
            talkback(False)
    if want('P') or 'P1' in only:
        print('== [호스트] 프로세스 종료 행(로그인 상태)', flush=True)
        host_rows_signed_in(args.phone)
    if 'q08' in only:  # 따로 실행: --only q01,q08 (새 번호로 가입한 뒤 나만의 루틴)
        routine_cls = 'kr.seesun.app.RealServerRoutineQaTest'
        print('== q08_나만의루틴_만들기_재생', flush=True)
        meta['q08'] = run_method('q08_나만의루틴_만들기_재생', args.phone, routine_cls)
        st = server_state(phone=args.phone)
        mine = [s for s in st.get('exercise_sessions', []) if s['exercise_name'] == '아침 근력']
        record('SRV-07', '서버_나만의루틴_기록은_실제_playlist_id(B6)', any(s['mode'] == 'CURRICULUM' and s['playlist_id'] for s in mine),
               [(s['exercise_name'], s['mode'], s['playlist_id']) for s in mine])
        print('== q09_나만의루틴_삭제', flush=True)
        meta['q09'] = run_method('q09_나만의루틴_삭제', args.phone, routine_cls)
        st = server_state(phone=args.phone)
        mine = [s for s in st.get('exercise_sessions', []) if s['exercise_name'] == '아침 근력']
        record('SRV-08', '서버_루틴삭제후_지난기록은_남음(playlist만_비움)', bool(mine) and not any(s['playlist_id'] for s in mine),
               [(s['exercise_name'], s['playlist_id']) for s in mine])
    if want('q06'):
        uid = server_state(phone=args.phone).get('user_id')
        print('== q06_탈퇴_오프라인_재가입', flush=True)
        meta['q06'] = run_method('q06_탈퇴_오프라인_재가입', args.phone)
        if uid:
            st = server_state(user_id=uid)
            (out / 'server_after_delete.json').write_text(json.dumps(st, ensure_ascii=False, indent=1), encoding='utf-8')
            record('SRV-06', '서버_탈퇴_개인정보_파기', st.get('is_deleted') and not any(st['pii'].values()), st.get('pii'))
    if want('P') or 'P4' in only:
        print('== [호스트] 프로세스 종료 행(온보딩 복원)', flush=True)
        host_row_onboarding_restore()
    if want('q07'):
        talkback(True)
        try:
            print('== q07_TalkBack_시작화면', flush=True)
            meta['q07'] = run_method('q07_TalkBack_시작화면', args.phone)
        finally:
            talkback(False)

    adb('pull', f'{DEVICE_SHOTS}/qa/.', str(out), check=False)
    adb('reverse', 'tcp:8000', 'tcp:8010', check=False)
    result_path = out / 'qa_results.json'
    old = json.loads(result_path.read_text(encoding='utf-8')) if result_path.exists() and only else {'rows': []}
    ids = {r['id'] for r in ROWS}
    rows = [r for r in old['rows'] if r['id'] not in ids] + ROWS
    result_path.write_text(json.dumps({'rows': rows, 'meta': meta}, ensure_ascii=False, indent=1), encoding='utf-8')
    passed = sum(r['result'] == 'PASS' for r in rows)
    print(f'== 합계 {passed}/{len(rows)} 통과', flush=True)


if __name__ == '__main__':
    main()
