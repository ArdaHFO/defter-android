# Defter 0.6.0-alpha validation

Completed on 3 November 2026. This publication transfers the validated implementation and updates bilingual documentation. The full regression was not rerun for publication; four dedicated English preview tests were rerun against the same production code. No APK was rebuilt for this GitHub update.

| Check | Recorded result |
| --- | --- |
| Full JVM/Compose/native-render suite | 342 passed in 56 suites, zero failures/errors/skips |
| Android Lint | Zero errors, 18 warnings, one informational finding |
| Android instrumentation | Seven passed on Android 15/API35 x86_64 |
| Source correspondence | 1,596 tested source/configuration files, checked against the publication |
| Upgrade preservation | All nine inventoried files matched before synthetic fixtures were added |
| Post-test original notes/assets | Five original page/asset files retained their exact hashes |
| Development APK | 0.6.0-alpha, code6, package dev.defter.app, 62,120,071 bytes |
| Signature and packaging | Same development certificate, all four ABIs, ZIP and eight 64-bit ELF libraries passed 16KiB checks |

APK SHA-256: `da6851c42ebdf35d8903c0de05167b7ec61a0dd6a11caba6e9ef691bbc1e8ed3`. APK binaries, private signing keys, runtime notebooks, recordings, and model weights are excluded from this source repository.

## Current evidence

[Check summary](verification/0.6.0-alpha/checks.json), [tested-source hashes](verification/0.6.0-alpha/tested-source-sha256.json), [suite summary](verification/0.6.0-alpha/unit-test-summary.json), [Lint summary](verification/0.6.0-alpha/lint-summary.json), [device tests](verification/0.6.0-alpha/device-tests.json), [instrumentation output](verification/0.6.0-alpha/device-instrumentation.txt), [upgrade integrity](verification/0.6.0-alpha/upgrade-data-integrity.json), [post-test integrity](verification/0.6.0-alpha/post-test-data-integrity.json), [APK audit](verification/0.6.0-alpha/apk-audit.json), and [visual scope](verification/0.6.0-alpha/visual-validation.json).

Coverage includes continuous page geometry and rendering, page-local stylus routing, gap/unloaded-page rejection, finger drag/fling, anchored pinch zoom, cancellation, temporary eraser, isolated undo/redo, append once, save failures/retry, stale async results, deletion/reorder, the five-page window against a 100,000-ID list, large text, contrast, and system animation settings. Existing PDF, audio, backup, recognition integration, clipboard, trash, language, and persistence tests also passed.

Seven real Android methods verify native Canvas paper/gap pixels, both-page input and persistence, native PDF import/export/annotation/trash/backup, packaged JNI version, codec chunks/resume, invalid containers, and cancellation cleanup. Input is synthetic. No physical microphone or fresh model-inference accuracy claim is made.

## Bugs corrected during development

Adjacent-page long presses could dismiss their newly opened menu. Center activation could move Undo away from the last touched page. A Compose snapshot race could pair an old page ID with a new navigation token and land at the wrong offset. Narrow footers and short tablet sidebars could hide actions at large text. Exiting continuous mode could retain the wrong coordinates. Large-text sidebar words and empty-library actions could become clipped. These received fixes and regression coverage. Unchanged page order no longer allocates notebook-wide lookup structures on each update.

The first focused run had two failures; both were corrected before 41 focused checks and the full 342-test regression passed. The Lint findings include conservative free-space checks, application-derived locale context warnings, delegated performClick analysis, icon/store-delivery suggestions, and KTX maintenance suggestions. They are retained advisories rather than a warning-free claim.

## Design and visual scope

The gray/white surfaces, blue emphasis, readable system typography, alignment, full touch targets, separate tab-close actions, adaptive large text, and short optional transitions adapt Apple's [layout](https://developer.apple.com/design/human-interface-guidelines/layout), [typography](https://developer.apple.com/design/human-interface-guidelines/typography), [color](https://developer.apple.com/design/human-interface-guidelines/color), and [motion](https://developer.apple.com/design/human-interface-guidelines/motion) principles to Android.

[English previews and provenance](images/README.md) are synthetic native Robolectric renders. The [two-page native Android Canvas image](images/continuous-pages-native-canvas.png) is a bitmap render of the actual View. The headless emulator's full display capture was black, including Android Home, with SwiftShader and ANGLE/SwiftShader. Those captures were rejected as visual evidence. This limitation is separate from passing Android input/layout/persistence/PDF/audio tests and native Canvas pixel assertions.

## Historical evidence and remaining work

[0.5 check summary](verification/0.5.0-alpha/checks.json) records 309 tests/50 suites and 27 warnings. [0.4 checks](verification/0.4.0-alpha/checks.json) and the [0.3 native-audio harness](verification/2026-11-02/native-audio-summary.json) remain historical evidence. Earlier Whisper inference has not been recast as current 0.6 testing.

Physical Tab S10 FE/S Pen latency, pressure, palm/side-button behavior, long microphone sessions, handwriting accuracy, speech-language accuracy, battery/thermal performance, and broad manufacturer compatibility remain device work. The base model's 99-language vocabulary is not 99 manually selectable or independently validated app languages.
