# Torsion Balance Tracker

Android app that measures torsion-balance beam motion with OpenCV. It tracks a black dot on the beam and a static fiducial dot on the chamber wall, and reports deflection as the **difference** between the two — so bumping the camera mount does not look like a real deflection.

## Physical setup

1. Place a 3–5 mm black dot on a white background on the **balance beam** (moving marker).
2. Place a 3–5 mm black dot on a white background on the **chamber wall** (fiducial marker).
3. Place an object of known length (e.g. 10 mm) in the frame for scale.
4. Mount the phone on a tripod, camera pointing at the beam.
5. Use stable, diffuse lighting.
6. Optional: put the **trigger LED** (on while the spark button is held) in a corner of the frame, away from both dots. See [docs/trigger-led.md](docs/trigger-led.md).

## Using the app

1. Open **Torsion Balance**.
2. Tap **Calibrate** and follow the on-screen steps. The last step learns arm direction with a short nudge (increasing image `xRel` → +θ or −θ). That sign is saved in DataStore; later calibrations and recordings skip the nudge while the phone stays in the same mount.
3. For spark runs: Settings → set the LED region (LED off) → enable **Trigger LED logging**.
4. Tap **Record** to start logging an experiment as CSV. With a saved direction, recording starts immediately — no need to touch a beam that has settled overnight.
5. Tap **Stop** to end the run.
6. Tap **Share** under the experiment to export the CSV.

### Arm direction

- Live UI shows `Direction: saved (+1) – Recalibrate` (or `manual` / `nudged`) when a sign is configured.
- **Recalibrate direction** (main screen or Settings) clears the saved sign and runs the nudge again.
- Settings also lets you choose **+1** / **−1** directly; the choice persists immediately (no Apply).
- Each CSV starts with `# sign_multiplier=` / `# sign_source=` metadata (`saved`, `manual`, or `nudged`). Details: [docs/arm-direction.md](docs/arm-direction.md).

### Sparks per run

The camera cannot resolve individual sparks (~67/s). Estimate from LED-on time:

```
sparks ≈ firing_rate_hz × total_led_on_ms / 1000
```

`total_led_on_ms` is in `LED_SUMMARY` event rows at the end of the CSV.

### Marker tracking

Arm and reference dots are found with contours in a small ROI around the last
known position, binarized at a slow, per-marker contrast threshold (blob vs local
background, with hysteresis) rather than per-frame Otsu. Short dropouts keep the last
position and set `flags=HELD`; longer gaps become `ARM_LOST` / `REF_LOST`. Edge-clipped
contours and large per-frame jumps are rejected; the fixed reference is only ever
re-searched within a few px of its last position. A sudden whole-frame brightness step
is a lighting event: rows are `HELD` while the thresholds re-learn, and it never counts
toward LOST. The screen stays on while recording. Camera lock persists manual
AE/ISO/focus and meters on the arm marker.
Details: [docs/marker-tracking.md](docs/marker-tracking.md).

### CSV notes

Each file begins with comma-free `#` metadata lines (`sign_multiplier`, `sign_source`, and the camera's `ae_mode`, `ae_lock`, `awb_lock` at Record), then the column header. Sample and event rows share a camera-capture timeline (`timestamp_ms`). Extra columns (`frame_index`, `led_on`, detector diagnostics, then per-frame `exposure_time_ns`, `iso`, `frame_mean`, `lighting_event`) are appended to the original header. Per-run totals are normal `LED_SUMMARY` event rows (no `#` **footer** — those break pandas/Excel/R; preamble `#` lines are fine with `comment='#'`). Manual MARK events are still `flags=SYNC` (timestamp = last processed frame, up to one frame early). Trigger edges are `LED_ON` / `LED_OFF` (long holds may note `suspicious_long_hold=true`). If an AE or AWB lock drops mid-run, a single `AE_UNLOCKED` / `AWB_UNLOCKED` event row records it. The CSV is the sole export; there is no JSON sidecar. Details: [docs/trigger-led.md](docs/trigger-led.md), [docs/marker-tracking.md](docs/marker-tracking.md).

The older 555 per-spark flash circuit is [legacy](docs/spark-detector.md): with logging enabled it now yields short `LED_ON`/`LED_OFF` pairs instead of `SYNC auto_spark`.

## Build

Requires Android Studio (or SDK + JDK 17–24) and Android SDK API 34.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` no longer pins a machine-local JDK path. If your default Java is outside JDK 17–24, set `org.gradle.java.home` in `~/.gradle/gradle.properties` (or export `JAVA_HOME`) to a JDK 17 or 21 install.

Install the APK on the phone, enable **Install unknown apps**, and grant **Camera** permission on first launch.

## License

[MIT](LICENSE) © 2026 Nick Kraakman
