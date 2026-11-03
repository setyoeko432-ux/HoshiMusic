---
name: hoshimusic-engineering
description: Repository-specific engineering guidance for understanding, modifying, debugging, testing, and documenting HoshiMusic. Use whenever an AI agent works on this repository, especially Kotlin/Compose UI, Media3 playback, JNI/C++ DSP, audio routing, lyrics/romanization, Room persistence, privacy, diagnostics, or Gradle/NDK builds.
---

# HoshiMusic Engineering Skill

## 1. Mission

Maintain HoshiMusic as a stable, private, local-first Android music player. Prefer the smallest coherent, reversible, verifiable change that solves the exact request.

The agent must:

- Inspect the current repository before proposing or editing code.
- Preserve existing user-visible behavior outside the requested scope.
- Treat audio correctness, privacy, data preservation, and playback stability as product requirements.
- Follow existing architecture and conventions instead of replacing them with generic Android templates.
- Verify behavior from source, tests, build output, and real-device evidence as appropriate.
- State uncertainty explicitly. Never turn an assumption into a fact or a planned test into a passed test.
- Avoid unrelated cleanup, modernization, dependency changes, or platform expansion.
- Keep changes understandable and easy to review or revert.

This skill supplements the repository's guardrail.md; it does not override it.

## 2. Mandatory repository preflight

Before any code edit, build-system edit, test execution that may download tools, database change, manifest/permission change, or architecture change:

1. Read guardrail.md in full. Do not edit it unless the user explicitly asks.
2. Inspect git status --short and the current branch. Never overwrite or discard pre-existing user changes.
3. Read relevant parts of README.md, CHANGELOG.md, module build files, and source call sites.
4. Check the current implementation, not just names or comments. Trace callers, lifecycle, manifest registration, serialization, JNI, generated-code, and test consumers where relevant.
5. Identify the smallest scope and the evidence needed to validate it.
6. Check whether the task crosses protected areas or requires user approval.

For tasks involving changes, state this preflight before acting:

~~~text
Scope:
Platform scope:
Files to inspect:
Files expected to change:
Protected areas that will remain untouched:
Build/dependency/database/permission/platform-target changes required: yes/no
Downloads or installations required: yes/no
Stop conditions:
~~~

Do not ask for approval for ordinary in-scope source edits. Do request explicit approval before any protected or approval-gated change described in guardrail.md.

## 3. Repository facts and current configuration

The following facts were verified in the repository's default branch while preparing this skill. They are a snapshot, not permanent constants. Re-check the files before every task because the project evolves.

### Product identity

- Product: Hoshi Music / HoshiMusic.
- Application ID and namespace: **com.naomi.hoshimusic**.
- Android minimum SDK: API 26.
- Main implementation: Kotlin, Jetpack Compose / Material 3, AndroidX Media3, Room, DataStore, WorkManager, JNI, C++.
- The application is designed to work locally and offline. It does not require an account or runtime network connection.
- Audio processing uses native 32-bit floating-point DSP internally, but do not infer that the entire Android output route is bit-perfect or lossless.

### Modules and supported platform

- **:app** is the Android application.
- **:shared** is a Kotlin Multiplatform library module.
- The checked-in shared module currently declares commonMain and androidMain and an Android target only.
- KMP and Compose Multiplatform plugin declarations do not authorize adding desktop, JVM, iOS, JavaScript, WebAssembly, or other targets.
- HoshiMusic is an Android application. Treat Android as the only supported platform unless the user explicitly requests a named platform expansion and approves the required architecture/build changes.

### Build snapshot observed in checked-in files

- Gradle wrapper: 9.6.0.
- Android Gradle Plugin: 9.3.0.
- Kotlin: 2.2.10.
- Compose Multiplatform: 1.9.3.
- KSP: 2.3.12.
- Gradle/JVM toolchain and Java compatibility: 17.
- App minSdk: 26.
- App compileSdk: 37.
- App targetSdk: 36.
- NDK: 26.1.10909125.
- CMake: 3.22.1.
- App version in the inspected app module: 1.4.7, versionCode 22.
- Media3: 1.11.1.
- Room: 2.8.5.
- Room database schema version: 13.

