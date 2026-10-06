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
3. Settings → **Set LED region** → tap the LED on the preview (LED should be **off**).
4. Enable **Trigger LED logging** and set the on-threshold so that:
   - LED off: Δ stays near 0
   - LED held: Δ is well above the threshold  
   Live mean / baseline / Δ appear on the preview next to the ROI box and in Settings.
5. Record as usual. Hold the spark trigger during the run; release between presses.

If the LED is already on when you first set the ROI, wait until it is off so the dark baseline can be learned. Detection also runs on the live preview (not only while recording), so a hold that starts just before Record is still logged as ON on the first recorded frame.

## Detection

Hysteresis on ROI mean luma versus a dark EMA baseline (updated only while the LED is classified **off**). There is no cooldown: a 1–5 s hold is one ON interval, not a burst of marks.

## CSV format

Header (new columns are appended so older parsers that ignore extras still work):

```
timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad,x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note,frame_index,led_on
```

| Column | Meaning |
|--------|---------|
| `timestamp_ms` | Time from the first recorded frame, using the camera capture timestamp when the device provides one (not `System.nanoTime()` at processing time). Event and sample rows share this clock. |
| `kind` | `sample` or `event` |
| `flags` / `note` | Tracking flags on samples. Events: `SYNC` + note for a manual MARK; `LED_ON` / `LED_OFF` for trigger edges. |
| `frame_index` | Processed analysis-frame counter from the start of the recording (0, 1, 2, …). ImageAnalysis uses `STRATEGY_KEEP_ONLY_LATEST`, so dropped frames never appear; a gap in `timestamp_ms` of ~2 frame periods means a drop. |
| `led_on` | `1` or `0` on every sample while LED logging is enabled; empty if logging was off. |

While LED logging is enabled, **every processed frame is written as a sample**, even if Settings → Sample rate is lower. That keeps `led_on` at camera-frame resolution. With logging off, sample-rate thinning still applies (`frame_index` still counts processed frames, so thinned files skip indices).

Reconstruct press duration either from `led_on` on sample rows or from `LED_ON`/`LED_OFF` event timestamps (same capture clock).

### Footer and sidecar

Each CSV ends with `#` comment lines (safe to skip). A sidecar `*.trigger.json` is written next to the CSV (included in ZIP export):

```
# --- trigger_led ---
# processed_frames=...
# duration_ms=...
# measured_fps=...
# led_logging=true
# press_count=...
# total_led_on_ms=...
# edges=ON@120,OFF@1820,...
# sparks_est = firing_rate_hz * total_led_on_ms / 1000
```

`press_count` is the number of ON edges (including a hold already in progress at Record, and a hold still down at Stop).

## Legacy hardware

The older 555 radio spark-detector (one ~110 ms flash per discharge) is documented in [spark-detector.md](spark-detector.md) as **legacy**. The current hardware keeps the LED on for the whole button press.
