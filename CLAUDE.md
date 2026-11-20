# TitanPointer: notes for the next session

Android app that turns the original Unihertz Titan's touch-sensitive keyboard into a touchpad with an
on-screen pointer. Status at v1.2: the user reports the no-root mode is "mostly working".

## Working with this user
- **The Titan's "scroll assistant" (keyboard scrolling) must stay ON: without it the pointer doesn't work at
  all (keyboard touches don't reach TitanPointer). Never suggest turning it off.** The user has said this many times.
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
(focus is put back only if no other field took it). Logged in Diag. Works on the phone (user, v1.9).

## v1.8
The foreground notification showed on the user's lock screen. It's now VISIBILITY_SECRET on both the
notification and a new channel `keepalive_hidden` (the old `keepalive` channel is deleted, since channel
settings can't change after creation). The user declined battery changes (screen-off auto-off, retry cap).

## v1.9
Diagnostics capture switch (`Prefs.DIAG`, default on; off clears the log and hides it). Keep-alive
notification text shortened to say long-press hides it: the foreground service stays even if the user turns
the notification off. The user confirmed the v1.7 notes-app fix and the top-row cut-off work.

## v1.10
- **Fling scrolling** (`Prefs.FLING`, default on, under Scrolling). `GestureEngine` keeps the last 16 scroll
  moves and on lift measures speed over the last 80 ms (`padVelocity`; a pause before lifting counts, so
  stop-then-lift doesn't fling). At ≥ 0.6 pad widths/s it calls `onScrollEnd(vx, vy)` with px/s (scaled and
  inverted like scrolling; 0,0 = no fling). `PointerService` then skips the 300 ms hold and calls
  `GestureInjector.flingEnd`, which adds one last 60 ms segment still moving at that speed (capped 12000 px/s,
  shortened near the screen edge) and lifts, so the app's own fling physics take over. Logged in Diag
  ("scroll fling"). Not yet tried on the phone: if apps don't fling from injected strokes, the fallback is
  our own decaying scroll on a timer; also watch for the old double-tap zoom with quick repeated flicks.
- **Advanced section**: the mode radio group and the root/ADB box (device picker, test & calibrate, now
  unnumbered) moved to a collapsible "Advanced: root / ADB (untested)" section above Diagnostics, expanded
  only if the mode is already root/adb. Section 2 is now "How it works" (shows a note when a helper mode is on).

## v1.11 (reverted in 1.12) and the Firefox URL bar investigation
Bug: in Firefox the URL bar ignores a **tap on the keyboard surface**, but the **click key (Enter) works** on
it, and pad taps work everywhere else (web page fields, other apps). Turning tap-drag off didn't help.
Both paths end in the identical `injector.tap(px, py)` (40 ms tap), so the difference comes from outside
our code, around the keyboard touch. 1.11 guessed a focus problem (auto-exit on text field focus): wrong,
removed in 1.12. Checked in Firefox's source (mozilla-firefox/firefox main, Nov 2026; searchfox is
blocked here, but a sparse `git clone` of github.com works): the URL bar is a plain Compose `clickable`/
`combinedClickable` (`compose/browser-toolbar/.../ui/Origin.kt`; old View toolbar `OriginView.kt` uses
setOnClickListener). A click opens the search/home screen. `filterTouchesWhenObscured` is only used in
`NoObscuredTouchesDialogFragment` (permission, add-on, prompt dialogs), not the toolbar. `SwipeGestureLayout`
only takes swipes. **Don't guess again: read the 1.12 log.**

## v1.12: click diagnostics
`ClickTrace` (only while Diag is on): each click logs source (tap on keyboard / click key), pad touch
duration and time since lift, the windows at the point and the deepest element there (needs
flagRetrieveInteractiveWindows, added), the injected tap's fate (sent/queued/completed/CANCELLED/REFUSED via
a `GestureInjector.Result` callback), and for 2 s every accessibility event from other apps (event types are
widened to all with setServiceInfo during the watch, then restored; content changes only counted). A
handled click shows as VIEW_CLICKED from Firefox. Test switch `Prefs.TAP_DELAY_TEST` (Diagnostics,
default off) delays pad-tap clicks by 1 s. Waiting on the user's log: tap vs Enter on the URL bar.

