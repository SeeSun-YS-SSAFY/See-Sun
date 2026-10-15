"""
해상도·글꼴 매트릭스 결과(run_layout_matrix.py 출력)를 모아 조건별 문제 표와 화면별 비교 시트를 만든다.

  python scripts/e2e/layout_report.py --root <res-matrix/before> [--sheets <out>/sheets] [--md <out>/problems.md]
  python scripts/e2e/layout_report.py --root <before> --after <after> --sheets <out> --md <out>   # 전/후 비교

입력: <root>/<조건>/<모듈>/{fit.tsv, measure.tsv, NN_화면.png}
출력:
  - 문제 표(마크다운): 조건마다 화면 수, 넘침(기본 글꼴), 어절 중간 끊김, 버튼 3줄 이상, 외톨이 글자 줄, 48dp 미만, 겹침,
    줄 길이 초과(>40자), 아래쪽 빈 공간 과다(주요 버튼이 화면 위쪽 + 빈 공간 30% 이상), 18sp 미만 버튼 글자
  - 화면별 비교 시트 PNG: 같은 화면을 조건별로 나란히(같은 높이로 줄여) 붙인 그림. --after가 있으면 위 줄 전, 아래 줄 후.
"""
import argparse
import csv
from collections import defaultdict
from pathlib import Path

ORDER = ['a_360x640', 'b_360x740', 'c_412x915', 'd_480x1000', 'e1_673x841_fold', 'e2_840x1120_tablet',
         'e3_841x673_fold_land', 'f1_360x740_font1.3', 'f2_360x740_font2.0', 'g_915x412_land']
SHEET_SCREENS = None  # None = 모든 화면


def load(root):
    """{조건: {화면: {'fit': row, 'm': row, 'png': path}}}"""
    data = defaultdict(dict)
    for cond_dir in sorted(Path(root).iterdir()):
        if not cond_dir.is_dir() or cond_dir.name == 'sheets':
            continue
        for mod in cond_dir.iterdir():
            if not mod.is_dir():
                continue
            fit = mod / 'fit.tsv'
            if fit.exists():
                for line in fit.read_text(encoding='utf-8').splitlines():
                    parts = line.split('\t')
                    if len(parts) >= 5:
                        data[cond_dir.name].setdefault(parts[0], {})['fit'] = parts
            meas = mod / 'measure.tsv'
            if meas.exists():
                with meas.open(encoding='utf-8') as f:
                    for row in csv.DictReader(f, delimiter='\t'):
                        data[cond_dir.name].setdefault(row['name'], {})['m'] = row
            for png in mod.glob('*.png'):
                data[cond_dir.name].setdefault(png.stem, {})['png'] = png
    return data


def split(v):
    return [x for x in (v or '').split('|') if x]


def problems(screens):
    """조건 하나의 문제 집계: {항목: [화면…]}"""
    p = defaultdict(list)
    for name, d in sorted(screens.items()):
        fit, m = d.get('fit'), d.get('m')
        if fit and fit[4] == 'SCROLL' and float(fit[2]) <= 1.0:
            p['넘침(기본 글꼴, 스크롤 필요)'].append(f'{name}(+{fit[3]}dp)')
        if fit and len(fit) > 5 and fit[5]:
            p['잘린 버튼'].append(f'{name}: {fit[5][:60]}')
        if not m:
            continue
        for key, label in [('mid_word_breaks', '어절 중간 끊김'), ('too_many_lines', '버튼 글자 3줄 이상'),
                           ('orphans', '한 글자만 다음 줄(외톨이)'), ('small_targets', '터치 영역 48dp 미만'),
                           ('overlaps', '눌리는 요소 겹침')]:
            items = split(m.get(key))
            if items:
                p[label].append(f'{name}: ' + ', '.join(items)[:90])
        if int(m.get('max_line_chars') or 0) > 40:
            p['본문 한 줄 40자 초과'].append(f"{name}({m['max_line_chars']}자, {m['max_line_w_dp']}dp)")
        try:
            empty = int(m.get('empty_pct') or 0)
            last = int(m.get('last_action_center_pct') or 100)
            prim = m.get('primary_center_pct')
        except ValueError:
            empty, last, prim = 0, 100, ''
        if empty >= 30 and not prim and last < 60:
            p['아래쪽 빈 공간 30%↑ + 행동이 화면 위쪽'].append(f'{name}(빈 {empty}%, 마지막 버튼 {last}%)')
        wrapped = split(m.get('wrapped_buttons'))
        if wrapped:
            p['버튼 글자 줄바꿈(2줄 포함)'].append(f'{name}: ' + ', '.join(wrapped)[:90])
        sp = float(m.get('min_button_sp') or 0)
        if 0 < sp < 18:
            p['버튼 안 글자 18sp 미만'].append(f'{name}({sp:g}sp)')
    return p


