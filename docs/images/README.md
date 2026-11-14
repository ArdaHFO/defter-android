# English 0.8 previews

These images render the actual Defter 0.8 interface. Notebook titles, lesson notes, silent audio and transcript text are synthetic. Transcript text is seeded by the fixture, not produced by speech inference. No microphone, model download or physical device is used for the gallery.

The 13 November 2026 regression provides the library, editor/map, focus, dark eraser, phone and large-text layouts. Three dedicated 14 November publication cases refresh the split editor, phone audio archive and tablet transcript. The extra continuous-page image is a bitmap of the installed Android View, rather than an emulator display capture.

| Preview | Capture |
| --- | --- |
| [Library](library-tablet.png) | Real first-page previews, English grid layout |
| [Editor/map](editor-map-tablet.png) | Visible-area rectangle and page navigation |
| [Focus](focus-map-tablet.png) | Toolbar-free workspace with exit/map access |
| [Dark eraser](eraser-dark-tablet.png) | Mode, content filters and size |
| [Phone](editor-phone.png), [phone focus](focus-phone.png) | 393dp portrait layouts |
| [Appearance](appearance-phone-large-text.png) | Enlarged text and scrolling settings |
| [Split editor](editor-split.png) | Independent reference and editable notebook |
| [Audio archive](audio-phone-library.png) | Standalone recordings and imports |
| [Transcript](audio-tablet-transcript.png) | Manually seeded English text and timestamps |
| [2x phone](responsive-phone-2x.png), [2x tablet](responsive-tablet-2x.png) | Adaptive large-text controls |
| [Continuous pages](continuous-pages-native-canvas.png) | Native Android Canvas pixel fixture |

[Machine-readable provenance](capture-provenance.json) records file hashes and fixture names. [Publication fixture source](../preview-fixtures/GithubEnglishPreviewTest.kt) and [production preview tests](../../app/src/test/java/dev/defter/app/ui/EnglishWorkspacePreviewTest.kt) retain the capture logic. [Validation](../VALIDATION.md) distinguishes fresh capture checks from retained full tests.

The headless API35 emulator did not provide useful display captures or FrameMetrics samples. The gallery is not physical S Pen latency, frame-rate, microphone quality or transcription-accuracy evidence.
