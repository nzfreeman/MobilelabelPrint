"""Deterministic EZPL bitmaps. This file also runs without an Odoo installation."""
import io
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROTATIONS = {'top': 0, 'left': 90, 'bottom': 180, 'right': 270}
TOKENS = re.compile(r'\{([A-Z_]+)\}', re.I)


def dots(mm, dpi):
    return int(math.floor(float(mm) * int(dpi) / 25.4 + 0.5))


def dimensions(template):
    w, h = template['width_mm'], template['height_mm']
    return (h, w) if ROTATIONS[template['feed_edge']] % 180 else (w, h)


def validate(template, dpi):
    numbers = ['width_mm', 'height_mm', 'gap_mm', 'offset_x_mm', 'offset_y_mm']
    if any(not math.isfinite(float(template[k])) for k in numbers):
        raise ValueError('라벨 크기와 위치 보정값은 유한한 숫자여야 합니다.')
    if not (0 < template['width_mm'] <= 1000 and 0 < template['height_mm'] <= 1000):
        raise ValueError('라벨 가로·세로는 0보다 크고 1000mm 이하여야 합니다.')
    if not 0 <= template['gap_mm'] <= 50 or int(dpi) not in (203, 300):
        raise ValueError('라벨 간격 또는 해상도를 확인하세요.')
    pw, ph = dimensions(template)
    if dots(pw, dpi) * dots(ph, dpi) > 24000000:
        raise ValueError('라벨 이미지가 너무 큽니다.')
    if not template['areas'] or len(template['areas']) > 8:
        raise ValueError('인쇄 영역을 1~8개 등록하세요.')
    for a in template['areas']:
        if any(not math.isfinite(float(a[k])) for k in ('x', 'y', 'w', 'h', 'font_pt')):
            raise ValueError('인쇄 영역의 위치·크기·글자 크기를 확인하세요.')
        if a['x'] < 0 or a['y'] < 0 or a['w'] <= 0 or a['h'] <= 0 or a['font_pt'] < 0:
            raise ValueError('인쇄 영역의 위치·크기·글자 크기를 확인하세요.')
        if a['x'] + a['w'] > template['width_mm'] + .01 or a['y'] + a['h'] > template['height_mm'] + .01:
            raise ValueError('인쇄 영역이 똑바로 놓은 라벨 밖으로 나갑니다.')
        x, y, w, h = placement(template, a, dpi)
        if x < 0 or y < 0 or x + w > dots(pw, dpi) + 1 or y + h > dots(ph, dpi) + 1:
            raise ValueError('위치 보정 후 인쇄 영역이 라벨 밖으로 나갑니다.')


def placement(t, a, dpi):
    w, h = t['width_mm'], t['height_mm']
    rotation = (ROTATIONS[t['feed_edge']] + (180 if t['upside_down'] else 0)) % 360
    x, y, aw, ah = a['x'], a['y'], a['w'], a['h']
    if rotation == 90:
        x, y, aw, ah = h - y - ah, x, ah, aw
    elif rotation == 180:
        x, y = w - x - aw, h - y - ah
    elif rotation == 270:
        x, y, aw, ah = y, w - x - aw, ah, aw
    return dots(x, dpi) + dots(t['offset_x_mm'], dpi), dots(y, dpi) + dots(t['offset_y_mm'], dpi), max(1, dots(aw, dpi)), max(1, dots(ah, dpi))


def lines(text, values):
    result = []
    for line in text.splitlines():
        keys = TOKENS.findall(line)
        if any(k.upper() not in values for k in keys):
            raise ValueError('알 수 없는 항목: ' + ', '.join(k for k in keys if k.upper() not in values))
        # An omitted field removes its complete line, including any manually entered prefix.
        if any(not str(values[k.upper()] or '').strip() for k in keys):
            continue
        line = TOKENS.sub(lambda m: str(values[m.group(1).upper()] or ''), line).strip()
        if line:
            result.append(line)
    return result


def _font(size, bold):
    path = Path(__file__).parent / 'static' / 'fonts' / ('BarlowSemiCondensed-Bold.ttf' if bold else 'BarlowSemiCondensed-Regular.ttf')
    return ImageFont.truetype(str(path), max(1, int(size)))


