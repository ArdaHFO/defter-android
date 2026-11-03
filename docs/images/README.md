# English interface previews

Captured on **3 November 2026** from the **Defter 0.6.0-alpha** Compose interface with English selected. These images replace the previous English 0.5 previews at the same repository paths.

| Image | Screen | Size |
| --- | --- | --- |
| [library-tablet.png](library-tablet.png) | Library, folders, notebook covers, and standalone Audio entry | 1280 × 800 |
| [editor-split.png](editor-split.png) | Notebook tabs, reference pane, Undo/Redo, and Finger: pan control | 1280 × 800 |
| [audio-phone-library.png](audio-phone-library.png) | Standalone phone audio archive | 393 × 851 |
| [audio-tablet-transcript.png](audio-tablet-transcript.png) | Playback, English speech selection, transcript sections, and TXT/SRT controls | 1280 × 900 |

## Method and scope

The images were rendered by **Robolectric's native graphics mode** using the real application Compose UI and a dedicated `GithubEnglishPreviewTest` fixture. Production source files were checked against the current source before capture. The capture test was added only to an isolated build workspace; it does not alter the application's production code or add a demo mode.

Notebook titles, notes, folders, graphs, and transcripts are **synthetic demonstration data**. The audio fixture is a 60-second silent WAV with a manually populated transcript. It demonstrates the interface, not the output of speech recognition. No personal notebook, recording, or transcript is used.

Four focused preview tests generated the screens. The tablet transcript was recaptured after selecting English so its language control matches the demonstration text. The dedicated task was `:app:testDebugUnitTest --tests dev.defter.app.ui.GithubEnglishPreviewTest`; it does not build an APK. These rendering checks are separate from the retained 342-test regression result in [validation](../VALIDATION.md).

These are **UI renders, not screenshots from a physical tablet or phone**. They do not measure S Pen latency, microphone recording, recognition accuracy, battery use, or device compatibility. Images were visually inspected for English controls, legibility, layout, and consistency before publication. They were not generated or retouched with AI.

[Capture metadata and image hashes](capture-provenance.json) identify the final published files.

Additional 0.6 evidence: [English 2x phone](responsive-phone-2x.png), [English 2x short tablet](responsive-tablet-2x.png), and [native Android Canvas page boundary](continuous-pages-native-canvas.png). The first two are native Robolectric renders from the development regression suite; the third is a real Android View bitmap render, not a display screenshot. Full emulator display capture returned black including Android Home and is not used as visual evidence.
