# Arm direction (sign)

The tracker reports deflection as signed millimetres along the image `xRel` axis
(arm marker minus reference fiducial, minus the zero offset). Whether an increase
in `xRel` should count as **+θ** or **−θ** depends on how the phone is mounted.

## Nudge calibration

During the first calibration (after optional arm length), the app asks you to
nudge the arm to the right. [SignNudgeDetector](../app/src/main/java/dev/qi/torsionbalance/vision/SignNudgeDetector.kt)
watches `xRel` from a fixed rest position: a sustained move past a pixel threshold
sets `sign_multiplier` to `+1` or `-1`.

## Persistence

The detected sign is written to DataStore (`sign_multiplier` + `sign_configured`)
as soon as a nudge completes, and again whenever you recalibrate or pick a value
in Settings. While the phone stays in the same fixed mount:

- Later calibration runs **skip** the nudge and keep the saved sign.
- **Record** starts immediately — you can leave an overnight-settled beam untouched.

Legacy installs that already finished calibration before `sign_configured` existed
are treated as configured (the nudge had already run).

## Recalibrate / manual

- Live UI: `Direction: saved (+1) – Recalibrate` (source is `saved`, `manual`, or `nudged`).
- **Recalibrate direction** clears the saved flag and re-enters the nudge step.
- Settings → **+1** / **−1** sets the sign without a physical nudge and persists
  immediately on tap (same rule as the Trigger LED enable switch).

## CSV metadata

Every recording writes two preamble lines before the column header:

```
# sign_multiplier=+1
# sign_source=saved
```

| `sign_source` | Meaning |
|---------------|---------|
| `saved` | Loaded from DataStore (prior session) |
| `manual` | Chosen in Settings this session |
| `nudged` | Detected by the nudge this session |

Lines are comma-free. Prefer `pandas.read_csv(..., comment='#')`, or skip lines
until the `timestamp_ms,...` header.
