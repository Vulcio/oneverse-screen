# Android TV architecture

- **Kotlin Activity/UI**: remote-first home, URL settings, full-screen WebView, NDI source chooser, and video surface.
- **Android Network Service Discovery**: an `NsdManager` instance is held for the Activity lifetime, as required by the NDI Android platform guidance.
- **C++ JNI bridge**: calls the official NDI finder, receiver, and sender APIs. NDI receiver frames are copied into a reusable direct buffer for preview; Android screen and Camera2 frames are converted to NDI RGBA before sending. Optional diagnostics show decoded pixel format and frame rates; the standard receiver API does not expose the source codec.
- **Installed SDK**: Gradle/CMake links the ABI-specific `libndi.so` and headers from the local NDI SDK folder. SDK files remain outside version control.

## Android TV behavior

On launch the app opens the configured startup destination: Dashboard, Web, NDI, or Hybrid. Web opens the optional custom URL, or `https://screen.oneverse.top` when no custom URL is set. In Hybrid View, the web page overlays a selected NDI source and can use the limited `OneverseHybrid` JavaScript bridge documented in `hybrid-web-controls.md`. Users can assign labels to discovered sources in Settings and select them from the page by label. When NDI or Hybrid is the startup destination, the app can reopen the last selected source. During playback it reports connection state and retries after a disconnect, preferring the source's latest discovered address. The source name/web address banner and NDI source details are independent options in Settings. Details use an opaque panel for contrast. The NDI preview refresh limit can be set from Auto to 120 fps. Back returns to the dashboard or source selector. D-pad focus and Select activate all controls. The display stays awake while the monitor is open.

Contribute mode can publish the device display through Android MediaProjection or publish a Camera2 feed. Both use a foreground service and the NDI sender API. Screen projection requires fresh system consent per session; optional audio uses playback capture where supported. Camera audio uses the microphone. The sender caps video at 30 fps and 1280 pixels on the longest dimension.

## Boundaries

This first implementation receives one video source at a time. It does not expose the NDI SDK's proprietary types to the UI. NDI headers and libraries are never committed. Android is the implemented target; iOS needs its own native Swift app and has no Flutter dependency.


