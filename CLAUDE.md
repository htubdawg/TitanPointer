# TitanPointer: notes for the next session

Android app that turns the original Unihertz Titan's touch-sensitive keyboard into a touchpad with an
on-screen pointer. Status at v1.2: the user reports the no-root mode is "mostly working".

## Working with this user
- Usually on their phone: always **build the APK and send it** (SendUserFile, `display: attach`).
  Don't ask them to build. Keep `dist/TitanPointer-<version>.apk` current and bump
  `versionCode`/`versionName` in `app/build.gradle` for each APK you send.
- Listen to what they say they observed. They were right that a no-root app can read the keyboard touches.
- Develop on the branch the session names (v1.3–1.5 were on `ccr-40c55928-ihkbz5`). Don't open a PR unless asked.
- Hardware facts live in `docs/titan-hardware.md`. Research the phone yourself (specs, photos) rather than asking the user to measure.

- **Signing**: builds use `signing/titanpointer.keystore` (committed). Never change it, or the user has to
  uninstall to update. v1.2 used a lost key, so going from 1.2 to 1.3 needed an uninstall.

## Facts measured on the user's Titan (Android 10, build QP1A.190711.020)
Input devices as an app sees them:
- `mtk-pad`: **the keyboard touch sensor**, source TOUCHPAD, X 0..1440, Y 0..720, **absolute** positions.
  Only **one finger** has been seen.
- `mtk-tpd`: the touchscreen (X -1..1434, Y 0..1435).
- `aw9523-key`, `mtk-kpd`, `cf-keys`: keys.

How the keyboard touches reach apps:
- As generic MotionEvents (SOURCE_TOUCHPAD) sent **only to the window with input focus**.
  Pointer capture isn't needed; it gets the same events.
- Browsers differ: Firefox turns them into touch events (the user's old "Titan Firefox Sniffer" drew
  with these). Chrome/WebView (including Claude's in-app browser) turns them into wheel events, one
  axis at a time.
- Raw `/dev/input` device names (for root/ADB mode) have never been confirmed on a real device.

## Architecture (`app/src/main/java/com/titanpointer/`)
- `PointerService`: AccessibilityService. Draws the cursor (`CursorView`, TYPE_ACCESSIBILITY_OVERLAY),
  injects taps/long presses/scrolls/drags through `GestureInjector` (dispatchGesture with continued
  strokes), and filters keys.
  - **No-root mode (default, `Prefs.MODE_NOROOT`)**: while pointer mode is on, adds a focusable 1×1
    invisible overlay ("catcher", FLAG_NOT_TOUCHABLE | FLAG_ALT_FOCUSABLE_IM) that gets the touchpad
    MotionEvents and feeds `GestureEngine`. Its keys go to `onPointerModeKey`: click key (default Enter)
    and long-press key (default Alt+Enter) are user-settable; Space = scroll mode; Back is re-sent as
    global Back (the catcher would swallow it); letters do nothing by default (`Prefs.LETTERS` can make them
    leave pointer mode); system/volume/media keys pass through. Any key-down cancels a pending tap.
  - Alt+Space toggles pointer mode by default (`Prefs.DEFAULT_TOGGLE_KEY/META`). It fires on key down.
  - **Root/ADB mode**: `PadLink` talks to the native helper `native/titanpad.c` (static arm64, shipped
    as `jniLibs/arm64-v8a/libtitanpad.so`, runs via `su -c ... stdio` or `serve <port> <token>` over
    ADB). It streams evdev events, optionally with EVIOCGRAB, through `TouchTracker` (MT protocol A/B)
    into `GestureEngine`. This lets you type while the pointer is on, but it's untested on hardware.
- `GestureEngine`: pure Java, unit tested (`app/src/test/.../GestureEngineTest.java`). **Single finger
  only** (the pad can't see more; multi-finger gestures were removed in 1.5). Move with acceleration,
  tap = click (delayed for tap-then-drag), double tap, tap-then-drag, scroll mode (taps never click,
  needs `scrollSlop` travel before scrolling), touches starting in the top key row ignored
  (`ignoreTopFrac`), suppression while typing. `PointerService` keeps the injected scroll finger down
  300 ms between swipes so quick swipes don't look like a double tap (that zoomed apps).
- `MainActivity`: setup/settings screen built in code. `ProbeActivity`: no-root input probe that
  logs every input path and device. `PointerTileService`: Quick Settings tile. `Prefs`: settings keys.

## Build (a fresh container needs this setup again)
```sh
mkdir -p /opt/android-sdk && cd /opt/android-sdk
curl -sSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip && mkdir -p cmdline-tools/latest
mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > /home/user/TitanPointer/local.properties
apt-get install -y gcc-aarch64-linux-gnu   # only needed to rebuild the helper (native/build.sh)
```
Then: `./gradlew lintRelease testDebugUnitTest assembleRelease` →
`app/build/outputs/apk/release/app-release.apk` (signed with the debug key). minSdk 26, so check for
APIs that need newer Android (lint catches them as NewApi).

## v1.4: survive closing the app
Swiping TitanPointer from Recents killed the process; Android 10 marks the accessibility service as
crashed and doesn't rebind it until re-toggled. `PointerService` now calls startForeground (IMPORTANCE_MIN
channel, type specialUse) so task removal doesn't kill the process. Don't move activities to another
process: prefs listeners, `PointerService.isRunning()` and `PadLink` assume one process.

## v1.5
Speed 0.1–2.0× (default 0.7), multi-finger gestures removed, scroll fixes, top-row ignore zone (22%,
estimated from a photo: unconfirmed), letters setting, settable click/long-press keys, Backspace no longer = Back.

## v1.6
User: pointer doesn't work in their notes app (text field; they use the BlackBerry Keyboard, which moves
the text cursor with pad swipes). Cause unknown. Added `Diag` (in-memory log shown/copyable at the bottom
of the app: focus gained/lost, windows, keys + source device, non-touchpad motion events) and focus
recovery: when the focus window loses focus it re-adds itself (max 3 tries per 10 s, not on the lock
screen) and the cursor fades while unfocused. Ask the user for the copied log from the notes app.

## v1.7
The v1.6 log showed the cause: with the BlackBerry Keyboard up, the focus window keeps focus but gets no
touchpad events. Pointer mode now hides the keyboard app (SHOW_MODE_HIDDEN) and clears the focused text
field (needs canRetrieveWindowContent, added to the service config); both are undone when it goes off
(focus is put back only if no other field took it). Logged in Diag. Not yet confirmed on the phone.

## Open items / ideas
- Confirm the 22% top-row cut-off and raw Y direction on the phone.
- Root/ADB path: confirm `/dev/input` names/ranges on a real Titan and test the grab behavior.
- The test page artifact (Titan Keyboard Probe): https://claude.ai/artifact/3YTvp88ubTZ1kfVoHWsXP3
