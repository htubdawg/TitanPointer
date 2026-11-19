# Unihertz Titan (original, 2019): hardware notes

Facts the app relies on. "Measured" means read on the user's phone; "photo" means estimated from the
official front product photo; anything else is marked unconfirmed.

## Phone
- Size: 153.6 × 92.5 × 16.65 mm (official spec).
- Screen: 4.6", square, 1440 × 1440 as reported by Android (`getRealMetrics`).
- Android 10, build QP1A.190711.020 on the user's phone (measured).

## Keyboard layout (photo)
Four rows directly below the screen, all on one touch-sensitive surface:

| Row | Keys, left to right |
|---|---|
| 1 (top) | Shift ⇧ · Recents □ · home / fingerprint key (centre) · Back ◁ · Alt |
| 2 | Q W E R T Y U I O P |
| 3 | A S D F G H J K L · Backspace ⌫ |
| 4 | Z X C V · Space · B N M · Enter ↵ |

Approximate geometry from the photo (scale: the 92.5 mm phone width is about 300 px, so about 3.3 px/mm):
- Keyboard area: about 78 mm wide × 33 mm tall.
- Top row: about 7 mm tall, about 22% of the keyboard height. The three letter rows are about 8 mm each.

Back, Recents, the home key, Shift and Alt are physical keys on the same touch surface, so pressing them
also produces touchpad movement. The app ignores touches that *start* in the top 22% of the pad
(`Prefs.DEFAULT_IGNORE_TOP`, adjustable in the app). This assumes raw Y = 0 is the top row; the default
direction settings move the pointer the right way on the user's phone, which fits that.

## Touch sensor (measured)
- Android input device `mtk-pad`, source TOUCHPAD, absolute X 0..1440, Y 0..720 (2:1 range; the physical
  area is closer to 2.4:1, so raw units are not exactly square: `Vertical speed` corrects this).
- **One finger only.** No multi-finger gestures are possible.
- Events reach only the window with input focus, as generic MotionEvents. In Firefox they become touch
  events; in Chrome/WebView, wheel events.

## Other input devices (measured)
- `mtk-tpd`: touchscreen, X -1..1434, Y 0..1435.
- `aw9523-key`, `mtk-kpd`, `cf-keys`: keys.

## Key codes (unconfirmed)
Expected Android key codes: Back = KEYCODE_BACK, Recents = KEYCODE_APP_SWITCH, Shift = KEYCODE_SHIFT_LEFT,
Alt = KEYCODE_ALT_LEFT, Enter = KEYCODE_ENTER, Backspace = KEYCODE_DEL. The home/fingerprint key has not been
checked. The probe screen in the app logs real key codes.