def render_area(a, values, dpi):
    width, height = max(1, dots(a['w'], dpi)), max(1, dots(a['h'], dpi))
    image = Image.new('L', (width, height), 255)
    text = lines(a['text'], values)
    if not text:
        return image, False
    draw = ImageDraw.Draw(image)
    padding = max(1, dots(.2, dpi))
    sample = 'Ag'

    def metrics(size):
        font = _font(size, a['bold'])
        bounds = [draw.textbbox((0, 0), v, font=font) for v in text]
        sample_box = draw.textbbox((0, 0), sample, font=font)
        line_height = max(max(b[3] - b[1] for b in bounds), sample_box[3] - sample_box[1])
        gap = max(1, int(size * .18))
        return font, bounds, line_height, gap

    def fits(size):
        _, bounds, line_height, gap = metrics(size)
        return max(b[2] - b[0] for b in bounds) <= width - 2 * padding and len(text) * line_height + (len(text) - 1) * gap <= height - 2 * padding

    if a['font_pt']:
        size = max(1, round(a['font_pt'] * dpi / 72))
        if not fits(size):
            raise ValueError('지정한 글자 크기가 영역보다 큽니다. 영역을 키우거나 글자 크기를 0(자동)으로 설정하세요.')
    else:
        lo, hi = 1, min(2000, height)
        size = 1
        while lo <= hi:
            mid = (lo + hi) // 2
            if fits(mid):
                size, lo = mid, mid + 1
            else:
                hi = mid - 1
        if not fits(size):
            raise ValueError('인쇄 영역에 내용이 들어가지 않습니다.')
    font, bounds, line_height, gap = metrics(size)
    y = padding
    for value, bounds in zip(text, bounds):
        x = (width - (bounds[2] - bounds[0])) / 2 if a['centered'] else padding
        draw.text((x - bounds[0], y - bounds[1]), value, fill=0, font=font)
        y += line_height + gap
    return image, True


def pack(image):
    """MSB first, black=1, zero-filled byte padding, matching the Android app."""
    width, height = image.size
    width_bytes = (width + 7) // 8
    pixels = image.tobytes()
    result = bytearray(width_bytes * height)
    for y in range(height):
        for x in range(width):
            if pixels[y * width + x] < 128:
                result[y * width_bytes + x // 8] |= 0x80 >> (x % 8)
    return bytes(result), width_bytes


def render(template, values, dpi=300, copies=1, speed=2, darkness=19, mode='keep'):
    dpi, copies = int(dpi), int(copies)
    if not 1 <= copies <= 9999:
        raise ValueError('인쇄 매수는 1~9999장이어야 합니다.')
    validate(template, dpi)
    pw, ph = dimensions(template)
    canvas = Image.new('L', (dots(pw, dpi), dots(ph, dpi)), 255)
    rotation = (ROTATIONS[template['feed_edge']] + (180 if template['upside_down'] else 0)) % 360
    chunks = []
    for a in template['areas']:
        image, has_text = render_area(a, values, dpi)
        if not has_text:
            continue
        if rotation:
            image = image.rotate(-rotation, expand=True)
        x, y, _, _ = placement(template, a, dpi)
        canvas.paste(image, (x, y))
        data, wb = pack(image)
        chunks.append(f'Q{x},{y},{wb},{image.height}\r\n'.encode('ascii') + data + b'\r\n')
    if not chunks:
        raise ValueError('인쇄할 내용이 없습니다.')
    head = f'^Q{ph:g},{template["gap_mm"]:g}\r\n^W{pw:g}\r\n^H{int(darkness)}\r\n^P{copies}\r\n^S{int(speed)}\r\n'
    if mode == 'thermal_transfer':
        head += '^AT\r\n'
    elif mode == 'direct':
        head += '^AD\r\n'
    payload = head.encode('ascii') + b'^C1\r\n^R0\r\n^L\r\n' + b''.join(chunks) + b'E\r\n'
    out = io.BytesIO()
    canvas.thumbnail((1000, 1400))
    canvas.save(out, format='PNG')
    return payload, out.getvalue()
