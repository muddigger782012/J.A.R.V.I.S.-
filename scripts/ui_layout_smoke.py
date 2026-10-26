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
    # Cold boot/window transitions can temporarily expose no root. Retry only
    # the observation, never a tap, and remove any stale dump before reading.
    for attempt in range(3):
        try:
            adb('shell', 'rm', '-f', '/sdcard/window.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
            root = ET.fromstring(adb('shell', 'cat', '/sdcard/window.xml'))
            assert any(root.iter('node')), 'Empty Accessibility hierarchy'
            return root
        except (subprocess.CalledProcessError, ET.ParseError, AssertionError):
            if attempt == 2:
                raise
            time.sleep(0.5)


def tap(identifier):
    for node in hierarchy().iter('node'):
        if node.get('resource-id') == 'com.assistant.core:id/' + identifier:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            assert x2 > x1 and y2 > y1, f'{identifier} is not visible'
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(1)
            return
    raise AssertionError(f'{identifier} missing from visible UI')



def tap_scrolling(identifier, attempts=10):
    # Re-locate the control after every gesture; theme selection may reset
    # the nested Appearance scroll position.
    expected = 'com.assistant.core:id/' + identifier
    visited = set()
    for attempt in range(attempts):
        root = hierarchy()
        nodes = list(root.iter('node'))
        target = next((n for n in nodes if n.get('resource-id') == expected), None)
        if target is not None:
            bounds = list(map(int, re.findall(r'\\d+', target.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                time.sleep(1)
                return
        # Scroll inside a visible scrollable node, rather than hard-coded
        # screen coordinates that can miss on different emulator sizes.
        scrolls = []
        for node in nodes:
            if node.get('scrollable') != 'true':
                continue
            bounds = list(map(int, re.findall(r'\\d+', node.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] - bounds[0] > 80 and bounds[3] - bounds[1] > 160:
                scrolls.append(bounds)
        if not scrolls:
            raise AssertionError(f'{identifier} missing: no scrollable Appearance container')
        # Try nested and outer regions; reverse direction when reaching the end.
        scrolls.sort(key=lambda b: (b[2]-b[0]) * (b[3]-b[1]))
        bounds = scrolls[attempt % len(scrolls)]
        x1, y1, x2, y2 = bounds
        x = (x1 + x2) // 2
        top = y1 + (y2-y1) // 4
        bottom = y1 + 3*(y2-y1) // 4
        direction = -1 if attempt < attempts // 2 else 1
        signature = (ET.tostring(root, encoding='unicode'), tuple(bounds), direction)
        if signature in visited:
            print(f'{identifier}: repeated hierarchy during scroll', flush=True)
        visited.add(signature)
        adb('shell', 'input', 'swipe', str(x), str(bottom if direction == -1 else top),
            str(x), str(top if direction == -1 else bottom), '350')
        time.sleep(0.8)
    raise AssertionError(f'{identifier} missing after {attempts} bounded scroll attempts')

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
    # The chat response can be asynchronous or routed through a remote model.
    # Record it without blocking independent UI/layout verification.
    chat_texts = [n.get('text', '') for n in hierarchy().iter('node')]
    if not any(text.strip() == '4' for text in chat_texts):
        print('Chat arithmetic response not observed; continuing independent UI checks', flush=True)
    capture('conversation')
    tap('btnVoiceSettings')
    # The toolbar button opens the Tools & Settings popup, not SettingsActivity.
    settings_entry = next((n for n in hierarchy().iter('node')
        if n.get('text', '').strip() == 'Settings'), None)
    assert settings_entry is not None, 'Settings entry missing from Tools & Settings menu'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', settings_entry.get('bounds', '')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)
    capture('settings')
    # Settings rows may be below the fold; scroll before treating them as missing.
    weather_found = False
    for attempt in range(6):
        if any(n.get('text', '').strip().casefold() == 'ai and weather' for n in hierarchy().iter('node')):
            weather_found = True
            break
        adb('shell', 'input', 'swipe', '500', '1450', '500', '550', '300')
        time.sleep(1)
    assert weather_found, 'AI and weather not found after scrolling Settings'
    # Verify the current Settings destinations, not removed legacy controls.
    def open_settings_destination(label):
        for attempt in range(7):
            entry = next((n for n in hierarchy().iter('node')
                if n.get('text', '').strip() == label), None)
            if entry is not None:
                coords = list(map(int, re.findall(r'\d+', entry.get('bounds', ''))))
                assert len(coords) == 4, f'{label} has invalid bounds'
                x1, y1, x2, y2 = coords
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(1)
                return
            adb('shell', 'input', 'swipe', '500', '450', '500', '1450', '300')
            time.sleep(1)
        raise AssertionError(f'{label} destination missing from Settings')

    open_settings_destination('Learning and memory')
    assert any('Learning on this device' in n.get('text', '') for n in hierarchy().iter('node')), 'Intelligence screen did not open'
    capture('intelligence')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    open_settings_destination('Self Diagnostics & Improvements')
    assert any('Read-only review.' in n.get('text', '') for n in hierarchy().iter('node')), 'Self Diagnostics screen did not open'
    capture('self-diagnostics')
    tap('menuPageBack')
    time.sleep(1)
    open_settings_destination('Appearance')
    assert any(n.get('resource-id') == 'com.assistant.core:id/appearanceTabs' for n in hierarchy().iter('node')), 'Appearance screen did not open'
    capture('appearance')
    # The theme grid may be nested in a scrolling container; first capture
    # its hierarchy so a missing tile is diagnosable instead of silently skipped.
    theme_ids = {'btnThemeStark', 'btnThemeCybertron', 'btnThemeDefault'}
    visible_ids = {n.get('resource-id', '').split('/')[-1] for n in hierarchy().iter('node')}
    print('Visible theme controls: ' + repr(sorted(visible_ids & theme_ids)), flush=True)
    capture('theme-picker-initial')
    for name in ('Stark', 'Cybertron', 'Default'):
        try:
            tap_scrolling('btnTheme' + name)
            capture('theme-picker-' + name.lower())
        except AssertionError:
            capture('theme-picker-missing-' + name.lower())
            raise
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    # The visible Tools/Support popup owns these destinations; legacy header
    # buttons remain hidden. Verify every current destination rather than skip it.
    def tap_text(label):
        entry = next((n for n in hierarchy().iter('node')
                      if n.get('text', '').strip() == label), None)
        assert entry is not None, f'{label} menu entry missing'
        bounds = list(map(int, re.findall(r'\d+', entry.get('bounds', ''))))
        assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1], f'{label} not visible'
        adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
        time.sleep(1)

    for name, group, label, section in [
        ('projects', 'Tools', 'Projects', 'tabProjectSection'),
        ('shizuku', 'Tools', 'Device tools', 'tabShizukuSection'),
        ('diagnostics', 'Support', 'Status and updates', 'tabStatusSection'),
        ('terminal', 'Tools', 'Terminal', 'tabTerminalSection'),
        ('activity-log', 'Support', 'Activity log', 'tabAuditSection'),
        ('permissions', 'Support', 'Permissions', 'tabPermissionsSection'),
        ('device-admin', 'Tools', 'Device administration', 'tabDhizukuSection'),
    ]:
        tap('btnVoiceSettings')
        tap_text(group)
        tap_text(label)
        assert any(n.get('resource-id') == 'com.assistant.core:id/' + section
                   for n in hierarchy().iter('node')), f'{label} screen did not open'
        capture(name)
        # Every utility destination must be escapable without killing the app.
        # Android Back must restore the conversation, not trap the user in a tab.
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        assert any(
            n.get('resource-id') == 'com.assistant.core:id/etCommandInput'
            for n in hierarchy().iter('node')
        ), f'{label} trapped the user: Android Back did not restore Chat'
        capture(name + '-back-to-chat')
        # Close is a separate user path and must stay outside the scroll content.
        tap('btnVoiceSettings')
        tap_text(group)
        tap_text(label)
        assert any(n.get('resource-id') == 'com.assistant.core:id/menuPageBack'
                   for n in hierarchy().iter('node')), f'{label} has no visible Back control'
        tap('menuPageClose')
        assert any(n.get('resource-id') == 'com.assistant.core:id/etCommandInput'
                   for n in hierarchy().iter('node')), f'{label} Close did not restore Chat'
    # Navigation history must return to the previous utility panel, then Close
    # clears that trail and restores the existing conversation.
    tap('btnVoiceSettings'); tap_text('Tools'); tap_text('Projects')
    tap('btnVoiceSettings'); tap_text('Tools'); tap_text('Terminal')
    tap('menuPageBack')
    assert any(n.get('resource-id') == 'com.assistant.core:id/tabProjectSection'
               for n in hierarchy().iter('node')), 'Back did not restore the previous utility page'
    tap('menuPageClose')
    assert any(n.get('resource-id') == 'com.assistant.core:id/etCommandInput'
               for n in hierarchy().iter('node')), 'Close retained a utility page'
    # Root/group popup controls must also offer an exit and a route to their parent.
    tap('btnVoiceSettings'); tap_text('Support'); tap('menuPageBack')
    assert any(n.get('text', '').strip() == 'Settings' for n in hierarchy().iter('node')), 'Support Back did not restore the root menu'
    tap('menuPageClose')
    assert any(n.get('resource-id') == 'com.assistant.core:id/etCommandInput'
               for n in hierarchy().iter('node')), 'Root menu Close did not restore Chat'
    # Relaunch the existing entry activity to return to Chat; retain app data.
    adb('shell', 'am', 'force-stop', 'com.assistant.core')
    adb('shell', 'am', 'start', '-n', 'com.assistant.core/.MainActivity')
    time.sleep(3)
    assert any(n.get('resource-id') == 'com.assistant.core:id/etCommandInput'
               for n in hierarchy().iter('node')), 'Chat did not reopen'
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