Confirm these from gradle/wrapper/gradle-wrapper.properties, gradle/libs.versions.toml, app/build.gradle.kts, shared/build.gradle.kts, gradle.properties, and the changelog before relying on them. Do not copy this list into build scripts as a replacement template.

### Documentation drift that must not be ignored

At the time this skill was prepared, the repository documentation is inconsistent:

- README.md still describes version 1.4.6 / versionCode 21 and compile/target SDK 35.
- guardrail.md Section 2 still lists an older toolchain baseline: Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose Multiplatform 1.7.0, and KSP 2.0.21-1.0.28.
- The checked-in wrapper and module build files show the newer configuration listed above.
- CHANGELOG.md documents the 1.4.7 toolchain transition and its rationale.

Do not silently downgrade or upgrade anything to reconcile these documents. For ordinary implementation work, preserve the actual checked-in build configuration. For any build-system modification, explicitly report this documentation conflict and request the user's decision about the intended baseline before changing versions. guardrail.md remains mandatory for its privacy, safety, protected-area, approval, and validation rules; do not edit it as part of an unrelated task.

## 4. Project map

Use the repository tree as the source of truth. Important areas include:

~~~text
app/
  src/
    main/
      AndroidManifest.xml
      cpp/
        CMakeLists.txt
        hoshi_dsp_core.cpp
        hoshi_dsp_core.h
        hoshi_dsp_tuning.h
      java/com/naomi/hoshimusic/
        data/              Library, metadata, lyrics, repositories
        data/db/           Room entities, DAOs, database, migrations
        data/prefs/        Preference and DSP preference models
        playback/          Media3 service, audio processor, sink and routing
        playback/dsp/      Presets, JNI bridge, runtime config and telemetry
        ui/                Compose screens, navigation and settings
        util/              Romanization, logging and format helpers
      res/
    debug/
      AndroidManifest.xml
      java/.../diag/       Debug-only diagnostics and trace tooling
    test/
      cpp/                 Native harnesses
      java/                JVM/Robolectric/unit tests
    androidTest/
      java/                Device/emulator instrumentation tests
  schemas/                 Exported Room schema snapshots
shared/
  src/commonMain/          Platform-agnostic declarations and shared models
  src/androidMain/         Android actual implementations
docs/                      Project documentation
guardrail.md               Mandatory project-specific agent guardrails
README.md
CHANGELOG.md
~~~

The exact directory list can change. Do not assume a source path exists until verified.

## 5. Architecture and ownership boundaries

### Playback path

The principal Android playback path is built around:

- MusicPlaybackService as the MediaSessionService owner.
- Media3 ExoPlayer and MediaSession for playback/session behavior.
- HoshiDspProcessor as the Media3 AudioProcessor adapter.
- DspRuntimeCoordinator and related config/repository classes for runtime DSP state.
- DspNativeEngine as the Kotlin/JNI handle and native-call boundary.
- app/src/main/cpp/hoshi_dsp_core.cpp and its headers as the native DSP engine.
- HoshiAudioSink, route monitors, and routing managers for sink/route behavior.
- PCM format conversion code in the DSP playback package where required by the actual negotiated pipeline.

Trace the live wiring before changing any component. A class existing in the repository does not prove that it is instantiated, attached to ExoPlayer, or active for a given playback route.

### Data and UI path

Typical ownership:

- MediaStore and SAF are the platform-facing sources for local media access.
- Repository and mapper classes own media discovery and mapping.
- Room owns persisted library, lyrics, translation, and analysis data.
- DataStore-backed preference classes own application preferences.
- ViewModels coordinate state for Compose UI.
- Compose UI displays state and dispatches user actions; it should not become a second persistence or playback engine.

Preserve established ownership. Do not add direct database, filesystem, JNI, or ExoPlayer work to a Composable merely to shorten a call chain.

### Shared module

commonMain must remain platform-neutral. Android APIs, MediaStore, Android Context, Media3, JNI, native libraries, and Android storage APIs belong in Android-specific code. Do not move platform-bound logic to commonMain to prepare for hypothetical platforms.

When changing an expect declaration, inspect every corresponding actual implementation and call site. The current shared module is not evidence of a finished cross-platform player.

## 6. Mandatory guardrails and protected functionality

