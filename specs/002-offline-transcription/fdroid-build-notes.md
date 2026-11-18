# F-Droid build notes (Phase 2)

Notes for the F-Droid metadata (`fdroiddata`) of this app. They record what the build needs and what is
still an open question; nothing here has been submitted.

## Recipe

- **Submodules:** `submodules: true`. The speech engine is the git submodule `whisper/src/main/cpp/whisper.cpp`
  (https://github.com/ggml-org/whisper.cpp), fetched at the pinned commit. The pin is `gradle/whisper.pin`
  (`tag=v1.9.5`, `commit=d1be6fde11ac6e0407606b4e42fe72d34add8037`); `./gradlew :app:verifyWhisperPin` fails when
  the checkout differs. Bumping the engine means changing the submodule and the pin together and re-running the
  spike's accuracy check, never one of them alone.
- **NDK and CMake:** `ndk: r29` (the exact SDK package is `ndk;29.0.14206865`) and CMake `3.31.6` from the SDK
  (`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`). Both are pinned in `whisper/build.gradle.kts`.
- **Binaries removed before the build (`rm:`):** the engine tree contains prebuilt files that F-Droid's scanner
  flags: `models/for-tests-*.bin`, `samples/jfk.wav` and three `gradle-wrapper.jar` files under `examples/` and
  `bindings/`. None is used. The recipe removes these directories of the submodule:

  ```yaml
  rm:
    - whisper/src/main/cpp/whisper.cpp/examples
    - whisper/src/main/cpp/whisper.cpp/bindings
    - whisper/src/main/cpp/whisper.cpp/models
    - whisper/src/main/cpp/whisper.cpp/samples
    - whisper/src/main/cpp/whisper.cpp/tests
    - whisper/src/main/cpp/whisper.cpp/ci
  ```

  The project enforces the same list locally: `./gradlew :app:verifyNoPrebuiltBinaries` scans the submodule and
  the project sources for `.so .a .jar .aar .dll .exe .dylib .class .apk .bin .onnx .mlmodelc`, skips exactly
  these six directories, and fails when `whisper/src/main/cpp/CMakeLists.txt` adds one of them. A new upstream
  version that adds a binary elsewhere fails that check before it reaches F-Droid.
- **No downloads at configure or build time:** `FetchContent` is not used and `GGML_CPU_KLEIDIAI` is off (it
  would download sources). The configure and build step was run once with the network off (see
  `spike-results.md`, "Build checks").
- **ABIs:** `arm64-v8a` and `x86_64` only. 32-bit ARM is not supported and says so in the README and the store
  text; the CameraX libraries still ship their other ABIs, the speech engine does not.
- **16 KB page alignment:** every `.so` in the APK has a LOAD segment alignment of `0x4000`
  (`llvm-readelf -lW`, recorded in `spike-results.md`). The link flag `-Wl,-z,max-page-size=16384` is set
  explicitly so an older NDK cannot silently regress.
- **Extracted native libraries:** `packaging.jniLibs.useLegacyPackaging = true`, because the ggml CPU variant
  libraries (`libggml-cpu-android_armv8.0_1.so` and six more) are loaded with `dlopen` from
  `nativeLibraryDir`. The APK is larger as a result (about 65 MB debug and 39 MB release).
  `libparakeet.so`, which the engine builds since 1.9 and nothing here uses, is excluded at packaging.
- **Reproducibility:** `-ffile-prefix-map` for the source and the build directory, `-Wl,--build-id=none`, no
  RUNPATH, exact toolchain pins, and `ndkVersion` set in both `:whisper` and `:app` (the app's strip step needs
  the NDK). Building in two different directories gives byte-identical native libraries (task T173,
  `spike-results.md`, "Reproducibility").

## Dependencies

New Maven dependency: `androidx.work:work-runtime:2.12.0` and its transitive AndroidX libraries (Room, SQLite,
startup, lifecycle), all from Google Maven and Apache-2.0. `gradle/verification-metadata.xml` was regenerated and
the diff reviewed. `verifyNoForbiddenDependencies` passes: no Google Play Services, Firebase or other
proprietary SDK.

## Third-party licenses

| Component | License | Where |
|-----------|---------|-------|
| whisper.cpp, ggml | MIT | built from source (submodule) |
| Whisper model weights (ggml conversion) | MIT | downloaded or imported by the user, never bundled |
| Silero VAD model | MIT | downloaded by the user together with a speech model, never bundled |
| AndroidX libraries (WorkManager, Compose, CameraX, ...) | Apache-2.0 | Maven |

Each model's license is shown with it in the app before the download. The README lists the notices.

## Open question (research R-16)

How F-Droid's inclusion policy treats an app that downloads model files from a third-party host
(`huggingface.co`). The files are free assets (MIT), the download starts only when the user taps Download after
a confirmation that names file, size and source, no meeting data is ever sent, and the app does not promote a
non-free network service. This is **not decided here**; it needs the F-Droid maintainers' answer when the
metadata is submitted. If the policy rejects it, the fallback is the import path alone (the user picks a model
file), which already works without any network.
