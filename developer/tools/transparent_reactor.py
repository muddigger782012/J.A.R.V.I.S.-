"""Remove the baked-in black square from the original J.A.R.V.I.S. reactor artwork.

Runs before Android resource compilation so both the launcher icon and in-app
reactor have real transparent pixels. The source asset remains untouched in Git.
"""
from pathlib import Path
from PIL import Image

asset = Path("developer/src/main/res/drawable-nodpi/developer_arc_reactor_art.webp")
image = Image.open(asset).convert("RGBA")
pixels = []
for r, g, b, a in image.getdata():
    # Black becomes transparent; feather near-black antialiasing pixels.
    brightness = max(r, g, b)
    opacity = max(0, min(255, round((brightness - 12) * 255 / 45)))
    pixels.append((r, g, b, a * opacity // 255))
image.putdata(pixels)
image.save(asset, format="WEBP", lossless=True, exact=True)
print(f"Transparent reactor prepared: {image.width}x{image.height}")

# Android's launcher must use the processed transparent pixels, not the
# original approved PNG (which may contain an opaque white canvas).
launcher = Path("developer/src/main/res/drawable-nodpi/developer_launcher_transparent.png")
image.save(launcher, format="PNG", optimize=True)
assert image.getpixel((0, 0))[3] == 0, "Launcher corner must be transparent"
print(f"Transparent launcher written: {launcher}")