guardrail.md is the controlling policy for prohibited changes and approval requirements. In particular, do not do any of the following without explicit, task-specific user approval:

- Add or change permissions, especially network permissions.
- Add accounts, cloud synchronization, analytics, ads, online crash reporting, or runtime network APIs.
- Add, remove, upgrade, or downgrade build plugins, dependencies, Gradle, AGP, Kotlin, Compose, KSP, SDK, NDK, CMake, or Java toolchains.
- Add another platform target or new source set.
- Change the application ID, signing identity, or release-key handling.
- Change Room schema/version or user-visible data formats.
- Change the :app / :shared ownership boundary.
- Modify Sleep Timer or seekBy / Seek ±10 seconds behavior.
- Delete or reset user data, playlists, favorites, queues, statistics, metadata, or preferences.
- Use fallbackToDestructiveMigration() to bypass a missing Room migration.
- Run destructive Git commands, rewrite history, force-push, mass-delete, or discard existing user changes.
- Print or commit secrets, signing material, passwords, tokens, API keys, or machine credentials.

A broad request such as "fix everything", "modernize", or "make it build" is not blanket authorization to change these areas.

If a request requires an approval-gated change, explain the exact reason, files, impact, alternatives, and validation plan. Stop until the user approves that specific change.

## 7. Privacy, permissions, and local-first behavior

Privacy is a core product property.

- Keep playback, metadata, lyrics, playlists, preferences, analysis, and diagnostics on-device.
- Do not introduce INTERNET or ACCESS_NETWORK_STATE permissions or network clients.
- Do not send filenames, metadata, lyrics, listening history, artwork, device names, route details, or diagnostics to any external service.
- Do not add analytics, telemetry upload, accounts, ads, cloud backup, or synchronization.
- Preserve the main manifest's android:allowBackup="false" and the existing backup/data-extraction protections unless the user explicitly requests a reviewed change.
- Request only the storage/media/Bluetooth/foreground-service/notification permissions actually needed by the feature and supported Android version.
- Keep optional permissions optional; denial must not disable unrelated local playback.
- Prefer MediaStore and Storage Access Framework flows already used by the app. Persist SAF permissions only when the app needs continued access.
- Do not log private user content. Diagnostic output should favor state, counts, error categories, sample rates, buffer sizes, and bounded technical values rather than full filenames or lyric text.

The checked-in main manifest has no INTERNET or ACCESS_NETWORK_STATE permission. Preserve this invariant and run the privacy tests when changing manifests or data flows.

## 8. Media3 and real-time audio engineering rules

Audio callback code is time-sensitive. A correctness fix that blocks the playback thread is not an acceptable fix.

### AudioProcessor lifecycle

Before editing HoshiDspProcessor or a Media3 audio component, inspect the exact Media3 version used by the project and its source/API contract. The current checked-in dependency is Media3 1.11.1; do not apply behavior from a different version without verification.

Respect the existing invariants documented in HoshiDspProcessor:

- Only advertise an output format the processor can actually produce.
- If a format cannot be processed, return the correct inactive / NOT_SET negotiation result.
- Keep isActive() consistent with negotiated format and Media3 lifecycle.
- Ensure every input buffer is either processed and made available as output or intentionally handled according to the processor contract. Do not silently drop a pending output.
- Do not change the output encoding or channel layout unless the entire downstream sink and conversion path supports it.
- Do not make the active state oscillate during playback. Lifecycle transitions belong to the correct configure/reset/flush boundaries.
- Inspect flush, reset, release, reconfigure, seek, track transition, and player recreation paths.

Never "fix" silence, buffering, or stalls by merely forcing isActive, claiming success on configure, swallowing errors, or bypassing the pipeline without proving the resulting data path.

### PCM and native buffer safety

DspNativeEngine.process() uses a direct ByteBuffer and explicit byte offset/length for the native processing region. This distinction is intentional: the buffer's current position is not a substitute for the explicit native offset.

When changing buffer handling:

