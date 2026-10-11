# Marker tracking

The app tracks two dark circular markers in the analysis Y-plane: the moving **arm**
dot and a fixed **reference** fiducial. Displacement is the signed difference of their
image `x` positions (converted with mm/px and the arm-direction sign).

## Detector pipeline

Each seeded marker is searched in a grayscale ROI centred on its **last known**
position (not the calibration tap seed after the first lock):

1. Gaussian blur (3×3) → Otsu threshold (`THRESH_BINARY_INV`) on an ROI centred on
   the last known position. Half-extent is at least
   `max(60, 2·√(expectedArea/π) + jumpGate)` on **both** axes so large markers are
   not border-clipped at `missFrames=0`. On consecutive misses the ROI widens by
   20 px/miss (up to ±200 px).
2. External contours → candidates filtered by blob area min/max, **adaptive expected
   area** (±60% of a slow EMA updated only on accepted hits), circularity, and
   dark-on-light contrast (background = ROI mean **excluding** the blob).
3. Contours whose bounding box **touches the ROI border** are rejected (stops
   false locks on edge-clipped shadows ~200 px away).
4. Per-frame **jump gate**: reject a hit farther than
   `baseMax + missFrames × 20` from the last good centre (defaults: **20 px** arm,
   **3 px** reference). The gate is **dropped only after LOST** (miss streak ≥ N);
   a single miss does not open the full ROI.
5. Remaining candidates are scored (distance, area match, circularity, contrast);
   the best score wins. Blob/background means are computed only for shortlisted
   candidates.
6. All OpenCV contours / temporary mats are released every frame.

## Hysteresis and flags

| Flag | Meaning |
|------|---------|
| `OK` | Both markers detected this frame |
| `HELD` | One or both missed, but the miss streak is still below the lost threshold. Last good position is held; displacement is left blank so analysis can mask the row |
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
arm_roi_mean,ref_roi_mean,arm_candidates,ref_candidates,arm_match_score,ref_match_score
```

| Column | Meaning |
|--------|---------|
| `arm_blob_area` / `ref_blob_area` | Accepted contour area (px²); empty on a miss |
| `arm_threshold` / `ref_threshold` | Otsu threshold used in that marker’s ROI |
| `arm_roi_mean` / `ref_roi_mean` | Mean luma of the search ROI |
| `arm_candidates` / `ref_candidates` | Contours in area range before scoring (includes border-clipped) |
| `arm_match_score` / `ref_match_score` | Best candidate score in `[0,1]`-ish; empty on a miss |

Sample rows with `flags=HELD` still write the held `x_*_px` / `y_*_px` so you can
plot continuity; treat `displacement_mm_*` as missing on those rows.
