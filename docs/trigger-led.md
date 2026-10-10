# Trigger LED logging

The experimenter holds a button that fires a spark circuit at a known rate (~67 sparks/s).
A trigger LED in the camera frame stays **lit for the whole time the button is held**.
The app logs LED on/off to about one camera frame per edge (~±33 ms at 30 fps).

Sparks in a run are **not** counted individually. Estimate:

```
sparks ≈ firing_rate_hz × total_led_on_ms / 1000
```

Example: 67 Hz × 3450 ms / 1000 ≈ 231 sparks.

## Setup

1. Complete beam calibration and **Lock Camera** (AE/AF lock) so the LED does not pull exposure.
2. Mount a bright LED in view, away from both tracking dots.
3. Settings → enable **Trigger LED logging** (the switch saves immediately). Optionally tune the on-threshold and tap **Apply LED settings**.
4. Settings → **Set LED region** → tap the LED on the preview (LED should be **off**). Pending LED settings are flushed before leaving Settings.
5. Confirm the threshold so that:
   - LED off: Δ stays near 0
   - LED held: Δ is well above the threshold  
   Live mean / baseline / Δ appear on the preview next to the ROI box and in Settings.
6. Record as usual. Hold the spark trigger during the run; release between presses.

If the LED is already on when you first set the ROI, wait until it is off so the dark baseline can be learned. Detection also runs on the live preview (not only while recording), so a hold that starts just before Record is still logged as ON on the first recorded frame.

## Detection

Hysteresis on ROI mean luma versus a dark EMA baseline (updated only while the LED is classified **off**). There is no cooldown: a 1–5 s hold is one ON interval, not a burst of marks.

If ambient light or AE drift rises while the LED is held, the frozen baseline can leave the LED stuck ON. Holds longer than **30 s** are flagged (`suspicious_long_holds` in the summary/sidecar) and an on-screen warning is shown.

## CSV format

Header (new columns are appended so older parsers that ignore extras still work):

```
timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad,x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on
```

| Column | Meaning |
|--------|---------|
| `timestamp_ms` | Time from the first recorded frame, using the camera capture timestamp when the device provides one (not `System.nanoTime()` at processing time). Event and sample rows share this clock. |
| `kind` | `sample` or `event` |
| `flags` / `note` | Tracking flags on samples. Events: `SYNC` + note for a manual MARK; `LED_ON` / `LED_OFF` for trigger edges; `LED_SUMMARY` + `key=value` note for the per-run summary. |
| `frame_index` | Processed analysis-frame counter from the start of the recording (0, 1, 2, …). ImageAnalysis uses `STRATEGY_KEEP_ONLY_LATEST`, so dropped frames never appear; a gap in `timestamp_ms` of ~2 frame periods means a drop. |
| `led_on` | `1` or `0` on every sample while LED logging is enabled; empty if logging was off. |

While LED logging is enabled, **every processed frame is written as a sample**, even if Settings → Sample rate is lower. That keeps `led_on` at camera-frame resolution. With logging off, sample-rate thinning still applies (`frame_index` still counts processed frames, so thinned files skip indices).

Reconstruct press duration either from `led_on` on sample rows or from `LED_ON`/`LED_OFF` event timestamps (same capture clock).

Manual **MARK** uses the last processed frame’s capture timestamp (up to one frame early relative to the button press).

### Summary rows and sidecar

There is **no `#` comment footer** (those break `pandas.read_csv` / Excel / R when they contain commas). At Stop the app appends normal `event` rows with `flags=LED_SUMMARY` and a comma-free `note` such as:

```
...,event,,,,,,,,LED_SUMMARY,total_led_on_ms=3450,<frame>,
...,event,,,,,,,,LED_SUMMARY,press_count=2,<frame>,
...,event,,,,,,,,LED_SUMMARY,measured_fps=30.303,<frame>,
...,event,,,,,,,,LED_SUMMARY,suspicious_long_holds=0,<frame>,
...,event,,,,,,,,LED_SUMMARY,capture_timestamp_fallbacks=0,<frame>,
...,event,,,,,,,,LED_SUMMARY,sparks_est=firing_rate_hz*total_led_on_ms/1000,<frame>,
```

A sidecar `*.trigger.json` next to the CSV has the full edge list (Share includes it when present; ZIP export always does).

`press_count` is the number of ON edges (including a hold already in progress at Record, and a hold still down at Stop).

## Performance note

CSV rows are written from the CameraX analysis thread into a buffered writer. Forced flushes happen on Stop and manual MARK (not on every LED edge). Under `STRATEGY_KEEP_ONLY_LATEST`, heavy disk I/O can still increase dropped frames on slow storage — prefer an every-frame sample rate only when you need LED logging, and leave AE locked.

## Legacy hardware (555 flash stretcher)

The older 555 radio spark-detector (one ~110 ms flash per discharge) is documented in [spark-detector.md](spark-detector.md). **Behaviour change:** the app no longer writes `SYNC auto_spark` rows. With LED logging enabled, each flash appears as a short `LED_ON`/`LED_OFF` pair (or a pulse in the `led_on` column). Prefer the button-held trigger LED for spark counting via on-time.
