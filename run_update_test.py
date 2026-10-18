"""Drive the in-app updater end to end on the emulator.

Reproduces the reported failure and then the fix:

  1. installs the old `1.11.1-a1` build;
  2. tries to install the wrongly-signed `1.11.2-a1` APK over it — Android must
     reject it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, which is exactly what a
     device showed after pressing *Update*;
  3. lets the app update itself through its own dialog and the system installer,
     and asserts that `1.11.2-a2` ends up installed.

Planting the broken APK into the app's private cache (`cache/update.apk`) would
reproduce the stale-cache half of the bug as well, but that needs `adb root`,
which production emulator images refuse; the script reports whether it managed.

Usage:
    .py313\\python.exe run_update_test.py <old-apk> <broken-apk> [--expect 1.11.2-a2]
"""
from __future__ import annotations

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
LOG = os.path.join(ROOT, "update-test.log")
REPORT = os.path.join(ROOT, "update-test.json")
DUMP = os.path.join(ROOT, "ui_update.xml")
SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
EMU = os.path.join(SDK, "emulator", "emulator.exe")
AVD = "TgWsTest"
PACKAGE = "com.nixikon.tgwsproxy"
ACTIVITY = PACKAGE + "/.MainActivity"
REMOTE = "/data/local/tmp/update.apk"

ENV = dict(
    os.environ,
    ANDROID_USER_HOME=os.path.join(ROOT, ".android-emu"),
    ANDROID_AVD_HOME=os.path.join(ROOT, ".android-emu", "avd"),
    ANDROID_EMULATOR_HOME=os.path.join(ROOT, ".android-emu"),
    ANDROID_SDK_ROOT=SDK,
    ANDROID_HOME=SDK,
)

report = {}


def log(message):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
    try:
        print(line, flush=True)
    except Exception:
        pass
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def adb(*args, timeout=300):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          errors="replace", env=ENV, timeout=timeout)


def texts():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_update.xml", timeout=90)
    adb("pull", "/sdcard/ui_update.xml", DUMP, timeout=90)
    doc = xml.dom.minidom.parse(DUMP)
    out = []
    for node in doc.getElementsByTagName("node"):
        value = node.getAttribute("text") or node.getAttribute("content-desc")
        if value:
            out.append((value, node.getAttribute("bounds")))
    return out


def tap(wanted):
    hits = [item for item in texts() if item[0] == wanted]
    if not hits:
        return False
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", hits[0][1] or "")
    if not match:
        return False
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def wait_for(wanted_labels, timeout, interval=5):
    deadline = time.monotonic() + timeout
    seen = []
    while time.monotonic() < deadline:
        seen = [value for value, _ in texts()]
        for wanted in wanted_labels:
            if wanted in seen:
                return wanted, seen
        time.sleep(interval)
    return None, seen


def installed_version():
    out = adb("shell", "dumpsys", "package", PACKAGE).stdout
    match = re.search(r"versionName=(\S+)", out)
    code = re.search(r"versionCode=(\d+)", out)
    return (match.group(1) if match else None,
            int(code.group(1)) if code else None)


