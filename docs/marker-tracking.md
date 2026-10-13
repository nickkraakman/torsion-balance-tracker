# Marker tracking

The app tracks two dark circular markers in the analysis Y-plane: the moving **arm**
dot and a fixed **reference** fiducial. Displacement is the signed difference of their
image `x` positions (converted with mm/px and the arm-direction sign).

## Detector pipeline

Each seeded marker is searched in a grayscale ROI centred on its **last known**
position (not the calibration tap seed after the first lock):

1. Gaussian blur (3×3) → binarize (`THRESH_BINARY_INV`) on an ROI centred on the
   last known position, using that marker's **learned contrast threshold** (see
   below). Otsu is used only until the threshold has been learned, during a lighting
   event, or as a fallback when the learned threshold finds nothing. Half-extent is at
   least `max(60, 2·√(expectedArea/π) + jumpGate)` on **both** axes so large markers
   are not border-clipped at `missFrames=0`. On consecutive misses the **arm** ROI
   widens by 20 px/miss (up to ±200 px); the reference ROI never widens.
2. External contours → candidates filtered by blob area min/max, **adaptive expected
   area** (±60% of a slow EMA updated only on accepted hits outside lighting events),
   circularity, and dark-on-light contrast against the **local background**: a ring
   around the blob (blob grown by 2 px, out to about one blob radius beyond that), so
   dark structure elsewhere in the ROI does not count as background.
3. Contours whose bounding box **touches the ROI border** are rejected (stops
   false locks on edge-clipped shadows ~200 px away).
4. Per-frame **jump gate** from the last good centre:
   - **Arm** (default **20 px**): `baseMax + missFrames × 20`, dropped only after LOST
     (miss streak ≥ N); a single miss does not open the full ROI.
   - **Reference** (default **3 px**): the fiducial is fixed to the chamber, so its gate
     is **never dropped**. After any miss, including LOST, it is re-searched within
     `max(refJump, 6 px)` of its last position. A camera bump that moves the image
     further than that needs a re-calibration (tap the reference again).
5. Remaining candidates are scored (distance, area match, circularity, contrast);
   the best score wins. Blob/background means are computed only for shortlisted
   candidates.
6. All OpenCV contours / temporary mats are released every frame.

## Contrast threshold

Each marker keeps a slow model of its blob luma and its local-background luma (EMA,
α = 0.02 after a 15-sample warm-up). The binarization threshold is their midpoint.
It does **not** follow the ROI's mean brightness, and it has **hysteresis**: the
applied threshold only moves once the midpoint has drifted 1.5 luma, so the same
threshold is used frame after frame and does not add centroid jitter. Slow drift
(dusk) is followed in 1.5-luma steps.

If the learned threshold finds nothing, the frame is retried with Otsu; a hit is
accepted (still gated) and re-learns the levels quickly (α = 0.5).

## Lighting events

The tracker keeps a short EMA (α = 0.05) of the **whole-frame** mean luma. A frame
that departs from it by more than `max(4, 6 % of baseline)` luma starts a
**lighting event** (screen dimming on its timeout, a room light, AE stepping). While
the event lasts:

- every row is `HELD` (displacement blank, `lighting_event=1`), even if both markers
  were found;
- misses do **not** count toward LOST, and gates and ROIs do not grow, so the
  tracker cannot re-acquire onto an unrelated blob;
- each marker's threshold is re-learned from Otsu hits near its last position.

The event ends once the frame mean has stayed within 1.5 luma for 10 frames (or
after 150 frames, so a flickering light cannot hold tracking forever); the baseline
then jumps to the new level. Slow drift such as dusk follows the EMA and never
fires an event.

The app sets `FLAG_KEEP_SCREEN_ON` while recording so the display timeout cannot
dim the scene mid-run.

## Hysteresis and flags

| Flag | Meaning |
|------|---------|
| `OK` | Both markers detected this frame |
| `HELD` | One or both missed, but the miss streak is still below the lost threshold, **or** a lighting event is in progress (`lighting_event=1`). Last good (or re-found) position is written; displacement is left blank so analysis can mask the row |
| `ARM_LOST` / `REF_LOST` / `BOTH_LOST` | Miss streak reached the threshold (default **10** frames, Settings → tracking robustness) |

Earlier builds flagged `ARM_LOST` on the first failed frame (overlapping `when`
branches made the 5-frame counter ineffective) and snapped the ROI back to the
calibration seed after five misses. That behaviour is gone.

## Camera lock

**Lock Camera** meters AE/AF on the **arm marker** region (not frame centre), then
stores manual exposure time, ISO, frame duration, and focus distance in DataStore
with the rest of the calibration. AWB is locked only after that session has metered
(or after a short warmup on restore) so boot white-balance gains are not frozen.
On app restart AE/ISO/focus are **restored** — the session does not re-sample AE
at frame centre.

## CSV diagnostics

New columns are **appended** so older parsers that ignore extras keep working:

```
…,frame_index,led_on,arm_blob_area,ref_blob_area,arm_threshold,ref_threshold,
arm_roi_mean,ref_roi_mean,arm_candidates,ref_candidates,arm_match_score,ref_match_score,
exposure_time_ns,iso,ae_mode,ae_lock,awb_lock,frame_mean,lighting_event
```

| Column | Meaning |
|--------|---------|
| `arm_blob_area` / `ref_blob_area` | Accepted contour area (px²); empty on a miss |
| `arm_threshold` / `ref_threshold` | Binarization threshold used in that marker's ROI this frame (learned contrast threshold, or Otsu while learning / on fallback) |
| `arm_roi_mean` / `ref_roi_mean` | Local background luma (ring around the accepted blob); ROI mean on a miss |
| `arm_candidates` / `ref_candidates` | Contours in area range before scoring (includes border-clipped) |
| `arm_match_score` / `ref_match_score` | Best candidate score in `[0,1]`-ish; empty on a miss |
| `exposure_time_ns` / `iso` | Actual sensor exposure time and sensitivity from the Camera2 capture result of that frame |
| `ae_mode` | `off` = manual exposure (the camera lock; exposure and ISO are fixed), `on` = auto exposure, etc. |
| `ae_lock` / `awb_lock` | `1` / `0` as reported by the capture result |
| `frame_mean` | Whole-frame mean luma (input to lighting-event detection) |
| `lighting_event` | `1` while a lighting event is in progress (row is `HELD`) |

Camera columns are matched to the frame by sensor timestamp (nearest result within one
frame period); they are empty if the device reported nothing close.

Sample rows with `flags=HELD` still write the held `x_*_px` / `y_*_px` so you can
plot continuity; treat `displacement_mm_*` as missing on those rows.
