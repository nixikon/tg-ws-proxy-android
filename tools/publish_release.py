"""Publish an APK as a GitHub release.

The in-app updater reads `releases/latest`, compares its tag with the installed
version and downloads the APK asset, so a release only has to satisfy two things:

  * the tag is the version, e.g. ``v1.11.0-a2``;
  * the asset name ends in ``.apk`` (a name containing ``android`` is preferred
    when several APKs are attached).

Usage:
    set GITHUB_TOKEN=<token with contents:write on the repo>
    python tools/publish_release.py --repo owner/name --tag v1.11.0-a2 \
        --apk dist/TgWsProxy-1.11.0-a2-android.apk [--notes "what changed"]

The release is created when missing and reused otherwise; an existing asset with
the same name is replaced. Requires a token with write access, which is *not* the
same token the app uses (the app only needs read access).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def _request(method: str, url: str, token: str, data=None, content_type=None):
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "tg-ws-proxy-android-release",
    }
    if content_type:
        headers["Content-Type"] = content_type
    body = None
    if data is not None:
        body = data if isinstance(data, bytes) else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        if exc.code == 404:
            return 404, None
        raise SystemExit(f"HTTP {exc.code} on {method} {url}\n{raw[:500]}")


def _token_from_gradle_properties() -> str:
    """Fallback token source: `tgws.publishToken` in android/gradle.properties.

    Deliberately separate from the app's read-only token (`tgws.updateToken`),
    because that one is compiled into the APK and must never have write access.
    """
    path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "android", "gradle.properties",
    )
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                stripped = line.strip()
                if stripped.startswith("tgws.publishToken="):
                    return stripped.split("=", 1)[1].strip()
    except OSError:
        pass
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--tag", required=True, help="e.g. v1.11.0-a2")
    parser.add_argument("--apk", required=True, help="path to the APK to upload")
    parser.add_argument("--notes", default="", help="release notes")
    parser.add_argument("--name", default="", help="asset name (default derived from the tag)")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "").strip() or _token_from_gradle_properties()
    if not token:
        print(
            "No token: set GITHUB_TOKEN or tgws.publishToken in android/gradle.properties",
            file=sys.stderr,
        )
        return 2
    if not os.path.isfile(args.apk):
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2

    asset_name = args.name or f"TgWsProxy-{args.tag.lstrip('vV')}-android.apk"
    repo_url = f"{API}/repos/{args.repo}"

    status, release = _request("GET", f"{repo_url}/releases/tags/{args.tag}", token)
    if status == 404:
        print(f"creating release {args.tag}")
        _, release = _request("POST", f"{repo_url}/releases", token, {
            "tag_name": args.tag,
            "name": args.tag.lstrip("vV"),
            "body": args.notes,
            "draft": False,
            "prerelease": False,
        })
    else:
        print(f"release {args.tag} exists (id={release['id']})")

    release_id = release["id"]

    # Replace an asset of the same name so re-running is safe.
    for asset in release.get("assets", []):
        if asset["name"] == asset_name:
            print(f"deleting existing asset {asset_name}")
            _request("DELETE", f"{API}/repos/{args.repo}/releases/assets/{asset['id']}", token)

    with open(args.apk, "rb") as handle:
        payload = handle.read()
    print(f"uploading {asset_name} ({len(payload) // 1024} KiB)")
    _request(
        "POST",
        f"{UPLOADS}/repos/{args.repo}/releases/{release_id}/assets?name={asset_name}",
        token,
        payload,
        content_type="application/vnd.android.package-archive",
    )

    print(f"done: {API}/repos/{args.repo}/releases/tags/{args.tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