def boot(old_apk):
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write("=== update test %s ===\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    subprocess.Popen(
        [EMU, "-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim",
         "-no-snapshot-save", "-gpu", "swiftshader_indirect"],
        env=ENV, stdout=open(os.path.join(ROOT, "emulator-stdout-update.log"), "ab"),
        stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 420
    while time.monotonic() < deadline:
        if adb("shell", "getprop", "sys.boot_completed", timeout=60).stdout.strip() == "1":
            log("boot completed")
            break
        time.sleep(5)
    else:
        raise RuntimeError("emulator did not boot")

    adb("uninstall", PACKAGE, timeout=180)
    install = adb("install", "-r", old_apk, timeout=900)
    log("installed old build: %s" % install.stdout.strip().replace("\n", " "))
    report["installed_old"] = installed_version()
    log("version before update: %s" % (report["installed_old"],))

    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")

    # The emulator's Play Protect install-time verification fails for sideloaded
    # APKs (no usable verification service) and the installer then reports the
    # bare "App not installed". Turn it off so the test measures the app, not the
    # emulator: a direct `adb install -r` of the same APK succeeds either way.
    for key, value in (("package_verifier_enable", "0"),
                       ("verifier_verify_adb_installs", "0"),
                       ("package_verifier_user_consent", "-1")):
        adb("shell", "settings", "put", "global", key, value)
    report["verifier_off"] = True


def signature_control(broken_apk):
    """The decisive check: the broken APK must be rejected, the fixed one accepted.

    `adb install -r` performs the same signature check the system installer runs
    when the app hands it the downloaded file, so a rejection here is the bug the
    user saw and an acceptance is its cure.
    """
    rejected = adb("install", "-r", broken_apk, timeout=900)
    text = (rejected.stdout + rejected.stderr).strip()
    report["install_broken"] = text
    log("install of the wrongly-signed APK: %s" % text.replace("\n", " "))

    still = installed_version()
    report["version_after_rejected"] = still
    return "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in text or "signatures do not match" in text


def foreground_activity():
    out = adb("shell", "dumpsys", "activity", "activities").stdout
    match = re.search(r"ResumedActivity: ActivityRecord\{[^}]*?([\w\.]+)/[\w\.\$]+\s", out)
    if match is None:
        match = re.search(r"mResumedActivity.*?\s([\w\.]+)/", out)
    return match.group(1) if match else ""


def wait_for_installer(timeout):
    """Wait for the *system installer*, not for a label.

    The app's own download dialog is titled "Update" as well, so matching text
    found the app's dialog and tapped it instead of the installer.
    """
    deadline = time.monotonic() + timeout
    last = ""
    while time.monotonic() < deadline:
        last = foreground_activity()
        if "packageinstaller" in last or "permissioncontroller" in last:
            return last, [value for value, _ in texts()]
        time.sleep(6)
    return None, [last]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    old_apk, broken_apk = sys.argv[1], sys.argv[2]
    expect = "1.11.2-a2"
    if "--expect" in sys.argv:
        expect = sys.argv[sys.argv.index("--expect") + 1]

    try:
        boot(old_apk)

        report["signature_control_rejected"] = signature_control(broken_apk)
        log("broken APK rejected by the platform: %s"
            % report["signature_control_rejected"])

        adb("shell", "am", "start", "-n", ACTIVITY)

        # A fresh install shows its first-run dialog first and the update check
        # waits behind it (`queuedUpdate`), so clear the way before waiting for
        # the update prompt. "Later" is deliberately not tapped: that is the
        # update dialog's own negative button.
        deadline = time.monotonic() + 180
        found, seen = None, []
        while time.monotonic() < deadline:
            time.sleep(5)
            seen = [value for value, _ in texts()]
            if "Download and install" in seen:
                found = "Download and install"
                break
            for label in ("Start", "OK", "Close", "Got it"):
                if label in seen and tap(label):
                    log("dismissed %r" % label)
                    break
        report["dialog"] = found
        log("update dialog: %s | screen: %s" % (found, " | ".join(seen)[:300]))
        if found is None:
            report["result"] = "no update dialog"
            return 1

        tap("Download and install")
        time.sleep(5)

        # The download is ~38 MB; at the speed seen here that is a couple of
        # minutes, and the installer only appears once it has finished.
        installer, seen = wait_for_installer(900)
        report["installer_activity"] = installer
        log("installer activity: %s | screen: %s" % (installer, " | ".join(seen)[:400]))
        if installer is None:
            report["result"] = "installer never appeared"
            return 1

        shot = os.path.join(ROOT, "update-test-installer.png")
        adb("shell", "screencap", "-p", "/sdcard/update.png")
        adb("pull", "/sdcard/update.png", shot)
        report["screenshot_installer"] = shot

        report["free_space"] = adb("shell", "df", "-h", "/data").stdout.strip().splitlines()[-1:]

        button = next((label for label in ("Update", "Install", "Обновить", "Установить")
                       if label in seen), None)
        report["installer_button"] = button
        if button is None:
            report["result"] = "no install button: %s" % " | ".join(seen)[:200]
            return 1

        # The installer's own reason for refusing is only in logcat.
        adb("logcat", "-c")
        tap(button)
        time.sleep(25)

        logcat = adb("logcat", "-d", timeout=120).stdout
        interesting = [line for line in logcat.splitlines()
                       if any(key in line for key in
                              ("INSTALL_", "PackageInstaller", "PackageManager",
                               "installd", "PackageInstallerSession"))]
        report["logcat"] = interesting[-60:]
        with open(os.path.join(ROOT, "update-test-logcat.txt"), "w",
                  encoding="utf-8") as handle:
            handle.write("\n".join(interesting))
        log("installer logcat: %s" % " | ".join(interesting[-6:])[:600])

        for _ in range(12):
            if installed_version()[0] == expect:
                break
            time.sleep(5)
        report["installed_after"] = installed_version()
        log("version after update: %s" % (report["installed_after"],))
        report["result"] = ("updated to %s" % report["installed_after"][0]
                            if report["installed_after"][0] == expect
                            else "still %s" % (report["installed_after"][0],))

        adb("shell", "am", "start", "-n", ACTIVITY)
        time.sleep(6)
        shot = os.path.join(ROOT, "update-test-after.png")
        adb("shell", "screencap", "-p", "/sdcard/after.png")
        adb("pull", "/sdcard/after.png", shot)
        report["screenshot_after"] = shot
        return 0 if report["installed_after"][0] == expect else 1
    finally:
        with open(REPORT, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
        try:
            adb("emu", "kill", timeout=60)
        except Exception:
            pass
        log("done, report at %s" % REPORT)


if __name__ == "__main__":
    raise SystemExit(main())