- Verify direct-buffer requirements and native address handling.
- Keep byte offsets and lengths explicit and validate bounds before entering JNI.
- Verify frame alignment, sample format, channel count, and byte count at every conversion boundary.
- For interleaved float32 stereo, one frame occupies 8 bytes. Confirm the actual buffer contract at the call site before applying this rule.
- Do not assume ByteBuffer.position() is the native processing offset.
- Do not read or write outside the designated region; preserve prefix/suffix sentinels in boundary tests.
- Validate behavior for zero-length input, odd or malformed lengths, unsupported formats, and buffer reconfiguration.
- Keep native handle creation, initialization, reset, flush, and release lifecycle-safe.
- Ensure native failure cannot leave a stale handle marked usable.
- Do not add unaligned casts or unsafe pointer arithmetic without proving alignment and bounds.

### Real-time constraints

Inside queueInput(), native process loops, or per-buffer/per-frame callbacks:

- Do not perform filesystem I/O, network access, logging, blocking waits, sleeps, or UI work.
- Do not allocate large objects, collections, strings, or temporary arrays on every buffer.
- Avoid contended locks and synchronous cross-thread calls.
- Preallocate and reuse state where practical.
- Keep diagnostics off the audio hot path. If a measurement is essential, use bounded, low-overhead counters or sampling and report its overhead.
- Do not let a diagnostic queue block an audio callback.
- Check for finite values, valid sample rates, safe parameter ranges, and graceful bypass behavior.
- Measure worst-case processing time on target devices; average desktop/native harness time is not sufficient.

### Filter and floating-point correctness

The native engine uses IIR / TPT-SVF filter structures and is sensitive to floating-point transformations.

- Do not reintroduce global -ffast-math into CMakeLists.txt.
- Do not casually change reassociation, denormal, signed-zero, NaN/Inf, or finite-math behavior.
- Keep selective compiler optimizations local and justified by tests.
- Test coefficient updates at sample-rate changes and processor reinitialization.
- Verify smoothing and state-reset behavior to avoid clicks, unstable filters, or stale coefficients.
- Treat clipping, denormals, NaN/Inf, and non-finite filter state as correctness failures to investigate, not as values to hide.

## 9. DSP parameter and preset contract

The Kotlin preset layer currently defines 15 parameter slots in DspPresets. The index mapping is a cross-language contract with the C++ engine. The inspected mapping is:

| Index | Kotlin constant | Meaning |
|---:|---|---|
| 0 | IDX_BASS | Virtual bass and low body |
| 1 | IDX_VOCAL | Vocal presence / consonant shaping |
| 2 | IDX_AIR | High-frequency air shelf |
| 3 | IDX_WARMTH | Warmth / mud / low-mid shaping |
| 4 | IDX_EXCITER | Harmonic exciter |
| 5 | IDX_TRANSIENT | Transient shaping |
| 6 | IDX_WIDTH | Stereo width |
| 7 | IDX_DEESSER | De-esser sensitivity |
| 8 | IDX_CROSSFEED | Crossfeed |
| 9 | IDX_AGC | AGC behavior |
| 10 | IDX_LIMITER | Limiter ceiling |
| 11 | IDX_LOUDNESS | Loudness compensation |
| 12 | IDX_PUNCH | Punch / attack shaping |
| 13 | IDX_BODY | Sustain / body shaping |
| 14 | IDX_SATURATE | Saturation |

Treat this table as a navigation aid. Before any parameter change, verify current Kotlin constants, default arrays, custom preset serialization, runtime resolver, JNI parameter transfer, C++ PARAM_COUNT, native index use, clamping, and tests.

When adding, removing, reordering, or redefining a parameter, update every contract participant in one coherent change. Do not change an index in only Kotlin or only C++.

### Preset distinctness

A different preset label or parameter array does not prove an audible difference.

For a "presets sound the same" report:

1. Compare the actual selected preset ID and effective parameter array.
2. Trace the array through preference/config resolution and DspRuntimeCoordinator.
3. Verify the processor receives the intended values.
4. Verify JNI forwards all slots and native code consumes each relevant slot.
5. Verify the true wet/dry gate and adaptive-enable state; telemetry showing changed parameter values alone is insufficient.
6. Confirm the changed parameter affects the processed samples and is not overwritten, clamped to neutrality, bypassed, or masked by a later stage.
7. Compare deterministic outputs using suitable test signals and level-matched comparisons.
8. Verify on a real device and listening route after synthetic tests pass.