## v1.13
The 1.12 log: keyboard-tap click and Enter click both injected at the same point, both "completed"; Firefox
opened its search dialog only for Enter (WINDOW_STATE_CHANGED ComponentDialog at +617 ms) and reported
nothing for the tap. The "under the pointer" lines logged nothing (cause unknown). Ruled out by the user's
tests: tap-drag delay, a 1 s delayed tap click, and a recent keyboard touch (scroll-mode tap then Enter
works). Remember **every key press is also a touch on the surface** (the log shows the pad touch right after
Enter). Firefox code checked further: `EngineViewScrollingGesturesBehavior` and `VerticalSwipeRefreshLayout`
don't block taps; nothing checks touch mode/input mode/touchpad. The only code difference left was that each
pad touch report calls `onPointerMove` → `updateViewLayout` on the cursor window even with zero movement
(Enter's own pad touch is dropped as typing first). 1.13 skips relayouts when the position is unchanged.
**Result: 1.13 didn't fix it, and Chrome's address bar ignores pad taps too** (Enter works). Chrome's
toolbar is classic Views, Firefox's is Compose, so it's not toolbar code. Closed as outside TitanPointer
(Android / the Titan's software); workaround: the click key. Don't reopen unless the user asks.

## v1.14: logging from the keyboard touch on
Reopened: an accessibility "one-hand mode" joystick app from the Play Store CAN click the address bar, and
the user reports Enter works even while moving the pointer with the pad (so the touch-mode idea is out:
`ViewRootImpl.processMotionEvent` does call `ensureTouchMode(false)` on every SOURCE_CLASS_POSITION
ACTION_DOWN, but that doesn't break Enter clicks during a pad touch). Rejected by the user: clicking via
ACTION_CLICK on the node (ignores the pointer position). `ClickTrace` rewritten: a watch starts at the pad
touch DOWN (not at the click), logs down/up position and moves, focus window touch mode, input focus node,
every event from other apps (types widened, notificationTimeout 0 during the watch; content changes capped),
the click, windows/element under the pointer (FLAG_RETRIEVE_INTERACTIVE_WINDOWS also set at runtime; logs the
window count if none match) and the tap result. Diag keeps 600 lines. Waiting on the user's log.

## v1.15
1.14 log: pad-tap clicks and the Enter click land on Firefox's address bar (a View TextView "reddit.com",
clickable/long-clickable); only Enter gets VIEW_CLICKED. Firefox sends no events during pad touches; touch
mode and input focus never change. User: Enter shortly after moving the pointer works; **no toolbar button
works with pad taps** (whole top toolbar, Firefox and Chrome). Chromium `ToolbarControlContainer` swallows
toolbar taps when the toolbar view isn't VISIBLE, but that would hit Enter too. Last code difference: Enter
clicks from inside `onKeyEvent`, pad taps from a Handler. Test switch `Prefs.KEY_TIMER_TEST` sends the click
key's click via `main.post` (no delay). If Enter then fails on the toolbar, that's the cause.
**Result: no difference** (Enter via main.post still works). So TitanPointer sends the identical tap
from the identical context either way. **No explanation fits all the user's results**: Enter works with
nothing before it, right after a slide, right after a plain (scroll-mode) tap, and while a finger is moving
on the pad; clicks produced by a pad tap fail (with or without the drag wait, with a 1 s delay). Don't
claim "a plain tap affects the next click": the scroll-mode test contradicts it. Cause unknown;
workaround: the click key.
Don't reopen unless the user asks.

## v1.16: tap test screen
Reopened at the user's request. User insists key presses never create double taps (don't argue it again).
`TapTestActivity` (Diagnostics → "Open tap test screen"): a browser-like top toolbar (clickable,
long-clickable "address bar" TextView + tab counter + menu), a plain button and a button in a ScrollView.
Logs to Diag with "[test]": every MotionEvent the window gets (action, latency since eventTime, device,
source, tool, flags incl. obscured bits, batching, meta/buttons, classification), each element's
dispatch result, pressed changes, performClick/performLongClick, focus and touch-mode changes. Goal: see
what a window receives for a keyboard-tap click vs an Enter click. Waiting on the user's log.

## v1.17
**1.16 reproduced it in our own app**: identical injected DOWN/UP (dev=0 src=0x1002 tool=0 flags=0x0, 1–7 ms
latency) reached the test window both times; on keyboard-tap clicks the clickable TextView's
`dispatchTouchEvent` returned false at DOWN (never pressed, onTouchEvent apparently not reached); on Enter
clicks it was pressed and clicked. Stock Android 10 `View.dispatchTouchEvent` can only refuse a clickable
view's touch via FLAG_TARGET_ACCESSIBILITY_FOCUS or the obscured filter, and flags were 0, so the Titan's
framework likely differs. User: **every** element on the test screen failed with pad taps (not position).
1.17 logs each gate at DOWN (flags, a11y-focus flag, securityFilter, enabled/clickable, a11y focus via
reflection), whether onTouchEvent is reached, the root layout's intercept/dispatch, and adds a bare View
that returns true from onTouchEvent. Waiting on the user's log.

## v1.18
**1.17 log, key result**: on pad-tap clicks a clickable TextView's `onTouchEvent` is REACHED and returns
**false** at DOWN (all gates before it pass, flags 0); on Enter clicks it returns true and clicks. A bare
View whose own onTouchEvent returns true accepts both. Stock Android 10 `View.onTouchEvent` always returns
true for a clickable view, so **the Titan's framework modifies View.onTouchEvent** to refuse touches after
keyboard-surface use until something (a key press, a real screen touch) resets it. That explains: View-based
toolbars/buttons fail; web content, Compose UIs and the joystick app (no pad touch) work. 1.18 adds
`StateProbe`: at each DOWN on a test element it snapshots the view's/AttachInfo/ViewRootImpl/DecorView
fields, static fields of view/input classes, IMM/InputManager instance fields, getprop and Settings
System/Global/Secure (hidden API exemption via meta-reflection, Android 10), and logs the diff from the
previous touch labelled ACCEPTED/REFUSED. Diag keeps 2000 lines. Waiting on the user's log.

## Root cause found (1.18 log)
The system property **`debug.sf.forcescroll_view`** is `yes` at every refused pad-tap click and `no` at every
accepted Enter click (nothing else flips). It's the Titan's keyboard-scroll feature: a keyboard-surface touch
sets it to `yes`, a key press sets it to `no`, and the Titan's modified `View.onTouchEvent` refuses touches
while it's `yes` (Compose/web content don't use that code, so they work). Apps can't set `debug.*`
properties (SELinux), so no-root TitanPointer can't reset it directly; root/ADB could (`setprop`). The
scroll assistant can't be turned off (the pointer needs it). **v1.19**: a keyboard-tap click is held while
the property reads `yes` (read via SystemProperties.get reflection), polling every 20 ms up to 3 s, then
clicks at the tap position; logs how long it waited or that it stayed `yes`. **Result: it never resets without a key
press** (every held click only went through right after an Enter), so taps did nothing; reverted in 1.20
(clicks go out immediately again; the log still shows the switch value). Likely purpose: the scroll
assistant turns keyboard swipes into fake screen touches (device 0, like accessibility taps), and the
switch stops those from clicking. **User test without TitanPointer at all: after scrolling a page with the
keyboard, a real finger tap on the browser's address bar is refused too.** So it's purely the Titan's
behaviour; TitanPointer only hits it because every pad-tap click follows a keyboard touch. If it never resets by itself,
no-root can't fix this; the click key remains the workaround.

