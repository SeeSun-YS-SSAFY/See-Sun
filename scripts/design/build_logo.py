"""시선(See:Sun) 로고를 의뢰인 PNG에서 벡터로 옮긴다.

입력: 1차 웹 앱 `frontend/public/Seesunlogo.png`(297x375, 의뢰인 제공, 고정본 — 읽기만)
- 원판(#FFDB65)·점자 점 10개: PNG 픽셀에서 중심·반지름을 재서 원으로 그린다.
- 둘레 글자 "ENVISION THE SOCIETY FREE OF BARRIER": PNG의 글자 띠(반지름·각도 범위)를 재고,
  Pretendard Bold(SIL OFL 1.1) 글자 윤곽을 원호 위에 글자마다 회전해 놓는다.
  크기·기준선·각도는 PNG와 겹침(IoU)이 가장 큰 값으로 고른다.
- 글자 표식 "See:Sun": Pretendard Medium 윤곽.

출력: Android VectorDrawable(디자인 시스템·런처·스플래시), SVG 원본, 비교 그림.
실행: python -X utf8 scripts/design/build_logo.py <Seesunlogo.png> <비교그림 출력 폴더>
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

ROOT = Path(__file__).resolve().parents[2]
RES_DS = ROOT / "android/core/designsystem/src/main/res/drawable"
RES_APP = ROOT / "android/app/src/main/res/drawable"
SVG_OUT = ROOT / "docs/design/assets"
FONT_DIR = Path.home() / "AppData/Local/Microsoft/Windows/Fonts"
FONT_TEXT = FONT_DIR / "Pretendard-Bold.ttf"
FONT_WORD = FONT_DIR / "Pretendard-Medium.ttf"

SUN = "#FFDB65"
DOT = "#FFFFFF"
INDIGO = (36, 27, 162, 255)
TEXT = "ENVISION THE SOCIETY FREE OF BARRIER"
LAUNCHER_DISC = 52


# ---------------------------------------------------------------- 측정
def measure(png: Path):
    im = np.array(Image.open(png).convert("RGBA")).astype(float)
    alpha = im[..., 3] / 255
    h, w = alpha.shape
    yy, xx = np.mgrid[0:h, 0:w]
    area = alpha.sum()
    cx, cy = (xx * alpha).sum() / area, (yy * alpha).sum() / area
    r = math.sqrt(area / math.pi)
    # 흰 정도: 원판 색(255,219,101) 대비 파랑 채널
    white = np.clip((im[..., 2] - 101) / (255 - 101), 0, 1) * alpha
    lab, n = ndimage.label(white > 0.5)
    dots = []
    for i in range(1, n + 1):
        m = lab == i
        if m.sum() > 40:  # 점자 점(약 66픽셀). 글자 조각은 작다.
            grown = ndimage.binary_dilation(m, iterations=1)
            wsum = white[grown].sum()
            dx, dy = (xx[grown] * white[grown]).sum() / wsum, (yy[grown] * white[grown]).sum() / wsum
            dots.append((dx, dy, math.sqrt(wsum / math.pi)))
    return (cx, cy, r), dots, white


# ---------------------------------------------------------------- 글자 윤곽
class PolyPen(BasePen):
    """곡선을 점으로 쪼개 윤곽(점 목록)으로 모은다. 변환은 점마다 적용한다."""

    def __init__(self, glyphset, steps=6):
        super().__init__(glyphset)
        self.contours: list[list[tuple[float, float]]] = []
        self.cur: list[tuple[float, float]] = []
        self.steps = steps

    def _moveTo(self, p):
        self.cur = [p]

    def _lineTo(self, p):
        self.cur.append(p)

    def _curveToOne(self, p1, p2, p3):
        p0 = self.cur[-1]
        for i in range(1, self.steps + 1):
            t = i / self.steps
            a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t**3
            self.cur.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))

    def _qCurveToOne(self, p1, p2):
        p0 = self.cur[-1]
        for i in range(1, self.steps + 1):
            t = i / self.steps
            a, b, c = (1 - t) ** 2, 2 * (1 - t) * t, t * t
            self.cur.append((a * p0[0] + b * p1[0] + c * p2[0], a * p0[1] + b * p1[1] + c * p2[1]))

    def _closePath(self):
        if len(self.cur) > 2:
            self.contours.append(self.cur)
        self.cur = []

    _endPath = _closePath


def glyph_contours(font: TTFont, ch: str):
    gs = font.getGlyphSet()
    name = font.getBestCmap()[ord(ch)]
    pen = PolyPen(gs)
    gs[name].draw(pen)
    return pen.contours, gs[name].width


def layout_arc(font, text, center, radius, cap, start_deg, end_deg):
    """글자 윤곽을 원 아래쪽 원호에 놓는다(왼쪽→오른쪽, 글자 윗부분이 원 중심을 향함). 좌표는 y 아래 방향."""
    k = cap / font["OS/2"].sCapHeight
    glyphs = [glyph_contours(font, c) for c in text]

    def ink(contours):
        xs = [p[0] for c in contours for p in c]
        return (min(xs), max(xs)) if xs else (0, 0)

    # 잉크 범위(첫 글자 왼쪽 ~ 마지막 글자 오른쪽)가 [start, end] 각도를 채우도록 자간을 정한다.
    first_l = ink(glyphs[0][0])[0] * k
    last_r = ink(glyphs[-1][0])[1] * k
    natural = sum(w for _, w in glyphs[:-1]) * k + last_r - first_l
    span = radius * math.radians(start_deg - end_deg)
    track = (span - natural) / (len(text) - 1)
    cx, cy = center
    out = []
    pen_x = -first_l
    for contours, adv in glyphs:
        mid = pen_x + adv * k / 2
        th = math.radians(start_deg) - mid / radius
        bx, by = cx + radius * math.cos(th), cy + radius * math.sin(th)
        tx, ty = math.sin(th), -math.cos(th)  # 진행 방향
        nx, ny = -math.cos(th), -math.sin(th)  # 위(원 중심) 방향
        for c in contours:
            pts = []
            for gx, gy in c:
                lx = (gx * k + pen_x) - mid
                ly = gy * k
                pts.append((bx + lx * tx + ly * nx, by + lx * ty + ly * ny))
            out.append(pts)
        pen_x += adv * k + track
    return out


def word_contours(font, text, cap):
    k = cap / font["OS/2"].sCapHeight
    out = []
    pen_x = 0.0
    for ch in text:
        contours, adv = glyph_contours(font, ch)
        for c in contours:
            out.append([(pen_x + gx * k, -gy * k) for gx, gy in c])
        pen_x += adv * k
    return out


# ---------------------------------------------------------------- 그리기(검증용)
def raster(shapes, size, scale, offset, supersample=4):
    """shapes: [(색, 'circle', (x,y,r)) | (색, 'poly', [윤곽...])] → RGBA(투명 바탕). 윤곽은 nonzero 감김으로 채운다."""
    s = supersample
    img = Image.new("RGBA", (size[0] * s, size[1] * s), (0, 0, 0, 0))
    for color, kind, data in shapes:
        rgb = tuple(int(color[i : i + 2], 16) for i in (1, 3, 5))
        if kind == "circle":
            x, y, r = data
            x, y, r = (x - offset[0]) * scale * s, (y - offset[1]) * scale * s, r * scale * s
            ImageDraw.Draw(img).ellipse((x - r, y - r, x + r, y + r), fill=rgb + (255,))
            continue
        wind = np.zeros((img.size[1], img.size[0]), dtype=np.int32)
        for c in data:
            pts = [((x - offset[0]) * scale * s, (y - offset[1]) * scale * s) for x, y in c]
            n = len(pts)
            area = sum(pts[i][0] * pts[(i + 1) % n][1] - pts[(i + 1) % n][0] * pts[i][1] for i in range(n))
            layer = Image.new("L", img.size, 0)
            ImageDraw.Draw(layer).polygon(pts, fill=1)
            wind += np.array(layer, dtype=np.int32) * (1 if area > 0 else -1)
        m = Image.fromarray(((wind != 0) * 255).astype(np.uint8), "L")
        img.paste(Image.new("RGBA", img.size, rgb + (255,)), (0, 0), m)
    return img.resize(size, Image.LANCZOS)


# ---------------------------------------------------------------- 내보내기
def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def path_d(contours, offset=(0, 0)):
    parts = []
    for c in contours:
        pts = [(x - offset[0], y - offset[1]) for x, y in c]
        parts.append(f"M{fmt(pts[0][0])},{fmt(pts[0][1])}L" + " ".join(f"{fmt(x)},{fmt(y)}" for x, y in pts[1:]) + "Z")
    return "".join(parts)


def circle_d(x, y, r, offset=(0, 0)):
    x, y = x - offset[0], y - offset[1]
    return f"M{fmt(x - r)},{fmt(y)}a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0Z"


def vector_xml(width_dp, height_dp, vw, vh, paths, comment, group=None, fill_type="nonZero"):
    ind = "        " if group else "    "
    body = "\n".join(
        f'{ind}<path\n{ind}    android:fillColor="{c}"\n{ind}    android:fillType="{fill_type}"\n{ind}    android:pathData="{d}" />'
        for c, d in paths
    )
    if group:
        body = f"    <group\n        {group}>\n{body}\n    </group>"
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{width_dp}dp"\n    android:height="{height_dp}dp"\n'
        f'    android:viewportWidth="{fmt(vw)}"\n    android:viewportHeight="{fmt(vh)}">\n'
        f"{body}\n</vector>\n"
    )


def main(png: Path, compare_dir: Path):
    (cx, cy, r), dots, white = measure(png)
    dot_r = float(np.median([d[2] for d in dots]))
    print(f"disc center=({cx:.2f},{cy:.2f}) r={r:.2f}; dots={len(dots)} r={dot_r:.2f}")
    font = TTFont(FONT_TEXT)

    # 글자 띠(측정): 각도 160.5°(왼쪽 끝) → 19°(오른쪽 끝), 반지름 81.5~88. 점자 점은 비교에서 뺀다.
    target = white.copy()
    yy, xx = np.mgrid[0 : target.shape[0], 0 : target.shape[1]]
    for dx, dy, _ in dots:
        target[(xx - dx) ** 2 + (yy - dy) ** 2 < (dot_r + 2) ** 2] = 0
    best = None
    for radius in np.arange(86.5, 89.01, 0.5):
        for cap in np.arange(4.6, 6.21, 0.2):
            for start, end in ((160.5, 19.0), (160.0, 19.5), (161.0, 18.5)):
                cont = layout_arc(font, TEXT, (cx, cy), radius, cap, start, end)
                a = np.array(raster([("#FFFFFF", "poly", cont)], (white.shape[1], white.shape[0]), 1.0, (0, 0), 2))[..., 3] / 255
                iou = np.minimum(a, target).sum() / np.maximum(a, target).sum()
                if best is None or iou > best[0]:
                    best = (iou, radius, cap, start, end, cont)
    iou, radius, cap, start, end, arc = best
    print(f"arc text fit: IoU={iou:.3f} baseline r={radius} cap={cap:.1f} angles {start}->{end}")

    off = (cx - r, cy - r)  # 원판 바깥 경계 = viewport
    vw = vh = 2 * r
    disc = (SUN, circle_d(cx, cy, r, off))
    dot_d = "".join(circle_d(x, y, dot_r, off) for x, y, _ in dots)
    full = [disc, (DOT, dot_d), (DOT, path_d(arc, off))]
    plain = [disc, (DOT, dot_d)]

    # 글자 표식 "See:Sun"(Figma 메인 1031:3818: 원판 오른쪽, 같은 노랑, 보통 굵기)
    word = word_contours(TTFont(FONT_WORD), "See:Sun", 70)
    xs = [p[0] for c in word for p in c]
    ys = [p[1] for c in word for p in c]
    woff = (min(xs), min(ys))
    ww, wh = max(xs) - min(xs), max(ys) - min(ys)

    src = "의뢰인 로고 Seesunlogo.png를 scripts/design/build_logo.py로 벡터화(원판·점자 점 실측). 둘레 글자 윤곽: Pretendard Bold(SIL OFL 1.1)"
    RES_DS.mkdir(parents=True, exist_ok=True)
    (RES_DS / "seesun_emblem.xml").write_text(vector_xml(96, 96, vw, vh, full, src), encoding="utf-8")
    (RES_DS / "seesun_emblem_plain.xml").write_text(
        vector_xml(48, 48, vw, vh, plain, src + ". 작은 크기용: 둘레 글자 생략(48dp에서 글자 높이 약 1.4dp)"), encoding="utf-8"
    )
    word_h_dp = 32
    (RES_DS / "seesun_wordmark.xml").write_text(
        vector_xml(round(word_h_dp * ww / wh), word_h_dp, ww, wh, [(SUN, path_d(word, woff))], "글자 표식 See:Sun(Figma 메인 1031:3818). 윤곽: Pretendard Medium(SIL OFL 1.1)"),
        encoding="utf-8",
    )

    def place(size_vp, disc_dp):
        s = disc_dp / vw
        o = (size_vp - disc_dp) / 2
        return f'android:scaleX="{fmt(s)}"\n        android:scaleY="{fmt(s)}"\n        android:translateX="{fmt(o)}"\n        android:translateY="{fmt(o)}"'

    # 런처 전경 108dp(보이는 원 72dp 안에 원판 52dp — 남색 테두리가 보이게) · 테마 아이콘 단색(점은 구멍) · 스플래시 240dp(보이는 원 160dp 안에 원판 150dp)
    RES_APP.mkdir(parents=True, exist_ok=True)
    (RES_APP / "ic_launcher_foreground.xml").write_text(vector_xml(108, 108, 108, 108, plain, src + ". 런처 전경(남색 바탕 위)", place(108, LAUNCHER_DISC)), encoding="utf-8")
    (RES_APP / "ic_launcher_monochrome.xml").write_text(
        vector_xml(108, 108, 108, 108, [("#FF000000", disc[1] + dot_d)], src + ". 테마 아이콘 단색 층(점자 점은 구멍)", place(108, LAUNCHER_DISC), "evenOdd"),
        encoding="utf-8",
    )
    (RES_APP / "splash_emblem.xml").write_text(vector_xml(240, 240, 240, 240, full, src + ". 스플래시 아이콘(Android 12+)", place(240, 150)), encoding="utf-8")

    SVG_OUT.mkdir(parents=True, exist_ok=True)
    (SVG_OUT / "seesun_emblem.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(vw)} {fmt(vh)}">\n' + "".join(f'  <path fill="{c}" d="{d}"/>\n' for c, d in full) + "</svg>\n",
        encoding="utf-8",
    )
    (SVG_OUT / "seesun_wordmark.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(ww)} {fmt(wh)}"><path fill="{SUN}" d="{path_d(word, woff)}"/></svg>\n', encoding="utf-8"
    )

    # 비교 그림: 원본 | 벡터(같은 크기) | 차이 | 벡터 512px
    compare_dir.mkdir(parents=True, exist_ok=True)
    orig = Image.open(png).convert("RGBA")
    W, H = orig.size
    shapes = [(SUN, "circle", (cx, cy, r))] + [(DOT, "circle", (x, y, dot_r)) for x, y, _ in dots] + [(DOT, "poly", arc)]

    def on_bg(im):
        b = Image.new("RGBA", im.size, INDIGO)
        b.alpha_composite(im)
        return b

    o2 = on_bg(orig)
    s2 = on_bg(raster(shapes, (W, H), 1.0, (0, 0)))
    big = on_bg(raster(shapes, (512, 512), 512 / vw, off))
    diff = np.abs(np.array(o2).astype(int) - np.array(s2).astype(int))[..., :3].sum(axis=2)
    diff_img = Image.fromarray(np.clip(255 - diff, 0, 255).astype(np.uint8), "L").convert("RGBA")
    f = ImageFont.truetype(str(FONT_TEXT), 22)
    pad = 24
    sheet = Image.new("RGB", (pad * 5 + W * 3 + 512, 512 + 120), (245, 245, 245))
    d = ImageDraw.Draw(sheet)
    x = pad
    for title, im in (("원본 PNG 297x375", o2), ("벡터 (같은 크기)", s2), ("차이 (진할수록 다름)", diff_img), ("벡터 512px", big)):
        d.text((x, 18), title, fill=(20, 20, 20), font=f)
        sheet.paste(im.convert("RGB"), (x, 60))
        x += im.size[0] + pad
    d.text((pad, sheet.size[1] - 40), "원판·점자 점: PNG 실측 그대로 / 둘레 글자: 원본 글자 높이가 5px라 글꼴을 알 수 없어, 비슷한 Pretendard Bold 윤곽을 같은 원호·크기에 배치", fill=(20, 20, 20), font=f)
    sheet.save(compare_dir / "01_로고_벡터_비교.png")

    crop = (int(cx - r), int(cy + 20), int(cx + r) + 1, int(cy + r) + 2)
    zo = o2.crop(crop).resize(((crop[2] - crop[0]) * 4, (crop[3] - crop[1]) * 4), Image.LANCZOS)
    zv = on_bg(raster(shapes, (W * 4, H * 4), 4.0, (0, 0))).crop(tuple(v * 4 for v in crop))
    z = Image.new("RGB", (zo.size[0], zo.size[1] * 2 + 100), (245, 245, 245))
    ImageDraw.Draw(z).text((10, 8), "위: 원본 PNG 4배 확대(흐림) / 아래: 벡터 4배 렌더(선명)", fill=(20, 20, 20), font=f)
    z.paste(zo.convert("RGB"), (0, 50))
    z.paste(zv.convert("RGB"), (0, 80 + zo.size[1]))
    z.save(compare_dir / "01b_로고_둘레글자_확대.png")
    print("wrote", compare_dir)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
