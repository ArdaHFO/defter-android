# Validation scope

This page separates the completed **0.4.0-alpha design validation on 2 November 2026** from earlier native-audio evidence. Repository preparation copied sources, inspected the reports and previews, and checked file hashes. It did not rerun the app, Gradle checks, emulator, microphone, or model inference.

## Current 0.4.0-alpha evidence

| Check | Recorded result | Scope |
| --- | --- | --- |
| Local JVM/Robolectric and Compose tests | 244 tests in 39 suites, 0 failures, errors, or skipped tests | Complete second test run for the redesigned interface. |
| Android Lint | 0 errors, 22 warnings | Static analysis, not a warning-free release. |
| Android test-source compilation | `compileDebugAndroidTestKotlin` passed | Compilation, not execution of the instrumentation suite. |
| Development APK | `assembleDebug` succeeded | Version 0.4.0-alpha, version code 4, package `dev.defter.app`. |
| Source correspondence | All 1,555 source/configuration manifest entries matched during publication preparation | Hash correspondence to the completed source report. |
| UI previews | Nine Robolectric native renders reviewed in the design workflow | Synthetic data at phone/tablet sizes, including the split editor and audio screens. |
| Update smoke check | Existing 0.3.0-alpha installation updated and launched in the dedicated Android emulator | Library/audio navigation and opening an existing notebook. |

The [completed check summary](verification/0.4.0-alpha/checks.json), [source hashes](verification/0.4.0-alpha/source.sha256), [per-suite test summary](verification/0.4.0-alpha/unit-test-summary.json), and [Lint summary](verification/0.4.0-alpha/lint-summary.json) retain these results. Test/Lint summaries omit machine hostnames and workstation paths.

The first full run passed 243 tests and encountered `AccessDeniedException` while replacing `library.json.new` during one audio test's setup, before its screen opened. No leaked application file handle was identified. A second complete run passed all 244 tests without changing storage code. A temporary Windows file lock is a supported explanation, but the process holding the lock was not identified.

The 22 Lint warnings include the earlier 19 plus three launcher-resource warnings. `ObsoleteSdkInt` flags a `v26` directory with a minimum API of 26. Two `MonochromeLauncherIcon` warnings concern the `v26` icon definitions; Android 13 monochrome resources are separately defined in `v33` variants. These warnings remain visible in the retained summary.

## APK and update scope

The existing [APK audit](verification/0.4.0-alpha/apk-audit.json) records a **61,637,104-byte development APK** with SHA-256 `5b02c8fc60343d08f7b5a3cc52fe69f3884c7a5f0a1858de38703b37eb4afd6f`. Its signature verified, and its certificate matched the previous development APK. ZIP alignment and all eight packaged 64-bit native libraries passed the 16 KiB checks. All four configured ABIs contained the Whisper engine and C++ runtime. The audit found no bundled private signing key or Whisper model weights. The APK binary and signing key are not included in this source repository.

The [emulator smoke report](verification/0.4.0-alpha/emulator-smoke.json) records a successful update from 0.3.0-alpha to 0.4.0-alpha, application launch, library-to-audio navigation, return navigation, and opening an existing notebook. Six existing content files and two pre-existing verification exports retained their hashes. The separate `files/profileInstalled` marker changed during installation/launch, so the report does not claim every file remained unchanged. No crash appeared in the inspected crash buffer.

The notebook audio entry was present in the UI hierarchy, but nested notebook-audio navigation was not exercised. The smoke check did not start recording, download a model, run inference, or capture screenshots. It was not a physical tablet test. The dedicated emulator was stopped after verification.

## Historical native audio evidence

The earlier **0.3.0-alpha report from 2 November 2026** recorded 244 local tests, 0 Lint errors / 19 warnings, and native Debug compilation for arm64-v8a, armeabi-v7a, x86_64, and x86. The older [summary](verification/2026-11-02/summary.json), [source manifest](verification/2026-11-02/verified-source-manifest.json), [four-ABI report](verification/2026-11-02/native-four-abi.json), and [native audio summary](verification/2026-11-02/native-audio-summary.json) are preserved as historical evidence. Their source manifest is not a claim that every file matches the redesigned 0.4.0 interface.

Its separate Android 15 x86_64 `app_process` harness used compiled production classes, the pinned whisper.cpp engine, the multilingual base model, a public JFK sample, and synthetic audio. Ten distinct scenarios covered codecs, resampling, cancellation, model validation, timestamped inference, audio import, and backup restoration.

Those scenarios passed across separate runs, not one uninterrupted run. An initial harness filesystem restriction was corrected. A later run exposed a WAV metadata assumption, fixed by validating an actual MediaExtractor audio track. Eight engine cases and two corrected storage cases then passed. The 32 relevant engine class files matched between the successful inference and final storage harnesses.

That earlier harness did not build or install an application APK, open the microphone, or read personal app data. The 0.4.0 design update did not change the drawing engine, storage schema, or speech engine. The older native-inference results were not rerun as part of the visual refresh.

## Remaining limits

- Physical microphone recording, long sessions, recovery under real device lifecycle conditions, and foreground-service behavior across manufacturers.
- Stylus latency, palm rejection, physical S Pen button events, and pressure behavior.
- Turkish/English handwriting-model downloads and accuracy on physical devices.
- Turkish speech accuracy, transcription speed, memory pressure, battery consumption, and thermal behavior on target devices.
- A production-signed store release and broad Android compatibility.

The four root README previews are current 0.4.0-alpha Robolectric native renders using synthetic data. They were visually inspected before inclusion. They are separate from the emulator smoke evidence and do not establish physical-device readiness.

## Reproducing checks

The root [build instructions](../README.md#build-from-source) describe the checked-in toolchain and the `Check`, `Test`, `Native`, and `Build` actions. Source tests remain under `app/src/test` and `app/src/androidTest`.

The previous native harness used a separate workspace, model cache, emulator, and build products. Machine-specific logs and assets are not shipped in this repository. The summaries establish the scope of prior evidence, not a one-command reproduction claim. New verification should record its source revision, tools, commands, results, and device scope.
