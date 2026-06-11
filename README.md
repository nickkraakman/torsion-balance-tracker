# Torsion Balance Displacement Tracker

Sideloaded Android app for tracking a high-contrast marker on a torsion balance arm through an acrylic chamber window. Uses dual-marker tracking (arm + fixed chamber fiducial), locked camera exposure, and CSV logging with raw and filtered displacement columns.

## Hardware setup


| Item               | Recommendation                                                             |
| ------------------ | -------------------------------------------------------------------------- |
| Arm marker         | **5 mm** matte black dot on a light arm surface                            |
| Reference fiducial | **3–5 mm** matte black dot on the **chamber frame** (not the arm)          |
| Phone mount        | Fixed outside the chamber — no hand-holding                                |
| Camera distance    | **10–15 cm** from the marker (not 20+ cm)                                  |
| Lighting           | Diffuse side light; phone slightly off normal to reduce acrylic glare      |
| Glare reduction    | Linear polarizer on the light + second polarizer rotated 90° over the lens |


At ~10 cm with a typical ~70° horizontal field of view, pixel scale is roughly **0.1 mm/px** (one pixel ≈ 0.1 mm in the image). Sub-pixel centroiding resolves finer than that; under bench conditions the pipeline targets **±0.05 mm** accuracy (see testing checklist). The on-screen readout shows **two** decimal places to match that practical precision; CSV logs retain full precision for post-processing.

If your calibrated scale is closer to **~0.14 mm/px**, the phone is likely a bit farther than 10 cm or has a narrower field of view — still usable, but spatial resolution is coarser than the “ideal bench” setup below.

### How the two markers are told apart

The app does **not** use color or shape. Each frame is converted to grayscale; dark blobs are found with Otsu thresholding. Arm and reference are tracked in **separate search windows** (ROIs) centered on their last known positions. Within each window, the blob **closest to the predicted center** wins, filtered by an **expected pixel area** captured when you tap that marker during calibration (±35% tolerance).

Implications:


