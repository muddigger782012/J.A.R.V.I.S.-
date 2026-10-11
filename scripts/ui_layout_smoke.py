"""Check the redesigned native screens in CI and capture review screenshots."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

output = Path('ui-review')
output.mkdir(exist_ok=True)


def adb(*args):
    print('ADB: ' + ' '.join(args), flush=True)
    return subprocess.check_output(['adb', *args], text=True, timeout=45)


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



def tap_scrolling(identifier, attempts=6):
    for attempt in range(attempts):
        nodes = list(hierarchy().iter('node'))
        target = next((n for n in nodes if n.get('resource-id') == 'com.assistant.core:id/' + identifier), None)
        if target is not None:
            bounds = list(map(int, re.findall(r'\\d+', target.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                time.sleep(1)
                return
        adb('shell', 'input', 'swipe', '500', '1350', '500', '420', '300')
        time.sleep(1)
    raise AssertionError(f'{identifier} missing after scrolling')

def capture(name):
    print('Screenshot: ' + name, flush=True)
    raw = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=45)
    (output / (name + '.png')).write_bytes(raw)
    (output / (name + '.xml')).write_text(ET.tostring(hierarchy(), encoding='unicode'))


try:
    apk_files = sorted(Path('app/build/outputs/apk/debug').glob('*.apk'))
    assert apk_files, 'No debug APK found after artifact download'
    device_abi = adb('shell', 'getprop', 'ro.product.cpu.abi').strip()
    compatible = [p for p in apk_files if device_abi in p.name]
    universal = [p for p in apk_files if 'universal' in p.name]
    if compatible:
        selected_apk = compatible[0]
    elif universal:
        selected_apk = universal[0]
    else:
        raise AssertionError(
            f'No compatible APK for emulator ABI {device_abi}. '
            f'Available APKs: {[p.name for p in apk_files]}'
        )
    print(f'Installing {selected_apk.name} for {device_abi}', flush=True)
    adb('install', '-r', str(selected_apk))
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
    # Confirm the new learning control opens its diagnostics without deleting data.
    learning_button = None
    for attempt in range(4):
        learning_button = next((n for n in hierarchy().iter('node')
            if n.get('text') == 'Intelligence • Local Learning'), None)
        if learning_button is not None:
            break
        adb('shell', 'input', 'swipe', '500', '1450', '500', '550', '300')
        time.sleep(1)
    assert learning_button is not None, 'Local Learning settings button not found'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', learning_button.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)
    assert any('Learned intent examples:' in n.get('text', '') for n in hierarchy().iter('node')), 'Learning diagnostics dialog did not open'
    capture('learning-diagnostics')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    tap('btnCloseVoiceSettings')
    # Exercise each palette through the native settings flow, then verify persistence.
    for name in ('Stark', 'Cybertron', 'Default'):
        # Exercise the theme picker directly; the nested Appearances scroll view
        # can hide controls from UIAutomator on the emulator.
        adb('shell', 'am', 'start', '-n', 'com.assistant.core/.ThemeSettingsActivity')
        time.sleep(1)
        if name == 'Cybertron':
            adb('shell', 'input', 'swipe', '500', '1500', '500', '500', '350')
            time.sleep(1)
        tap_scrolling('btnTheme' + name)
        assert any(n.get('text') in (name + ' • Active', 'Current theme: ' + name) for n in hierarchy().iter('node')), 'Theme not applied'
        capture('theme-picker-' + name.lower())
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        capture('theme-' + name.lower())
        if name == 'Cybertron':
            adb('shell', 'am', 'force-stop', 'com.assistant.core')
            adb('shell', 'am', 'start', '-n', 'com.assistant.core/.MainActivity')
            time.sleep(2)
            tap('btnVoiceSettings')
            assert any('Cybertron' in n.get('text', '') and 'Appearances' in n.get('text', '') for n in hierarchy().iter('node')), 'Theme did not survive restart'
            tap('btnCloseVoiceSettings')
    adb('shell', 'wm', 'size', '720x1280')
    adb('shell', 'wm', 'density', '360')  # 320dp width
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(2)
    tap('etCommandInput')
    assert any(n.get('resource-id') == 'com.assistant.core:id/btnRunAssistantCommand' for n in hierarchy().iter('node')), 'Send hidden on small screen'
    capture('small-screen-keyboard')
except Exception:
    import traceback
    (output / 'failure.txt').write_text(traceback.format_exc())
    try:
        capture('failure')
    except Exception as error:
        print('Failure screenshot unavailable: ' + str(error), flush=True)
    try:
        (output / 'logcat.txt').write_text(adb('logcat', '-d'))
    except Exception as error:
        print('Logcat unavailable: ' + str(error), flush=True)
    raise