Use objective measures such as RMS, peak, crest factor, band energy, spectral difference, null residual, and per-preset output hashes where they answer the specific question. Do not equate a numerical difference with a perceptually meaningful improvement. Do not use unlevel-matched loudness as proof that a preset is better.

## 10. DSP tests and diagnostics

Inspect and reuse the existing tests before creating a duplicate harness.

Important existing test/harness areas include:

- app/src/test/cpp/DspNativeHarness.cpp
- app/src/test/cpp/root_cause/DspRootCauseHarness.cpp
- app/src/test/cpp/root_cause/DspRealtimeArtifactHarness.cpp
- app/src/test/java/.../HoshiDspTest.kt
- app/src/test/java/.../playback/dsp/DspContractTest.kt
- app/src/test/java/.../playback/dsp/DspPresetTest.kt
- app/src/test/java/.../playback/dsp/DspRealtimeArtifactTest.kt
- app/src/test/java/.../playback/dsp/PcmPipelineForensicTest.kt
- app/src/androidTest/java/.../playback/DspLifecycleTest.kt
- app/src/androidTest/java/.../playback/DspPresetEngineTest.kt
- app/src/androidTest/java/.../playback/DspRootCauseInstrumentedTest.kt
- app/src/androidTest/java/.../playback/HoshiAudioSinkTest.kt

These files cover different layers. Read their setup and assertions rather than assuming that a test name proves complete coverage.

### Diagnostics logger

The app includes DiagLog, which writes to the app's internal files/hoshi_diag.log and mirrors to logcat when available.

Important behavior observed in the implementation:

- The caller queues entries; a background writer performs disk I/O.
- The queue is bounded. Entries may be dropped when full.
- Log rotation is bounded.
- Diagnostic logging is intended not to crash the app.
- It must not be used as a reason to log every audio buffer.
- Device/OEM logcat behavior can differ; an empty logcat does not prove that a code path did not run.

The typical debug retrieval command is:

~~~powershell
adb shell run-as com.naomi.hoshimusic cat files/hoshi_diag.log
~~~

Use the installed package ID and confirm the debug build is installed. For a large log, pull or copy it to a controlled local test location rather than pasting unbounded output. Avoid collecting private song or lyric content.

### Debug-only probe and trace kit

There is debug-only instrumentation under app/src/debug, including the audio pipeline trace kit and a debug manifest receiver. The receiver is exported for the explicit purpose of allowing an ADB shell broadcast to reach the debug app.

- Keep diagnostic receivers, probe entry points, test controls, and their behavior in the debug source set unless the user explicitly approves a security-reviewed production feature.
- Never move debug-only exported components into src/main as a quick fix.
- Never treat an obscure broadcast action string as authentication.
- Inspect exactly what a probe reads, writes, and executes before invoking it.
- Debug probes must not modify user preferences, library data, playback state, or persistent user data unless the specific test requires it and the behavior is clearly isolated and reversible.
- A successful am broadcast result only proves broadcast delivery status; confirm the receiver ran by checking the expected diagnostic output.
- Debug-only diagnostics must not leak into release APK behavior.

## 11. Audio routing and claims

The app has Bluetooth and wired-route monitoring/management code. Android audio route behavior is OEM-, OS-, device-, codec-, and sink-dependent.

Always distinguish:

- A device's advertised capability from the route currently active.
- A codec reported by a capability query from the codec actually negotiated for this session.
- A sample rate requested by the player from the output sample rate actually used by AudioTrack / the HAL.
- A requested audio attribute from an effect actually bypassed by the OEM audio stack.
- A "bypass" mode from a verified end-to-end bypass.
- Float processing inside the DSP from bit-perfect delivery to the DAC.
- A successful build from real-device audio correctness.

Do not claim "bit-perfect", "lossless output", "Hi-Res", "USB exclusive", "hardware bypass", or a specific active Bluetooth codec unless the complete relevant output path has been verified with appropriate device evidence.

For Bluetooth crackle, silence, playback restarts, or route transitions, capture the negotiated input format, processor configuration, output format, buffer sizes, lifecycle events, route transitions, and device/OS details. Investigate format changes, frame alignment, partial sample carry-over, flush/reset handling, and sink recreation. Do not assume one cause without evidence.

## 12. Room, persistence, and user data

Room schema and user data are protected.

