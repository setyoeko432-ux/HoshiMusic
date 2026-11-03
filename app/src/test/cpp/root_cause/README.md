# HOSHIMUSIC NATIVE DSP ROOT CAUSE DIAGNOSTIC SUITE

## Overview

This dedicated diagnostic suite is built specifically to investigate and verify potential root causes for Native DSP preset behavior in HoshiMusic.

The suite is **DIAGNOSTIC ONLY** — it measures, audits, and reports actual DSP pipeline behavior without altering production DSP code or tuning parameters.

---

## Test Suite Architecture

| Test | Category | Target Component | Diagnostic Scope |
|---|---|---|---|
| **Test A** | JNI Buffer Offset & Memory Boundaries | `DspNativeEngine.cpp` & `HoshiDspProcessor.kt` | Validates `GetDirectBufferAddress()` behavior, explicit byte offsets, and sentinel memory boundaries (`prefix` and `suffix`). |
| **Test B** | DSP Master Enable & Passthrough | `HoshiDspProcessor.kt` & `DspEngine` | Audits `dspEnabled`, `adaptiveDspEnabled`, `curAdspWet`, and verifies output signal deltas between Active and Bypass states. |
| **Test C** | Preset Preview Override & Resolution | `DspParamResolver.kt` & `DspPreviewBridge` | Tests parameter array resolution across preset switches, ensuring stale previews do not override newly selected presets. |
| **Test D** | AGC Interference Diagnostic | `DspEngine` AGC Chain | Measures input vs output RMS, gain reduction, and pre-gain across input levels (`-30 dBFS`, `-20 dBFS`, `-14 dBFS`, `-6 dBFS`). |
| **Test E** | Transient & Body Directionality | `DspEngine` Mid/Side Transient | Sweeps `TRANSIENT`, `PUNCH`, and `BODY` from `0.0` to `2.0` on impulse bursts to measure attack/sustain envelopes and crest factors. |
| **Test F** | Buffer Size & Convergence | `DspEngine` Adaptive Loop | Compares convergence times across block sizes (`512`, `1024`, `2048`, `4096` frames) over identical audio durations. |
| **Test G** | Parameter Sensitivity Matrix | `DspEngine` All 15 Parameters | Performs 1-by-1 parameter sweeps for all 15 parameters (`0.5`, `1.0`, `1.8`) to measure individual output RMS and peak deltas. |
| **Test H** | Builtin Preset Differentiation | `DspPresets.BUILTINS` | Renders all 18 builtin presets through `DspEngine` and calculates pairwise output RMS deltas and band energy profiles. |

---

## Suite Components & File Locations

1. **Native Diagnostic Harness**:
   `app/src/test/cpp/root_cause/DspRootCauseHarness.cpp`
2. **Native Diagnostic Runner**:
   `app/src/test/cpp/root_cause/run_root_cause_harness.js`
3. **Android JNI Instrumentation Test**:
   `app/src/androidTest/java/com/naomi/hoshimusic/playback/DspRootCauseInstrumentedTest.kt`
4. **Kotlin Configuration & Resolver Test**:
   `app/src/test/java/com/naomi/hoshimusic/playback/dsp/DspRootCauseResolverTest.kt`
5. **Report Output Directory**:
   `app/src/test/cpp/root_cause/reports/`

---

## Execution Commands

### 1. Run Native C++ Diagnostic Suite via Node.js / NDK:
```bash
node app/src/test/cpp/root_cause/run_root_cause_harness.js
```

### 2. Run Kotlin JVM Unit Tests (Configuration & Resolver Suite):
```bash
./gradlew app:testDebugUnitTest
```

### 3. Run Android Instrumented JNI Tests (Device or Emulator required):
```bash
./gradlew app:connectedAndroidTest
```

---

## Report Formats & Output CSVs

After running `run_root_cause_harness.js`, detailed diagnostic files are generated in `app/src/test/cpp/root_cause/reports/`:

- `root-cause-report.json`: Full diagnostic JSON payload containing all raw test outputs.
- `jni-buffer-validation.csv`: Sentinel boundary and buffer offset test results.
- `agc-interference.csv`: AGC pre-gain, slow RMS, and gain reduction across input levels.
- `buffer-convergence.csv`: Convergence levels for 512, 1024, 2048, and 4096 block sizes.
- `parameter-sensitivity.csv`: RMS and peak output deltas for all 15 parameters.
- `preset-differentiation.csv`: Output RMS deltas vs Reference baseline for all 18 builtin presets.

---

## Status Definitions

- **PASS**: The diagnostic test executed and confirmed the component operates safely according to specifications.
- **FAIL**: The diagnostic test proved a defect or regression is active in the component.
- **WARNING**: The test identified potential sub-optimal behavior or slight interference that requires attention.
- **INCONCLUSIVE**: The test metrics did not yield a definitive pass or fail condition.
- **NOT TESTED**: The test could not be executed (e.g. required physical Android hardware was unavailable).
