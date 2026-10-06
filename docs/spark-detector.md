# Spark-Gap Discharge Detector (legacy)

> **Legacy hardware.** The current setup lights a trigger LED for as long as the spark
> button is held; the app logs on/off duration (see [trigger-led.md](trigger-led.md)).
> This page describes an older 555 monostable that stretched each RF-detected discharge
> into a ~110 ms pulse so the camera could mark *individual* sparks.

Generates a ~110 ms LED flash for each spark-gap discharge, visible to the torsion-balance camera.
A historical app feature wrote a timestamped `event` row (`SYNC` / `auto_spark`) for each pulse.
That per-flash MARK path has been replaced by LED on/off logging.

## Why not audio?

The spark gap operates inside a vacuum chamber at ~10⁻³ mbar. Gas density is roughly a million
times lower than atmospheric, so essentially no acoustic energy is radiated. Structure-borne
vibration through the mounts would be buried in roughing-pump noise. Optical flash detection
without a dedicated circuit is unreliable because the spark lasts microseconds and may fall between
camera frames. A spark gap is, however, a broadband RF emitter — easy to detect electrically.

## Principle

```
Antenna picks up discharge EMP
  → envelope detector (D1, R1, C1)
  → Q1 NPN pulls 555 TRIG pin low
  → 555 monostable stretches event to ~110 ms
  → LED visible in camera frame
```

Powered by a 5 V USB power bank. Fully isolated from both the HV circuit and the phone.

## Schematic

```
                          VCC (+5 V)
        ┌──────────┬──────────────┬──────────────┐
        │          │              │              │
        │        R2 10k    ┌──────┴──────┐     C4 100n
        │          │       │ 4       8   │       │
ANT     │          ├───────┤ 2(TRIG)     │      GND
10-20cm │          │       │             │
wire    │     Q1 C─┘       │   NE555     ├── R3 100k ──┐
  │     │   2N3904         │ (monostable)│             │
  ├─D1──┴─B               │             ├────┬────────┤
  │ 1N4148  E             │  3(OUT) ────┼─R5─┤ 6,7   │
 R1 1M   │  │             │  1      5   │330R │     C2 1µF
  │     C1 1n │            └──┬─────┬───┘     ▼ LED  │
 GND    │  GND              GND  C3 10n        │      │
       GND                        │           GND    GND
                                 GND
```

## Component list

| Ref | Value | Note |
|-----|-------|------|
| ANT | 10–20 cm stiff wire | Solid-core hookup wire works; route it toward the discharge wiring or GDT, outside the vacuum chamber is fine |
| D1 | 1N4148 | Signal diode, envelope detector |
| R1 | 1 MΩ | Base bias / RF integration; raise toward 10 MΩ for higher sensitivity |
| C1 | 1 nF | RF bypass; with R1 forms a simple peak detector |
| Q1 | 2N3904 | NPN; emitter → GND, collector → 555 pin 2, base via D1 |
| R2 | 10 kΩ | Pull-up on 555 TRIG (pin 2) to VCC |
| NE555 | any CMOS variant | TLC555 / LMC555 preferred for 5 V operation; pin 4 (RESET) tied to VCC |
| R3 | 100 kΩ | Timing resistor (pins 6/7) → t ≈ 1.1 × R3 × C2 ≈ 110 ms |
| C2 | 1 µF | Timing capacitor (pin 6 to GND) |
| C3 | 10 nF | Control voltage bypass (pin 5 to GND) |
| C4 | 100 nF | VCC decoupling (pins 8/4 to GND, close to IC) |
| LED | Bright diffused, any colour | White or green maximises luma delta; diffuse cap avoids overexposure |
| R5 | 330 Ω | LED current limit at 5 V: (5 − 2) / 330 ≈ 9 mA |

## Operation

1. Place the antenna wire within ~1 m of the spark-gap discharge path (or wrap 5–10 turns
   around the discharge ground-return lead for a more selective current-transformer pickup).
2. Mount the LED in the camera field of view, away from both tracking markers, in a corner.
   Dim it with a layer of translucent tape if it overexposes or blooms into adjacent ROIs.
   The camera AE is locked by the app so the flash will not disturb exposure.
3. The 555 output goes high for ≈ 110 ms after each discharge — 3+ frames at 30 fps,
   so the camera cannot miss it.

## Tuning

**Pulse width:** Change R3 or C2 to adjust: t ≈ 1.1 × R × C.
Use ≥ 100 ms (3 frames at 30 fps) to guarantee capture.

**Sensitivity:** Increase R1 (up to ~10 MΩ) or lengthen the antenna for more gain.
Decrease R1 or shorten the antenna if nearby equipment causes false triggers.

**Non-retriggerable:** The standard 555 monostable ignores new triggers during the output pulse.
Two discharges separated by less than the pulse width (110 ms) produce one MARK event —
at 30 fps video they would be indistinguishable anyway. If you need to count rapid double-fires,
shorten the pulse width (R3/C2) and note the limitation in your experiment log.

**False trigger test:** Before a real run, hold the antenna near relay clicks, motor switches,
and any other equipment in the lab. If the LED fires, reduce sensitivity or switch to the
current-transformer pickup.

## Alternative pickup (current transformer)

Wind 5–10 turns of insulated wire around the discharge ground-return conductor.
Feed the output through a 1 kΩ resistor into the D1/base node, and add back-to-back
1N4148 diodes from base to GND as clamps. This is more selective and immune to ambient RF.

## PCB note

This circuit can be built on a breadboard or perfboard. If a proper PCB is desired,
run it through the repo's SKiDL pipeline (`/new-circuit` in the Claude Code session).