- Current inspected database version: 13. Re-check HoshiDatabase.kt and exported schemas before every database task.
- A schema change requires a deliberate version change, a complete migration path from every supported prior version, schema export updates, and migration tests.
- Preserve all existing migration steps; never replace them with destructive migration.
- Never call fallbackToDestructiveMigration() as a shortcut.
- Do not delete databases, preferences, playlists, queue state, favorites, lyrics corrections, or listening statistics to make a test pass.
- Use the existing test database and migration-test patterns.
- Instrumented migration tests are not equivalent to JVM unit tests. Report which one actually ran.
- Test upgrade behavior with representative existing database states where the migration affects user data.
- Consider foreign keys, indices, defaults, nullability, primary keys, and query compatibility.
- Keep schema JSON snapshots in sync with actual Room entities and migrations.

When modifying persisted preference keys or serialized values, inspect default values, old-value handling, migration/compatibility logic, reset behavior, UI presentation, and tests.

## 13. Local media, formats, metadata, playlists, and lyrics

### Local media

- Preserve MediaStore query behavior and content-URI handling.
- Do not replace scoped-storage or SAF flows with broad filesystem access.
- Keep URI permission persistence and release behavior correct.
- Do not assume file extensions prove the actual codec/container.
- Distinguish metadata parsed from a file from metadata inferred by heuristics.
- Preserve embedded artwork loading and avoid unnecessarily decoding large images on the main thread.

### Playlists

- Preserve M3U/M3U8 import/export semantics and SAF document handling.
- Do not silently rewrite or normalize user playlist paths in a destructive way.
- Test malformed files, missing entries, duplicate entries, Unicode paths, and permission loss as relevant.
- Keep queue persistence and playlist-file behavior distinct.

### Lyrics, romanization, and translation

- Keep LRC timestamps, offsets, line ordering, and cache invalidation correct.
- Treat SYLT parsing as binary/tag parsing, not ordinary LRC text parsing.
- Distinguish romanization from translation. Romanization changes script/transliteration; translation changes meaning/language.
- Preserve original lyrics and user translation corrections.
- Be careful with timestamp alignment, token segmentation, punctuation, mixed scripts, and languages with no whitespace word boundaries.
- Do not make language-detection guesses appear certain.
- Test empty, malformed, partial, multi-timestamp, Unicode, and mixed-language inputs.
- Avoid replacing a correction/workbench feature with automated output that overwrites user data.

## 14. Compose UI and interaction changes

- Follow existing Compose state ownership and ViewModel conventions.
- Use stable keys for lazy lists and preserve scroll/selection behavior.
- Keep long-running I/O, metadata parsing, romanization, database operations, and playback control off the main thread.
- Avoid creating a second source of truth for playback or preferences inside UI state.
- Respect dark/light/system and dynamic/custom theme behavior.
- Consider compact phone layouts, landscape, large screens, and the existing dual-pane behavior.
- Preserve accessibility semantics, meaningful labels, touch targets, and state descriptions.
- Avoid introducing expensive recomposition, repeated bitmap decoding, or unnecessary state collection.
- Do not modify protected Sleep Timer or seek behavior unless explicitly requested.
- Add/adjust Compose tests only where the repository's current test setup supports the behavior; otherwise describe device/manual validation needed.

## 15. Build and dependency workflow

Build-system files are approval-gated by guardrail.md. Before modifying any of these, explain the intended change and obtain explicit approval:

- settings.gradle.kts
- root or module build.gradle.kts
- gradle.properties
- gradle/wrapper/gradle-wrapper.properties
- gradle/libs.versions.toml
- ProGuard/R8 configuration
- CMakeLists.txt or NDK configuration

For build diagnosis:

1. Inspect wrapper, plugin catalog, module build files, local SDK/NDK/CMake availability, and the actual Gradle JVM.
2. Verify Java 17 before Gradle tasks.
3. If the Gradle JVM is not Java 17 or a required pinned component is missing, stop and report it. Do not auto-install, auto-download, hardcode a machine path, or upgrade the project as a workaround.
4. Do not trigger dependency/toolchain downloads without the user's intentional Sync/Build action.
5. Run the narrowest task that can expose the first root cause.
6. Fix one root cause at a time. Do not bury the first failure beneath unrelated changes.
7. Do not rewrite build files from a generic template.
8. Re-check the diff for unintended version, repository, plugin, target, permission, signing, or packaging changes.

