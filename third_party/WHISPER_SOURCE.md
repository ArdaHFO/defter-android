# Vendored Whisper source

- Upstream: https://github.com/ggml-org/whisper.cpp
- Release: v1.9.5, published 2026-10-06
- Commit: `d1be6fde11ac6e0407606b4e42fe72d34add8037`
- Download: https://api.github.com/repos/ggml-org/whisper.cpp/zipball/d1be6fde11ac6e0407606b4e42fe72d34add8037
- Downloaded archive SHA-256: `211fd125a02c30b38d6c0aa5d6635371679b5725de709200c09f95ca8a835ceb`
- Unmodified source subset: root CMakeLists.txt/LICENSE/AUTHORS/README.md, include/, src/, cmake/, ggml/. Examples, samples and model weights are not bundled.
- License: MIT, preserved at whisper.cpp/LICENSE and applicable ggml notices.
- Android builds use only the CPU backend and require no network fetch for this source.

The app's optional multilingual `ggml-base.bin` comes from
https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin.
Expected length is 147951465 bytes and SHA-256 is
`60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.
Metadata was verified through the official Hugging Face API on 2026-11-02.
Original Whisper weights are MIT licensed: https://github.com/openai/whisper#license.
Models are downloaded only after the user explicitly chooses Download.
