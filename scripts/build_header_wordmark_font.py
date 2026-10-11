"""Build original, reference-inspired angular lettering for the J.A.R.V.I.S. header.

Requires fontTools. This limited wordmark font is not used for general app text.
"""
from pathlib import Path
from math import cos, sin, pi, hypot
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/font/jarvis_wordmark.ttf"
PATHS = {
    "J": ([(60, 230), (60, 125), (110, 70), (650, 70), (700, 120), (700, 690)],),
    "A": ([(60, 70), (680, 690), (800, 690), (800, 70)],),
    "R": ([(60, 70), (60, 690), (650, 690), (720, 625), (720, 445), (655, 380), (60, 380)],
          [(410, 380), (750, 70)]),
    "V": ([(60, 690), (60, 70), (180, 70), (780, 690)],),
    "I": ([(80, 690), (80, 70)],),
    "S": ([(750, 690), (110, 690), (60, 640), (60, 445), (110, 390),
           (670, 390), (725, 335), (725, 125), (670, 70), (60, 70)],),
}
WIDTHS = {"J": 770, "A": 870, "R": 820, "V": 850, "I": 160, "S": 820, "period": 150, "space": 200, ".notdef": 800}

def polygon(pen, points):
    pen.moveTo(points[0])
    for point in points[1:]:
        pen.lineTo(point)
    pen.closePath()

def dot(pen, x, y, radius):
    polygon(pen, [(round(x + radius * cos(i * 2 * pi / 24)),
                   round(y + radius * sin(i * 2 * pi / 24))) for i in range(24)])

def stroke(pen, points, radius=23):
    for (x, y), (xx, yy) in zip(points, points[1:]):
        length = hypot(xx - x, yy - y)
        dx, dy = -(yy - y) * radius / length, (xx - x) * radius / length
        polygon(pen, [(round(x + dx), round(y + dy)), (round(x - dx), round(y - dy)),
                      (round(xx - dx), round(yy - dy)), (round(xx + dx), round(yy + dy))])
    for x, y in points:
        dot(pen, x, y, radius)

def build():
    order = [".notdef", "space", "period", *PATHS]
    glyphs = {}
    for name in order:
        pen = TTGlyphPen(None)
        for path in PATHS.get(name, ()):
            stroke(pen, path)
        if name == "period":
            polygon(pen, [(42, 44), (42, 110), (108, 110), (108, 44)])
        glyphs[name] = pen.glyph()
    font = FontBuilder(1000, isTTF=True)
    font.setupGlyphOrder(order)
    font.setupCharacterMap({**{ord(ch): ch for ch in PATHS}, ord("."): "period", ord(" "): "space"})
    font.setupGlyf(glyphs)
    font.setupHorizontalMetrics({name: (WIDTHS[name], 0 if name == "space" else (glyphs[name].xMin if glyphs[name].numberOfContours else 0)) for name in order})
    font.setupHorizontalHeader(ascent=850, descent=-150)
    font.setupNameTable({"familyName": "Jarvis Header Wordmark", "styleName": "Regular",
                        "uniqueFontIdentifier": "JarvisHeaderWordmark-Regular-v1",
                        "fullName": "Jarvis Header Wordmark Regular", "psName": "JarvisHeaderWordmark-Regular",
                        "version": "Version 1.0"})
    font.setupOS2(sTypoAscender=850, sTypoDescender=-150, usWinAscent=850, usWinDescent=150,
                 sxHeight=620, sCapHeight=713, usWeightClass=400)
    font.setupPost()
    font.setupMaxp()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    font.save(OUTPUT)

if __name__ == "__main__":
    build()
