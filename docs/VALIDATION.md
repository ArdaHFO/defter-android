# Defter 0.8.0-alpha validation

Updated 14 November 2026. Production development completed its full checks on 13 November. This source publication preserves those tested bytes, refreshes documentation, and runs three dedicated English reference/audio preview cases. The full regression, installed-device suite and APK build were not rerun for publication.

| Check | Retained development result |
| --- | --- |
| JVM/Robolectric | 393 tests in 68 suites, zero failures/errors/skips |
| Installed Android | 13 tests on API35 x86_64, R8-optimized nondebuggable release |
| Read-only inventory | Two checks, before upgrade and after fixtures |
| Debug/release Lint | Zero errors, 22/20 retained warnings |
| Upgrade | All 114 existing file hashes match before new fixtures |
| After fixtures | All 113 original non-catalogue content files match, no missing files |
| Shared catalogue | Changed to register newly added synthetic notebooks |
| Packaging | Version 0.8.0-alpha/code11, same development certificate, four ABIs, ZIP/64-bit ELF 16KiB checks pass |
| Publication visuals | Three fresh English capture cases, nine retained regression layouts, one native Canvas bitmap |

## Current evidence

[Unit results](verification/0.8.0-alpha/unit-test-summary.json), [Android results](verification/0.8.0-alpha/device-tests.json), [Lint findings](verification/0.8.0-alpha/lint-summary.json), [source hashes](verification/0.8.0-alpha/tested-source-sha256.json), [full source-copy hashes](verification/0.8.0-alpha/source-copy-sha256.json), [upgrade integrity](verification/0.8.0-alpha/upgrade-data-integrity.json), [APK audit](verification/0.8.0-alpha/apk-audit.json), [UI diagnostics](verification/0.8.0-alpha/native-ui-diagnostics.json), [legacy ink diagnostics](verification/0.8.0-alpha/legacy-ink-diagnostics.json), and [visual scope](verification/0.8.0-alpha/visual-validation.json).

The tested APK is 45,094,525 bytes with SHA-256 `1d520f27b472a57f0b8cce2ddebe802cbd1eaac39fed17cd5da859662744094e`. This identifies the retained local development artifact. APK binaries, signing keys, runtime notes/recordings and model weights are excluded from this source publication.

## Coverage and fixes

Coverage includes page-map seeking, bounded five-page editing windows, geometry-only reporting across 10,000 page IDs without reading a guarded 50,000-point ink list, focus/stylus/Back behavior, recreation, theme and panel preferences, native View identity, grid/list cards, context menu edges/RTL/safe insets, tooltip long press, nonblocking notices, 2x system text and contrast. Existing PDF, clipboard, selection, filtered partial erasing, undo, page management, continuous input, old formats, audio lifecycle, backup and localization tests remain included.

The 13 installed Android methods verify button/input paths, legacy two-page 50,000-point ink, both toolbar and stylus-button erasers, continuous-page input/pixels, PDF import/export, native engine identity, codec chunks/resume and cancellation. Two new methods verify map/focus/stylus and theme/list/panel behavior in the optimized target. The read-only inventory checks are separate from these 13 functional methods.

The editable View now stays mounted as reference panes open/close/resize. Reference margins follow the same light/dark theme as editable margins. Appearance failures do not prevent note loading, and preferences never rewrite paper or ink. Popup bounds use the actual application window. Preview rendering is limited and cancellable. Selected tab typography no longer changes tab width. Tooltips do not steal focus. Supplemental notices wait behind menus/dialogs while critical errors and deletion confirmations retain their dialogs. The prior eraser continuity repair and persisted document format are preserved.

Old card/menu assertions were updated for the new UI without dropping folder or clipboard behavior checks. One Windows full run hit an atomic snapshot-replace error. The lock holder was not identified, and later full runs passed without changing the storage implementation or disabling tests. Compile-stage issues were corrected before the final passing suite. Host failures were not recast as Android-device failures.

## Diagnostics and limits

The retained optimized-release UI diagnostic reports zero main-thread disk violations and zero redraws for 100 unchanged bindings. Its touch-to-test-idle durations include test synchronization and are not physical touch or pen latency. The legacy manual input/draw maximum was about 7.88ms in that synthetic emulator run, not a hardware frame-rate benchmark. The AOSP test image produced zero FrameMetrics samples, so no FPS result is claimed.

Remaining Lint advisories cover target-API maintenance, future app-bundle language splitting, application-derived locale contexts, storage-space checks, custom Canvas click accessibility and KTX style. They remain visible in the JSON findings rather than being blanket-suppressed. New popup sizing warnings were fixed.

Physical Tab S10 FE/S23 S Pen latency, palm rejection, actual side-button firmware behavior, long microphone sessions, handwriting/speech accuracy, all 99 model languages, battery and thermal performance still require separate device work. Whisper tests here check the native library and codec/error lifecycle. They do not benchmark real speech. [English screenshot provenance](images/README.md) also identifies manually seeded transcript examples.

## Historical evidence

[0.6 checks](verification/0.6.0-alpha/checks.json), [0.5 checks](verification/0.5.0-alpha/checks.json), [0.4 checks](verification/0.4.0-alpha/checks.json) and [earlier native-audio work](verification/2026-11-02/native-audio-summary.json) remain dated historical evidence. They are not new 0.8 tests.
