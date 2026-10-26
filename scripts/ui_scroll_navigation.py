"""Bounded UI-test navigation using the actual visible scroll viewport."""
import re


def tap_scrolling(identifier, hierarchy, adb, sleep, attempts=6):
    previous = None
    towards_start = True
    for _ in range(attempts):
        nodes = list(hierarchy().iter('node'))
        target = next((n for n in nodes if n.get('resource-id') == 'com.assistant.core:id/' + identifier), None)
        if target is not None:
            bounds = list(map(int, re.findall(r'\d+', target.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                sleep(1)
                return
        viewport = next((n for n in nodes if n.get('resource-id') == 'com.assistant.core:id/appearanceScroll'), None)
        if viewport is None:
            viewport = next((n for n in nodes if n.get('scrollable') == 'true' and 'Horizontal' not in n.get('class', '')), None)
        assert viewport is not None, f'{identifier}: no visible scroll viewport'
        bounds = list(map(int, re.findall(r'\d+', viewport.get('bounds', ''))))
        assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1], 'Invalid scroll viewport'
        current = tuple((n.get('resource-id'), n.get('text'), n.get('bounds')) for n in viewport.iter('node'))
        # Start by searching upwards. Once the top is observed twice, search downwards.
        # Applying a theme can preserve its position, so a forward-only search misses earlier tiles.
        if towards_start and current == previous:
            towards_start = False
        x = (bounds[0] + bounds[2]) // 2
        upper = bounds[1] + (bounds[3] - bounds[1]) // 4
        lower = bounds[3] - (bounds[3] - bounds[1]) // 4
        start, end = (upper, lower) if towards_start else (lower, upper)
        adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '400')
        previous = current
        sleep(1)
    raise AssertionError(f'{identifier} missing after scrolling')
