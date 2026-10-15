"""Figma(왼쪽) ↔ 앱(오른쪽) 나란히 비교 시트를 만든다.

실행: python -X utf8 scripts/design/figma_sheets.py <쌍 목록 json> <출력 폴더>
쌍 목록: [{"name": "01_메인", "title": "메인", "figma": [["Main_001 · 1031:3818", "<png>"]], "app": [["MainScreen 50_main", "<png>"]], "notes": ["…"]}]
모든 그림은 같은 높이(기본 860px)로 맞춘다. 360×740dp 캡처와 Figma 360×740 프레임을 같은 배율로 놓기 위함이다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT = Path.home() / "AppData/Local/Microsoft/Windows/Fonts/Pretendard-Bold.ttf"
FONT_R = Path.home() / "AppData/Local/Microsoft/Windows/Fonts/Pretendard-Regular.ttf"
H = 860
PAD = 20
FIGMA_BAR = (36, 27, 162)
APP_BAR = (60, 60, 60)


def fit(path: str) -> Image.Image:
    im = Image.open(path).convert("RGB")
    return im.resize((round(im.width * H / im.height), H), Image.LANCZOS)


def sheet(spec: dict, out: Path) -> Path:
    f_title = ImageFont.truetype(str(FONT), 30)
    f_head = ImageFont.truetype(str(FONT), 24)
    f_sub = ImageFont.truetype(str(FONT_R), 18)
    f_note = ImageFont.truetype(str(FONT_R), 20)
    groups = [("Figma (의뢰인 디자인)", FIGMA_BAR, [(c, fit(p)) for c, p in spec["figma"]]),
              ("앱 (feat/figma-align)", APP_BAR, [(c, fit(p)) for c, p in spec["app"]])]
    widths = [sum(im.width for _, im in g[2]) + PAD * (len(g[2]) - 1) for g in groups]
    notes = spec.get("notes", [])
    W = PAD * 3 + sum(widths)
    Ht = 70 + 70 + H + 40 + 30 * len(notes) + PAD
    s = Image.new("RGB", (W, Ht), (245, 245, 245))
    d = ImageDraw.Draw(s)
    d.text((PAD, 18), spec["title"], fill=(20, 20, 20), font=f_title)
    x = PAD
    for (label, bar, ims), w in zip(groups, widths):
        d.rectangle((x, 70, x + w, 130), fill=bar)
        d.text((x + 12, 76), label, fill=(255, 255, 255), font=f_head)
        d.text((x + 12, 104), " / ".join(c for c, _ in ims), fill=(230, 230, 230), font=f_sub)
        xx = x
        for _, im in ims:
            s.paste(im, (xx, 140))
            xx += im.width + PAD
        x += w + PAD
    y = 140 + H + 20
    for n in notes:
        d.text((PAD, y), "• " + n, fill=(20, 20, 20), font=f_note)
        y += 30
    out.parent.mkdir(parents=True, exist_ok=True)
    s.save(out, optimize=True)
    return out


def main(spec_path: Path, out_dir: Path):
    for spec in json.loads(spec_path.read_text(encoding="utf-8")):
        print(sheet(spec, out_dir / f"{spec['name']}.png"))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
