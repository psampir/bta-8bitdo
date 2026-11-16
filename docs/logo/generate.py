#!/usr/bin/env python3
"""Generate the 8BitDo Controller Support icon.

Original artwork, not the official 8BitDo wordmark: the two lines are typeset from
free fonts (Anton for BTA, Bungee for 8BitDo, both SIL Open Font License) and given
a hand-drawn metal / 3D treatment.

Renders at 1024x1024 and downsamples to 512 so the outlines stay crisp.

    python3 docs/logo/generate.py [--fonts DIR] [--out DIR]

Fonts are not committed; fetch them first:

    mkdir -p /tmp/opencode/fonts
    curl -L -o /tmp/opencode/fonts/Anton-Regular.ttf \
        https://github.com/google/fonts/raw/main/ofl/anton/Anton-Regular.ttf
    curl -L -o /tmp/opencode/fonts/Bungee-Regular.ttf \
        https://github.com/google/fonts/raw/main/ofl/bungee/Bungee-Regular.ttf
"""

import argparse
import math
import os
import random
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

SIZE = 1024
OUT = 512
STROKE = 16

INK = (18, 18, 18, 255)
METAL_TOP = (245, 245, 247)
METAL_MID = (170, 172, 176)
METAL_BOTTOM = (104, 106, 110)
BTA_EXTRUDE = (62, 64, 69, 255)
EIGHT_EXTRUDE = (36, 36, 41, 255)

FONT_BTA = None
FONT_EIGHTBITDO = None


def font(path, size):
    return ImageFont.truetype(str(path), size)


def measure(fnt, text, stroke=STROKE):
    x0, y0, x1, y1 = fnt.getbbox(text, stroke_width=stroke)
    return x1 - x0, y1 - y0


def fit(path, text, max_width, start):
    """Largest font size whose rendered width stays inside max_width."""
    size = start
    while size > 40:
        fnt = font(path, size)
        if measure(fnt, text)[0] <= max_width:
            return fnt
        size -= 6
    return font(path, 40)


def draw_centred(draw, fnt, text, cx, cy, fill, stroke_fill=None, stroke=STROKE,
                 offset=(0, 0)):
    x0, y0, x1, y1 = fnt.getbbox(text, stroke_width=stroke)
    x = int(cx - (x1 - x0) / 2 - x0 + offset[0])
    y = int(cy - (y1 - y0) / 2 - y0 + offset[1])
    draw.text((x, y), text, font=fnt, fill=fill, stroke_width=stroke,
              stroke_fill=stroke_fill if stroke_fill is not None else fill)
    return x, y


def vertical_gradient(size, top, mid, bottom):
    w, h = size
    strip = Image.new("RGB", (1, h))
    px = strip.load()
    for y in range(h):
        t = y / max(1, h - 1)
        if t < 0.5:
            a, b, u = top, mid, t / 0.5
        else:
            a, b, u = mid, bottom, (t - 0.5) / 0.5
        px[0, y] = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
    return strip.resize((w, h), Image.BILINEAR).convert("RGBA")


def bevel_layer(mask, offset=5, light=(255, 255, 255, 110), dark=(0, 0, 0, 120)):
    """Top-left highlight and bottom-right shade, clipped to the glyph mask."""
    highlight = ImageChops.subtract(mask, ImageChops.offset(mask, offset, offset))
    shadow = ImageChops.subtract(mask, ImageChops.offset(mask, -offset, -offset))
    layer = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    layer.paste(Image.new("RGBA", mask.size, light), (0, 0), highlight)
    layer.paste(Image.new("RGBA", mask.size, dark), (0, 0), shadow)
    return layer


