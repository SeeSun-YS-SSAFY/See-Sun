"""
QA용 장애 주입 프록시(로컬 전용, 표준 라이브러리만): 기기 → 프록시 → 로컬 백엔드.

    backend\\.venv\\Scripts\\python.exe scripts\\e2e\\fault_proxy.py --listen 8020 --upstream 8010
    adb reverse tcp:8000 tcp:8020

모드는 기기 시험이 HTTP로 바꾼다(adb reverse 경유, 백엔드로는 보내지 않음):
    GET /__fault/pass                        정상 전달
    GET /__fault/500?path=/me/&method=PATCH  조건에 맞는 요청에 500(운영 Django와 같은 HTML 본문)
    GET /__fault/503                         503
    GET /__fault/hang?seconds=25             응답 없이 기다림(앱 제한 시간 20초 → 시간 초과)
    GET /__fault/reset                       연결을 끊음(서버 꺼짐과 같음)
    GET /__fault/status                      지금 모드
조건(path 부분 문자열, method)이 없으면 /api/ 요청 전부에 적용한다(미디어 /media/는 path로 고를 때만).
"""
import argparse
import http.client
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATE = {'mode': 'pass', 'path': None, 'method': None, 'seconds': 25, 'hits': 0}
LOCK = threading.Lock()
UPSTREAM = ('127.0.0.1', 8010)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):  # 조용히: 장애를 건 요청만 남긴다
        pass

    def _control(self):
        parsed = urlparse(self.path)
        mode = parsed.path.split('/__fault/', 1)[1].strip('/') or 'status'
        q = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        with LOCK:
            if mode != 'status':
                STATE.update(mode=mode, path=q.get('path'), method=q.get('method'),
                             seconds=int(q.get('seconds', 25)), hits=0)
            body = json.dumps(STATE).encode()
        print(f'[fault] {STATE}', flush=True)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _matches(self):
        with LOCK:
            s = dict(STATE)
        if s['mode'] == 'pass':
            return None
        if s['path']:
            if s['path'] not in self.path:
                return None
        elif not self.path.startswith('/api/'):
            return None
        if s['method'] and s['method'].upper() != self.command:
            return None
        with LOCK:
            STATE['hits'] += 1
        print(f'[fault] {s["mode"]} {self.command} {self.path}', flush=True)
        return s

    def _handle(self):
        if self.path.startswith('/__fault/'):
            return self._control()
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else None
        fault = self._matches()
        if fault:
            if fault['mode'] in ('500', '503'):
                # 운영 Django(DEBUG=False)가 처리 못한 예외에 주는 것과 같은 HTML 본문(앱은 일반 문구로 바꿔 읽어야 한다)
                payload = (b'<!doctype html><html lang="en"><head><title>Server Error (500)</title></head>'
                           b'<body><h1>Server Error (500)</h1><p></p></body></html>')
                self.send_response(int(fault['mode']))
                self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return
            if fault['mode'] == 'hang':
                time.sleep(fault['seconds'])
                self.close_connection = True
                return
            if fault['mode'] == 'reset':
                try:
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b'\x01\x00\x00\x00\x00\x00\x00\x00')
                except OSError:
                    pass
                self.close_connection = True
                return
        conn = http.client.HTTPConnection(*UPSTREAM, timeout=60)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ('connection', 'keep-alive')}
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            res = conn.getresponse()
            data = res.read()
        except OSError as e:
            self.send_error(502, f'upstream: {e}')
            return
        self.send_response(res.status)
        for k, v in res.getheaders():
            if k.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                self.send_header(k, v)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = _handle


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--listen', type=int, default=8020)
    ap.add_argument('--upstream', type=int, default=8010)
    args = ap.parse_args()
    global UPSTREAM
    UPSTREAM = ('127.0.0.1', args.upstream)
    server = ThreadingHTTPServer(('127.0.0.1', args.listen), Handler)
    print(f'fault proxy 127.0.0.1:{args.listen} → 127.0.0.1:{args.upstream}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
