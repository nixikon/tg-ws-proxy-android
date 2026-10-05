# TG WS Proxy for Android — fork

An Android APK fork of [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy),
the local MTProto proxy that relays Telegram traffic over WebSocket. **This is a
fork:** the original application and its proxy engine were written by Flowseal;
what is added here is the Android front end. The fork's package is
`com.nixikon.tgwsproxy` and the link to the original is on the *About* screen.

The desktop app is a Python/tkinter tray application. This fork keeps its proxy
engine **byte-identical to upstream** and replaces only the desktop shell
(customtkinter + pystray + psutil) with a native Android front end: a Kotlin UI
and a foreground service, with the original Python running on the embedded
CPython interpreter via [Chaquopy](https://chaquo.com/chaquopy/).

## Deliverable

Ready-made APKs live in [Releases](../../releases/latest): the latest is
`TgWsProxy-1.11.0-a9-android.apk` (a fork release build, ~38 MB, ABIs
`arm64-v8a` + `x86_64`, package `com.nixikon.tgwsproxy`), with `a8` and older
builds kept beside it for rollback. Building from source is described below.

The APK is signed with the standard Android **debug** key so it can be
sideloaded directly (`adb install` or tapping the file). For a Play Store
release, replace the signing config in
[app/build.gradle.kts](android/app/build.gradle.kts) with your own keystore.

**Using it:** install → open → *Start* → *Open in Telegram* (or *Copy link* and
send it to yourself in Telegram, then tap it). The proxy listens on
`127.0.0.1:1443` by default and must keep running in the background, which the
foreground service handles.

## Architecture

```
┌──────────────────────────── Android app process ────────────────────────────┐
│  MainActivity / SettingsActivity / LogsActivity   (Kotlin, programmatic UI) │
│         │  Jobs.run(...) — never blocks the UI thread                       │
│  PythonBridge  ──JSON──►  android_entry.py  (Chaquopy bridge)                │
│                                   │                                         │
│  ProxyService (foreground) ───────┤ asyncio loop on its own thread          │
│  BootReceiver (autostart)         ▼                                         │
│                            proxy/*  — the upstream proxy core, unmodified    │
│                            (AES-CTR via javax.crypto on Android)            │
└─────────────────────────────────────────────────────────────────────────────┘
```

* **`android_entry.py`** is the only new Python module in the bridge layer. It
  exposes JSON-in/JSON-out functions (`init`, `configure`, `start`, `stop`,
  `restart`, `state`, `stats_summary`, `proxy_link`, `test_cfproxy`,
  `test_cfworker`, `selftest`) so nothing but primitives crosses the Chaquopy
  boundary.
* **AES-CTR** is provided by `javax.crypto.Cipher` through the Java bridge —
  native speed and no pip dependency. The backend is validated at import time
  against the NIST SP 800-38A F.5.1 known-answer test, including the small
  incremental `update()` calls the relay actually feeds it; `cryptography` and
  a `ctypes`/libcrypto path remain as fallbacks for desktop/Docker and routers.
* **TLS trust** uses a CA bundle shipped in the APK (extracted to `filesDir` by
  the Kotlin side and registered before any SSL context is built), falling back
  to Android's `/system/etc/security/cacerts` and then to platform defaults.
* **No pip packages are required at runtime** — only the Python standard
  library — so the build works fully offline once the Gradle cache is warm.

## Feature parity

Everything the desktop tray app does has an Android equivalent:

| Desktop feature | Android implementation |
| --- | --- |
| Start / stop / restart proxy | `ProxyService` foreground service; main-screen buttons and notification actions |
| Tray icon menu | Ongoing notification with *Open in Telegram* and *Restart*; everything else lives on the main screen |
| `tg://proxy` link (dd, or ee with Fake TLS) | `proxy_link()` — same logic as `_run` in the core; *Copy link* / *Open in Telegram* |
| Host, port, secret + regenerate | Settings → MTProto Connection |
| DC → IP mapping | Settings → Telegram Data Centers |
| CF proxy: enable, custom domains, connectivity test | Settings → Cloudflare Proxy (auto and per-domain test modes) |
| HTTP/2 media multiplexing (1.11.0) | Settings → Cloudflare Proxy → "Media multiplexing (HTTP/2)" |
| CF Worker: domains, connectivity test | Settings → Cloudflare Worker |
| verbose, no_secure, buf_kb, pool_size, log_max_mb | Settings → Logs & Performance |
| Update check | **Repointed** — instead of upstream desktop binaries it checks this fork's own GitHub releases and installs the new APK in place (revisions 3 and 6) |
| Appearance: auto / light / dark | Settings → Interface (`AppCompatDelegate` night mode) |
| Language: Русский / English | Settings → Interface, per-app locales, full RU/EN string catalogs |
| Start on system boot | Settings → Startup → `BOOT_COMPLETED` receiver |
| First-run instructions window | First-run dialog with the same instructions and "open now" switch |
| IPv6 warning (shown once) | Shown once when a non-loopback IPv6 address exists |
| Bind-failure diagnostics (port busy / permission / bad address) | Classified in Python (`utils/diagnostics.py`), localised in Kotlin |
| Open logs | In-app log viewer (tail of the rotating log) with *Refresh* / *Share* |
| Statistics line | Live stats on the main screen and in the notification |
| Single instance | `singleTop` activity + a single service instance |
| Donate / docs links | Buttons on the main and settings screens, language-aware doc URLs |
| Quick Settings tile | `ProxyTileService`: start/stop without opening the app, localised state and subtitle; if the system refuses a background start, the tile opens the app and the app starts the proxy right away |
| Battery optimization exemption | Settings → Startup: system dialog `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

`fake_tls_domain` and `force_test_dc` are additionally exposed in the settings
form. Both already existed in the proxy configuration (the desktop build only
reached `fake_tls_domain` through the CLI), so this is an addition rather than a
reduction.

## Changes to the Python core

**Twelve of the fourteen files in `proxy/` are byte-identical to upstream
1.11.0** (it was nine of eleven on 1.10.4; three modules were added in 1.11.0).

| File | Status | Why |
| --- | --- | --- |
| `proxy/tg_ws_proxy.py` | identical | — |
| `proxy/bridge.py` | identical | — |
| `proxy/raw_websocket.py` | identical | — |
| `proxy/fake_tls.py` | identical | — |
| `proxy/pool.py` | identical | — |
| `proxy/config.py` | identical | — |
| `proxy/balancer.py` | identical | — |
| `proxy/stats.py` | identical | — |
| `proxy/__init__.py` | identical | — |
| `proxy/cf_h2.py` | identical | new in 1.11.0 |
| `proxy/h2_transport.py` | identical | new in 1.11.0 |
| `proxy/network_debug.py` | identical | new in 1.11.0 |
| `proxy/_aes.py` | modified | Adds the JVM backend and per-backend known-answer validation; public API unchanged |
| `proxy/utils.py` | modified | `certifi` replaced by a configurable CA lookup that also understands Android's trust store (plus the 1.11.0 `ws_domains` change) |

Under `utils/`, `logging_setup.py` is untouched; `default_config.py` and
`diagnostics.py` drop their tkinter/i18n imports (Android localises in Kotlin)
and `update_check.py` was removed with the feature. `app_paths.py` and
`connectivity.py` are new — the latter is a straight port of the desktop
connectivity probes, returning tokens instead of translated strings.

## Android-specific deviations

1. **Updates come from the fork's own repository.** Upstream publishes no Android
   asset, so the desktop update check was removed (revision 3) and replaced by an
   in-app self-update pointing at this fork's GitHub releases (revision 6).
2. **Autostart is best-effort.** Some vendors block background starts from
   `BOOT_COMPLETED`; the failure is swallowed and opening the app always works.
   This is stated in the settings hint.
3. **`--portable`, the single-instance lock file and the tray icon** are
   desktop packaging concerns with no Android meaning; the Android equivalents
   are the app sandbox, `singleTop`, and the notification.

## Building from source

Requirements: JDK 17, Android SDK with platform 36 + build-tools 36, and network
access on first build (Gradle fetches AGP, Kotlin, AndroidX and the Chaquopy
runtime, and Chaquopy downloads its `httpx[http2]` packages from PyPI).

```powershell
$env:JAVA_HOME    = "C:\path\to\jdk17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

cd android
.\gradlew.bat :app:assembleRelease     # ./gradlew on Linux/macOS
# output: app\build\outputs\apk\release\app-release.apk
```

The Gradle wrapper pins the same Gradle version the builds here were made with
(8.14.3). `android/local.properties` is not committed — point `ANDROID_HOME` (or
`sdk.dir`) at your SDK instead.

## Revision 14 — starting from the tile without a bogus error dialog

The symptom, from a tablet: tapping the Quick Settings tile opened the app and put
a "Failed to start proxy" dialog on top of it, reading
`Непредвиденная ошибка: startForegroundService() not allowed due to
mAllowStartForeground false: service com.nixikon.tgwsproxy/.ProxyService`.

| What | Details |
| --- | --- |
| **Cause** | Android 12+ forbids starting a foreground service from the background, and a tile is not an activity, so on some ROMs the system rejects the start (on other devices the same start goes through). The workaround has been in place since revision 4: the tile opens the app, and the app starts the proxy from a visible window. The defect was elsewhere — the system's refusal went into the same error queue the UI reads for real failures, and surfaced as a modal dialog with the raw English exception text, while the proxy was in fact already starting |
| **A system refusal is no longer an app error** | `ProxyService` now tells the background-start restriction (`ForegroundServiceStartNotAllowedException`, plus the message variants vendor ROMs use) apart from real refusals: the first is only logged (`start refused: background start not allowed (…)`), the second is still reported in a dialog. The class is matched by name because it exists only since API 31 while `minSdk` is 24 |
| **An explanation instead of a dialog** | When the tile had to open the app, the app shows one short toast: "The system blocked a background start — turning the proxy on from the app". The tile passes the reason in the intent (`EXTRA_TILE_FALLBACK`), so the toast does not appear when the app was opened for any other reason |
| **Separate text for real refusals** | The `start_rejected` code no longer falls through to "Unexpected error" with raw text: the dialog explains what to do in the UI language (`error_start_rejected` in RU and EN), and the technical line stays in the log |
| **Version** | `1.11.0-a9` (versionCode 15) |

If the tile on your device always opens the app: that is system policy, not an app
failure. Lifting the background restrictions helps — Settings → Battery →
Unrestricted for TG WS Proxy (the app itself has an "Ignore battery optimization"
switch in the Autostart section).

## Revision 13 — the sources are published in the open repository

| What | Details |
| --- | --- |
| **Source publication** | The sources are published in [nixikon/tg-ws-proxy-android](https://github.com/nixikon/tg-ws-proxy-android): the Gradle project (`android/`), the helper scripts (`tools/`), the release texts (`docs/`), the README in two languages and the licence. APK builds, Gradle caches, the upstream clone and `local.properties` are not published — `.gitignore` covers them |
| **Gradle wrapper** | The project now carries a wrapper (`android/gradlew`, `gradlew.bat`, `gradle/wrapper/`, Gradle 8.14.3) so building does not depend on a local Gradle install: `cd android && ./gradlew :app:assembleRelease` |
| **Repository README** | Rewritten for a repository that holds the sources: builds, installation, features, the calls section, project layout, build requirements, how the core differs from upstream, licence |

No changes to the app itself in that revision: the version stayed `1.11.0-a8`.

## Revision 12 — checking the direct path to Telegram

| What | Detail |
| --- | --- |
| **`call path` line in Diagnostics** | Checks whether the phone reaches Telegram **directly, without the proxy** (`149.154.167.51:443` — DC2, `91.105.192.100:443` — DC203/call). That is the question calls depend on: their media does not go through the proxy, so only the direct path matters. TCP only — the media part is UDP |
| **Clearer explanation in *Calls*** | Current Telegram for Android has no "calls through the proxy" switch (the string is gone from the client), so the client never hands a call to the proxy: the proxy neither breaks nor helps calls. A "how to find out which it is" section was added |
| **Version** | `1.11.0-a8` (versionCode 14) |

Observed on the emulator on this machine's network: `call path` reports
`unreachable` for both addresses — direct access to Telegram is closed and only the
proxy works. That is exactly the situation in which calls are impossible without a
VPN.

## Revision 11 — the status-bar icon on "silent" ROMs, and a Calls section

| What | Detail |
| --- | --- |
| **The *Proxy status* channel is recreated** | Importance raised from Silent (LOW) to Default — several ROMs, MIUI among them, use exactly that to decide whether a notification gets a status-bar icon. A channel's importance cannot be changed after creation, so the channel got a new id `proxy_status_v2` and the old `proxy_status` is deleted on first launch. Sound and vibration are off on the new channel, so the notification stays quiet |
| **Hint for a silenced channel** | If the channel is still below Default, the main screen explains that the icon will not show and offers a *Notification settings* button |
| **Diagnostics** | A new line, `notifications: enabled=… channel=… importance=… (status-bar icon expected / will not show)`, tells you straight away whether the icon has a chance |
| **Calls section** | The main screen now carries a short note that calls do not work through an MTProto proxy plus a *Why, and what to do* button with the reason and the options (turn off "use proxy for calls", disable the proxy for the call, or use a VPN) |
| **Scrolling dialogs** | "Calls through the proxy" and "Licence" are no longer clipped at the bottom: the long text sits in a scroller |
| **Version** | `1.11.0-a7` (versionCode 13) |

## Calls and voice chats

**Voice and video calls, and voice chats, do not work through an MTProto proxy.**
That is not a fault of this port: it is how the protocol is built, and the original
project says the same in its FAQ
([issue #389](https://github.com/Flowseal/tg-ws-proxy/issues/389):
"MTProto-proxy архитектурно не поддерживает голосовые звонки и войс-чаты").

The reason is that the proxy carries MTProto over TCP and nothing else. A call's
audio and video are a separate stream over UDP (WebRTC) — flowing directly between
the clients or through Telegram relays — and that stream never reaches the proxy.
On top of that, **current Telegram for Android has no "calls through the proxy"
switch at all** (the `useProxyForCalls` string survives only in old translations and
is unused in the code), so the client never hands a call to the proxy: the proxy
does not break calls, and it cannot help them either.

What helps:

* disable the proxy in Telegram and call again — if the call goes through, it is
  the proxy connection; if not, the network is blocking calls;
* turn off "Use proxy for calls" in Telegram's proxy settings, if the option is
  there;
* or disable the proxy for the duration of the call (Telegram → Settings → Data
  and Storage → Proxy), then switch it back on;
* if calls fail without the proxy too, a VPN is required: it carries UDP, an
  MTProto proxy does not.

Whether the direct path to Telegram is open can be checked with the `call path`
line in the app's Diagnostics: it opens a TCP connection to `149.154.167.51:443`
(DC2) and `91.105.192.100:443` (DC203/call) bypassing the proxy. Call media is UDP
and is not covered by that probe, but if even TCP is unreachable the direct path is
closed entirely and calls are impossible without a VPN.

Messages, files, video and voice messages keep working: they go over TCP through
the media DC. The same explanation ships inside the app, in the *Calls* section on
the main screen.

## Revision 10 — the "T" in the status bar, and button labels that wrap properly

| What | Detail |
| --- | --- |
| **"T" next to the clock** | The notification icon was redrawn: a "T" of two round-capped strokes instead of a hard rectangle. While the proxy runs it sits in the status bar — including with the app in the background — so the proxy being alive is visible at a glance. It goes away with the notification when the proxy stops |
| **Hint when notifications are off** | If notifications for the app are blocked, or the *Proxy status* channel is silenced, the icon cannot appear at all. The main screen then shows "Notifications for the app are off…" with a *Notification settings* button that opens the system screen for the app |
| **Button label wrapping** | Button side padding was trimmed (16 → 8 dp) and the *About* dialog lost its wide side margins; the "Original project" and "Fork on GitHub" labels carry an explicit line break. On a 360 dp screen the label is no longer broken mid-word ("Оригинальны / й проект") but wraps at the space. Checked at 360 dp and 411 dp |
| **Version** | `1.11.0-a6` (versionCode 12) |

## Revision 9 — updates reported at launch, plus a notification for them

| What | Detail |
| --- | --- |
| **No delay before the check** | The artificial four-second pause is gone: the check starts together with the first screen. The log line about the new version appears one second after launch |
| **Result cache** | A release that was found (version, notes, asset URL, size, SHA-256) is kept in `SharedPreferences`. The next launch shows the dialog instantly, before the network answers; if the network confirms the running build is current, the cache is dropped |
| **Notification about a new version** | When the proxy is started from the Quick Settings tile or by autostart there is no activity on screen, so the release is now announced by a notification on a separate *Updates* channel (importance DEFAULT): title "Version X is available", text "You have Y", and a **Download** button that goes straight to the download and install. Tapping the notification itself opens the app with the dialog. Each version is announced once |
| **Fork link** | The *About* dialog now has four buttons — *Original project*, *Fork on GitHub*, *Licence* and *Close* — with the text in a scroller. The fork address comes from `tgws.updateRepo` instead of a second hard-coded constant |
| **Dialogs no longer stack** | The update is shown after the first-run and IPv6 prompts are dismissed: it is queued rather than raised on top of them |
| **Version** | `1.11.0-a5` (versionCode 11) |

New and changed files: `UpdateNotifier.kt` (background check and notification),
`Notifications.kt` (the updates channel and the notification action),
`UpdateChecker.kt` (cache), `MainActivity.kt` (dialog queue, notification
actions), `ProxyService.kt` (check on service start), `SettingsActivity.kt`
(rewritten *About* dialog).

## Revision 8 — donate button removed

| What | Detail |
| --- | --- |
| **"Donate ♥" button** | Removed from Settings: the fork collects no donations, and the link pointed at the original project's funding page. The `button_donate` strings in both catalogs and the `TgLinks.fundingUrl` helper went with it |
| **What remains** | In the same place, the *About* button with attribution to the original author and the licence text |
| **Version** | `1.11.0-a4` (versionCode 10) |

The in-app update check goes from `1.11.0-a3` to `1.11.0-a4` on this build, which is
exactly what an `a3` user sees.

## Revision 7 — fork identity and attribution to the original author

| What | Detail |
| --- | --- |
| **Own identity** | `applicationId` and namespace renamed to `com.nixikon.tgwsproxy`; all 13 Kotlin files and the intent action strings renamed with them |
| **Attribution in the app** | An *About* button in Settings: name and version, an explicit statement that this is a fork of tg-ws-proxy by Flowseal, links to the original and to the fork, and a *License* button with the full MIT text and both copyrights |
| **Attribution in the repository** | The public repository's README names the original author, and a LICENSE file carrying both copyrights was added |
| **Version** | `1.11.0-a3` (versionCode 9) |

Changing `applicationId` makes this a new application as far as the device is
concerned: the old build will not be updated in place but installed alongside it
and has to be removed by hand.

## Revision 6 — notification and in-app updates

| What | Detail |
| --- | --- |
| **Notification** | Two actions remain: "Open in Telegram" and "Restart". "Copy link" and "Stop" were removed — the proxy can be stopped from the Quick Settings tile or the app |
| **In-app updates** | On launch the app checks the latest release of the configured GitHub repository and, when a newer version exists, offers to download and install the APK. The download goes through the API asset endpoint (which also works if the repository is private), the SHA-256 is verified when GitHub supplies `digest`, and installation goes through `FileProvider` and the system installer |
| **Version scheme** | `1.11.0-a1` → `1.11.0-a2` → … → `1.11.0`. The comparison understands the suffix: `1.11.0-a1 < 1.11.0-a2 < 1.11.0` |
| **Configuring the source** | `tgws.updateRepo` and `tgws.updateToken` in [android/gradle.properties](android/gradle.properties). Empty means the check is disabled. The current source is shown in Diagnostics as `update source` |
| **Publishing a release** | `python tools/publish_release.py --repo owner/name --tag v1.11.0-a2 --apk dist/TgWsProxy-1.11.0-a2-android.apk` |

**About the token.** The release repository is public, so no token is needed at
all — `tgws.updateToken` is empty and the check runs anonymously. If the
repository is ever made private again, the token still has to be a separate
fine-grained one with **read-only access to that repository only**, because it is
compiled into the APK and can be extracted from it. Publishing a release needs a
different token with write access; that one never goes into the APK and is passed
to the script via `GITHUB_TOKEN`.

Keeping the repository public also matters because the anonymous GitHub API allows
only 60 requests per hour per IP; for that case the check falls back to
`releases.atom`, which has no such limit.

## Revision 5 — core updated to 1.11.0, tile flicker fixed

### Update to upstream 1.11.0

The core moved from 1.10.4 to 1.11.0. **Twelve of the fourteen files in `proxy/`
are now byte-identical to upstream** (was nine of eleven); only `_aes.py` (JVM AES
backend) and `utils.py` (CA bundle lookup plus the 1.11.0 `ws_domains` change)
remain adapted for Android.

| New in 1.11.0 | How it landed in the APK |
| --- | --- |
| **HTTP/2 media multiplexing** — three new modules: `cf_h2.py` (948 lines), `h2_transport.py`, `network_debug.py` | Copied verbatim; exposed as a "Media multiplexing (HTTP/2)" switch in the Cloudflare Proxy section, config key `h2` |
| New dependency **`httpx[http2]==0.28.1`** | Added to Chaquopy's `pip` block. Every package (`httpx`, `httpcore`, `h11`, `h2`, `hpack`, `hyperframe`, `anyio`, `sniffio`, `idna`, `certifi`, `typing_extensions`) is pure Python, so no NDK is needed |
| WebSocket pool semantics changed (`0` disables the direct DC→IP route) | Came with the core; the settings hint was updated |
| `ws_domains`: the `kws{dc}-1` host was dropped for non-media | Merged into our `utils.py` |
| New statistics counters (`h2`, `h2_tcp`, `h2_req`, `h2_err`) | An "HTTP/2" line was added to the main screen |

This is the fork's **first non-stdlib dependency**, and it changes the build
requirements: the first build now reaches the pip repository (a warm Gradle cache
used to be enough). It does not affect runtime — the packages are packed inside
the APK (`requirements-*.imy`), so the app needs no network for them.

The **Diagnostics** screen gained an `http2_stack` line so it is visible that
`httpx` and `h2` really are available on the device.

### Tile flicker on shutdown, fixed

Symptom: on shutdown the tile showed "off", then flipped back to "on" for a
moment, and only then turned off for good.

Cause: the tile's poll applied the **actual** state, while
`ProxyService.active` stays `true` until Python finishes (up to a few seconds).
After 1.2 s the poll put the tile back to "on".

Fix: while a toggle is settling the tile shows **the state the user asked for**
(with a "Starting…" / "Stopping…" subtitle) and switches to the actual state only
once they agree or the timeout expires. The transition state lives in the
companion object, so the system recreating the tile does not lose it.

Verified by sampling the tile every 1.4 s for 13 s after tapping "off": the
sequence is monotonic and never returns to "on".

## Revision 4 — Quick Settings tile and battery optimization

| Added | Detail |
| --- | --- |
| **Quick Settings tile** | `ProxyTileService` starts and stops the proxy without opening the app. State comes from `ProxyService.active`, the subtitle is localised ("Running" / "Stopped"), and it is declared as a `TOGGLEABLE_TILE` so the system renders it as a switch. If a background service start is refused, the tile opens the app instead |
| **Battery optimization exemption** | A switch in Settings → Startup. Turning it on opens the system `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog; turning it off opens the system list, because the exemption cannot be revoked programmatically. The state is read back from the system on resume rather than stored by us |
| Whole switch row is tappable | Only the thumb used to react, which is fiddly on a phone. Applies to every switch in the settings form |

One more fix found while testing: the tile subtitle stuck at "Starting…" because the system does not re-read a tile while the Quick Settings panel is open. The tile now polls the real state for a few seconds after a tap.

**Fix after testing on a real device.** The tile did not start the proxy. The system may refuse to start a foreground service from a tile — a tile is not an activity, so the app counts as being in the background. The code then opened the app, but the proxy did not start by itself, so the user still had to press Start. Now an app opened by the tile starts the proxy immediately (action `ACTION_START_PROXY`), and if the service has still not come up when the poll expires, the tile opens the app itself. Either way the proxy ends up running without pressing anything. Both paths were verified: the silent one (`accepted=true`, app stays in the background) and the fallback one.

## Revision 3 — layout, scope and further fixes

Requested changes plus everything found while running the app on an emulator:

| Problem | Fix |
| --- | --- |
| Button labels wrapped raggedly across three-per-row grids | Buttons now sit on a fixed-height (56 dp) grid, two per row, with centred text, 13 sp labels and MaterialButton's default insets removed — the insets were what squeezed the labels |
| The settings form ran to ~7700 px, so **Save** was far off-screen | Save/Cancel moved into a fixed footer outside the scroll area — exactly the desktop's `tray_settings_scroll_and_footer` arrangement |
| **Update check removed** | It compared against the upstream *desktop* releases, which say nothing about this Android fork and would have offered a Windows/Linux binary as an "update". The Settings section, the main-screen banner, the config key, the Kotlin/Python API and `utils/update_check.py` are all gone |
| Content drew underneath the status bar | targetSdk 35+ enforces edge-to-edge; every screen root now sets `fitsSystemWindows`, and the status/navigation bar icon colour is declared per light/dark theme |
| **Config was never persisted** — every `ConfigStore.load()` generated a fresh random secret, so the link shown in the UI did not match the secret the proxy used, and the secret changed on every restart | `ConfigStore.load()` now writes the defaults on first run, so there is exactly one config |
| `proxy_link()` reported the secret Python generated at import (the proxy was not configured yet), so the first-run dialog showed a link that could never work | The saved config is passed in and applied while the proxy is stopped |
| First-run dialog claimed "The proxy is running" while it was stopped | Neutral title, plus a line telling the user to press Start first |
| The CF Worker **Test** button stayed disabled after typing domains | A text watcher re-evaluates it (the desktop used a variable trace for the same reason) |
| Raw `stats.summary()` one-liner was hard to read on a phone | Rendered as five labelled lines — every upstream field is still shown, and the raw line remains in the log |
| Donate button cluttered the main screen | Moved to Settings, matching its desktop placement |

## Revision 2 — fixes after the first on-device test

The first build launched but pressing **Start** appeared to do nothing. Root
cause and fixes:

| Problem | Fix |
| --- | --- |
| `Python.start()` was called lazily from a worker thread. Chaquopy wants it once per process from `Application.onCreate()`; when it failed there, the exception was discarded and every later Python call failed too | `App` now extends Chaquopy's `PyApplication`, which starts the runtime on the main thread in `onCreate`. `PythonBridge.startRuntime()` keeps a main-thread-hop fallback, and a failure there is caught so the app cannot die at launch |
| `MainActivity.refreshState()` returned early when the state query failed, so the error dialog was never reached — a broken runtime looked like a frozen UI | Errors are now reported regardless of whether the state query worked, deduplicated per message, and appended to `proxy.log` (`AppLog`) so they also appear in the Logs screen |
| Start failures inside `ProxyService` were only visible through the state poll, which is exactly what breaks when Python is down | Failures are recorded in the service, written to the log, and consumed by the UI on its next poll; `startForeground` and `startForegroundService` failures are caught instead of crashing the process |
| The AES fallback used `Class.forName`, which returns a `java.lang.Class` *object* that cannot be called as a constructor | Uses the documented `java.jclass("javax.crypto.Cipher")` instead |
| No way to diagnose a failure on a device without a debugger | New **Diagnostics** button: runs `android_entry.selftest()` and shows Python version, OpenSSL, the active AES backend, Java bridge reachability, CA bundle, core import, asyncio bind and app-dir writability — each line pass/fail, with a Copy button |

`versionCode` is now `2` / `versionName` `1.10.4-a2`, and the diagnostics dialog
shows the installed build label so builds can be told apart.

## Verification

Four independent layers, all green:

| Layer | Command | Result |
| --- | --- | --- |
| Upstream test suite (CPython 3.12) | `python -m pytest tests --ignore=tests/test_update_check.py` | 47 passed, 4 subtests passed |
| Upstream test suite (CPython **3.13.9**, the version Android embeds) | `.py313\python.exe -m pytest tests --ignore=tests/test_update_check.py` | 47 passed, 4 subtests passed |
| End-to-end proxy (real Telegram round trip, host) | `python tools/e2e_proxy_test.py <dir>` | obfuscated2 handshake accepted, WebSocket bridge established, genuine `resPQ` reply received |
| APK integrity | `python tools/verify_apk.py <apk> android/app/src/main/python` | 22 Python files byte-identical to source, CA store intact, 8 native libs per ABI, RU/EN strings present, `requirements-*.imy` with `httpx`/`h2` inside |
| **Emulator: app drives real traffic** | `python tools/mtproto_probe.py 127.0.0.1 <port> <secret>` after `adb forward` | `resPQ` returned by the app running on the emulator |
| Emulator: UI flows | `python tools/android_ui.py tap/texts/wait/shot` | every screen and action listed above |

`tests/test_update_check.py` is excluded on purpose: it imports
`utils.update_check`, which revision 3 removed along with the feature. It is the
only test that fails, and it fails with exactly
`ModuleNotFoundError: No module named 'utils.update_check'` — the remaining 47
tests pass unchanged.

The end-to-end test drives the same `android_entry` entry point the app uses and
acts as a Telegram client: it builds a real obfuscated2 handshake, sends a
well-formed `req_pq_multi` through the proxy, and asserts a decrypted MTProto
`resPQ` comes back — exercising the handshake crypto, the relay re-encryption,
`MsgSplitter` and the WebSocket client together.

Java's `AES/CTR/NoPadding` was verified separately against the NIST vector,
including 1-byte incremental updates, confirming it matches OpenSSL's counter
arithmetic exactly.

One build detail: importing the `proxy` package from
`android/app/src/main/python` with the host Python drops a `__pycache__`
directory next to the sources. No bytecode reaches the APK (`pyc { src = false }`),
but the empty directory is carried into `app.imy`, and `verify_apk.py` rightly
complains about it — delete the directory before building.

### Verified on an Android emulator (Android 15, x86_64)

The emulator was initially unusable here: it reported
`Unable to open AEHD device: ERROR_ACCESS_DENIED`, which looked like missing
hardware acceleration. It was actually this session's file sandbox blocking the
hypervisor device — running the emulator with full sandbox access gives
`AEHD (version 2.2) is installed and usable`. The whole app was then driven
through `adb` and checked on screen:

| Checked | Result |
| --- | --- |
| Install, launch, first-run dialog, IPv6 warning | renders, dialogs appear once, then never again |
| Notification permission and foreground service | ongoing notification with 2 actions, `FOREGROUND_SERVICE` flag set |
| Start / Stop / Restart | status and button flip correctly, listener comes up and goes down |
| **Real Telegram traffic through the app** | obfuscated2 handshake accepted and a genuine `resPQ` returned — on two different ports |
| Diagnostics | all green: `python 3.13.9`, `ssl OpenSSL 3.0.18`, **`aes_ctr backend=jvm`**, `java_bridge javax.crypto reachable`, CA bundle, asyncio bind, app dir |
| Logs screen | shows the live rotating log, with Refresh/Share |
| Copy link / Open in Telegram | link matches the configured secret; the no-Telegram fallback dialog works as on the desktop |
| Settings save + restart | port changed 1443 → 1455, saved, proxy restarted on the new port, traffic verified there |
| Secret persistence | unchanged across `force-stop` + relaunch (this was the bug in revision 2) |
| CF proxy connectivity test | "CF Proxy: available — ✓ 6 of 6 servers reachable" |
| Russian UI | full interface switches to RU, including the formatted statistics |
| **1.11.0 core** | `✓ proxy_core: core 1.11.0` in Diagnostics, `CF H2 media: enabled` in the log, and the running proxy returned `resPQ` |
| **HTTP/2 dependency on device** | `✓ http2_stack: httpx 0.28.1, h2 4.4.1` — the new dependency works on Android |
| **Tile without flicker** | sampled every 1.4 s for 13 s after tapping "off": "Running" → "Stopped" and it stays, with no return to "on" |
| **Quick Settings tile** | "Stopped" → tap → "Running" with the app staying in the background (`accepted=true`); tapping again → "Stopped" with `Proxy stopped`; a proxy started from the tile returned `resPQ` |
| **Tile fallback path** | a force-stopped app opened with `ACTION_START_PROXY` logged `auto-start requested from the tile` and `Listening on 127.0.0.1:1455`, with no button press |
| **`a9`: starting from the tile, hint instead of an error dialog** | a force-stopped app opened with `ACTION_START_PROXY` + `EXTRA_TILE_FALLBACK` (what the tile does after the system refuses a background start): the toast "The system blocked a background start — turning the proxy on from the app" was on screen, the log showed `auto-start requested from the tile` and `Listening on 127.0.0.1:1443`, the status read "Running" with the "T" icon, and there was no dialog with `Непредвиденная ошибка: startForegroundService() not allowed due to mAllowStartForeground false…` |
| **Battery exemption** | system dialog appeared; after Allow the package is in the Doze whitelist (`dumpsys deviceidle whitelist`) and the switch showed as on |
| **In-app update `a3` → `a4`** | on the emulator with `a3` installed: `version 1.11.0-a4 is available` in the log 5 s after launch, the dialog showed both versions and the release notes; *Download and install* → permission prompt → the system *Install unknown apps* screen → download of `cache/update.apk` at exactly 39 967 379 bytes (= the asset size) → system installer *Do you want to update this app?* → install → `versionName=1.11.0-a4`, `versionCode=10` |
| **The `a4` build after updating** | Settings, scrolled to the bottom: the *About* button is there, *Donate ♥* is gone; *Start* brings the listener up on `127.0.0.1:1443`, and a real request through the proxy returned `resPQ` |
| **Re-checking on `a4`** | no update is offered any more: `1.11.0-a4` is not newer than itself |
| **Update reported right after launch** | launched at 19:30:41, and at 19:30:42 the log already said `version 1.11.0-a6 is available` — one second instead of the previous five |
| **Dialog queue** | on a clean install the first-run dialog, then the IPv6 warning, then the update dialog appeared in turn; nothing was raised on top of anything else |
| **Notification when started from the tile** | app in the background, tile tapped in the Quick Settings panel: the service came up in the background, the log said `background check: version 1.11.0-a6 is available`, and `dumpsys notification` showed `id=1002` on channel `proxy_updates` (importance DEFAULT) with title `Version 1.11.0-a6 is available`, text `You have 1.11.0-a5. Tap Download to update the app.` and a single `"Download"` action; the version was recorded as announced (`notified_version` in `shared_prefs/tgws_update.xml`) |
| **Download button on the notification** | `am start -a ...DOWNLOAD_UPDATE`: no "version available" dialog, straight to the install-permission prompt, the notification was cancelled, and after granting the permission the download started (`cache/update.apk`, `Downloading… 37%` on screen) |
| **Fork link** | Settings → *About*: four buttons — *Original project*, *Fork on GitHub*, *Licence*, *Close* |
| **Re-checking on `a5`** | after the test release was deleted no update is offered and the cache is cleared (`shared_prefs/tgws_update.xml` is an empty `<map/>`) |
| **"T" in the status bar** | with the proxy running, the screenshot shows the "T" next to the clock (the foreground service notification icon); with the proxy stopped the icon is gone |
| **Notifications-off hint** | after `pm revoke POST_NOTIFICATIONS` and declining the prompt, the main screen showed the "notifications are off" line and the *Notification settings* button; after granting the permission the line disappeared and notification `id=1001` came back |
| **Wrapping in *About* at 360 dp** | screen narrowed to 360 dp (`wm size 1080x2340`, `wm density 480`): Russian shows "Оригинальный / проект" and "Форк на / GitHub", English shows "Original / project" and "Fork on / GitHub", with nothing broken mid-word |
| **Notification channel after `a6` → `a7`** | notification `id=1001` arrives on channel `proxy_status_v2` with `importance=3` and `mSound=null`; the old `proxy_status` channel is marked `mDeleted=true` |
| **"T" icon and notification state** | with the proxy running the screenshot shows the "T" next to the clock; Diagnostics reports `notifications: enabled=true channel=proxy_status_v2 importance=3 (status-bar icon expected)` |
| **Calls section** | the main screen shows the note, and *Why, and what to do* opens a scrollable dialog with the reason and the options |

The `resPQ` round trip is the important one: it exercises Chaquopy, the
**JVM AES backend** (the first time that path actually ran), the handshake
crypto, the relay re-encryption, `MsgSplitter`, the WebSocket client and the
foreground service together.

### Not verified

* Autostart on boot — the `BOOT_COMPLETED` path is wired up but was not
  triggered (would need a device reboot; vendor restrictions vary anyway).
* Notification action buttons were not tapped individually.
* Throughput and stability under sustained real traffic.
* Screens were checked at one screen size only.

## Project layout

```
android/                       Gradle project
  app/src/main/
    java/com/nixikon/tgwsproxy/
      App.kt                   Application, theme + per-app language
      MainActivity.kt          status, controls, first-run, diagnostics
      SettingsActivity.kt      the full settings form + connectivity tests
      LogsActivity.kt          log viewer / share
      ProxyService.kt          foreground service owning the proxy lifetime
      ProxyTileService.kt      Quick Settings tile
      BootReceiver.kt          autostart
      PythonBridge.kt          Chaquopy calls + background job runner
      ProxyConfig.kt           config model, JSON I/O, validation
      Notifications.kt         notification channel and actions
      Support.kt               tg:// link, clipboard, localised status text
      UiKit.kt                 programmatic view builders
    python/
      android_entry.py         Kotlin <-> Python bridge
      proxy/                   the upstream proxy core (12/14 files untouched)
      utils/                   app paths, logging, diagnostics, connectivity probes
      certs/cacert.pem         bundled trust store
    res/values, res/values-ru  EN / RU string catalogs
tools/                         build and verification helpers (see below)
docs/                          release notes
LICENSE                        MIT with both copyrights
```

`tools/` holds the scripts used for verification and for provisioning this
sandboxed environment: `e2e_proxy_test.py` and `mtproto_probe.py` (real MTProto
clients), `android_ui.py` (drive the emulator UI by label), `verify_apk.py`,
`fetch_wheels.py`, `fetch_python313.py`.

## Port or fork

This is now a **fork**. It started as a port: the engine was moved to another
platform and a new front end was written for it, but the identity stayed someone
else's. It now has every property of a fork:

| Property | How it is implemented |
| --- | --- |
| Its own app identity | `applicationId` and namespace are `com.nixikon.tgwsproxy` (previously the upstream author's namespace) |
| Its own repository with history | [nixikon/tg-ws-proxy-android](https://github.com/nixikon/tg-ws-proxy-android) |
| Its own release line | `v1.11.0-a1` → `v1.11.0-a2` → `v1.11.0-a3`, with the upstream base (1.11.0) pinned in the number |
| Attribution to the original author | Settings → *About* with a link to the original and the full MIT text; a [LICENSE](LICENSE) file carrying both copyrights |
| Licence compliance | The MIT text is embedded in the app: MIT requires the copyright notice to accompany every copy |

The proxy engine itself remains upstream code — 12 of 14 files are byte-identical,
deliberately, so upstream updates can be pulled in almost mechanically.

**Important consequence of the `applicationId` change:** as far as the device is
concerned this is a new application. The old build (`com.flowseal.tgwsproxy`) will
not be updated but installed alongside it, and has to be removed by hand. Settings
and the secret do not carry over either — the proxy has to be added in Telegram
again.

## License

MIT. The original project is © 2026 Flowseal
([tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)); this fork is © 2026
nixikon. The full text lives in [LICENSE](LICENSE) and is embedded in the app
itself (Settings → *About* → *License*).

The bundled root certificate store,
[app/src/main/python/certs/cacert.pem](android/app/src/main/python/certs/cacert.pem),
comes from [certifi](https://github.com/certifi/python-certifi) (MPL 2.0) and is
redistributed with the app.
