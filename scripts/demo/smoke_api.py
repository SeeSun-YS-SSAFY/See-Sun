"""
로컬 데모 백엔드 점검(표준 라이브러리만 사용).

가짜 Firebase 토큰으로 로그인 → 필수 동의 → 운동 카테고리·목록·상세 → 안내 음성·픽토그램 URL이 200인지,
운동 기록 일괄 업로드(멱등)와 주간 집계까지 확인한다. run_local_backend.ps1 로 서버를 띄운 뒤 실행한다.

    backend\\.venv\\Scripts\\python.exe scripts\\demo\\smoke_api.py [--base http://127.0.0.1:8000/api/v1/]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))


def call(method, url, token=None, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    if token:
        req.add_header('Authorization', f'Bearer {token}')
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw and res.headers.get_content_type() == 'application/json' else raw)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def check(label, ok, detail=''):
    print(f"[{'통과' if ok else '실패'}] {label} {detail}")
    if not ok:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8000/api/v1/')
    parser.add_argument('--uid', default='demo-smoke')
    args = parser.parse_args()
    base = args.base.rstrip('/') + '/'

    status, body = call('POST', base + 'auth/firebase/', body={
        'id_token': f'fake:{args.uid}:+821000000000', 'birthdate': '1960-03-01', 'name': '데모'})
    check('가짜 Firebase 토큰 교환', status == 200, str(status))
    token = body['access_token']

    status, docs = call('GET', base + 'consents/documents/')
    required = [d for d in docs if d['required']]
    status, _ = call('POST', base + 'me/consents/', token, {'items': [
        {'type': d['type'], 'version': d['version'], 'agreed': True, 'method': 'TOUCH'} for d in required]})
    check('필수 동의', status == 200, ', '.join(d['type'] for d in required))

    status, cats = call('GET', base + 'exercises/category/', token)
    check('운동 카테고리', status == 200 and len(cats) == 4, f'{len(cats)}개')
    total, first = 0, None
    for cat in cats:
        status, listing = call('GET', base + f"exercises/category/{cat['category_id']}/", token)
        total += len(listing['exercises'])
        first = first or listing['exercises'][0]
    check('운동 목록 합계 29개', total == 29, str(total))

    status, detail = call('GET', base + f"exercises/{first['exercise_id']}/", token)
    audios = detail.get('audios', [])
    check('운동 상세·구간 음성', status == 200 and len(audios) > 0, f"{detail['exercise_name']} 구간 {len(audios)}개")
    for label, url in (('안내 음성', audios[0]['url']), ('픽토그램', detail['pictograms'][0]),
                       ('운동 이름 음성', first.get('name_audio_url'))):
        status, payload = call('GET', url)
        check(f'{label} URL 200', status == 200 and len(payload) > 0, f'{url} ({len(payload)} bytes)')

    started = datetime.now(KST) - timedelta(minutes=2)
    session = {'client_uuid': str(uuid.uuid4()), 'exercise_name': detail['exercise_name'], 'mode': 'MANUAL',
               'started_at': started.isoformat(), 'ended_at': (started + timedelta(seconds=30)).isoformat(),
               'status': 'COMPLETED',
               'items': [{'exercise_id': detail['exercise_id'], 'sequence_no': 1, 'duration_ms': 30000}]}
    status, res = call('POST', base + 'exercise-sessions/bulk/', token, {'sessions': [session]})
    check('운동 기록 업로드', status == 200 and res['results'][0]['status'] == 'created', str(res)[:120])
    status, res = call('POST', base + 'exercise-sessions/bulk/', token, {'sessions': [session]})
    check('같은 client_uuid 재전송 = duplicate', res['results'][0]['status'] == 'duplicate')
    status, weekly = call('GET', base + 'exercise/weekly/', token)
    check('주간 운동 집계', status == 200 and weekly['session_count'] >= 1, weekly.get('tts_message', ''))
    print('모두 통과')


if __name__ == '__main__':
    main()
