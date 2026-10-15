"""
픽토그램 보기용 사본(2026-10-14 소유자 msg 722 「저시력 사용자가 더 잘 보이게」, 원본은 그대로).

1차 픽토그램은 842×595 캔버스에 그림과 작은 단계 글자가 넓이의 약 20%만 차지한다(나머지는 흰 여백). 앱 카드에서 그림이
작게 보이므로, 운동마다 모든 그림을 같은 상자로 잘라 「display/」 하위 폴더에 따로 쓴다.

- 그림 영역: 흰색이 아닌 화소의 가로줄 묶음(band)을 찾고, 색(채도)이 있는 첫 묶음부터를 그림으로 본다. 그 위에 있는 색 없는
  묶음(단계 이름 「동작 시작」, 「x30회」, 「⏱ 1s」 같은 인쇄 글자)은 뺀다 — 글자 위치를 고정값으로 두지 않고 찾는다.
- 운동 하나의 모든 그림 상자를 합친 상자로 같은 자리를 자른다(그림이 바뀔 때 사람이 튀지 않게). 합친 상자 안에 어떤 그림의
  인쇄 글자가 들어오면 사본에서 그 글자 줄을 흰색으로 지운다.
- 상자 둘레에 고른 여백을 두고, 원본과 같은 가로세로 비(842:595)로 넓혀 카드에 맞춘다. 키우지 않는다(원본 화소 그대로).
"""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

WHITE_LIMIT = 200  # 가장 어두운 채널이 이보다 어두우면 그림 화소(JPEG 잡음은 이보다 밝다)
SATURATION = 40  # 채널 차이가 이보다 크면 색 있는 화소(사람 그림), 작으면 회색·검정(글자·바닥선)
BAND_GAP = 1  # 빈 줄이 하나라도 있으면 묶음을 나눈다
MARGIN_RATIO = 0.06
SAMPLE_STEP = 2


@dataclass(frozen=True)
class Box:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    def union(self, other: Box) -> Box:
        return Box(min(self.left, other.left), min(self.top, other.top), max(self.right, other.right), max(self.bottom, other.bottom))

    def contains(self, other: Box) -> bool:
        return self.left <= other.left and self.top <= other.top and self.right >= other.right and self.bottom >= other.bottom


def _rows(image):
    """가로줄마다 (그림 화소 수, 색 화소 수)."""
    rgb = image.convert('RGB')
    width, height = rgb.size
    px = rgb.load()
    rows = []
    for y in range(height):
        ink = color = 0
        for x in range(0, width, SAMPLE_STEP):
            r, g, b = px[x, y]
            if min(r, g, b) < WHITE_LIMIT:
                ink += 1
            if max(r, g, b) - min(r, g, b) > SATURATION:
                color += 1
        rows.append((ink, color))
    return rows


def bands(image) -> list[tuple[int, int, int]]:
    """그림 화소가 있는 가로줄 묶음 [(위, 아래, 색 화소 수)]."""
    rows = _rows(image)
    out, y = [], 0
    while y < len(rows):
        if rows[y][0] == 0:
            y += 1
            continue
        top = y
        while y < len(rows) and rows[y][0] > 0:
            y += 1
        out.append((top, y, sum(c for _, c in rows[top:y])))
    return out


def caption_bands(image) -> list[tuple[int, int]]:
    """그림 위쪽의 색 없는 묶음(인쇄 글자: 단계 이름·횟수·시간) [(위, 아래)]."""
    found = bands(image)
    first = next((i for i, (_, _, color) in enumerate(found) if color > 0), 0)
    return [(top, bottom) for top, bottom, _ in found[:first]]


def figure_box(image) -> Box | None:
    """인쇄 글자(위쪽의 색 없는 묶음)를 뺀 그림 상자. 그림 화소가 없으면 None."""
    found = bands(image)
    if not found:
        return None
    first = next((i for i, (_, _, color) in enumerate(found) if color > 0), 0)
    top, bottom = found[first][0], found[-1][1]
    rgb = image.convert('RGB')
    px = rgb.load()
    left, right = rgb.size[0], 0
    for y in range(top, bottom):
        for x in range(rgb.size[0]):
            r, g, b = px[x, y]
            if min(r, g, b) < WHITE_LIMIT:
                left = min(left, x)
                right = max(right, x + 1)
    if right <= left:
        return None
    return Box(left, top, right, bottom)


def crop_box(figures: list[Box], size: tuple[int, int]) -> Box:
    """그림 상자들을 합치고 여백·가로세로 비(원본 캔버스 비)를 맞춘 자르기 상자. 캔버스 밖으로는 나가지 않는다."""
    width, height = size
    union = figures[0]
    for b in figures[1:]:
        union = union.union(b)
    margin = max(8, round(max(union.width, union.height) * MARGIN_RATIO))
    w, h = union.width + margin * 2, union.height + margin * 2
    aspect = width / height
    if w / h < aspect:
        w = round(h * aspect)
    else:
        h = round(w / aspect)
    w, h = min(w, width), min(h, height)
    cx, cy = (union.left + union.right) / 2, (union.top + union.bottom) / 2
    left = int(round(min(max(cx - w / 2, 0), width - w)))
    top = int(round(min(max(cy - h / 2, 0), height - h)))
    return Box(left, top, left + w, top + h)


def render(image, box: Box) -> bytes:
    """
    잘라 낸 보기용 그림(JPEG, 같은 입력이면 같은 바이트). 운동의 다른 그림 때문에 넓어진 상자 안에 이 그림의 인쇄 글자가 들어오면
    그 줄은 흰색으로 지운다(사본만, 원본은 그대로).
    """
    from PIL import ImageDraw

    rgb = image.convert('RGB')
    captions = caption_bands(rgb)
    if captions:
        rgb = rgb.copy()
        draw = ImageDraw.Draw(rgb)
        for top, bottom in captions:
            draw.rectangle((0, max(top - 2, 0), rgb.size[0], bottom + 2), fill=(255, 255, 255))
    out = BytesIO()
    rgb.crop((box.left, box.top, box.right, box.bottom)).save(out, format='JPEG', quality=92, optimize=True)
    return out.getvalue()


def display_path(original: Path, root: Path) -> Path:
    """MEDIA_ROOT/exercises/pictograms/<분류>/<파일> → MEDIA_ROOT/exercises/pictograms/display/<분류>/<파일>."""
    rel = original.relative_to(root)
    return root / 'display' / rel


def make_for_exercise(paths: list[Path], root: Path) -> list[tuple[Path, Path, bool]]:
    """운동 하나의 그림들을 같은 상자로 잘라 쓴다. [(원본, 사본, 새로 썼는가)]. 원본은 바꾸지 않는다."""
    from PIL import Image

    images = [(p, Image.open(p)) for p in paths if p.exists()]
    if not images:
        return []
    boxes = [b for b in (figure_box(im) for _, im in images) if b is not None]
    size = images[0][1].size
    box = crop_box(boxes, size) if boxes else Box(0, 0, size[0], size[1])
    result = []
    for path, image in images:
        data = render(image, box)
        target = display_path(path, root)
        changed = not target.exists() or target.read_bytes() != data
        if changed:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        result.append((path, target, changed))
    return result
