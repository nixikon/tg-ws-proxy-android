"""Drive the emulator UI through adb.

Tapping by coordinates goes stale as soon as a layout changes, so this addresses
elements by their visible label using the accessibility tree instead.

Usage:
    python tools/android_ui.py tap   "<label>" [index]
    python tools/android_ui.py texts
    python tools/android_ui.py wait  "<label>" [timeout-seconds]
    python tools/android_ui.py shot  <output.png>
    python tools/android_ui.py start
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from xml.dom import minidom

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
ENV = dict(os.environ, ANDROID_USER_HOME=os.path.join(WS, ".android-emu"))
DUMP = os.path.join(WS, ".hosttests", "ui_auto.xml")
PACKAGE = "com.nixikon.tgwsproxy"
ACTIVITY = "com.nixikon.tgwsproxy/.MainActivity"

# The console here is cp1251, which cannot represent characters the UI uses
# (arrows, the donate heart — the latter was removed in 1.11.0-a4). Force UTF-8 so
# dumps are never cut short.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, timeout: int = 90) -> subprocess.CompletedProcess:
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True, env=ENV, timeout=timeout
    )


def dump() -> minidom.Document:
    adb("shell", "uiautomator", "dump", "/sdcard/ui_auto.xml")
    os.makedirs(os.path.dirname(DUMP), exist_ok=True)
    adb("pull", "/sdcard/ui_auto.xml", DUMP)
    return minidom.parse(DUMP)


def _elements(doc: minidom.Document):
    return doc.getElementsByTagName("node")


def texts(doc: minidom.Document) -> list[str]:
    out = []
    for node in _elements(doc):
        value = node.getAttribute("text") or node.getAttribute("content-desc")
        if value:
            out.append(value)
    return out


def find(doc: minidom.Document, label: str, index: int = 0):
    hits = [n for n in _elements(doc) if n.getAttribute("text") == label]
    if not hits:
        return None
    return hits[min(index, len(hits) - 1)]


def _clickable(node):
    current = node
    while current is not None and getattr(current, "nodeType", None) == current.ELEMENT_NODE:
        if current.getAttribute("clickable") == "true":
            return current
        current = current.parentNode
    return node


def _center(node):
    match = _BOUNDS.match(node.getAttribute("bounds") or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_label(label: str, index: int = 0) -> int:
    doc = dump()
    node = find(doc, label, index)
    if node is None:
        print("NOT_FOUND: %s" % label)
        return 1
    point = _center(_clickable(node))
    if point is None:
        print("NO_BOUNDS: %s" % label)
        return 1
    adb("shell", "input", "tap", str(point[0]), str(point[1]))
    print("TAPPED: %s at %d,%d" % (label, point[0], point[1]))
    return 0


def wait_for(label: str, timeout: float = 30.0) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if find(dump(), label) is not None:
            print("FOUND: %s" % label)
            return 0
        time.sleep(1.5)
    print("TIMEOUT: %s" % label)
    return 1


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    command = sys.argv[1]

    if command == "tap":
        return tap_label(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)
    if command == "texts":
        for value in texts(dump()):
            print(value)
        return 0
    if command == "wait":
        return wait_for(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 30.0)
    if command == "shot":
        adb("shell", "screencap", "-p", "/sdcard/ui_shot.png")
        adb("pull", "/sdcard/ui_shot.png", sys.argv[2])
        print("SHOT: %s" % sys.argv[2])
        return 0
    if command == "start":
        adb("shell", "am", "start", "-n", ACTIVITY)
        print("STARTED: %s" % ACTIVITY)
        return 0
    if command == "force-stop":
        adb("shell", "am", "force-stop", PACKAGE)
        print("STOPPED: %s" % PACKAGE)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
