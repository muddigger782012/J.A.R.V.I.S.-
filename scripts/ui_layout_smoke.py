"""Tap the public Android UI in a disposable emulator and capture review screenshots."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.assistant.core"
OUTPUT = Path("ui-review")


def adb(*args):
    print("ADB: " + " ".join(args), flush=True)
    return subprocess.check_output(["adb", *args], text=True, timeout=45)


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if not match:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def find_target(root, *, identifier=None, label=None, viewport=None):
    for node in root.iter("node"):
        if node.get("package") != PACKAGE or node.get("enabled") == "false" or not bounds(node):
            continue
        if viewport is not None:
            x1, y1, x2, y2 = bounds(node)
            if x1 >= viewport[0] or y1 >= viewport[1] or x2 <= 0 or y2 <= 0:
                continue
        if identifier is not None and node.get("resource-id") == PACKAGE + ":id/" + identifier:
            return node
        description = node.get("content-desc", "").casefold()
        wanted = label.casefold() if label is not None else None
        # Menu descendants are intentionally hidden from accessibility; their row owns the label.
        if wanted is not None and node.get("clickable") == "true" and (
            description == wanted or description.startswith(wanted + ". ") or node.get("text", "").casefold() == wanted
        ):
            return node
    return None


def choose_apk(paths, abi):
    abi_name = re.compile(r"(?:^|[-.])" + re.escape(abi) + r"(?:[-.]|$)")
    compatible = sorted(path for path in paths if abi_name.search(path.name))
    universal = sorted(path for path in paths if "universal" in path.name)
    if not compatible and not universal:
        raise AssertionError(f"No APK for emulator ABI {abi}; available: {[p.name for p in paths]}")
    return (compatible or universal)[0]


def hierarchy():
    adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/window.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/window.xml"))


def wait_for(check, message, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = hierarchy()
        if check(root):
            return root
        time.sleep(0.3)
    raise AssertionError(message)


def has_text(root, value):
    return any(value in node.get("text", "") for node in root.iter("node") if node.get("package") == PACKAGE)


def has_id(root, identifier):
    return find_target(root, identifier=identifier) is not None


def has_label(root, label):
    return find_target(root, label=label) is not None


def screen_size():
    matches = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    assert matches, "Cannot determine emulator screen size"
    return tuple(map(int, matches[-1]))


def tap_node(node):
    rectangle = bounds(node)
    assert rectangle, "Target has no visible bounds"
    x1, y1, x2, y2 = rectangle
    width, height = screen_size()
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(width, x2), min(height, y2)
    assert x2 > x1 and y2 > y1, "Target is outside the visible screen"
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.4)


def scroll(root, *, upward=False):
    width, height = screen_size()
    regions = [
        bounds(node) for node in root.iter("node")
        if node.get("package") == PACKAGE and node.get("scrollable") == "true" and bounds(node)
    ]
    x1, y1, x2, y2 = max(regions, key=lambda r: (r[2] - r[0]) * (r[3] - r[1])) if regions else (0, 0, width, height)
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(width, x2), min(height, y2)
    center = (x1 + x2) // 2
    start, end = y1 + (y2-y1)*4//5, y1 + (y2-y1)//5
    if upward:
        start, end = end, start
    adb("shell", "input", "swipe", str(center), str(start), str(center), str(end), "300")
    time.sleep(0.4)


def tap(*, identifier=None, label=None, scrolling=False):
    for attempt in range(8 if scrolling else 3):
        root = hierarchy()
        node = find_target(root, identifier=identifier, label=label, viewport=screen_size())
        if node is not None:
            tap_node(node)
            return
        if scrolling:
            # Return from a child screen may preserve the menu's old scroll position.
            scroll(root, upward=attempt < 2)
        else:
            time.sleep(0.4)
    raise AssertionError(f"Missing UI target: {identifier or label}")


def capture(name):
    print("Screenshot: " + name, flush=True)
    OUTPUT.mkdir(exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=45))
    (OUTPUT / (name + ".xml")).write_text(ET.tostring(hierarchy(), encoding="unicode"))


def start_main():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    wait_for(lambda root: has_id(root, "etCommandInput"), "Main chat did not open")


def open_settings():
    tap(identifier="btnVoiceSettings")
    tap(label="Settings")
    wait_for(lambda root: has_label(root, "Back to chat") and has_label(root, "Wake word and listening"),
             "Settings category hub did not open")


def open_appearance():
    open_settings()
    tap(label="Appearance", scrolling=True)
    wait_for(lambda root: has_label(root, "Themes") and has_label(root, "Fonts"), "Appearance menu did not open")


def back_to_chat_from_themes():
    tap(identifier="btnCloseAppearances")  # Themes -> Appearance categories.
    wait_for(lambda root: has_label(root, "Fonts"), "Themes Back did not return to Appearance")
    tap(identifier="btnCloseAppearances")  # Appearance -> Settings.
    wait_for(lambda root: has_label(root, "Wake word and listening"), "Appearance Back did not return to Settings")
    tap(label="Back to chat")
    wait_for(lambda root: has_id(root, "etCommandInput"), "Settings Back did not return to chat")


def require_emulator():
    assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1" or (
        adb("shell", "getprop", "ro.boot.qemu").strip() == "1"
    ), "This script changes disposable emulator settings; it must not run on a physical device"


def prepare_emulator():
    adb("shell", "am", "force-stop", PACKAGE)
    # Prevent search/assistant setup windows from stealing the emulator's tap focus.
    subprocess.run(["adb", "shell", "pm", "disable-user", "--user", "0", "com.google.android.googlequicksearchbox"], check=False, timeout=45)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    adb("shell", "svc", "power", "stayon", "true")
    # Debug run-as only: avoid background wake startup/model downloads during layout review.
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
    fixtures = {
        "voice_settings.xml": '<map><boolean name="auto_start_voice" value="false" /><boolean name="auto_start_foreground_service" value="false" /><boolean name="use_foreground_service_mode" value="false" /></map>',
        "main_ui.xml": '<map><boolean name="prompted_mic_permission" value="true" /></map>',
    }
    for name, xml in fixtures.items():
        subprocess.run(["adb", "shell", "run-as", PACKAGE, "tee", "shared_prefs/" + name],
                       input=xml, text=True, stdout=subprocess.DEVNULL, check=True, timeout=45)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
    # The math/chat and bundled plan routes should work without an internet connection.
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")


def run_smoke():
    require_emulator()
    paths = list(Path("app/build/outputs/apk/debug").glob("*.apk"))
    assert paths, "No debug APK found after artifact download"
    selected = choose_apk(paths, adb("shell", "getprop", "ro.product.cpu.abi").strip())
    adb("install", "-r", str(selected))
    prepare_emulator()
    start_main()
    capture("home")
    tap(identifier="etCommandInput")
    adb("shell", "input", "text", "2%s+%s2")
    capture("keyboard")
    tap(identifier="btnRunAssistantCommand")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for(lambda root: any(node.get("text") == "4" for node in root.iter("node")), "Local math reply was not rendered")
    capture("conversation")

    open_settings()
    capture("settings")
    tap(label="Wake word and listening")
    wait_for(lambda root: has_id(root, "etWakeWord"), "Wake settings editor did not open")
    assert not has_id(hierarchy(), "btnThemes"), "Wake settings still links to a sibling appearance hub"
    capture("voice-settings")
    tap(identifier="btnCloseVoiceSettings")
    wait_for(lambda root: has_label(root, "Appearance"), "Voice Back did not return to Settings")
    tap(label="AI and weather", scrolling=True)
    wait_for(lambda root: has_text(root, "Gemini API key"), "Shared AI settings did not open")
    capture("ai-settings")
    tap(label="Cancel")
    wait_for(lambda root: has_label(root, "Appearance"), "AI Cancel did not return to Settings")

    tap(label="Learning and memory", scrolling=True)
    wait_for(lambda root: has_label(root, "Intelligence Plan"), "Intelligence menu did not open")
    tap(label="Commands and learning", scrolling=True)
    tap(label="Review learned commands", scrolling=True)
    wait_for(lambda root: has_text(root, "Saved learning"), "Learning review dialog did not open")
    capture("learning-diagnostics")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap(identifier="btnCloseIntelligence")
    wait_for(lambda root: has_label(root, "Intelligence Plan"), "Intelligence page Back did not return to categories")
    tap(label="Intelligence Plan")
    wait_for(lambda root: has_text(root, "estimated scope completion"), "Offline intelligence plan did not render")
    capture("intelligence-plan")
    tap(label="Back")
    wait_for(lambda root: has_label(root, "Commands and learning"), "Plan Back did not return to Intelligence")
    tap(identifier="btnCloseIntelligence")
    wait_for(lambda root: has_label(root, "Appearance"), "Intelligence Back did not return to Settings")
    tap(label="Back to chat", scrolling=True)

    for name in ("Stark", "Cybertron", "Default"):
        open_appearance()
        tap(label="Themes")
        tap(label=name, scrolling=True)
        wait_for(lambda root: any(node.get("content-desc") == name + ". Active theme" for node in root.iter("node")),
                 name + " theme did not become active")
        capture("theme-picker-" + name.lower())
        back_to_chat_from_themes()
        capture("theme-" + name.lower())
        adb("shell", "am", "force-stop", PACKAGE)
        start_main()
        open_appearance()
        tap(label="Themes")
        wait_for(lambda root: any(node.get("content-desc") == name + ". Active theme" for node in root.iter("node")),
                 name + " theme did not survive app restart")
        back_to_chat_from_themes()

    # Exercise app font preferences through their public editor before narrowing the emulator.
    open_appearance()
    tap(label="Fonts")
    root = hierarchy()
    slider = next(node for node in root.iter("node") if node.get("class") == "android.widget.SeekBar" and bounds(node))
    x1, y1, x2, y2 = bounds(slider)
    adb("shell", "input", "tap", str(x2 - 2), str((y1 + y2) // 2))
    wait_for(lambda root: has_text(root, "Font size: 150%"), "App font preference did not reach 150%")
    tap(label="Save fonts", scrolling=True)
    tap(identifier="btnCloseAppearances")
    wait_for(lambda root: has_label(root, "Themes"), "Font Back did not return to Appearance")
    tap(identifier="btnCloseAppearances")
    tap(label="Back to chat", scrolling=True)

    adb("shell", "wm", "size", "720x1280")
    adb("shell", "wm", "density", "360")  # 320 dp viewport.
    adb("shell", "settings", "put", "system", "font_scale", "1.3")
    adb("shell", "am", "force-stop", PACKAGE)
    start_main()
    capture("small-screen-chat")
    tap(identifier="etCommandInput")
    wait_for(lambda root: has_id(root, "btnRunAssistantCommand"), "Send hidden at narrow width with enlarged fonts")
    capture("small-screen-keyboard")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    open_settings()
    capture("small-screen-settings")
    tap(label="Appearance", scrolling=True)
    wait_for(lambda root: has_label(root, "Themes"), "Narrow Appearance menu did not open")
    capture("small-screen-appearance")
    tap(identifier="btnCloseAppearances")
    tap(label="Back to chat", scrolling=True)
    (OUTPUT / "verification.txt").write_text(
        "Passed actual emulator taps, offline local math/plan, Settings/Voice/AI/Intelligence/Appearance Back paths, "
        "Stark/Cybertron/Default restart persistence, and narrow chat/keyboard with 150% app fonts and 1.3 system font scale.\n"
        "Screenshots are review artifacts. This does not establish physical-phone appearance, voice quality, or a passing release build.\n"
    )


def main():
    OUTPUT.mkdir(exist_ok=True)
    try:
        run_smoke()
    except Exception:
        import traceback
        (OUTPUT / "failure.txt").write_text(traceback.format_exc())
        try:
            capture("failure")
        except Exception as error:
            print("Failure screenshot unavailable: " + str(error), flush=True)
        try:
            (OUTPUT / "logcat.txt").write_text(adb("logcat", "-d"))
        except Exception as error:
            print("Logcat unavailable: " + str(error), flush=True)
        raise


if __name__ == "__main__":
    main()
