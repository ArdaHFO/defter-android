# Validation scope

This page separates the completed **0.5.0-alpha validation on 3 November 2026** from earlier 0.4 design and 0.3 native-audio evidence. The GitHub update publishes the already-validated source and refreshes documentation and English UI previews. It does not represent a new full regression run or a physical-device certification.

## Current 0.5.0-alpha evidence

| Check | Recorded result | Scope |
| --- | --- | --- |
| JVM/Robolectric and Compose tests | 309 tests in 50 suites, no failures, errors, or skipped tests | Completed full test run for the input, navigation, localization, and bulk-trash update. |
| Android Lint | 0 errors, 27 warnings | Static analysis, not a warning-free release. |
| Android test-source compilation | `compileDebugAndroidTestKotlin` passed | Compilation, not execution of the instrumentation suite. |
| Source correspondence | 1,580 source/configuration hashes matched | Recorded source manifest is rechecked against this publication staging copy. |
| Development APK | Version 0.5.0-alpha, code 5, package `dev.defter.app` | Existing separately distributed debug-signed artifact. No APK build is needed for this documentation update. |
| Android emulator update | 0.4 updated to 0.5 on Android 15 / API 35 x86_64 | Cold launch, existing notebook navigation, English default on a Turkish system, and font scale 2.0. |
| Data preservation | Eight content/test files retained their hashes | AndroidX `files/profileInstalled` changed as expected. Shared preferences and cache were outside the comparison. |

Retained reports are the [check summary](verification/0.5.0-alpha/checks.json), [source hashes](verification/0.5.0-alpha/source.sha256), [per-suite test summary](verification/0.5.0-alpha/unit-test-summary.json), [Lint summary](verification/0.5.0-alpha/lint-summary.json), [APK audit](verification/0.5.0-alpha/apk-audit.json), [packaged locales](verification/0.5.0-alpha/packaged-locales-audit.json), and [emulator smoke summary](verification/0.5.0-alpha/emulator-smoke.json). These public summaries omit workstation paths and machine hostnames.

The completed test run covers finger/stylus separation, cancellation, page-edge gestures, single-page creation, stale page events, save failures, editable copy/cut/paste and undo, external clipboard precedence, asset preservation, bulk deletion, active audio-job protection, language changes, and content integrity. Existing notebook, PDF data-model, eraser-button, audio, and transcript tests also passed.

The 27 Lint warnings retain the earlier 22 and add two KTX suggestions, two static-context warnings for an application-derived locale cache, and one AAB language-delivery warning. The existing APK includes all locales. A future Play App Bundle must address language-split delivery separately.

Development failures and corrections are not hidden by the final passing result. The first full run had six failing tests. Focused fixes were followed by the complete passing run. The final large-font test issue was traced to Robolectric overriding the Activity locale when changing font scale; the test configuration was corrected while preserving layout assertions. Real Android locale/font-scale behavior was subsequently checked in the emulator.

## APK and runtime limits

The previously built APK is **62,087,303 bytes**, SHA-256 `c01b69aea3eccf9afc4c445191cd1f9ede766a71fedde19e5c04e05461a9f570`. Its signature verified and matched the previous development certificate. ZIP alignment and eight packaged 64-bit native libraries passed 16 KiB checks. Whisper and the C++ runtime are present for all four configured ABIs. No signing key or model weights are bundled. The APK binary and private signing material are not in this source repository.

All 638 string resources and 10 plural groups were found in default, English, and Turkish configurations. Default values matched English. The emulator kept English at actual system locale `tr-TR` and font scale 2.0. Existing content remained intact, the crash buffer was empty, and no microphone recording, model download, or inference was performed during that smoke check.

This emulator check does not establish physical S Pen behavior, copy/paste gesture execution, page-creation gestures, or bulk deletion on a physical tablet. Those flows have scoped automated coverage, which is different evidence.

## English preview refresh

The root README now uses English **0.5.0-alpha** interface renders with synthetic demonstration notebooks and transcripts. The [preview provenance](images/README.md) records the capture method and files. These are real Compose UI renders produced by Robolectric, not photographs or screenshots from a physical Tab S10 FE, and not evidence of live speech recognition accuracy.

## Historical evidence

The preserved [0.4 check summary](verification/0.4.0-alpha/checks.json) records 244 tests in 39 suites and 0 Lint errors / 22 warnings on 2 November 2026. Its [APK audit](verification/0.4.0-alpha/apk-audit.json) and [emulator smoke report](verification/0.4.0-alpha/emulator-smoke.json) apply to the earlier design release, not the current source.

The earlier **0.3.0-alpha** [native-audio summary](verification/2026-11-02/native-audio-summary.json) and [four-ABI report](verification/2026-11-02/native-four-abi.json) remain separately preserved. An Android 15 x86_64 `app_process` harness used compiled production classes, the pinned Whisper engine, the multilingual base model, a public JFK sample, and synthetic audio. Ten distinct scenarios covered codecs, resampling, cancellation, model validation, timestamped inference, import, and backup restoration across separate runs. It did not use a physical microphone or validate 99 languages. These native-inference checks were not rerun for publication.

## Remaining device work

- Physical stylus latency, palm rejection, S Pen side-button events, and pressure behavior.
- Long microphone sessions, lifecycle recovery, and foreground services across manufacturers.
- Handwriting model downloads and real Turkish/English recognition accuracy.
- Speech accuracy by language, transcription speed, memory, battery, and temperature on target devices.
- Production signing, store delivery, and broad Android-device compatibility.

For new runs, follow the root [build instructions](../README.md#build-from-source), then record the source revision, command, device, and result. Compilation, automated UI rendering, emulator smoke checks, native-engine inference, and physical-device tests should remain separately identified.
