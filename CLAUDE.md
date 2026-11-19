# TitanPointer: notes for the next session

Android app that turns the original Unihertz Titan's touch-sensitive keyboard into a touchpad with an
on-screen pointer. Status at v1.2: the user reports the no-root mode is "mostly working".

## Working with this user
- Usually on their phone: always **build the APK and send it** (SendUserFile, `display: attach`).
  Don't ask them to build. Keep `dist/TitanPointer-<version>.apk` current and bump
  `versionCode`/`versionName` in `app/build.gradle` for each APK you send.
- Listen to what they say they observed. They were right that a no-root app can read the keyboard touches.
- Develop on branch `ccr-b45e6ac7-nxr23a`. Don't open a PR unless asked.

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
    MotionEvents and feeds `GestureEngine`. Its keys go to `onPointerModeKey`: Enter = click,
    Alt+Enter = long press, Space = scroll lock, Backspace/Back = global Back; any other key turns
    pointer mode off (that key press is lost); system/volume/media keys pass through.
  - Alt+Space toggles pointer mode by default (`Prefs.DEFAULT_TOGGLE_KEY/META`). It fires on key down.
  - **Root/ADB mode**: `PadLink` talks to the native helper `native/titanpad.c` (static arm64, shipped
    as `jniLibs/arm64-v8a/libtitanpad.so`, runs via `su -c ... stdio` or `serve <port> <token>` over
    ADB). It streams evdev events, optionally with EVIOCGRAB, through `TouchTracker` (MT protocol A/B)
    into `GestureEngine`. This lets you type while the pointer is on, but it's untested on hardware.
- `GestureEngine`: pure Java, unit tested (`app/src/test/.../GestureEngineTest.java`). Move with
  acceleration, tap = click (delayed for tap-then-drag), double tap, tap-then-drag, 2-finger scroll,
  2-finger tap = long press, 3-finger tap = configurable action, scroll lock, suppression of touches
  while typing.
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

## Open items / ideas
- Ask the user what "mostly working" leaves out, and tune from there (speed, acceleration, tap
  sensitivity, scroll speed).
- No-root mode: typing needs leaving pointer mode, and the key that leaves it is lost. Possible fix:
  re-type that key into the focused field with accessibility ACTION_SET_TEXT, or keep pointer mode
  but pass keys through some other way.
- The catcher may lose focus to other overlays or dialogs. There's no recovery logic yet.
- Multitouch over the no-root path is unconfirmed (only one pointer seen).
- Root/ADB path: confirm `/dev/input` names/ranges on a real Titan and test the grab behavior.
- The test page artifact (Titan Keyboard Probe): https://claude.ai/artifact/3YTvp88ubTZ1kfVoHWsXP3