gradle.properties currently disables automatic JDK installation. Preserve that safeguard.

### Windows / PowerShell commands

Use the Windows wrapper from the repository root:

~~~powershell
.\gradlew.bat --version
.\gradlew.bat :app:compileDebugKotlin --stacktrace
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
~~~

For a shared-module change, inspect available tasks first and run the narrow Android compilation task, for example:

~~~powershell
.\gradlew.bat :shared:compileDebugKotlinAndroid --stacktrace
~~~

For device tests, confirm a suitable device/emulator and required local tooling are available before running:

~~~powershell
.\gradlew.bat :app:connectedDebugAndroidTest
~~~

Unix equivalents use ./gradlew.

Do not run every task by habit. Choose the smallest validation sequence required by the changed subsystem.

### Release builds

- Do not run or claim a signed release build unless signing configuration and key handling are understood and explicitly authorized.
- Never create, expose, print, copy, or commit signing keys or passwords.
- The inspected app build script did not define a release signing configuration; the changelog notes release outputs were unsigned at that point. Re-check current configuration before making any release claim.
- ABI splits are configured in the app module. Report which APK ABI outputs were actually produced.
- Do not claim an APK is installable merely because assembleRelease succeeds.

## 16. Validation matrix

Choose the minimum sufficient set and report exactly what ran.

| Change area | Minimum useful validation |
|---|---|
| Kotlin/Compose app code | Static inspection, :app:compileDebugKotlin, focused unit tests |
| Shared common/Android code | Inspect expect/actual pairs, :shared Android compile task, relevant tests |
| Media3 playback lifecycle | Focused playback tests, app compile, device playback/recreation checks |
| DSP Kotlin/JNI bridge | Kotlin tests, native build, JNI offset/lifecycle instrumented tests |
| Native DSP math | Native harness, finite/clipping checks, preset/output comparisons, target-device listening |
| Audio route / Bluetooth / USB | Focused tests plus real-device route transitions and diagnostics |
| Room entities/migrations | Schema review, migration unit/instrumented tests, existing-data upgrade test |
| Manifest/permissions/privacy | Merged-manifest inspection, privacy tests, permission-denial checks |
| Local media / SAF / playlists | Unit tests plus on-device URI and permission lifecycle tests |
| Lyrics / romanization | Parser/tokenization tests covering Unicode, timestamps, malformed and mixed-language data |
| Compose UI | Compile, focused UI/state tests where present, phone/large-screen manual checks |
| Build configuration | Explicit approval, wrapper/JVM verification, narrow Gradle task, diff review |

For audio changes, source inspection and a green JVM test are not substitutes for testing the real Android audio path. For a persistence change, compilation is not a substitute for running the migration. For privacy changes, source inspection alone is not a substitute for checking the merged manifest.

Stop at the first unexplained root-cause failure. Do not continue with broader tasks merely to produce a longer list of results.

## 17. Test design and evidence quality

A useful test should identify a failure mode, not merely exercise a method.

- Prefer deterministic inputs and assertions.
- Use sentinel regions for native memory-boundary tests.
- Test both enabled and disabled/bypass states.
- Test lifecycle sequences, not only one successful call.
- Test edge sample rates, channel layouts, zero-length input, format changes, and reset/flush where relevant.
- For DSP, distinguish parameter propagation, native gate state, actual sample modification, numerical safety, timing, and listening evidence.
- For Room, test old-to-new schema migration with retained user data.
- For privacy, inspect packaged/merged permissions, not just a source manifest snippet.
- Avoid flaky timing assertions; record device and environment for performance measurements.
- Do not weaken or delete an existing test merely because a change makes it fail. Understand the regression first.

When using synthetic audio, document signal type, sample rate, channel count, frame count, level, enabled features, preset, and measurement window. Keep comparisons level-matched when evaluating subjective tonal or loudness differences.

## 18. Code change protocol

For every implementation task:

