# Code review and fixes — 21 October 2026

Scope: Android Activity and service lifecycle, Kotlin/JNI integration, native frame handling, web controls, settings persistence, build configuration, and Android TV compatibility. The iOS directory remains an incomplete placeholder.

## Findings addressed

| Priority | Finding | Correction |
| --- | --- | --- |
| High | Hybrid View's `addJavascriptInterface` was visible to embedded frames, and checking the top-level URL did not authenticate the calling frame. | Replaced it with an AndroidX WebKit message listener that checks the configured origin, main-frame identity, and active WebView. Public methods now return Promises. |
| High | Audio float samples used ByteBuffer's default big endian order while JNI read native floats, producing corrupted audio. | Added native-order PCM16-to-planar-float conversion with stereo and buffer reuse tests. |
| High | Activity cleanup could destroy the shared NDI runtime while the service still needed its sender. | Activity stop releases only its receiver. Runtime initialization remains process-wide. |
| High | Sender teardown ran on the main thread and could close ImageReaders while capture callbacks were using their Images. | Startup, image callbacks and teardown share a background queue across service instances. Audio is stopped before its recorder is released. |
| High | The previous URL-origin helper referred to an unimported `Uri`, preventing Kotlin compilation. | Replaced URL and origin handling with a validated, independently tested policy. |
| Medium | WebViews were abandoned when leaving display screens; displayed preview bitmaps could be recycled while still in use. | Detach and destroy old WebViews; leave displayed bitmap lifetime to Android rather than manually recycling them. |
| Medium | Returning to Hybrid View after backgrounding could leave frame polling stopped. | Restart polling while retaining the current page, and invalidate stale lifecycle callbacks. |
| Medium | Local camera previews ignored sensor rotation and duplicated the sender's conversion logic. | Shared preview conversion and orientation rules; native transmitted pixels and dimensions are rotated consistently. |
| Medium | Unexpected received pixel formats could cause old buffer contents to be displayed as new frames; sender size arithmetic could overflow. | Reject unsupported formats, bound allocations and stride calculations, and retain the metadata from the frame actually captured after a buffer resize. |
| Medium | Clearing a custom URL could reveal an older saved startup URL. Source label edits could be lost on Save Settings. | Clear the legacy URL preference when saving/clearing; save and validate source labels with the settings transaction. |
| Medium | Camera, microphone and Wi-Fi permission implications could exclude compatible Android TVs without those features. Contribution discovery could lose its multicast lock in the background. | Declare the hardware optional and retain a separate multicast lock for the active sender. |
| Low | Drawing allocated repeated geometry/paint objects, source discovery could accumulate scheduled catch-up work, and deprecated Gradle property assignment forms remained. | Reuse drawing objects, use a fixed delay for discovery, and update property assignments. |

Android 13+ Back gestures now use the same in-app navigation as the TV remote. The existing application ID is retained, and the version code is increased to 2.

## Verification

- Seven JUnit tests cover URL validation and origins, viewport boundaries, camera rotation, native-endian audio, and stereo channel layout.
- Four Node.js tests cover Hybrid API frame restrictions, request/reply handling, compatibility aliases, error rejection, readiness, and timeouts.
- Native rotation assertions compile for arm64-v8a, armeabi-v7a, and x86_64; the JNI library compiles and links against the installed NDI SDK for all three ABIs.
- Kotlin compilation, debug APK assembly, and Android lint were checked in a temporary verification project. CMake's filesystem operations and normal Gradle test-result renaming fail in this agent's Windows sandbox, so the verification copy uses directly compiled NDK libraries and JUnitCore. Its temporary signing key is for verification only; build the normal project with your usual debug key to update an installed app.
- Lint completed without errors but reported warnings, including localization strings, intentionally enabled JavaScript, backward-compatible deprecated APIs, and newer dependency/target versions. The sandbox also prevented writing lint's API cache; these checks do not replace device testing.

No physical camera, MediaProjection, Android TV, or live NDI interoperability test was performed in this review. Those remain the practical checks after installation, especially starting/stopping sharing repeatedly, selecting different cameras, rotating the device, backgrounding/resuming Hybrid View, and stopping a share from Android's notification.

The Hybrid API migration and trust boundary are described in [hybrid-web-controls.md](hybrid-web-controls.md). Android's [WebMessageListener API](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener) supplies the calling origin and main-frame identity used by the new bridge.