def crack_layer(mask, rng, count=4):
    """A few random-walk cracks, clipped to the glyph mask."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for _ in range(count):
        x = rng.uniform(SIZE * 0.18, SIZE * 0.82)
        y = rng.uniform(SIZE * 0.2, SIZE * 0.8)
        angle = rng.uniform(-math.pi, math.pi)
        points = [(x, y)]
        for _ in range(rng.randint(6, 12)):
            angle += rng.uniform(-0.85, 0.85)
            step = rng.uniform(10, 24)
            x += math.cos(angle) * step
            y += math.sin(angle) * step
            points.append((x, y))
        draw.line(points, fill=(44, 44, 46, 200), width=rng.choice((2, 2, 3)), joint="curve")
        draw.line([(px + 2, py + 2) for px, py in points], fill=(248, 248, 250, 80), width=1)
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    return layer


def lockup():
    """The whole BTA / 8BitDo lockup, transparent, centred in SIZE x SIZE."""
    bta_text, eight_text = "BTA", "8BitDo"
    bta_font = fit(FONT_BTA, bta_text, max_width=780, start=490)
    eight_font = fit(FONT_EIGHTBITDO, eight_text, max_width=880, start=450)

    bta_w, bta_h = measure(bta_font, bta_text)
    eight_w, eight_h = measure(eight_font, eight_text)
    gap = 52
    total = bta_h + gap + eight_h
    top = (SIZE - total) // 2
    bta_cy = top + bta_h / 2
    eight_cy = top + bta_h + gap + eight_h / 2

    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # BTA: grey metal extrusion, black outline, gradient face, bevel and cracks.
    for depth in range(14, 0, -1):
        draw_centred(draw, bta_font, bta_text, SIZE / 2, bta_cy, BTA_EXTRUDE,
                     offset=(depth, depth))
    bx, by = draw_centred(draw, bta_font, bta_text, SIZE / 2, bta_cy, INK)
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).text((bx, by), bta_text, font=bta_font, fill=255)
    layer.paste(vertical_gradient((SIZE, SIZE), METAL_TOP, METAL_MID, METAL_BOTTOM),
                (0, 0), mask)
    layer.alpha_composite(bevel_layer(mask))
    layer.alpha_composite(crack_layer(mask, random.Random(11), count=5))

    # 8BitDo: chunky near-black extrusion, white face, black outline.
    for depth in range(18, 0, -1):
        draw_centred(draw, eight_font, eight_text, SIZE / 2, eight_cy, EIGHT_EXTRUDE,
                     offset=(depth, depth))
    draw_centred(draw, eight_font, eight_text, SIZE / 2, eight_cy, (255, 255, 255, 255),
                 stroke_fill=INK)

    return layer


def slate_background():
    """Dark slate with subtle grain, a soft top light and a vignette."""
    rng = random.Random(7)
    base = vertical_gradient((SIZE, SIZE), (46, 52, 60), (30, 34, 40), (17, 20, 24))
    noise = Image.new("L", (SIZE // 2, SIZE // 2))
    noise.putdata([int(max(0, min(255, rng.gauss(128, 26)))) for _ in range(noise.width * noise.height)])
    noise = noise.resize((SIZE, SIZE), Image.BILINEAR).filter(ImageFilter.GaussianBlur(1.2))
    grain = Image.merge("RGBA", (noise, noise, noise, Image.new("L", (SIZE, SIZE), 24)))
    base = Image.alpha_composite(base, grain)

    light = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(light).ellipse((-SIZE * 0.35, -SIZE * 0.8, SIZE * 1.35, SIZE * 0.5), fill=60)
    light = light.filter(ImageFilter.GaussianBlur(100))
    base = Image.composite(Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255)), base,
                           light.point(lambda v: v // 3))

    vignette = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(vignette).ellipse((-SIZE * 0.2, -SIZE * 0.2, SIZE * 1.2, SIZE * 1.2), fill=255)
    vignette = vignette.filter(ImageFilter.GaussianBlur(120))
    return Image.composite(base, Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 255)), vignette)


def compose(background):
    logo = lockup()
    if background == "transparent":
        canvas = logo
    elif background == "white":
        canvas = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255))
        canvas.alpha_composite(logo)
    else:
        canvas = slate_background()
        canvas.alpha_composite(logo)
    return canvas.resize((OUT, OUT), Image.LANCZOS)


def main():
    global FONT_BTA, FONT_EIGHTBITDO
    parser = argparse.ArgumentParser()
    parser.add_argument("--fonts", default=os.environ.get("BTA_LOGO_FONTS", "/tmp/opencode/fonts"))
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent))
    args = parser.parse_args()

    fonts = Path(args.fonts)
    FONT_BTA = fonts / "Anton-Regular.ttf"
    FONT_EIGHTBITDO = fonts / "Bungee-Regular.ttf"
    for path in (FONT_BTA, FONT_EIGHTBITDO):
        if not path.is_file():
            raise SystemExit(f"missing font: {path} (see the docstring)")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    images = {
        "icon-transparent.png": compose("transparent"),
        "icon-dark.png": compose("dark"),
        "icon-white.png": compose("white"),
    }
    for name, image in images.items():
        image.save(out / name, optimize=True)
        image.resize((64, 64), Image.LANCZOS).save(
            out / name.replace("icon-", "preview-64-"), optimize=True)

    # Primary icon: the dark slate variant reads on both light and dark UIs.
    images["icon-dark.png"].save(out / "icon.png", optimize=True)
    print(f"wrote {len(images) + 1} images to {out}")


if __name__ == "__main__":
    main()
