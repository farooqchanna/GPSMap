package com.example.gpsmapcamera

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.openlocationcode.OpenLocationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var photoPreview: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var stampPreview: View
    private var photoUri: Uri? = null
    private var photoBitmap: Bitmap? = null
    private var mapSnapshot: Bitmap? = null
    private var chosenLat = 26.411997
    private var chosenLon = 67.864260
    private var chosenDate = Calendar.getInstance()
    private var currentAddress = "Sehwān, Sindh, Pakistan"
    private var addressLine = "Hyderabad Rd, Sehwān, Sindh, Pakistan"
    private var map: GoogleMap? = null
    private var mapView: MapView? = null
    private var optionsDialog: AlertDialog? = null
    private var stampTitle = "Sehwān, Sindh, Pakistan 🇵🇰"
    private var stampAddress = ""
    private var stampCoords = ""
    private var stampDate = ""
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("Camera permission is required to take a photo.")
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { loadPhoto(it); showOptions() }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photoUri = it; loadPhoto(it); showOptions() }
    }
    private val placesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            place.latLng?.let { setLocation(it.latitude, it.longitude) }
        } else if (result.resultCode == AutocompleteActivityMode.OVERLAY.ordinal) {
            // Cancellation is intentionally non-fatal.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        photoPreview = findViewById(R.id.photoPreview)
        emptyHint = findViewById(R.id.emptyHint)
        stampPreview = findViewById(R.id.stampPreview)
        findViewById<Button>(R.id.captureButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<Button>(R.id.configureButton).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveStampedPhoto() }
        val key = BuildConfig.MAPS_API_KEY
        if (key.isNotBlank() && key != "YOUR_MAPS_API_KEY") {
            if (!Places.isInitialized()) Places.initialize(applicationContext, key)
        }
    }

    private fun getStringResource(name: String): String = BuildConfig.MAPS_API_KEY

    private fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GPSCamera_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        photoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        photoUri?.let { takePicture.launch(it) } ?: toast("Could not create camera output.")
    }

    private fun loadPhoto(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { stream ->
                photoBitmap = BitmapFactory.decodeStream(stream)
            }
            photoPreview.setImageBitmap(photoBitmap)
            emptyHint.visibility = View.GONE
            stampPreview.visibility = View.VISIBLE
            findViewById<Button>(R.id.configureButton).isEnabled = true
            findViewById<Button>(R.id.saveButton).isEnabled = true
        } catch (e: Exception) { toast("Unable to open this image.") }
    }

    private fun showOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_stamp_options, null)
        val mapWidget = dialogView.findViewById<MapView>(R.id.locationMap)
        mapView = mapWidget
        mapWidget.onCreate(null)
        mapWidget.getMapAsync(this)
        val dateSummary = dialogView.findViewById<TextView>(R.id.dateTimeSummary)
        val locationSummary = dialogView.findViewById<TextView>(R.id.locationSummary)
        fun refreshDate() { dateSummary.text = formatDate(chosenDate.time) }
        refreshDate()
        locationSummary.text = "$currentAddress\nLat $chosenLat, Long $chosenLon"
        dialogView.findViewById<Button>(R.id.searchPlaceButton).setOnClickListener {
            try {
                val key = getStringResource("maps_api_key")
                if (!Places.isInitialized() && key.isNotBlank()) Places.initialize(applicationContext, key)
                val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY,
                    listOf(com.google.android.libraries.places.api.model.Place.Field.ID,
                        com.google.android.libraries.places.api.model.Place.Field.LAT_LNG,
                        com.google.android.libraries.places.api.model.Place.Field.ADDRESS))
                    .build(this)
                placesLauncher.launch(intent)
            } catch (e: Exception) { toast("Check the Maps/Places API key and enabled APIs.") }
        }
        dialogView.findViewById<Button>(R.id.useGpsButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 900)
            } else {
                LocationServices.getFusedLocationProviderClient(this).lastLocation
                    .addOnSuccessListener { loc -> if (loc != null) { setLocation(loc.latitude, loc.longitude); locationSummary.text = "$currentAddress\nLat $chosenLat, Long $chosenLon" } else toast("Current location is not available.") }
            }
        }
        dialogView.findViewById<CheckBox>(R.id.useCurrentTime).setOnCheckedChangeListener { _, checked ->
            if (checked) { chosenDate = Calendar.getInstance(); refreshDate() }
        }
        dialogView.findViewById<Button>(R.id.pickDateButton).setOnClickListener {
            val c = chosenDate
            DatePickerDialog(this, { _, y, m, d ->
                chosenDate.set(y, m, d); dialogView.findViewById<CheckBox>(R.id.useCurrentTime).isChecked = false; refreshDate()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        dialogView.findViewById<Button>(R.id.pickTimeButton).setOnClickListener {
            val c = chosenDate
            TimePickerDialog(this, { _, h, min ->
                chosenDate.set(Calendar.HOUR_OF_DAY, h); chosenDate.set(Calendar.MINUTE, min)
                dialogView.findViewById<CheckBox>(R.id.useCurrentTime).isChecked = false; refreshDate()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }
        optionsDialog = AlertDialog.Builder(this).setTitle("Stamp options").setView(dialogView)
            .setNegativeButton("Cancel", null).create()
        dialogView.findViewById<Button>(R.id.applyOptionsButton).setOnClickListener {
            buildStampText()
            updateStampViews()
            optionsDialog?.dismiss()
            requestMapSnapshot()
        }
        optionsDialog?.show()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
        googleMap.uiSettings.isMapToolbarEnabled = false
        googleMap.uiSettings.isCompassEnabled = false
        googleMap.uiSettings.isZoomControlsEnabled = false
        val pos = LatLng(chosenLat, chosenLon)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 17.5f))
        googleMap.addMarker(MarkerOptions().position(pos).draggable(true).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
        googleMap.setOnMapClickListener { latLng ->
            googleMap.clear()
            googleMap.addMarker(MarkerOptions().position(latLng).draggable(true))
            setLocation(latLng.latitude, latLng.longitude)
        }
        googleMap.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) { setLocation(marker.position.latitude, marker.position.longitude) }
        })
    }

    private fun setLocation(lat: Double, lon: Double) {
        chosenLat = lat; chosenLon = lon
        val pos = LatLng(lat, lon)
        map?.let { it.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17.5f)); it.clear(); it.addMarker(MarkerOptions().position(pos).draggable(true)) }
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { reverseGeocode(lat, lon) }
            currentAddress = data.first
            addressLine = data.second
            optionsDialog?.findViewById<TextView>(R.id.locationSummary)?.text = "$currentAddress\nLat $lat, Long $lon"
            buildStampText()
            updateStampViews()
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double): Pair<String, String> {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val a: Address? = if (Build.VERSION.SDK_INT >= 33) {
                var found: Address? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                geocoder.getFromLocation(lat, lon, 1) { list -> found = list.firstOrNull(); latch.countDown() }
                latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
                found
            } else {
                @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
            if (a == null) Pair("$lat, $lon", "") else {
                val city = a.locality ?: a.subAdminArea ?: a.subLocality ?: ""
                val state = a.adminArea ?: ""
                val country = a.countryName ?: ""
                val title = listOf(city, state, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
                val street = a.thoroughfare ?: a.featureName ?: ""
                val line = listOf(street, city, state, country).filter { it.isNotBlank() }.joinToString(", ")
                Pair(title.ifBlank { a.getAddressLine(0) ?: "$lat, $lon" }, line)
            }
        } catch (_: Exception) { Pair("$lat, $lon", "") }
    }

    private fun buildStampText() {
        val locale = Locale.getDefault()
        val parts = currentAddress.split(",").map { it.trim() }
        val country = parts.lastOrNull().orEmpty()
        stampTitle = "$currentAddress ${countryFlag(country)}".trim()
        val plus = try { OpenLocationCode(chosenLat, chosenLon).code } catch (_: Exception) { "" }
        stampAddress = (if (plus.isNotBlank()) "$plus, " else "") + addressLine
        stampCoords = String.format(Locale.US, "Lat %.6f° Long %.6f°", chosenLat, chosenLon)
        stampDate = formatDate(chosenDate.time)
    }

    private fun countryFlag(country: String): String {
        val code = when (country.lowercase(Locale.ROOT)) {
            "pakistan" -> "PK"; "india" -> "IN"; "united states", "united states of america" -> "US"
            "united kingdom" -> "GB"; "canada" -> "CA"; "australia" -> "AU"; "united arab emirates" -> "AE"
            "saudi arabia" -> "SA"; "bangladesh" -> "BD"; "nepal" -> "NP"; "china" -> "CN"
            "japan" -> "JP"; "germany" -> "DE"; "france" -> "FR"; "turkey", "türkiye" -> "TR"
            "sri lanka" -> "LK"; "afghanistan" -> "AF"; "iran" -> "IR"; "indonesia" -> "ID"
            else -> ""
        }
        return if (code.isEmpty()) "" else code.map { Character.toChars(0x1F1E6 + it.code - 'A'.code).concatToString() }.joinToString("")
    }

    private fun formatDate(date: Date): String {
        val zone = TimeZone.getDefault()
        val base = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a 'GMT' XXX", Locale.ENGLISH)
        base.timeZone = zone
        return base.format(date)
    }

    private fun updateStampViews() {
        val root = stampPreview
        root.findViewById<TextView>(R.id.stampTitle)?.text = stampTitle
        root.findViewById<TextView>(R.id.stampAddress)?.text = stampAddress
        root.findViewById<TextView>(R.id.stampCoordinates)?.text = stampCoords
        root.findViewById<TextView>(R.id.stampDateTime)?.text = stampDate
        root.findViewById<ImageView>(R.id.stampMapImage)?.setImageBitmap(mapSnapshot)
    }

    private fun requestMapSnapshot() {
        map?.snapshot { bitmap ->
            if (bitmap != null) {
                mapSnapshot = bitmap
                updateStampViews()
            }
        }
    }

    private fun saveStampedPhoto() {
        val source = photoBitmap ?: return toast("Choose a photo first.")
        buildStampText()
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val density = resources.displayMetrics.density
        val scale = output.width / photoPreview.width.coerceAtLeast(1).toFloat()
        val overlayScale = scale.coerceAtLeast(1f)
        val boxHeight = 160f * density * overlayScale
        val mapSize = 145f * density
        val badgeHeight = 26f * density
        val left = 0f
        val top = (output.height - boxHeight - 8f * density * overlayScale).coerceAtLeast(0f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.argb(136, 20, 20, 20)
        canvas.drawRect(left, top + badgeHeight, output.width.toFloat(), output.height.toFloat() - 8f*density, paint)
        paint.color = Color.argb(153, 26, 26, 26)
        val badgeW = 140f*density*overlayScale
        canvas.drawRect(output.width-badgeW, top, output.width.toFloat(), top+badgeHeight*overlayScale, paint)
        paint.color = Color.WHITE
        paint.textSize = 11f * resources.displayMetrics.scaledDensity * overlayScale
        canvas.drawText("📷  GPS Map Camera", output.width-badgeW+8f*density*overlayScale, top+18f*density*overlayScale, paint)

        val mapRect = RectF(0f, top+badgeHeight*overlayScale, mapSize*overlayScale, top+badgeHeight*overlayScale+mapSize*overlayScale)
        if (mapSnapshot != null) canvas.drawBitmap(mapSnapshot!!, null, mapRect, paint)
        else { paint.color = Color.DKGRAY; canvas.drawRect(mapRect, paint) }
        // Center marker and directional cone are composited independently of the map SDK snapshot.
        val cx = mapRect.centerX(); val cy = mapRect.centerY()
        val cone = Path().apply { moveTo(cx, cy); lineTo(cx-mapRect.width*.32f, cy+mapRect.height*.30f); lineTo(cx-mapRect.width*.12f, cy+mapRect.height*.08f); close() }
        paint.color = Color.argb(102, 33, 150, 243); canvas.drawPath(cone, paint)
        paint.color = Color.RED; canvas.drawCircle(cx, cy, 6f*density*overlayScale, paint)
        paint.color = Color.WHITE; paint.textSize = 16f*resources.displayMetrics.scaledDensity*overlayScale; paint.isFakeBoldText = true
        canvas.drawText("Google", 8f*density*overlayScale, mapRect.bottom-8f*density*overlayScale, paint)

        val tx = mapRect.right + 12f*density*overlayScale
        val maxWidth = output.width - tx - 12f*density*overlayScale
        fun text(value: String, size: Float, y: Float, color: Int = Color.WHITE): Float {
            paint.color = color; paint.textSize = size*resources.displayMetrics.scaledDensity*overlayScale; paint.isFakeBoldText = false
            paint.setShadowLayer(2f*overlayScale, 0f, 0f, Color.argb(128,0,0,0))
            val lines = wrapText(value, paint, maxWidth)
            var yy = y
            lines.take(2).forEach { canvas.drawText(it, tx, yy, paint); yy += paint.fontSpacing }
            paint.clearShadowLayer()
            return yy
        }
        var y = top + badgeHeight*overlayScale + 8f*density*overlayScale + 22f*overlayScale
        y = text(stampTitle, 22f, y)
        y = text(stampAddress, 14f, y+2f*overlayScale, Color.rgb(245,245,245))
        y = text(stampCoords, 14f, y+2f*overlayScale, Color.rgb(245,245,245))
        text(stampDate, 14f, y+2f*overlayScale, Color.rgb(245,245,245))

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GPS_Stamped_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GPSMapCamera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) return toast("Could not create gallery file.")
        try {
            contentResolver.openOutputStream(uri).use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, 95, stream!!)
            }
            toast("Stamped photo saved to Pictures/GPSMapCamera")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    private fun wrapText(value: String, paint: Paint, width: Float): List<String> {
        val words = value.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) <= width || line.isEmpty()) line = next
            else { lines.add(line); line = word }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 900 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            toast("Tap Use current GPS location again to fetch your location.")
        }
    }

    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
}
