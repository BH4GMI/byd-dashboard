# BYD-dashboard · Cast any app to the BYD DiLink 5 instrument cluster

[简体中文](README.md) · **English** · [Русский](README.ru.md)

> ## Real-device verification status
>
> **4.3-user-intent has been run end-to-end on a real car (DiLink 5.0)**:
> in-place update → the target app cold-starts onto display 3 → a picture appears on the instrument
> cluster → the position guard holds.
>
> - Before/after on the same car, the same target and the same kind of initial state:
>   `归位失败` ("failed to return to place") dropped from **22 times within 8 seconds to 0**,
>   `投屏完成` ("cast complete") landed on display 3, and the cluster's `screencap -d 2` went from
>   blank the whole time to a normal 256 KB picture.
> - The `apk/dashcast.apk` built from this repository is **byte-for-byte identical** (same SHA-256) to
>   the one verified on the car — the package you download from Releases is exactly that file.
> - Explicitly **not verified**: a clean install (rather than an in-place update), upgrading from
>   `1.0.0`, auto-start on boot, and **any car model or firmware other than this one car**.
> - Please **do not** try it for the first time while driving; for anything that affects the instrument
>   cluster display, first make sure it does not interfere with reading the car's own information.
> - This project is provided "as is", with no warranty of any kind; see the
>   [disclaimer](#disclaimer) at the end.
>
> If you are willing to report your real-car result (success or failure), it is valuable to this project.

Without root, without flashing, without a PC — with nothing but **ADB permission**, the app on the head
unit obtains `uid 2000 (shell)` for itself, and then uses that privilege to cast any app to the
instrument-cluster display, inject touch events into it, and pin it there.

> Intended for a DiLink 5.0 head unit that you own and that has wireless ADB enabled.
> Not affiliated with BYD Auto Industry Company Limited; for interoperability research only.
> The full licence text is in [`LICENSE`](LICENSE).
> Current version **4.3-user-intent** (`versionCode 115`). Version history: [`CHANGELOG.md`](CHANGELOG.md).
>
> **The relationship between `main` and Releases (deliberate, not a missed sync)**
> `main` keeps **the car-verified 4.3-user-intent** as the trusted baseline; versions that have not yet
> been verified on a car are released from `beta/<version>` branches. So the current Latest release is
> `v4.3-user-intent`, matching `main`.
>
> - If you only want a **verified** version: take [`v4.3-user-intent`](https://github.com/BH4GMI/byd-dashboard/releases/tag/v4.3-user-intent) (= `main`).
> - If you want to try the newest unverified version: take [`v5.3`](https://github.com/BH4GMI/byd-dashboard/releases/tag/v5.3) (`versionCode 121`, developed on `beta/5.3`), and read its verification-status note first.
>   (The `5.0` release and tag have been withdrawn; its content was folded into `5.1`.)

---

## The problem it solves

DiLink 5's instrument cluster is a **projection display that ships with the head unit itself** — there
is no need to create one, and doing so would be the wrong approach:

| displayId | Name | Size | Key properties |
| --- | --- | --- | --- |
| 0 | Built-in screen | 1920×1080 | layerStack 0, the only HWC display |
| 2 | `fission_bg_XDJAScreenProjection` | 1920×720 @320 | layerStack 2, `touch NONE`, owner `com.byd.containerservice` (uid 1000) — **this is the instrument cluster** |
| 3 / 4 | `shared_fission_bg_XDJAScreenProjection_0/1` | 1920×720 | layerStack 3/4 — **the cast slots**; `screencap` of them comes out all black |

A normal app that tries to send another app to a secondary display is rejected outright by AMS:

```
SecurityException: Permission Denial: ... with launchDisplayId=2
```

**But `uid 2000` can.** So the whole thing boils down to one real problem:
**getting `uid 2000` for the app itself.**

## Core mechanisms

1. **Local ADB bootstrap** — using a key it generated on first run (or one embedded at build time), the
   app connects to the head unit's own loopback `127.0.0.1:5555`, completes the standard ADB
   wire-protocol handshake, and obtains a `shell,v2,raw:` channel. No PC is involved at any point.
2. **Spawning the uid 2000 privileged process** — the privileged code **lives in the dex of the very
   same APK** and is started by `app_process` with `CLASSPATH=<the installed APK>` and
   `--nice-name=com.byd.dashcast-priv`. No separate agent jar, and no files written to the device.
3. **Casting** — send the target app to **display 3 (the cast slot)**: `am start-activity --display 3`
   when no task exists yet, `am display move-stack <taskId> 3` when one does. The mapping from the slot
   to the instrument cluster is performed by the head unit's own `com.byd.containerservice`; this
   project does not get involved.
4. **Preview** — the main UI carries a `TextureView` that is 1:1 with the instrument cluster. The
   privileged process uses `SurfaceControl` to reuse the cluster's layerStack and wires its composition
   result **directly onto that Surface**: zero frame capture, zero encode/decode, zero CPU readback.
5. **Touch injection** — a transparent layer sharing the same coordinate system covers the preview;
   finger events are injected into the instrument cluster by the privileged process via
   `InputManager.injectInputEvent`. **The cluster display is `touch NONE`, so all input has to be injected.**
6. **Position guard** — when the app itself starts an Activity without a display, AMS moves the entire
   root task back to the default display; that is app-side behaviour which cannot be prevented, only
   undone afterwards. Undoing it is the job of the foreground service `CastGuardService`: once the cast
   is established it checks every 2 seconds, **and keeps checking after the UI is dismissed**; to take
   the cast back, the user taps 「收回投屏」 ("stop casting") on the persistent notification, and the
   service stops once the target has been moved back to the main display. It never expires on its own,
   and it never pins you to the secondary display.

Software structure: [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) (Chinese only).

## Repository layout

```
apk/              head-unit app: src/ res/ AndroidManifest.xml build.ps1
  assets/         optionally holds a fixed ADB private key at build time (contents are not committed)
tools/            dashcast.ps1 — a pure-adb casting/control driver; no app install required
docs/             software structure technical document (for secondary development)
CHANGELOG.md      version history
LICENSE           full text of GNU GPL v3.0
```

## Build

Prerequisites: a JDK (`JAVA_HOME`) and an Android SDK with `build-tools;35.0.0` and `platforms;android-32`.
The SDK location is read in order from `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk`;
`preflight` prints what it resolved and exits with a failure if a dependency is missing.

```powershell
.\apk\build.ps1 -NoInstall
```

The artifact is `apk\dashcast.apk`. The chain is
`preflight → assets → aapt2 compile → aapt2 link → javac → d8 → aapt add → zipalign → apksigner`.

- Drop `-NoInstall` and add `-Serial <ip:port>` to install straight onto the head unit.
- `apk\build.ps1 -BundledKey <some.pk8>` can embed a fixed ADB private key into the APK.
  **You do not have to**: on first run the app generates its own key pair and stores it in its private
  directory; the cost is one "Allow USB debugging?" dialog on the head unit, which you accept.
- If `apk\debug.keystore` is missing, the build script runs `keytool -genkeypair` itself; you do not
  need to supply one.

**To upgrade an existing installation on the head unit, you must use the same signing key**:

```powershell
.\apk\build.ps1 -Keystore <keystore path> -StorePass <store password> -KeyAlias <alias>
```

Without `-Keystore` the script still falls back to `apk\debug.keystore`; `debug.keystore` is only good
for a clean install and can never upgrade a head-unit build signed with a different key. If you pass
`-Keystore` and the file does not exist, the script **fails outright** instead of falling back to a
temporary key — that would produce an APK that cannot be installed.

## Requirements

- Wireless **ADB** is enabled on the head unit. On DiLink 5 that chain is
  `sys.connect.adb.wiress=1` → init runs `setprop service.adb.tcp.port 5555` → adbd restarts.
- The app connects to the head unit's **own loopback** `127.0.0.1:5555`; no PC is involved.

## First run

The launcher entry is **the main UI** (`CastActivity`). If authorisation has not happened yet, it
switches to the authorisation guide screen and the head unit shows 「允许 USB 调试吗」 ("Allow USB
debugging?"). There is one step you have to get right:

- In that dialog you **must tick 「一律允许使用这台计算机进行调试」 ("Always allow debugging from this
  computer") and then tap 「允许」 ("Allow")**: tapping 「允许」 alone does not write the public key into
  `adb_keys`, so the dialog will appear again next time.
- Authorisation through 「一律允许」 ("always allow") has a **quiet period** (framework default
  604800000 ms = 7 days): if no connection succeeds within that window, the framework deletes the key
  from `adb_keys` and asks again. **The current source code does nothing about this** (it neither writes
  `adb_allowed_connection_time` nor performs any other renewal), so after a long idle period you may
  have to authorise once more. This is a known limitation; see section 12.2 of
  [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md).
- Once authorised, it reconnects automatically on every boot with no further action — **provided the
  "auto-start on boot" chain actually works, and that has not yet been verified under clean conditions**
  (again see section 12.1).

## UI

- Everything is laid out in `px` (the head unit is 1920×1080 @240dpi, so 1dp = 1.5px), because the
  physical size of this display is fixed.
- The theme is `Theme.DeviceDefault.NoActionBar` plus a translucent window — translucency is
  **structurally required**: whether a window is translucent is fixed by the manifest theme at window
  creation time, and `setTheme()` inside `onCreate` cannot change it.
- The UI's own root layout is an opaque dark background; only the auto-start-on-boot path, which does
  not call `setContentView`, leaves the whole window fully transparent and therefore invisible to the user.
- The front-end spec is a separate HTML preview (rendered 1:1 at 1920×1080) that is **not part of this
  repository**.

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) | Software structure technical document (Chinese): module breakdown, process and IPC contracts, startup flow, build chain, extension points |
| [`CHANGELOG.md`](CHANGELOG.md) | Version history and upgrade notes |
| [`LICENSE`](LICENSE) | GNU GPL v3.0 |

## Known limitations

- **Writing the ADB identity file is not durable.** `AdbKeyStore.write()` overwrites in place: no
  `fsync`, no temp file plus atomic replace, no read-back verification. After a power loss the file can
  come back as "correct length, all zeros", be judged corrupt, be deleted and recreated, and the user
  sees the authorisation dialog once more. This is a known root cause, not fixed yet.
- **The preview frame-rate ceiling is decided by the app being cast.** Measured while dragging on a
  navigation map, that display itself produced only about 7.3 fps; the preview is a faithful mirror and
  the path imposes no ceiling of its own. With the preview switched off, the same drag produced slightly
  fewer frames, which rules out "the new display is stealing GPU".
- **While the content is static, the pass-through picture "freezes" on the last frame** — this is not a
  fault: SurfaceFlinger does not recompose an unchanged layer stack. So "the picture is not moving"
  cannot be used to judge whether the other side is alive.
- **End-to-end touch latency has not been measured yet.** What is certain is that every event went from
  "fork a process (40–140 ms per event)" to "one oneway Binder call".
- **The position guard's recovery delay has been measured**: after the target task is forcibly moved
  back to the main display (`am display move-stack <id> 0`), the next check (≤2 s) pulls it back into the
  cast slot and leaves a trace in `dashcast.log`. The check interval is 2 s, so the worst-case delay is 2 s.
- **Auto-start on boot (`BootReceiver`) does not run on this car**: the head unit's modified broadcast
  queue skips third-party apps' boot broadcasts
  (`BroadcastQueue: skip reciever for uid … BOOT_COMPLETED … ignored !!!`, verbatim from the device). So
  the ADB channel has to be opened after the app has been launched, and "install it and forget it" only
  holds for **apps the head unit starts by itself** — see the 「已知边界与未确认项」 ("Known limitations
  and unconfirmed items") section of [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md).
- Other limitations are listed in the 「已知边界与未确认项」 ("Known limitations and unconfirmed items")
  section of [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md).

## Open source and free

This project is **open source and free**, released under the **GNU General Public License version 3
(GPL-3.0)**; the full text is in [`LICENSE`](LICENSE).

- Redistributing it, or publishing a modified version, requires meeting the conditions of GPL-3.0:
  keep the copyright and licence notices, **provide the complete corresponding source code**, and
  license it under the same licence (copyleft).

## Disclaimer

- **This software is provided "as is"**, without warranty of any kind, express or implied, including but
  not limited to merchantability, fitness for a particular purpose and non-infringement.
- **The author accepts no liability for any direct, indirect, incidental, special or consequential
  damages arising from installing or using this software**, including but not limited to vehicle damage,
  head-unit malfunction or loss of the factory warranty, data loss, third-party claims, fines or penalty
  points.
- Whether to use it, and how to use it, is for the user to judge and to bear all risk for; please make
  sure your actions comply with local law and with the head unit manufacturer's terms of service.
- Not affiliated with BYD Auto Industry Company Limited.
- The `com.byd.*` names appearing in the code are the head-unit platform's own package names and are
  used for interoperability only.
- Please use this only on a vehicle you own or are authorised to use.
