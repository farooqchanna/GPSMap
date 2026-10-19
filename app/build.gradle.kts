plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val mapsApiKey: String = run {
    val props = java.util.Properties()
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
    props.getProperty("MAPS_API_KEY") ?: (project.findProperty("MAPS_API_KEY") as String? ?: "")
}
android {
    buildFeatures { buildConfig = true }
    namespace = "com.example.gpsmapcamera"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.gpsmapcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.libraries.places:places:4.1.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.openlocationcode:openlocationcode:1.0.4")
}
