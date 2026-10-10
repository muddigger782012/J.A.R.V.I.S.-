"""Check the redesigned native screens in CI and capture review screenshots."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

output = Path('ui-review')
output.mkdir(exist_ok=True)


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True)


def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/window.xml'))


def tap(identifier):
    for node in hierarchy().iter('node'):
        if node.get('resource-id') == 'com.assistant.core:id/' + identifier:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            assert x2 > x1 and y2 > y1, f'{identifier} is not visible'
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(1)
            return
    raise AssertionError(f'{identifier} missing from visible UI')


def capture(name):
    raw = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'])
    (output / (name + '.png')).write_bytes(raw)
    (output / (name + '.xml')).write_text(ET.tostring(hierarchy(), encoding='unicode'))


try:
    adb('install', '-r', 'app-debug.apk')
    adb('shell', 'pm', 'grant', 'com.assistant.core', 'android.permission.RECORD_AUDIO')
    adb('shell', 'am', 'start', '-n', 'com.assistant.core/.MainActivity')
    time.sleep(3)
    assert any(n.get('resource-id') == 'com.assistant.core:id/etCommandInput' for n in hierarchy().iter('node')), 'Main screen did not open'
    capture('home')
    tap('etCommandInput')
    adb('shell', 'input', 'text', '2%s+%s2')
    time.sleep(1)
    capture('keyboard')
    tap('btnRunAssistantCommand')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    assert any(n.get('text') == '4' for n in hierarchy().iter('node')), 'Chat response card not rendered'
    capture('conversation')
    tap('btnVoiceSettings')
    capture('settings')
    assert any(n.get('text', '').casefold() == 'weather and ai' for n in hierarchy().iter('node')), 'Weather and AI is not visible in settings'
    tap('btnCloseVoiceSettings')
    # Exercise each palette through the native settings flow, then verify persistence.
    for name in ('Stark', 'Cybertron', 'Default'):
        tap('btnVoiceSettings')
        tap('btnThemes')
        if name == 'Cybertron':
            adb('shell', 'input', 'swipe', '500', '1500', '500', '500', '350')
            time.sleep(1)
        tap('btnTheme' + name)
        assert any(n.get('text') == name + ' • Active' for n in hierarchy().iter('node')), 'Theme not applied'
        capture('theme-picker-' + name.lower())
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        tap('btnCloseVoiceSettings')
        capture('theme-' + name.lower())
        if name == 'Cybertron':
            adb('shell', 'am', 'force-stop', 'com.assistant.core')
            adb('shell', 'am', 'start', '-n', 'com.assistant.core/.MainActivity')
            time.sleep(2)
            tap('btnVoiceSettings')
            assert any(n.get('text') == 'Themes • Cybertron' for n in hierarchy().iter('node')), 'Theme did not survive restart'
            tap('btnCloseVoiceSettings')
    adb('shell', 'wm', 'size', '720x1280')
    adb('shell', 'wm', 'density', '360')  # 320dp width
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(2)
    tap('etCommandInput')
    assert any(n.get('resource-id') == 'com.assistant.core:id/btnRunAssistantCommand' for n in hierarchy().iter('node')), 'Send hidden on small screen'
    capture('small-screen-keyboard')
except Exception:
    capture('failure')
    (output / 'logcat.txt').write_text(adb('logcat', '-d'))
    raise
