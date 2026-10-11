"""Trace the approved Jarvis label into resolution-independent Android artwork.

Requires Pillow, numpy and matplotlib. The uploaded reference remains unchanged.
"""
from pathlib import Path
import numpy as np
from PIL import Image
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/res/drawable-nodpi/jarvis_label_reference.jpg"
OUTPUT = ROOT / "app/src/main/res/drawable/jarvis_label_sharp.xml"

def trace():
    # Same letter-only viewport as the approved bitmap; preserve its proportions.
    pixels = np.asarray(Image.open(SOURCE).convert("L"))[415:456, 575:951]
    contours = plt.contour(pixels, levels=[128]).allsegs[0]
    paths = []
    for points in contours:
        if len(points) < 4:
            continue
        # Smooth subpixel sampling noise without redrawing letter geometry.
        if np.linalg.norm(points[0] - points[-1]) < .01:
            points = points[:-1]
        points = .8 * points + .1 * np.roll(points, 1, axis=0) + .1 * np.roll(points, -1, axis=0)
        paths.append("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z")
    plt.close("all")
    data = " ".join(paths)
    OUTPUT.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="188dp" android:height="20.5dp"\n'
        '    android:viewportWidth="376" android:viewportHeight="41">\n'
        '    <path android:fillColor="?attr/jarvis_on_dark" android:fillType="evenOdd"\n'
        f'        android:pathData="{data}" />\n</vector>\n')
    print(f"Traced {len(paths)} closed contours into {OUTPUT.name}")

if __name__ == "__main__":
    trace()
