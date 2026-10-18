"""Build the APKs with the fork's signing key, then check what came out.

`app/build.gradle.kts` signs both build types with the *debug* signing config, so
Gradle looks the keystore up in the Android user home — and silently creates a
fresh one when that home has none. That is how 1.11.2-a1 was first published
under a different certificate than every earlier release: Android then refuses to
install it over an existing copy (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the
in-app updater looks broken although the download itself succeeds.

This script points the build at the keystore home the fork uses
(`<workspace>/.android-home`), runs Gradle and verifies the result with
`tools/verify_apk.py`, so a wrong key fails here instead of on a user's phone.

Usage:
    python tools/build_apk.py [--workspace DIR] [--no-debug] [--verify-only]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_apk  # noqa: E402  (needs the path above)

RELEASE_APK = os.path.join("android", "app", "build", "outputs", "apk",
                           "release", "app-release.apk")


def _gradle_launcher(workspace: str) -> str | None:
    """Project wrapper first, then whatever distribution is already unpacked."""
    for name in ("gradlew.bat", "gradlew"):
        candidate = os.path.join(workspace, "android", name)
        if os.path.isfile(candidate):
            return candidate
    for home in (os.environ.get("GRADLE_USER_HOME"),
                 os.path.join(workspace, ".gradle-home"),
                 os.path.join(os.path.expanduser("~"), ".gradle")):
        if not home or not os.path.isdir(home):
            continue
        dists = os.path.join(home, "wrapper", "dists")
        if not os.path.isdir(dists):
            continue
        for folder in sorted(os.listdir(dists), reverse=True):
            for root, _dirs, files in os.walk(os.path.join(dists, folder)):
                for name in ("gradle.bat", "gradle"):
                    if name in files:
                        return os.path.join(root, name)
    return None


def _keystore_home(workspace: str) -> str | None:
    """Android user home that actually holds the fork's debug keystore."""
    for home in (os.environ.get("ANDROID_USER_HOME"),
                 os.path.join(workspace, ".android-home"),
                 os.path.join(os.path.expanduser("~"), ".android")):
        if home and os.path.isfile(os.path.join(home, "debug.keystore")):
            return home
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", default=os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--no-debug", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    workspace = os.path.abspath(args.workspace)
    apk = os.path.join(workspace, RELEASE_APK)

    if not args.verify_only:
        gradle = _gradle_launcher(workspace)
        if gradle is None:
            print("No Gradle launcher found (project wrapper or an unpacked "
                  "distribution in GRADLE_USER_HOME)", file=sys.stderr)
            return 2
        keystore_home = _keystore_home(workspace)
        if keystore_home is None:
            print("No debug.keystore found: set ANDROID_USER_HOME to the Android "
                  "user home that holds the fork's signing key", file=sys.stderr)
            return 2

        env = dict(os.environ, ANDROID_USER_HOME=keystore_home)
        home = os.environ.get("GRADLE_USER_HOME") or os.path.join(workspace, ".gradle-home")
        if os.path.isdir(home):
            env["GRADLE_USER_HOME"] = home

        tasks = [":app:assembleRelease"]
        if not args.no_debug:
            tasks.append(":app:assembleDebug")
        print("keystore home: %s" % keystore_home)
        print("gradle:        %s" % gradle)
        result = subprocess.run([gradle, "-p", os.path.join(workspace, "android"), *tasks],
                                env=env)
        if result.returncode != 0:
            return result.returncode

    if not os.path.isfile(apk):
        print("APK not found: %s" % apk, file=sys.stderr)
        return 2

    digest = verify_apk.signer_sha256(apk)
    with open(apk, "rb") as handle:
        file_sha = hashlib.sha256(handle.read()).hexdigest()
    print("\nrelease APK: %s" % apk)
    print("size:        %d bytes" % os.path.getsize(apk))
    print("sha256:      %s" % file_sha)
    print("signer:      %s" % digest)
    if digest is None:
        print("FAIL: the APK carries no v2 signature", file=sys.stderr)
        return 1
    if digest.lower() != verify_apk.EXPECTED_SIGNER:
        print("FAIL: signer is not the fork's release key (%s)."
              % verify_apk.EXPECTED_SIGNER, file=sys.stderr)
        print("      An APK signed with another key cannot be installed over an "
              "existing copy.", file=sys.stderr)
        return 1
    print("Signer matches the fork's release key.")
    print("Now run:  python tools/verify_apk.py %s android/app/src/main/python" % apk)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