def write_md(data, md_path, title, after=None):
    conds = [c for c in ORDER if c in data] + [c for c in data if c not in ORDER]
    labels = ['넘침(기본 글꼴, 스크롤 필요)', '잘린 버튼', '어절 중간 끊김', '버튼 글자 3줄 이상', '한 글자만 다음 줄(외톨이)',
              '터치 영역 48dp 미만', '눌리는 요소 겹침', '본문 한 줄 40자 초과', '아래쪽 빈 공간 30%↑ + 행동이 화면 위쪽',
              '버튼 글자 줄바꿈(2줄 포함)', '버튼 안 글자 18sp 미만']
    out = [f'# {title}', '']
    head = '| 조건 | 화면 수 | ' + ' | '.join(labels) + ' |'
    out += [head, '|' + '---|' * (len(labels) + 2)]
    for c in conds:
        pb = problems(data[c])
        cells = []
        for lab in labels:
            n = len(pb.get(lab, []))
            if after is not None and c in after:
                na = len(problems(after[c]).get(lab, []))
                cells.append(f'{n}→{na}')
            else:
                cells.append(str(n))
        out.append(f'| {c} | {len(data[c])} | ' + ' | '.join(cells) + ' |')
    out.append('')
    for c in conds:
        out.append(f'## {c}')
        for tag, src in [('전', data)] + ([('후', after)] if after else []):
            if src is None or c not in src:
                continue
            pb = problems(src[c])
            out.append(f'### {tag}' if after else '')
            for lab in labels:
                items = pb.get(lab, [])
                if items:
                    out.append(f'- **{lab}** ({len(items)}): ' + '; '.join(items[:12]) + (' …' if len(items) > 12 else ''))
        out.append('')
    Path(md_path).parent.mkdir(parents=True, exist_ok=True)
    Path(md_path).write_text('\n'.join(out), encoding='utf-8')


def sheets(data, out_dir, after=None, height=560):
    from PIL import Image, ImageDraw, ImageFont
    font = None
    for f in ['C:/Windows/Fonts/malgunbd.ttf', 'C:/Windows/Fonts/malgun.ttf']:
        if Path(f).exists():
            font = ImageFont.truetype(f, 20)
            break
    conds = [c for c in ORDER if c in data]
    names = sorted({n for c in conds for n, d in data[c].items() if 'png' in d})
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    rows_src = [('전', data)] + ([('후', after)] if after else [])
    for name in names:
        rows = []
        for tag, src in rows_src:
            tiles = []
            for c in conds:
                d = (src or {}).get(c, {}).get(name)
                if not d or 'png' not in d:
                    continue
                im = Image.open(d['png']).convert('RGB')
                w = max(1, int(im.width * height / im.height))
                if w > height * 1.6:  # 가로 화면은 폭 기준으로 줄인다
                    w = int(height * 1.6)
                    im = im.resize((w, int(im.height * w / im.width)))
                else:
                    im = im.resize((w, height))
                tile = Image.new('RGB', (w, height + 34), 'white')
                tile.paste(im, (0, 34))
                dr = ImageDraw.Draw(tile)
                m = d.get('m') or {}
                v = m.get('violations', '')
                dr.text((4, 4), f'{tag} {c} 위반{v}' if after else f'{c} 위반{v}', fill='black', font=font)
                tiles.append(tile)
            if tiles:
                rows.append(tiles)
        if not rows:
            continue
        W = max(sum(t.width for t in r) + 8 * (len(r) - 1) for r in rows)
        H = sum(max(t.height for t in r) for r in rows) + 8 * (len(rows) - 1)
        sheet = Image.new('RGB', (W, H), (230, 230, 230))
        y = 0
        for r in rows:
            x = 0
            for t in r:
                sheet.paste(t, (x, y))
                x += t.width + 8
            y += max(t.height for t in r) + 8
        safe = name.replace('/', '_')
        sheet.save(out_dir / f'{safe}.png', optimize=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', required=True)
    ap.add_argument('--after')
    ap.add_argument('--sheets')
    ap.add_argument('--md')
    args = ap.parse_args()
    before = load(args.root)
    after = load(args.after) if args.after else None
    if args.md:
        write_md(before, args.md, '해상도·글꼴 매트릭스 문제 표' + (' (전→후)' if after else ''), after)
    if args.sheets:
        sheets(before, args.sheets, after)
    for c in [c for c in ORDER if c in before]:
        pb = problems(before[c])
        print(c, len(before[c]), {k: len(v) for k, v in pb.items()})


if __name__ == '__main__':
    main()
