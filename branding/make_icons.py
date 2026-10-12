"""Generate every TAK Beacon icon from branding/lighthouse-source.webp.

    python -I branding/make_icons.py

The source is white artwork on black, so luminance becomes alpha: the artwork
keeps its exact shape on any background, and the same mask serves as the
monochrome (themed) layer and the status-bar notification icon.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "branding", "lighthouse-source.webp")
RES = os.path.join(ROOT, "tracker", "app", "src", "main", "res")
OUT = os.path.join(ROOT, "branding", "generated")

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
BG = (0, 0, 0, 255)


def artwork_mask():
    """White-on-transparent artwork, cropped to its bounds and squared."""
    lum = Image.open(SRC).convert("L")
    alpha = lum.point(lambda v: 0 if v < 40 else (255 if v > 215 else int((v - 40) * 255 / 175)))
    alpha = alpha.crop(alpha.getbbox())
    side = max(alpha.size)
    sq = Image.new("L", (side, side), 0)
    sq.paste(alpha, ((side - alpha.width) // 2, (side - alpha.height) // 2))
    art = Image.new("RGBA", (side, side), (255, 255, 255, 0))
    art.putalpha(sq)
    return art


def fit(art, canvas, fraction):
    """Art centred on a transparent canvas, scaled to `fraction` of its side."""
    size = max(1, round(canvas * fraction))
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    a = art.resize((size, size), Image.LANCZOS)
    out.alpha_composite(a, ((canvas - size) // 2, (canvas - size) // 2))
    return out


def on_bg(img, rounded=None, circle=False):
    bg = Image.new("RGBA", img.size, (0, 0, 0, 0))
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    w, h = img.size
    if circle:
        d.ellipse((0, 0, w - 1, h - 1), fill=255)
    elif rounded:
        d.rounded_rectangle((0, 0, w - 1, h - 1), radius=rounded, fill=255)
    else:
        d.rectangle((0, 0, w, h), fill=255)
    bg.paste(Image.new("RGBA", img.size, BG), (0, 0), mask)
    bg.alpha_composite(img)
    return bg


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def main():
    art = artwork_mask()
    for name, scale in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        # Adaptive layers are 108dp; the safe zone is the central 66dp circle.
        fg = fit(art, round(108 * scale), 0.56)
        save(fg, d, "ic_launcher_foreground.png")
        save(fg, d, "ic_launcher_monochrome.png")
        # Legacy launchers (API < 26 never uses these: minSdk is 26, but some
        # launchers still read the plain mipmap).
        legacy = round(48 * scale)
        save(on_bg(fit(art, legacy, 0.78), rounded=round(legacy * 0.18)), d, "ic_launcher.png")
        save(on_bg(fit(art, legacy, 0.70), circle=True), d, "ic_launcher_round.png")
        # Status-bar icon: 24dp, white silhouette only.
        save(fit(art, round(24 * scale), 0.92), os.path.join(RES, f"drawable-{name}"), "ic_stat_beacon.png")

    # In-app brand mark (used on the home screen header).
    save(fit(art, 288, 1.0), os.path.join(RES, "drawable-nodpi"), "brand_lighthouse.png")

    # Play Console: 512x512, full-bleed square, Play applies its own mask.
    save(on_bg(fit(art, 512, 0.80)), OUT, "play-store-icon-512.png")
    # ATAK plugin: the bare white glyph for ATAK's dark toolbar, and the glyph on
    # a dark tile for android:icon, which Android draws on light backgrounds.
    plugin_res = os.path.join(ROOT, "plugins", "TAKBeacon", "app", "src", "main", "res", "drawable")
    save(fit(art, 192, 0.92), plugin_res, "ic_toolbar.png")
    tile = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle((0, 0, 255, 255), radius=28, fill=(0x12, 0x12, 0x12, 255))
    tile.alpha_composite(fit(art, 256, 0.78))
    save(tile, plugin_res, "ic_launcher.png")
    print("icons written to", RES, "and", OUT)


if __name__ == "__main__":
    main()