1. Restate scope and constraints.
2. Inspect the full relevant call chain and tests.
3. Identify protected and approval-gated areas.
4. Write a short implementation plan.
5. Name the exact files expected to change.
6. Make the smallest coherent patch.
7. Re-read changed files and inspect the full diff.
8. Search for impacted call sites, tests, manifests, JNI names, serialization, and ProGuard rules as applicable.
9. Run focused validation.
10. Report residual risks and device-only validation.

Do not:

- Reformat or refactor unrelated files.
- Delete a method merely because text search finds one direct reference.
- Assume reflection, JNI, Android manifest registration, Compose navigation, WorkManager, Room-generated code, or serialization consumers are visible through simple call-site search.
- Replace a functioning subsystem with a new abstraction without a demonstrated need.
- Add fallback behavior that hides a real error or reports a false success.
- Change product behavior as an incidental side effect of a bug fix.

## 19. Research and documentation

When external technical behavior matters and web access is available, prefer primary sources:

- Official Android documentation for platform permissions, storage, background execution, and audio APIs.
- Official AndroidX Media3 documentation and source/Javadoc for the exact version declared by the project.
- Official Kotlin, Compose, Gradle, AGP, KSP, Room, NDK, and CMake documentation for the exact installed versions.
- Android issue tracker / upstream issue discussions for version-specific defects, followed by a local reproduction.
- Relevant C++ and JNI specifications for native memory and ABI questions.

Verify publication/version dates and applicability. Do not apply an answer for a different Media3, AGP, Android API, or NDK release without checking compatibility. Distinguish upstream-documented behavior from locally reproduced behavior.

Internet research for development does not authorize runtime network access in the app.

When web access is unavailable, say that external verification was not performed. Continue only with repository evidence and established knowledge; mark version-sensitive assumptions for verification rather than inventing citations or claiming a web audit.

Keep README, changelog, comments, and tests accurate when the user asks for documentation updates. Do not silently edit guardrail.md or rewrite project history.

## 20. Required completion report

At the end of every non-trivial task, report:

- Scope completed and scope not completed.
- Files changed and the purpose of each change.
- Relevant files deliberately left unchanged.
- Commands actually executed (exact command lines).
- Exact build/test/lint results, including failures and skipped tests.
- Whether code was only inspected, compiled, unit-tested, instrumented-tested, or tested on a physical device.
- Device/Android version/route details when relevant to an audio result.
- First unresolved blocker and remaining risks.
- Behavior that still requires real-device or listening verification.
- Confirmation that no unapproved dependency, plugin, permission, toolchain, database, platform target, signing credential, or network feature was introduced.
- Confirmation that protected functionality was not changed.

Never report "all tests pass" unless all relevant tests were actually run and passed. Never report a feature is fixed solely because the patch compiles.

## 21. Quick task routing

Use these starting points to reduce investigation time:

- Preset sounds unchanged: trace selected ID → effective 15-slot parameters → runtime coordinator → JNI → native parameter consumption → actual wet/dry gate → processed samples → level-matched device listening.
- Crackle over Bluetooth: trace negotiated processor formats, PCM converter, alignment/carry-over, flush/reset, route events, sink lifecycle, buffer timing, and device logs.
- Playback freezes/restarts: inspect MusicPlaybackService, HoshiDspProcessor lifecycle/format negotiation, HoshiAudioSink, Media3 version contract, and recovery/recreation logic.
- JNI corruption or wrong samples: inspect direct-buffer address, explicit offset/length, stereo frame alignment, native bounds, handle lifecycle, and sentinel tests.
- Database startup/migration issue: inspect Room version, every migration edge, exported schema JSON, DAO queries, and migration tests. Never clear app data as the default fix.
- Missing library items: inspect MediaStore query/permissions, SAF folder grants, mapping, filters, cache invalidation, and repository/ViewModel flow.
- Lyrics mismatch: inspect source selection, LRC/SYLT parsing, timestamp offsets, romanization vs translation mode, token boundaries, and user overrides.
- UI state bug: trace state owner → ViewModel/repository → Compose collection → event callback. Avoid adding parallel state.
- Build failure: check JDK 17, wrapper, plugin catalog, SDK/NDK/CMake availability, and the first failing task before proposing any version change.
- Privacy concern: inspect main and merged manifests, backup/data-extraction rules, dependencies, code paths, debug-only components, and privacy tests.

End of skill.
