"""Emulator smoke test for the 1.11.2-a1 build.

Boots the local AVD headless, installs the release APK, starts the proxy the way
the Quick Settings tile does, reads the connect link out of the UI, and drives a
real MTProto handshake through `adb forward` with `tools/mtproto_probe.py`.
Everything is appended to `emulator-smoke-1112.log` next to this file.

Usage:  .py313\\python.exe run_emulator_smoke.py [path-to-apk]
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.dom.minidom

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(ROOT, "emulator-smoke-1112.log")
REPORT = os.path.join(ROOT, "emulator-smoke-1112.json")
DUMP = os.path.join(ROOT, "ui_smoke.xml")
SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
EMU = os.path.join(SDK, "emulator", "emulator.exe")
AVD = "TgWsTest"
PACKAGE = "com.nixikon.tgwsproxy"
ACTIVITY = PACKAGE + "/.MainActivity"
START_PROXY = PACKAGE + ".action.START_PROXY"
PY313 = os.path.join(ROOT, ".py313", "python.exe")
APK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    ROOT, "android", "app", "build", "outputs", "apk", "release", "app-release.apk")

ENV = dict(
    os.environ,
    ANDROID_USER_HOME=os.path.join(ROOT, ".android-emu"),
    ANDROID_AVD_HOME=os.path.join(ROOT, ".android-emu", "avd"),
    ANDROID_EMULATOR_HOME=os.path.join(ROOT, ".android-emu"),
    ANDROID_SDK_ROOT=SDK,
    ANDROID_HOME=SDK,
)

DISMISS = ("Allow", "Don't allow", "Close", "OK", "Later", "Got it",
           "Разрешить", "Запретить", "Закрыть", "ОК", "Позже", "Понятно")
report = {}


def log(message):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
    try:
        print(line, flush=True)
    except Exception:
        pass
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def adb(*args, timeout=180, check=False):
    result = subprocess.run([ADB, *args], capture_output=True, text=True,
                            errors="replace", env=ENV, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError("adb %s failed: %s" % (" ".join(args), result.stderr.strip()))
    return result


def texts():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_smoke.xml", timeout=90)
    adb("pull", "/sdcard/ui_smoke.xml", DUMP, timeout=90)
    doc = xml.dom.minidom.parse(DUMP)
    out = []
    for node in doc.getElementsByTagName("node"):
        value = node.getAttribute("text") or node.getAttribute("content-desc")
        if value:
            out.append((value, node.getAttribute("bounds")))
    return out


def tap(label, index=0):
    hits = [item for item in texts() if item[0] == label]
    if not hits:
        return False
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", hits[min(index, len(hits) - 1)][1] or "")
    if not match:
        return False
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def start_emulator():
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write("=== emulator smoke test %s ===\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    adb("start-server")
    proc = subprocess.Popen(
        [EMU, "-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim",
         "-no-snapshot-save", "-gpu", "swiftshader_indirect"],
        env=ENV, stdout=open(os.path.join(ROOT, "emulator-stdout-1112.log"), "ab"),
        stderr=subprocess.STDOUT)
    log("emulator started pid=%d" % proc.pid)
    deadline = time.monotonic() + 420
    while time.monotonic() < deadline:
        if adb("shell", "getprop", "sys.boot_completed", timeout=60).stdout.strip() == "1":
            log("boot completed")
            return proc
        time.sleep(5)
    raise RuntimeError("emulator did not boot in time")


def find_link(screen):
    return next((value for value, _ in screen if "tg://proxy" in value), "")


def main():
    proc = start_emulator()
    try:
        for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb("shell", "settings", "put", "global", scale, "0")

        adb("uninstall", PACKAGE, timeout=120)
        install = adb("install", "-r", APK, timeout=900)
        log("install rc=%d out=%r err=%r" % (install.returncode, install.stdout.strip(),
                                             install.stderr.strip()))
        report["install"] = {"rc": install.returncode, "out": install.stdout.strip(),
                             "err": install.stderr.strip()}

        version = adb("shell", "dumpsys", "package", PACKAGE).stdout
        found = re.search(r"versionName=(\S+)", version)
        report["version"] = found.group(1) if found else None
        log("package version: %s" % report["version"])

        # Grant the notification permission up front so the runtime prompt does
        # not cover the very screen the link is read from.
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        adb("shell", "am", "start", "-n", ACTIVITY, "-a", START_PROXY)

        screen, link, deadline = [], "", time.monotonic() + 120
        while time.monotonic() < deadline:
            time.sleep(6)
            screen = texts()
            labels = [value for value, _ in screen]
            screen_link = find_link(screen)
            if "Start" in labels:
                # First-run dialog: it carries the link too, but pressing Start
                # is also what starts the proxy.
                log("tapping Start (first-run dialog)")
                tap("Start")
                time.sleep(12)
                continue
            if "Copy link" in labels or "Скопировать ссылку" in labels:
                log("tapping Copy link")
                tap("Copy link") or tap("Скопировать ссылку")
                time.sleep(3)
                screen = texts()
                link = find_link(screen)
                if link:
                    break
            if screen_link and "Copy link" not in labels:
                link = screen_link
                break
            log("screen: %s" % " | ".join(labels)[:500])
            for label in DISMISS:
                if tap(label):
                    log("dismissed dialog via %r" % label)
                    break

        report["main_texts"] = [value for value, _ in screen]
        report["link"] = link
        log("link: %s" % link[:200])

        match = re.search(r"port=(\d+).*?secret=([0-9a-fA-F]+)", link)
        if not match:
            report["probe"] = "no connect link in the UI"
        else:
            port, secret = int(match.group(1)), match.group(2)
            # The link carries the "secure" marker in front of the 16-byte key
            # (`dd...`); the probe needs the bare key.
            if len(secret) == 34 and secret[:2].lower() in ("dd", "ee"):
                secret = secret[2:]
            report["port"], report["secret"] = port, secret
            adb("forward", "tcp:%d" % port, "tcp:%d" % port)
            time.sleep(5)
            attempts = []
            for attempt in range(2):
                probe = subprocess.run(
                    [PY313, os.path.join(ROOT, "tools", "mtproto_probe.py"),
                     "127.0.0.1", str(port), secret, "--dc", "2", "--timeout", "45"],
                    capture_output=True, text=True, errors="replace", cwd=ROOT, timeout=180)
                output = probe.stdout + probe.stderr
                attempts.append(output.strip().splitlines()[-4:])
                log("probe attempt %d: %s" % (attempt + 1, attempts[-1]))
                if "resPQ" in output:
                    break
                time.sleep(5)
            report["probe"] = attempts
            report["probe_ok"] = any("resPQ" in " ".join(chunk) for chunk in attempts)

        # Dismiss whatever dialog is still on screen (the link dialog and the
        # IPv6 warning both end in a button) so the main screen is reachable.
        for _ in range(6):
            labels = [value for value, _ in texts()]
            if "Diagnostics" in labels or "Диагностика" in labels:
                break
            for label in DISMISS:
                if tap(label):
                    log("dismissed dialog via %r" % label)
                    break
            time.sleep(3)

        def capture(button: str, alt: str, key: str):
            if not (tap(button) or tap(alt)):
                log("button not found: %s / %s" % (button, alt))
                return
            time.sleep(9)
            values = [value for value, _ in texts()]
            report[key] = values
            log("%s: %s" % (key, " | ".join(values)[:1800]))
            for label in DISMISS:
                if tap(label):
                    break
            adb("shell", "input", "keyevent", "4")
            time.sleep(3)

        capture("Logs", "Журнал", "app_logs")
        capture("Diagnostics", "Диагностика", "diagnostics")

        adb("shell", "screencap", "-p", "/sdcard/smoke.png")
        shot = os.path.join(ROOT, "smoke-1112-main.png")
        adb("pull", "/sdcard/smoke.png", shot)
        report["screenshot"] = shot
        log("screenshot: %s" % shot)

        logcat = adb("logcat", "-d", "-t", "800").stdout
        report["logcat"] = [line for line in logcat.splitlines()
                            if "FATAL" in line or "tg-mtproto-proxy" in line or "ProxyService" in line][-25:]
        log("logcat tail: %s" % report["logcat"][-8:])
    finally:
        with open(REPORT, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
        try:
            adb("emu", "kill", timeout=60)
        except Exception:
            proc.terminate()
        log("done, report at %s" % REPORT)


if __name__ == "__main__":
    main()