### Truth table (keep this; don't make the user repeat results)
Rule: a keyboard-surface touch sets `debug.sf.forcescroll_view`=yes; only a real key press sets it to no; the
Titan's View.onTouchEvent refuses taps on standard Views while it's yes.
| What | Key press just before the click? | Switch at click | Result |
|---|---|---|---|
| pad tap | no | yes | fails |
| pad tap, tap-drag off | no | yes | fails |
| pad tap, click delayed 1 s | no | yes | fails |
| pad tap, click held up to 3 s waiting for no (1.19) | no | stays yes | fails |
| Enter alone | yes (itself) | no | works |
| Enter while moving the pointer | yes (itself) | no | works |
| scroll-mode tap, Space, Enter | yes | no | works |
| pad tap with a letter pressed at the same moment | yes | no | works |
| joystick app | no pad touch | never set | works |
| web content / Compose | n/a | yes | works (doesn't use View.onTouchEvent) |
No-root can't inject keys or set debug.* props; root/ADB mode could `setprop debug.sf.forcescroll_view no`
before each click (not implemented).

## v1.21: find how the phone decides
1.20 log in Chrome with pointer mode: the switch DOES turn on (Chrome alone keeps it off; Firefox is the only
app on the scroll assistant's forced list), so TitanPointer's focus window triggers it. Any key, incl. Shift,
resets it. `FrameworkScan` (Diagnostics → "Search phone software for the scroll switch"): streams files under
/system, /system_ext, /product, /vendor, /odm for "forcescroll"/"force_scroll"/"forceScroll" (also inside
jar/apk dex entries), parses dex (also embedded in vdex/odex/oat) to log every method that loads such a
string plus one level of callers, with the strings, fields and calls in each; native/config hits log nearby
text. Unit tested on a d8-built dex (`FrameworkScanTest`, `src/test/resources/probe.dex`). Waiting on the log.

## 1.21 scan result: how the phone does it
- `framework.jar` `View.onTouchEvent` and `View.performButtonActionOnTouchDown` read
  `SystemProperties.get("debug.sf.forcescroll_view")` ("yes" → refuse). MediaTek debug hooks around them.
- `libinputreader.so` is the only other user of `debug.sf.forcescroll_view`: the system's input reader sets it
  (keyboard touch → yes, key → no), apparently not per app.
- `services.jar` `WindowManagerService$H.handleMessage` (focus change, `updateFocusedWindowLocked`) reads
  Settings.Global `touchpad_force_scroll_app` (comma list; Firefox) and writes
  `persist.sys.forcescroll_package_name`, which `libinputflinger.so` reads (with `ro.agui.touchpad_function`,
  `persist.sys.touchpad_switch`). That's the per-app forced scrolling (pad → fake touches).
- `/product/app/AguiLEDSettings` `TouchpadActivity` edits the list and Settings.Global `touchpad_switch(_value)`.
- Correction: keyboard taps on Chrome's address bar already failed back at 1.13 (not just on 1.19), while a
  finger tap after keyboard scrolling works in Chrome alone, and the 1.20 log showed the switch `yes` with
  TitanPointer in front of Chrome. So TitanPointer's focus window does make the input reader set the switch;
  the reader's rule is native code (only strings visible), so how to avoid it is unknown. Only fix found: ADB shell (uid shell) can `setprop debug.sf.forcescroll_view no`
  before each click via the existing ADB helper (`native/titanpad.c serve`); offered to the user, not built.

## v1.22
Diagnostics capture is off by default (`Prefs.DIAG` default false, `Diag.enabled` starts false; the tap test
screen and the software search switch it on themselves). The Diagnostics section is collapsible like Advanced
(shared `MainActivity.collapsible()`), expanded only when capturing is on.

## Open items / ideas
- Rejected by the user (don't suggest again): haptic feedback on click, per-app rules (bloat), edge
  auto-scroll while dragging (Android's text selection already scrolls).
- **Planned for later: make TitanPointer a keyboard app (IME).** Agreed with the user as the fix for typing while
  pointer mode is on in no-root mode. An IME gets the hardware keys while a text field is focused and can
  commit text; the BlackBerry Keyboard already gets the pad's touches as an IME (it moves the text cursor
  with them, and the v1.6 log showed it takes them from our focus window). Cost: replaces the BlackBerry
  Keyboard (its predictions etc.).
- The 22% top-row cut-off and Y direction work for the user (v1.9).
- Root/ADB path: confirm `/dev/input` names/ranges on a real Titan and test the grab behavior.
- The test page artifact (Titan Keyboard Probe): https://claude.ai/artifact/3YTvp88ubTZ1kfVoHWsXP3
