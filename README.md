# PocketAI — offline AI chat for Android

A fully local AI chat app. The model runs on your phone via llama.cpp's `llama-server` —
no cloud, no accounts, no API keys. Chat history stays on the device.

## How it works

- Kotlin Android app (minSdk 26, targetSdk 35) with a RecyclerView chat UI
- Talks to `llama-server` over an OpenAI-compatible endpoint on localhost
- `ChatStore` persists conversations in app storage
- `SearchHelper` adds optional DuckDuckGo web search alongside the local model

## Two flavors

| Flavor | What it does |
|---|---|
| `embedded` | Self-contained. Bundles an arm64 `llama-server` binary and runs it as a private child process on `127.0.0.1:8181`. Downloads a Gemma 3 4B (Q4_K_M, abliterated) GGUF from Hugging Face into app storage on first run. No Termux needed. |
| `termux` | Pairs with [Termux](https://termux.dev): starts `llama-server` inside Termux via `RunCommandService` (`allow-external-apps=true` required) and uses the model you manage there (`pkg install llama-cpp`, model at `~/models/model.gguf`). |

## Building

- Android Studio (or Gradle 8.14) with JDK 17
- `gradle :app:assembleEmbeddedDebug` or `gradle :app:assembleTermuxDebug`
- The embedded flavor expects a cross-compiled arm64 `llama-server` in
  `app/src/embedded/jniLibs/arm64-v8a/libllama_server.so` — see
  `scripts/embedded_pipeline.sh` for an example pipeline that builds it with the
  Android NDK toolchain (paths in the script are examples from the dev machine).

## Status

Personal project, early and evolving. Tested on arm64 devices.

## License

[MIT](LICENSE)
