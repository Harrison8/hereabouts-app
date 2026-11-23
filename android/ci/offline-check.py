"""Opens the signed Release app on an emulator with the network off and takes a picture of each screen.

Run by the hand-only signed build in .github/workflows/android.yml, on an emulator that is already running.
Usage: python3 offline-check.py <apk> <picture folder>
It drives the phone with adb: it finds each control in the screen dump (uiautomator) by its text or description.
The pictures are saved even when a check fails, and the exit code says whether every check passed.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
PACKAGE = "garden.hereabouts.app"
os.makedirs(OUT, exist_ok=True)
failures = []


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        print("adb", *args, "->", r.returncode, r.stdout, r.stderr)
    return r.stdout


def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("picture", name)


def dump(name=None):
    """Nodes of the current screen: (text, description, class, clickable, left, top, right, bottom)."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    if name:
        with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8") as f:
            f.write(xml)
    nodes = []
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except ET.ParseError:
        return nodes
    for n in root.iter("node"):
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
        if len(b) == 4:
            nodes.append((n.get("text", ""), n.get("content-desc", ""), n.get("class", ""), n.get("clickable") == "true", *b))
    return nodes


def shows(nodes, pattern):
    return any(re.search(pattern, n[0], re.I) or re.search(pattern, n[1], re.I) for n in nodes)


def tap(node):
    # A short press rather than "input tap": the emulator is slow and a very quick tap can be lost.
    x, y = str((node[4] + node[6]) // 2), str((node[5] + node[7]) // 2)
    adb("shell", "input", "swipe", x, y, x, y, "120")


def find(nodes, pattern, clickable=None):
    for n in nodes:
        if (re.search(pattern, n[0], re.I) or re.search(pattern, n[1], re.I)) and (clickable is None or n[3] == clickable):
            return n
    return None


def save_log():
    """The phone's error log, for finding out why a screen did not open. Written next to the pictures."""
    with open(os.path.join(OUT, "logcat-errors.txt"), "w", encoding="utf-8") as f:
        f.write(adb("logcat", "-d", "-v", "time", "*:E", "AndroidRuntime:E", "DEBUG:E", check=False)[-60000:])


def check(ok, what):
    print(("PASS " if ok else "FAIL ") + what)
    if not ok:
        failures.append(what)


# Install, then turn the network off: airplane mode, wifi and mobile data.
adb("install", "-r", APK)
adb("shell", "svc", "wifi", "disable")
adb("shell", "svc", "data", "disable")
adb("shell", "cmd", "connectivity", "airplane-mode", "enable", check=False)
time.sleep(5)
print(adb("shell", "dumpsys", "connectivity", check=False)[:1500])
adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(8)

# First open: a ZIP code gets past it. The ZIP lookup uses the tables inside the app, not the network.
nodes = dump("01-first-open")
shot("01-first-open")
field = next((n for n in nodes if n[2].endswith("EditText")), None)
check(field is not None, "first open shows the ZIP field")
if field:
    tap(field)
    time.sleep(1)
    adb("shell", "input", "text", "22030")
    time.sleep(2)
    shot("02-zip-typed")
    # Close the keyboard, then tap the "Show N plants" button (its text is on a view that sits inside the clickable one).
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)
    for _ in range(3):
        nodes = dump()
        button = find(nodes, r"^Show \d+ plants$")
        if button is None:
            break
        tap(button)
        time.sleep(6)
        shot("02b-after-show-tap")

# The plant list.
time.sleep(4)
nodes = dump("03-plant-list")
shot("03-plant-list")
check(shows(nodes, r"Search \d+ plants|Native to"), "the plant list opens offline")
check(shows(nodes, r"No signal"), "the plant list shows the offline banner")

# One plant page: the first big tappable card below the search field.
height = max((n[7] for n in nodes), default=640)
width = max((n[6] for n in nodes), default=320)
# A plant card is a button described as "Name, Latin name, Perennial · 1.5 to 2.5 ft".
cards = [n for n in nodes if n[3] and n[2].endswith("Button") and " · " in n[1]]
check(bool(cards), "the list has a plant to tap")
if cards:
    tap(cards[0])
    time.sleep(4)
    nodes = dump("04-plant-page")
    shot("04-plant-page")
    check(shows(nodes, r"^Back$") and shows(nodes, r"^Save "), "a plant page opens offline")

    # A seller link: Where to buy opens the sellers; offline they show the banner.
    # The page is long: scroll down until the Where to buy row shows (the Save button at the top says "Save ...").
    where = None
    for _ in range(8):
        where = next((n for n in nodes if re.search(r"^Where to buy$", n[0]) and n[5] < height * 0.85), None)
        if where:
            break
        adb("shell", "input", "swipe", str(width // 2), str(int(height * 0.8)), str(width // 2), str(int(height * 0.35)), "300")
        time.sleep(2)
        nodes = dump()
    check(where is not None, "the plant page has a Where to buy control")
    if where:
        tap(where)
        time.sleep(3)
        nodes = dump("05-seller-offline")
        shot("05-seller-offline")
        check(shows(nodes, r"No signal"), "the seller link shows the offline banner")
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)

# The Garden tab.
nodes = dump()
tab = find(nodes, r"^Garden$")
check(tab is not None, "the Garden tab is there")
if tab:
    tap(tab)
    time.sleep(3)
nodes = dump("06-garden")
shot("06-garden")
check(shows(nodes, r"My garden|No plants saved yet|Saved plants"), "the Garden tab opens offline")

save_log()
sys.exit(1 if failures else 0)
