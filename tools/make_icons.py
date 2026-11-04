#!/usr/bin/env python3
"""Regenerate the launcher icon set from a single source image.

    python3 tools/make_icons.py [source.png]

The source is expected to be the artwork on a flat white background (any size,
PNG/JPG). Everything that is not near-white is treated as the mark; it is
trimmed to its bounding box and re-centred on a white square for each density.

Outputs into res/:
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png   48..192 px
    mipmap-xxxhdpi/ic_launcher_foreground.png                 432 px (108dp)
    mipmap-anydpi-v26/ic_launcher.xml                         adaptive icon
    values/colors.xml                                         background colour

Needs Pillow (pip install pillow).
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(HERE, "res")
DEFAULT_SRC = os.path.join(HERE, "tools", "icon-source.png")

# Fraction of the canvas the mark occupies. The mark's petals bulge to all four
# sides, so its extreme points sit on the diagonals: content at f reaches
# f/2*sqrt(2) from the centre. An adaptive icon only shows a 72dp circle inside
# its 108dp canvas (radius 1/3), so f must stay under 0.47 or masks clip the tips.
# 0.44 leaves visible clearance; legacy bitmaps get the same treatment at 0.66.
LEGACY_FRAC = 0.66
FOREGROUND_FRAC = 0.44
FOREGROUND_PX = 432
LEGACY = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]


def trim(im):
    """Crop to everything that is not near-white."""
    rgb = im.convert("RGB")
    px = rgb.load()
    w, h = rgb.size
    minx, miny, maxx, maxy = w, h, 0, 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if not (r > 238 and g > 238 and b > 238):
                minx = min(minx, x)
                miny = min(miny, y)
                maxx = max(maxx, x)
                maxy = max(maxy, y)
    if maxx <= minx or maxy <= miny:
        raise SystemExit("source image is blank (no non-white pixels found)")
    return rgb.crop((minx, miny, maxx + 1, maxy + 1))


def square(mark, size, frac):
    inner = int(size * frac)
    scale = min(inner / mark.size[0], inner / mark.size[1])
    scaled = mark.resize(
        (max(1, int(mark.size[0] * scale)), max(1, int(mark.size[1] * scale))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGB", (size, size), (255, 255, 255))
    canvas.paste(scaled, ((size - scaled.size[0]) // 2, (size - scaled.size[1]) // 2))
    return canvas


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    mark = trim(Image.open(src))
    print("mark: %dx%d from %s" % (mark.size[0], mark.size[1], src))

    for dpi, size in LEGACY:
        out = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(out, exist_ok=True)
        square(mark, size, LEGACY_FRAC).save(os.path.join(out, "ic_launcher.png"), optimize=True)
        print("  mipmap-%s/ic_launcher.png %dpx" % (dpi, size))

    out = os.path.join(RES, "mipmap-xxxhdpi")
    os.makedirs(out, exist_ok=True)
    square(mark, FOREGROUND_PX, FOREGROUND_FRAC).save(
        os.path.join(out, "ic_launcher_foreground.png"), optimize=True
    )
    print("  mipmap-xxxhdpi/ic_launcher_foreground.png %dpx" % FOREGROUND_PX)

    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w") as f:
        f.write(
            "\n".join(
                [
                    '<?xml version="1.0" encoding="utf-8"?>',
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
                    '    <background android:drawable="@color/ic_launcher_background" />',
                    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />',
                    "</adaptive-icon>",
                    "",
                ]
            )
        )
    os.makedirs(os.path.join(RES, "values"), exist_ok=True)
    with open(os.path.join(RES, "values", "colors.xml"), "w") as f:
        f.write(
            "\n".join(
                [
                    '<?xml version="1.0" encoding="utf-8"?>',
                    "<resources>",
                    '    <color name="ic_launcher_background">#FFFFFF</color>',
                    "</resources>",
                    "",
                ]
            )
        )
    print("  adaptive icon + colors.xml")


if __name__ == "__main__":
    main()
