# GPS Map Camera (Kotlin / Android Studio)

This version uses **OpenStreetMap tiles through osmdroid** instead of Google Maps SDK. It does not require a Google Maps API key or Google Cloud billing.

## Open and build
1. Extract this folder and open it in Android Studio.
2. Install/use **JDK 17** and Android SDK Platform 35 (Android Studio can install these from SDK Manager).
3. Let Gradle sync. The project-level plugin is Android Gradle Plugin 8.7.3 and the wrapper is configured for Gradle 8.9.
4. Build with **Build > Make Project** or run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
5. The APK, if the build succeeds, will be at `app/build/outputs/apk/debug/app-debug.apk`.

No `local.properties` API key entry is needed. Android Studio normally creates `local.properties` automatically to point to your local Android SDK. Do not commit that machine-specific file.

## Features
- Take a photo with the device's camera or select one from the gallery.
- View an OpenStreetMap map, tap to choose a location, or search an address (OpenStreetMap Nominatim).
- Use the device's current GPS location (grant location permission when prompted).
- Stamp location/address, coordinates, plus code, and selected date/time onto the photo.
- Save the stamped JPEG to `Pictures/GPSMapCamera`.

## Notes
- **Map "Access blocked" fix:** OpenStreetMap blocks tile requests that use a default or `com.example.*` user agent. The app now sends a proper `USER_AGENT` (see `MainActivity.companion`) and defaults to Esri satellite tiles, with a Street/Satellite toggle on the map. If you publish the app, change `USER_AGENT` to your real app name and contact URL/email.
- Google Maps SDK itself cannot be used without a Google Maps API key. This project avoids that requirement by using OpenStreetMap instead.
- Map tiles and address search need an internet connection. Address search and reverse lookup use OpenStreetMap Nominatim, with Photon and Android's Geocoder as fallbacks, so they work on devices without Google services. Coordinates and plus codes are generated locally.
- OpenStreetMap data attribution is included. Respect the OpenStreetMap tile usage policy; for high-volume production use, configure an appropriate tile provider.
- This source project has not been built in this environment because Android SDK/Gradle tooling is not installed here. Please run a build in Android Studio or CI and share any new error text if one appears.
