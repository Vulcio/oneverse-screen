# Oneverse Screen

Oneverse Screen is a free, open-source, ad-free display app designed primarily for Android TV. It can show a web page full screen or receive and display one NDI® video source from the local network. The project is written in native Kotlin with a small C++ JNI bridge to the NDI SDK.

The app is intended for always-on screens, venue displays, studios, and other setups where a TV or Android device should open directly to a web page or a live NDI feed. Its interface is designed for a TV remote, and the display stays awake while the app is in use.

> NDI® is a registered trademark of Vizrt NDI AB. Oneverse Screen is an independent project and is not affiliated with or endorsed by Vizrt NDI AB.

## Features

### Display destinations

- **Web View:** Show a web page full screen. The default page is [screen.oneverse.top](https://screen.oneverse.top); users can save a custom HTTP or HTTPS URL in Settings, choose it as the startup destination, and adjust page scale for a TV display.
- **NDI monitor:** Discover NDI sources on the local network, select one, and display its video full screen.
- **Hybrid View:** Display an NDI source as the background with the configured web page layered over it; adjust the web overlay opacity in Settings. The configured page can use a limited JavaScript bridge to position/size the NDI viewport or request a switch to a currently discovered source by its exact identity or a user-assigned label.
- **Startup choice:** Open the dashboard, Web View, NDI source selection, or Hybrid View when the app launches.
- **Contribute:** Make the Android device a discoverable NDI source by sharing its screen or camera, with a user-editable source name.
- **Immersive display:** Hide Android system bars and keep the device awake while the app is running.

### NDI source handling

- Discover available NDI sources and show their advertised names and network addresses.
- Mark the active source and the previously selected source in the source list, using an aligned state column.
- Keep receiving and consuming frames while the source list is open, so the current source can remain marked active. A small **×** control disconnects it.
- Remember the most recently selected source and optionally reconnect to it at startup when NDI is the startup destination.
- Show a small red indicator if the stream disconnects while retaining the last displayed frame; reconnect automatically when the source returns.
- Show optional source-name and video-detail overlays. These are off by default.
- Set an optional NDI preview refresh limit, or use the device's display refresh rate.
- Let the configured Hybrid View page read discovered source names/addresses, set or reset the NDI viewport using screen percentages, and request a source switch.

### Contribute mode

- **Screen sharing:** Send the device display as an NDI source so a receiver can see other apps and on-screen interactions. Android asks the user to approve every screen-sharing session and shows its screen-sharing indicator.
- **Camera sharing:** Send a camera feed as an NDI source. The app requests camera permission when this feature is first used and selects a camera available on the device.
- **Optional audio:** Screen sharing can include app playback audio on Android 10 and newer when the playing app allows capture. Camera sharing can include microphone audio. Audio is off by default.
- **Persistent controls:** An Android foreground-service notification indicates that contribution is active and provides a Stop action, including when the user switches to another app during screen sharing.
- **Capture size and rate:** Video is currently sent at up to 1280 pixels on its longest dimension and capped at 30 frames per second to control device load and network use.

### Product principles

- No advertising, analytics, tracking, accounts, subscriptions, or paid features.
- No bundled NDI SDK in the source repository. Developers install the SDK locally and accept its terms separately.
- The app source is licensed under MIT. This license does not cover NDI or other third-party components.

## Project status and limitations

- **Implemented:** Android application, primarily designed for Android TV. It can also be built for the Android ABIs listed in the Gradle configuration; device compatibility and performance may vary.
- **Not implemented:** iOS app. The `ios` directory is not a finished iOS product.
- The app receives one NDI source at a time.
- Screen audio capture is limited by Android and by the app producing audio; some apps do not allow their playback to be captured. Older Android versions can share video, but not other-app playback audio through Android's playback-capture API.
- Camera contribution requires a camera on the device. Android TV devices often do not have one.
- Source discovery provides the advertised source name and address. Resolution, frame rate, and codec are learned only after attempting to receive video, so the app cannot reliably identify every incompatible stream before connection.
- Latency depends on the sending device, capture and encoding settings, network, Android device, and display pipeline. The app's preview refresh setting controls its polling limit; it does not change the sender's capture or encoding rate.
- The Hybrid View JavaScript bridge (`OneverseHybrid`, with the previous `SongverseHybrid` name retained for compatibility) is available only to the configured page in Hybrid View. It exposes viewport controls, discovered source labels, and source selection; see [`docs/hybrid-web-controls.md`](docs/hybrid-web-controls.md) for setup, API details, and the trust boundary.
- This is an independent community project and is not endorsed by Vizrt NDI AB.

## Building the Android app

### Requirements

- Android Studio with Android SDK Platform 37
- Android NDK `30.0.16248370`
- CMake `4.1.2`
- The Android NDI SDK, installed separately

The project uses Android Gradle Plugin 9.4.1 and the Gradle 9.8 wrapper. The app's minimum Android API level is 23. The configured native ABIs are `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

### Steps

1. Install the requirements above using Android Studio's SDK Manager where available.
2. Obtain the Android NDI SDK from [NDI for Developers](https://ndi.video/for-developers/) and review its license agreement.
3. Open the repository's `android` folder in Android Studio and sync Gradle.
4. If the NDI SDK is not installed in the default location, create `android/local.properties` and set its path:

   ```properties
   ndiAndroidSdk=C:/path/to/NDI 6 SDK (Android)
   ```

   The default path configured by the project is `C:/Program Files/NDI/NDI 6 SDK (Android)`. `local.properties` is machine-specific and should not be committed.

5. Build the `app` debug configuration and run it on an Android TV device or compatible Android device. NDI source discovery and reception require the app and source to be reachable on the same local network.

Gradle and CMake use the SDK's ABI-specific `libndi.so` files and headers from the local SDK installation. These files, generated build outputs, and local SDK paths are excluded from the source repository.

## NDI SDK, licensing, and attribution

The Oneverse Screen application code is MIT licensed; see [`LICENSE`](LICENSE). The NDI SDK is separately licensed software and is not included in this repository. Installing or using it is subject to the NDI SDK license agreement and any applicable third-party terms. Review the agreement that accompanies the exact SDK version you use.

Before distributing an APK containing NDI SDK binaries, review NDI's current [licensing guidance](https://docs.ndi.video/all/developing-with-ndi/sdk/licensing), [software distribution guidance](https://docs.ndi.video/all/developing-with-ndi/sdk/software-distribution), and the SDK's third-party notices. Do not assume that the application's MIT license grants rights to redistribute SDK binaries. NDI's guidance also specifies product attribution and links near NDI use; the app's source selector and this documentation identify the trademark and link to NDI.

Do not use “NDI” as part of the product name without first consulting NDI. Oneverse Screen uses NDI only to describe interoperability with the NDI technology.

## Repository layout

- `android/` — Android Studio project, Kotlin app, and C++ NDI bridge.
- `docs/` — implementation and architecture notes.
- `ios/` — incomplete placeholder; there is no usable iOS app yet.
- `LICENSE` — MIT license for the project source code, subject to the separate third-party licensing above.

## Contributing

Issues and pull requests are welcome. Please do not commit SDK binaries, local SDK paths, signing keys, generated build directories, or credentials. When changing NDI integration or packaging, preserve the SDK's licensing, trademark, and third-party attribution requirements.