| Do                                                                                                                           | Why                                                                                                              |
| ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Use **different sizes** — **5 mm** arm dot, **3 mm** reference dot (dots not too big, then it can't lock onto them properly) | Area is the main intentional discriminator between markers                                                       |
| Place the reference fiducial on the **frame**, well away from the arm’s travel path                                          | When the arm swings near the fiducial, both search windows can overlap; the tracker may lock onto the wrong blob |
| Use **matte black** on a **light, uniform** background                                                                       | Maximizes contrast for thresholding                                                                              |
| Keep dots **filled and compact** (filled circles)                                                                            | Reliable single contour and stable centroid                                                                      |



| Avoid                                 | Why                                                                                                         |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| **Same-size** arm and reference dots  | Same expected area → easier to confuse when ROIs overlap                                                    |
| **Colored** dots (e.g. blue vs black) | Grayscale conversion erases hue; no benefit unless the app is changed                                       |
| **Different shapes** (cross vs dot)   | Shape is not classified; thin crosses often **fragment** into multiple contours under Otsu and track poorly |
| Glossy markers or busy backgrounds    | Weak or split blobs, lost tracking                                                                          |


### During calibration and recording

- **Stay out of the light path** — leaning over the setup casts shadows on the dots and changes illumination even with AE locked. This is a common cause of mid-run `ARM_LOST` while the reference still tracks.
- **Hold camera distance constant** — moving closer after **Lock Camera** changes blob size (area filters reject the marker) and invalidates the locked exposure.
- **Re-run Lock Camera** after any change to distance, lighting, or acrylic/polarizer alignment.
- **Do not touch the mount** during zero-drift or quantitative runs.

## Calibration (required before quantitative recording)

1. **Calibrate** → tap the **arm marker** on the live preview.
2. Tap the **reference fiducial** on the chamber frame.
3. Enter a known distance (e.g. `10.0` mm), then **press and drag** each scale endpoint on the preview — release to set (allows precise placement vs a finger tap).
4. Place the balance at rest → **Zero**.
5. **Lock Camera** — waits for auto-exposure to settle, then locks AE (`CONTROL_AE_LOCK`), disables AF/OIS/EIS, and re-tunes blob area limits for the locked image.
6. Optional: set **arm length** in Settings (enables `angle_rad` in CSV) — distance from the **torsion pivot to the arm marker**, not the full tip-to-tip span.
7. **Nudge the arm** in the direction you want as positive — sign is auto-detected.

## Recording

- **Record** → name the experiment → CSV is written to app storage.
- **MARK** writes a `kind=event` row with `flags=SYNC` for discharge alignment.
- **Zero** can be used during live view to re-zero without full recalibration.

CSV path on device:

```
Android/data/dev.qi.torsionbalance/files/Documents/TorsionBalance/<name>_<timestamp>.csv
```

Files are removed if the app is uninstalled — use **Export all (ZIP)** or per-experiment **Share** before uninstalling.

## CSV schema

```csv
timestamp_ms,kind,displacement_mm_raw,displacement_mm_filt,angle_rad,x_arm_px,y_arm_px,x_ref_px,y_ref_px,flags,note
```

- `timestamp_ms` — milliseconds since record start (monotonic)
- `displacement_mm_raw` — unfiltered; use for impulse/ring-down analysis
- `displacement_mm_filt` — Kalman-smoothed; matches on-screen readout
- `flags` — `OK`, `ARM_LOST`, `REF_LOST`, `BOTH_LOST`

## Post-processing example (Python)

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("bench_reference_fiducial_20260526T120000.csv")
samples = df[df["kind"] == "sample"].copy()
samples["t_s"] = samples["timestamp_ms"] / 1000.0

# Recompute displacement from pixels if calibration is refined later
mm_per_px = 0.035  # from calibration
zero_x_rel = 0.0
samples["x_rel_px"] = samples["x_arm_px"] - samples["x_ref_px"]
samples["disp_recalc_mm"] = (samples["x_rel_px"] - zero_x_rel) * mm_per_px

fig, ax = plt.subplots(2, 1, sharex=True, figsize=(10, 6))
ax[0].plot(samples["t_s"], samples["displacement_mm_raw"], label="raw", alpha=0.8)
ax[0].plot(samples["t_s"], samples["displacement_mm_filt"], label="filt", alpha=0.8)
ax[0].set_ylabel("mm")
ax[0].legend()
ax[0].set_title("Displacement")

events = df[df["kind"] == "event"]
for _, ev in events.iterrows():
    ax[0].axvline(ev["timestamp_ms"] / 1000.0, color="red", linestyle="--", alpha=0.5)

# Reference fiducial drift check: arm static, bump mount — raw should stay flat
ax[1].plot(samples["t_s"], samples["x_ref_px"], label="x_ref")
ax[1].plot(samples["t_s"], samples["x_arm_px"], label="x_arm")
ax[1].set_xlabel("time (s)")
ax[1].set_ylabel("px")
ax[1].legend()
plt.tight_layout()
plt.savefig("experiment_plot.png", dpi=150)
```

## Build and sideload

### Prerequisites

- Android Studio Hedgehog or newer, or command-line SDK + **JDK 17–24** (AGP 8.8 does not run on JDK 25 yet; set `org.gradle.java.home` in `gradle.properties` if needed)
- Android SDK API 34 (`ANDROID_HOME` set, or `local.properties` with `sdk.dir=...`)
- Version pins live in `gradle/libs.versions.toml` (AGP 8.8.2, Gradle 8.14.3, Kotlin 1.9.22, Compose compiler 1.5.10)

### Build

```bash
cd android/torsion-balance-tracker
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

Or

./gradlew installDebug # Builds and immediately sideloads
```

Release (unsigned):

```bash
./gradlew assembleRelease
```

Sign with your keystore for sideloading outside debug.

### OpenCV dependency

By default Gradle resolves `com.quickbirdstudios:opencv:4.5.3.0` from Maven Central.

To use a local AAR instead, place it at `app/libs/opencv.aar` (see `app/libs/README.md`).

### Install

1. Copy the APK to the phone.
2. Enable **Install unknown apps** for your file manager.
3. Install the APK.
4. Grant **Camera** permission on first launch.

## Testing checklist

“Ideal bench” means the recommended conditions above: ~10 cm distance, fixed mount, diffuse light, high-contrast dots, locked camera — the targets below assume that setup. Real runs can still pass individual tests with slightly coarser scale or brief tracking dropouts; inspect the CSV.

1. **Bench (no acrylic):** black dot on paper, slide with ruler — within ~0.05 mm at 10 cm.
2. **Reference fiducial:** bump the mount with arm static — `displacement_mm_raw` stays near zero.
3. **Acrylic:** repeat through chamber window.
4. **Zero drift:** 60 s static recording with locked camera — std dev of `displacement_mm_raw` (OK rows only) **< 0.02 mm**. Do not lean over the setup; a ~0.04 mm slow trend over 60 s can occur without failing the noise spec.
  - **Reference pass** (`run_02_20260609T154652.csv`, 64 s, ideal bench): std **0.007 mm**, peak-to-peak **0.04 mm**, 100% OK, slow trend **+0.0005 mm** over 64 s.
  - **Cautionary example** (`run_01_20260609T135313.csv`, operator shadow mid-run): std **0.013 mm** (still passes noise spec) but **20% `ARM_LOST`** — fix lighting and stay out of the light path before discharge experiments.
5. **Impulse:** discharge experiment; MARK events in CSV; raw column shows sharp step, filtered lags slightly.

