# GPS Map Camera (Kotlin/XML)

## Setup
1. Open this folder in Android Studio (JDK 17).
2. In Google Cloud Console enable **Maps SDK for Android** and **Places API** and configure billing.
3. Put your key in `local.properties` as `MAPS_API_KEY=YOUR_KEY`, then expose it through a manifest placeholder as shown below.
4. Restrict the key to your Android package and signing certificate. Never commit a production API key.

### Configure API key placeholder
Add this inside `defaultConfig` in `app/build.gradle.kts`:
```kotlin
manifestPlaceholders["MAPS_API_KEY"] = project.findProperty("MAPS_API_KEY") ?: ""
```
Then add `MAPS_API_KEY=...` to local.properties. The app uses `com.google.android.geo.API_KEY`.

## Included
- Camera intent (`TakePicture`) and gallery image picker
- Options dialog shown before stamp composition
- Google Places Autocomplete, draggable/tappable satellite map, reverse geocoding, plus code
- Date and time pickers with requested format
- XML stamp layout and JPEG gallery output using MediaStore

## Notes before shipping
- This is a starter project, not a tested release build. Validate API versions and layout on your target SDK/device.
- The MapView snapshot includes Google's required map attribution. Do not remove or obscure attribution. The sample's extra "Google" text is illustrative; for production, use the official attribution asset and its required clear space.
- Android's Geocoder is service-dependent and may return incomplete addresses. Plus codes are generated locally.
- The sample uses a small country-to-flag lookup; extend it with ISO 3166 country codes for global coverage.
- The stamp is composed onto the source bitmap. Map snapshot dimensions and UI density differ by device; tune canvas scale/line wrapping for your desired export fidelity.
- For production CameraX, add `androidx.camera:camera-camera2`, `camera-lifecycle`, and `camera-view`; this sample uses the system camera via `TakePicture`.
- No broad storage permission is needed for saving to MediaStore on Android 10+; the manifest intentionally avoids obsolete `WRITE_EXTERNAL_STORAGE`.
