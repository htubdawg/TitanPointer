# TitanPointer

Turns the touch-sensitive physical keyboard of the original **Unihertz Titan** into a laptop-style
touchpad with an on-screen mouse pointer.

| On the keyboard | Result |
|---|---|
| Slide | Move the pointer (with acceleration) |
| Tap | Click (tap at the pointer) |
| Tap twice | Double tap |
| Tap, then touch again and slide | Drag (press and hold at the pointer and move) |
| Space, then slide | Scroll mode: slides scroll, taps never click |

The Titan's keyboard reports one finger at a time. Touches that start on the top key row (Shift, Recents,
home, Back, Alt) are ignored. See [docs/titan-hardware.md](docs/titan-hardware.md).

Typing is detected automatically: touches during and just after a key press don't move the pointer or click.

## Install

1. Install `dist/TitanPointer-1.2.apk` (or build it, see below).
2. Open **TitanPointer** and follow the steps on screen:
   1. **Turn on the accessibility service** (Settings → Accessibility → TitanPointer). It draws the
      pointer, injects taps, and watches key presses to tell typing apart from touchpad use.
   2. **Give it access to the keyboard sensor** using one of the options below.
   3. **Find devices** and choose the keyboard's touch sensor (the ★ entry is the app's best guess).
   4. **Test**: touch the keyboard and check that the dots follow your fingers. Adjust the direction
      settings (swap or invert) if the pointer moves the wrong way.
3. Turn pointer mode on or off with the Quick Settings tile **Keyboard pointer**, the switch in the
   app, or a shortcut key you choose.

### No-root mode (default)

The Titan's keyboard sensor shows up to apps as its own touchpad device (`mtk-pad`, 1440×720, absolute
positions), but Android only sends its events to the window with keyboard focus. In no-root mode, while
pointer mode is on, TitanPointer keeps an invisible window focused (screen touches pass straight through),
so the keys get pointer jobs:

| Key | Action |
|---|---|
| Enter | Click (changeable) |
| Alt + Enter | Long press (changeable) |
| Space | Scroll mode on/off |
| Back | Back, as usual |
| Letters | Nothing by default (setting: turn pointer mode off) |
| Alt + Space | Pointer mode on/off (changeable) |

Root or ADB mode reads the sensor directly
and lets you type while the pointer is on.

### Root or ADB mode

Android doesn't let normal apps read raw touch data from the keyboard's sensor. TitanPointer ships a tiny
helper (`native/titanpad.c`, about 300 lines) that reads `/dev/input/eventN` and passes the touches to
the app. It needs one of these:

- **Root (Magisk):** choose *Root* and approve the su prompt. Nothing else to do.
- **No root, using ADB:** choose *ADB helper*, enable USB debugging, and after every reboot run the
  command shown in the app from a computer, or this script:
  ```sh
  scripts/start-adb-helper.sh <token shown in the app>
  ```
  The helper listens only on `127.0.0.1` and rejects any client that doesn't send the app's random token.

### Exclusive access

With **Exclusive access** on, the helper uses `EVIOCGRAB` while pointer mode is on, so Android and the
Titan's built-in keyboard scrolling stop reacting to your swipes. It is turned off automatically if the
chosen device also produces key presses or is the main touchscreen, because grabbing it would block
typing or the screen. If the sensor turns out to be part of the main touchscreen, use **Calibrate
area** to limit the pointer to the keyboard region.

## Build

Requirements: JDK 17+, the Android SDK (platform 34), and `aarch64-linux-gnu-gcc` (only needed to
rebuild the helper).

```sh
native/build.sh              # rebuild the helper into app/src/main/jniLibs/arm64-v8a/libtitanpad.so
./gradlew testDebugUnitTest  # gesture engine unit tests
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it can be installed directly.

## How it works

```
keyboard sensor ─► /dev/input/eventN ─► titanpad (root or adb shell) ─text─► PadLink
   ─► TouchTracker (evdev MT protocol A/B) ─► GestureEngine ─► PointerService
        ├─ overlay cursor (TYPE_ACCESSIBILITY_OVERLAY)
        └─ dispatchGesture(): taps, long presses, and continued strokes for scroll and drag
```
