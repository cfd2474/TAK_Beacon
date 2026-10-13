"""Google Play store graphics for TAK Beacon.

    python -I branding/make_store_assets.py [output dir]

Writes:
  TAKBeacon-play-icon-512.png        512x512, 32-bit PNG   (Play: app icon)
  TAKBeacon-feature-graphic.png      1024x500, 24-bit PNG  (Play: feature graphic, no alpha)

The artwork is branding/lighthouse-source.webp; the type is Libre Franklin
(SIL Open Font License), from the plugin's user manual fonts.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "branding", "lighthouse-source.webp")
FONTS = os.path.join(ROOT, "plugins", "TAKBeacon", "docs", "user_manual", "fonts")
AMBER = (255, 179, 0)
BG = (11, 14, 17)


def artwork(height):
    """White lighthouse artwork as an RGBA image of the given height."""
    lum = Image.open(SRC).convert("L")
    alpha = lum.point(lambda v: 0 if v < 40 else (255 if v > 215 else int((v - 40) * 255 / 175)))
    alpha = alpha.crop(alpha.getbbox())
    w = round(alpha.width * height / alpha.height)
    alpha = alpha.resize((w, height), Image.LANCZOS)
    art = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    art.putalpha(alpha)
    return art


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def feature_graphic():
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), BG)
    art = artwork(400)
    ax, ay = W - art.width - 44, H - art.height - 40
    # A soft amber glow on the lantern (about 80% across, 18% down the artwork).
    gx, gy = ax + int(art.width * 0.82), ay + int(art.height * 0.18)
    glow = Image.new("L", (W, H), 0)
    gd = ImageDraw.Draw(glow)
    for r in range(240, 0, -4):
        gd.ellipse((gx - r, gy - r, gx + r, gy + r), fill=int(70 * (1 - r / 240) ** 1.6))
    img.paste(Image.new("RGB", (W, H), AMBER), (0, 0), glow)
    img.paste(art, (ax, ay), art)

    d = ImageDraw.Draw(img)
    d.text((64, 118), "TAK Beacon", font=font("LibreFranklin-Bold.otf", 82), fill=(255, 255, 255))
    d.rectangle((68, 236, 188, 242), fill=AMBER)
    sub = font("LibreFranklin-Regular.otf", 33)
    d.text((66, 266), "Your position on the TAK map,", font=sub, fill=(214, 220, 226))
    d.text((66, 308), "even when ATAK is closed.", font=sub, fill=(214, 220, 226))
    d.text((66, 380), "PLI  ·  Emergency alerts  ·  Satellite-ready",
           font=font("LibreFranklin-Medium.otf", 24), fill=AMBER)
    return img


def icon():
    # Same artwork as the launcher icon: full-bleed black square; Play applies its own mask.
    side = 512
    img = Image.new("RGBA", (side, side), BG + (255,))
    art = artwork(round(side * 0.80))
    if art.width > side * 0.86:
        art = art.resize((round(side * 0.86), round(art.height * side * 0.86 / art.width)), Image.LANCZOS)
    img.alpha_composite(art, ((side - art.width) // 2, (side - art.height) // 2))
    return img


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "branding", "generated")
    os.makedirs(out, exist_ok=True)
    icon().save(os.path.join(out, "TAKBeacon-play-icon-512.png"), optimize=True)
    feature_graphic().save(os.path.join(out, "TAKBeacon-feature-graphic.png"), optimize=True)
    print("written to", out)


if __name__ == "__main__":
    main()
