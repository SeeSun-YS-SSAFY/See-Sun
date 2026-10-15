"""docs/policies/*.md 를 site/*.html 로 바꾼다.

정본은 Markdown(docs/policies/)이다. 문구를 고칠 때는 Markdown을 고치고 이 스크립트를 다시 돌린다.
필요: Python 3.10+, `pip install markdown`
실행: python site/build.py        (저장소 루트에서)

- 처리방침의 "부록(게시하지 않음)" 절은 웹 페이지에서 뺀다.
- 2단계 제목(h2)에 "1. …" 번호가 있으면 id="s1" 처럼 고정 앵커를 단다(앱 동의 화면의 url이 이 앵커를 쓴다).
- 표는 가로 스크롤 영역(키보드로 초점 가능)으로 감싸고, 머리글 칸에 scope="col"을 단다.
"""
from __future__ import annotations

import html
import re
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / 'site'

PAGES = [
    {
        'src': ROOT / 'docs' / 'policies' / '개인정보처리방침.md',
        'out': SITE / 'privacy.html',
        'title': '개인정보 처리방침 — 시선(See:Sun)',
        'nav': 'privacy',
        'cut_from': '## 부록(게시하지 않음)',
    },
    {
        'src': ROOT / 'docs' / 'policies' / '이용약관.md',
        'out': SITE / 'terms.html',
        'title': '이용약관 — 시선(See:Sun)',
        'nav': 'terms',
        'cut_from': None,
    },
]

NAV = [
    ('index', 'index.html', '처음'),
    ('privacy', 'privacy.html', '개인정보 처리방침'),
    ('terms', 'terms.html', '이용약관'),
    ('deletion', 'account-deletion.html', '계정 삭제 요청'),
]

TEMPLATE = '''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<link rel="stylesheet" href="assets/style.css">
</head>
<body>
<a class="skip-link" href="#main">본문으로 건너뛰기</a>
<header class="site-header">
<p class="brand">시선(See:Sun)</p>
<nav class="site-nav" aria-label="사이트 메뉴">
<ul>
{nav}
</ul>
</nav>
</header>
<main id="main" tabindex="-1">
{body}
</main>
<footer class="site-footer">
<p>운영: enVision(연세대학교 학생 동아리), 대표 ○○○ · 문의 ○○○</p>
<p>이 페이지는 Markdown 정본(docs/policies)에서 만든 것입니다. 쿠키와 방문 분석 도구를 쓰지 않습니다.</p>
</footer>
</body>
</html>
'''


def nav_html(current: str) -> str:
    items = []
    for key, href, label in NAV:
        cur = ' aria-current="page"' if key == current else ''
        items.append(f'<li><a href="{href}"{cur}>{label}</a></li>')
    return '\n'.join(items)


def add_section_ids(body: str) -> str:
    def repl(m: re.Match) -> str:
        text = m.group(1)
        num = re.match(r'(\d+)\.\s', text)
        if num:
            return f'<h2 id="s{num.group(1)}">{text}</h2>'
        if text.startswith('처음에'):
            return f'<h2 id="summary">{text}</h2>'
        if text == '목차':
            return f'<h2 id="toc">{text}</h2>'
        art = re.match(r'제(\d+)조', text)
        if art:
            return f'<h2 id="a{art.group(1)}">{text}</h2>'
        return m.group(0)
    return re.sub(r'<h2>(.*?)</h2>', repl, body)


def link_toc(body: str) -> str:
    """'목차' 절의 번호 목록 항목을 각 절 앵커로 잇는다."""
    m = re.search(r'(<h2 id="toc">목차</h2>\s*<ol>)(.*?)(</ol>)', body, flags=re.S)
    if not m:
        return body
    items = re.findall(r'<li>(.*?)</li>', m.group(2), flags=re.S)
    linked = ''.join(f'\n<li><a href="#s{i}">{t}</a></li>' for i, t in enumerate(items, start=1))
    return body[:m.start()] + m.group(1) + linked + '\n' + m.group(3) + body[m.end():]


def wrap_tables(body: str) -> str:
    counter = {'n': 0}

    def repl(m: re.Match) -> str:
        counter['n'] += 1
        return (f'<div class="table-wrap" role="region" tabindex="0" '
                f'aria-label="표 {counter["n"]}, 좌우로 넘겨 볼 수 있음">{m.group(0)}</div>')
    body = re.sub(r'<table>.*?</table>', repl, body, flags=re.S)
    return body.replace('<th>', '<th scope="col">')


def build(page: dict) -> None:
    text = page['src'].read_text(encoding='utf-8')
    if page['cut_from'] and page['cut_from'] in text:
        text = text.split(page['cut_from'], 1)[0].rstrip().rstrip('-').rstrip()
    body = markdown.markdown(text, extensions=['tables', 'sane_lists'], output_format='html')
    body = add_section_ids(body)
    body = link_toc(body)
    body = wrap_tables(body)
    out = TEMPLATE.format(title=html.escape(page['title']), nav=nav_html(page['nav']), body=body)
    page['out'].write_text(out, encoding='utf-8', newline='\n')
    print(f'wrote {page["out"].relative_to(ROOT)}')


if __name__ == '__main__':
    for p in PAGES:
        build(p)
