#!/usr/bin/env python3
"""Regenerate the two tintable brandmark masks from the splash raster.

    python3 tools/brandmark/split_glyph.py [size]     # run from the repo root

The source artwork `app/src/main/res/drawable-nodpi/splash_bt_glyph.png` has a
WHITE "B" and a gold "T" baked into it, because it was drawn for the splash
screen's pinned dark canvas. Inside the app that is an invisible logo on the
light theme (owner report 2026-09-02), and a second baked variant would only
move the problem — it would need a third for true black, and every one of them
would be a per-theme hex.

So the colour is taken OUT of the artwork exactly once, here, and put back at
draw time by `ui/components/Wordmark.kt`'s `BtBrandmark`:

    bt_brandmark_ink.png   the full silhouette (B u T)  -> tinted BtColors.textPrimary
    bt_brandmark_gold.png  the "T" alone                -> tinted BtColors.gold, on top

Both outputs are white pixels carrying nothing but an alpha channel, so
`ColorFilter.tint` (SrcIn) reproduces the letterforms in whatever ink the theme
asks for. Painting the union underneath and the T over it also reproduces the
seam between the two glyphs, which now blends into the ink instead of into a
baked white.

The splash itself keeps the original raster: that frame is drawn by the window
manager before Compose exists and cannot read a theme at all.

Re-run this ONLY when the source artwork changes, and commit both outputs.
Requires Pillow.
"""
import sys

from PIL import Image

SRC = "app/src/main/res/drawable-nodpi/splash_bt_glyph.png"
OUT_DIR = "app/src/main/res/drawable-nodpi"

# Blue channel of the two baked colours: #FFFFFF and the brand gold #F6B82E. It
# is the cleanest single-channel discriminator between them, and it degrades
# gracefully across the antialiased pixels where the glyphs meet.
GOLD_B = 46
WHITE_B = 255

# Half the source's 864 px. The mask is drawn at 72 dp, i.e. 288 px on a 4x
# screen, so 432 keeps a 1.5x oversample while halving the checked-in bytes.
# The FRAMING is kept (the source's splash safe-zone padding included) so the
# mark occupies exactly the box it always did.
DEFAULT_SIZE = 432


def main() -> None:
    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    px = im.load()

    ink = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    gold = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    ink_px = ink.load()
    gold_px = gold.load()

    counts = {"ink": 0, "gold": 0, "seam": 0}
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            t = (WHITE_B - b) / float(WHITE_B - GOLD_B)
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            ink_px[x, y] = (255, 255, 255, a)
            gold_alpha = int(round(a * t))
            if gold_alpha:
                gold_px[x, y] = (255, 255, 255, gold_alpha)
            counts["gold" if t > 0.98 else "ink" if t < 0.02 else "seam"] += 1

    size = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SIZE
    if size != w:
        ink = ink.resize((size, size), Image.LANCZOS)
        gold = gold.resize((size, size), Image.LANCZOS)

    ink.save(OUT_DIR + "/bt_brandmark_ink.png", optimize=True)
    gold.save(OUT_DIR + "/bt_brandmark_gold.png", optimize=True)
    print("classified pixels:", counts, "- written at", size, "px")


if __name__ == "__main__":
    main()
