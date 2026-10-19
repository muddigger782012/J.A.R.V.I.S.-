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
