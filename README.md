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
2. Tap **Calibrate** and follow the on-screen steps.
3. For spark runs: Settings → set the LED region (LED off) → enable **Trigger LED logging**.
4. Tap **Record** to start logging an experiment as CSV.
5. Tap **Stop** to end the run.
6. Tap **Share** under the experiment to export the CSV (and `*.trigger.json` sidecar when present).

### Sparks per run

The camera cannot resolve individual sparks (~67/s). Estimate from LED-on time:

```
sparks ≈ firing_rate_hz × total_led_on_ms / 1000
```

`total_led_on_ms` is in `LED_SUMMARY` event rows at the end of the CSV and in the `*.trigger.json` sidecar.

### CSV notes

Sample and event rows share a camera-capture timeline (`timestamp_ms`). Extra columns `frame_index` and `led_on` are appended to the original header. Per-run totals are normal `LED_SUMMARY` event rows (no `#` footer — those break pandas/Excel/R). Manual MARK events are still `flags=SYNC` (timestamp = last processed frame, up to one frame early). Trigger edges are `LED_ON` / `LED_OFF`. Details: [docs/trigger-led.md](docs/trigger-led.md).

The older 555 per-spark flash circuit is [legacy](docs/spark-detector.md): with logging enabled it now yields short `LED_ON`/`LED_OFF` pairs instead of `SYNC auto_spark`.

## Build

Requires Android Studio (or SDK + JDK 17–24) and Android SDK API 34.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` no longer pins a machine-local JDK path. If your default Java is outside JDK 17–24, set `org.gradle.java.home` in `~/.gradle/gradle.properties` (or export `JAVA_HOME`) to a JDK 17 or 21 install.

Install the APK on the phone, enable **Install unknown apps**, and grant **Camera** permission on first launch.
