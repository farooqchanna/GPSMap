package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextUtils
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import com.google.openlocationcode.OpenLocationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val STAMP_STYLE_DUAL_BOX = 0
        const val STAMP_STYLE_BOTTOM_BAND = 1

        /** A real, descriptive user agent. OSM tile servers reject generic / "com.example" ones. */
        const val USER_AGENT = "GPSMapCamera/1.0 (Android app; https://github.com/gpsmapcamera/app)"

        /** OpenStreetMap street tiles (needs the user agent above). */
        val STREET_TILES: ITileSource = XYTileSource(
            "OSM-Street", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/"
            ),
            "© OpenStreetMap contributors"
        )

        /** Esri World Imagery satellite tiles (no key needed, never shows "Access blocked"). */
        val SATELLITE_TILES: ITileSource = object : OnlineTileSourceBase(
            "Esri-Satellite", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
            "© Esri, Maxar, Earthstar Geographics"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex) + mImageFilenameEnding
        }
    }

    private data class StampData(
        val title: String,          // "Sehwān, Sindh, Pakistan 🇵🇰"
        val address: String,        // "Cv67+jgc, Hyderabad Rd, Sehwān, Sindh , Pakistan"
        val lat: Double,
        val lng: Double,
        val time: Date = Date(),
        val mapBitmap: Bitmap?,     // satellite snapshot with pin
        val badgeIcon: Bitmap?      // small app icon (optional)
    )

    private var selectedStampStyle = STAMP_STYLE_DUAL_BOX
    private var satelliteMap = true
    private lateinit var photoPreview: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var stampPreview1: View
    private lateinit var stampPreview2: View
    private lateinit var stampStyleTabLayout: TabLayout
    private var photoUri: Uri? = null
    private var photoBitmap: Bitmap? = null
    private var mapSnapshot: Bitmap? = null
    private var chosenLat = 25.366589
    private var chosenLon = 68.505276
    private var chosenDate = Calendar.getInstance()
    private var currentAddress = "Pahore, Sindh, Pakistan"
    private var addressLine = "Abbas Khan, Pahore, Sindh , Pakistan"
    private var mapMarker: Marker? = null
    private var mapView: MapView? = null
    private var optionsDialog: AlertDialog? = null
    private var geocodeJob: Job? = null
    private var mapAttribution = ""
    private data class PlaceHit(val label: String, val lat: Double, val lon: Double)
    private var stampTitle = "Pahore, Sindh, Pakistan 🇵🇰"
    private var stampAddress = ""
    private var stampCoords = ""
    private var stampDate = ""
    private var stampFontSize = 14f
    private var stampFontColor = Color.WHITE
    private var stampMapSizeDp = 94f
    private var showAddressLine = true
    private var showCoordinates = true
    private var showDateTime = true
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("Camera permission is required to take a photo.")
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { loadPhoto(it); showOptions() }
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveStampedPhoto() else toast("Storage permission is required to save the photo.")
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photoUri = it; loadPhoto(it); showOptions() }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        photoPreview = findViewById(R.id.photoPreview)
        emptyHint = findViewById(R.id.emptyHint)
        stampPreview1 = findViewById(R.id.stampPreview1)
        stampPreview2 = findViewById(R.id.stampPreview2)
        stampStyleTabLayout = findViewById(R.id.stampStyleTabLayout)
        stampStyleTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newStyle = tab?.position ?: 0
                if (selectedStampStyle != newStyle) {
                    selectedStampStyle = newStyle
                    updateStampViews()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        findViewById<View>(R.id.captureButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<View>(R.id.galleryButton).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<View>(R.id.configureButton).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveStampedPhoto() }
        // IMPORTANT: OpenStreetMap blocks tile requests that identify themselves with a default /
        // "com.example.*" user agent (that is what shows the "Access blocked" tile). Send a real one.
        val osmConfig = Configuration.getInstance()
        osmConfig.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osmConfig.userAgentValue = USER_AGENT
        val osmPath = File(cacheDir, "osmdroid")
        osmConfig.osmdroidBasePath = osmPath
        osmConfig.osmdroidTileCache = File(osmPath, "tiles")
        osmConfig.tileDownloadThreads = 2
        osmConfig.tileFileSystemThreads = 2
        buildStampText()
        fetchMapSnapshot(chosenLat, chosenLon)
        handleIncomingIntent(intent)
    }

    // A place shared from Google Maps (Share -> GPS Map Camera) arrives here while the app is open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setConfigureEnabled(enabled: Boolean) {
        findViewById<View>(R.id.configureButton).isEnabled = enabled
        findViewById<View>(R.id.configureLabel).alpha = if (enabled) 1f else 0.45f
    }

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
            setConfigureEnabled(true)
            findViewById<Button>(R.id.saveButton).isEnabled = true
            updateStampViews()
            if (mapSnapshot == null) {
                fetchMapSnapshot(chosenLat, chosenLon)
            }
        } catch (e: Exception) { toast("Unable to open this image.") }
    }

    private fun showOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_stamp_options, null)

        // Setup dialog tabs: Stamp Style | Location | Date & Details
        val dialogTabLayout = dialogView.findViewById<TabLayout>(R.id.dialogTabLayout)
        val tabContentStyle = dialogView.findViewById<View>(R.id.tabContentStyle)
        val tabContentLocation = dialogView.findViewById<View>(R.id.tabContentLocation)
        val tabContentDetails = dialogView.findViewById<View>(R.id.tabContentDetails)

        dialogTabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        tabContentStyle?.visibility = View.VISIBLE
                        tabContentLocation?.visibility = View.GONE
                        tabContentDetails?.visibility = View.GONE
                    }
                    1 -> {
                        tabContentStyle?.visibility = View.GONE
                        tabContentLocation?.visibility = View.VISIBLE
                        tabContentDetails?.visibility = View.GONE
                    }
                    2 -> {
                        tabContentStyle?.visibility = View.GONE
                        tabContentLocation?.visibility = View.GONE
                        tabContentDetails?.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Setup style selection cards inside dialog
        val cardStyle1 = dialogView.findViewById<View>(R.id.cardStyle1)
        val cardStyle2 = dialogView.findViewById<View>(R.id.cardStyle2)
        val radioStyle1 = dialogView.findViewById<RadioButton>(R.id.radioStyle1)
        val radioStyle2 = dialogView.findViewById<RadioButton>(R.id.radioStyle2)
        val summaryView = dialogView.findViewById<TextView>(R.id.styleDescriptionSummary)

        fun refreshStyleUi() {
            val isStyle1 = selectedStampStyle == STAMP_STYLE_DUAL_BOX
            radioStyle1?.isChecked = isStyle1
            radioStyle2?.isChecked = !isStyle1
            cardStyle1?.setBackgroundResource(if (isStyle1) R.drawable.bg_card_style_selected else R.drawable.bg_card_style_unselected)
            cardStyle2?.setBackgroundResource(if (!isStyle1) R.drawable.bg_card_style_selected else R.drawable.bg_card_style_unselected)
            summaryView?.text = if (isStyle1) "Active: Style 1 (Modern Dual Box)" else "Active: Style 2 (Classic Bottom Band from gps_stamp_style.md)"
            if (stampStyleTabLayout.selectedTabPosition != selectedStampStyle) {
                stampStyleTabLayout.getTabAt(selectedStampStyle)?.select()
            }
            updateStampViews()
        }
        refreshStyleUi()

        cardStyle1?.setOnClickListener {
            selectedStampStyle = STAMP_STYLE_DUAL_BOX
            refreshStyleUi()
        }
        cardStyle2?.setOnClickListener {
            selectedStampStyle = STAMP_STYLE_BOTTOM_BAND
            refreshStyleUi()
        }
        val mapWidget = dialogView.findViewById<MapView>(R.id.locationMap)
        mapView = mapWidget
        mapWidget.setTileSource(if (satelliteMap) SATELLITE_TILES else STREET_TILES)
        mapWidget.setMultiTouchControls(true)
        mapWidget.setBuiltInZoomControls(false)
        mapWidget.minZoomLevel = 3.0
        mapWidget.maxZoomLevel = 19.0
        mapWidget.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        mapWidget.controller.setZoom(16.0)
        mapWidget.controller.setCenter(GeoPoint(chosenLat, chosenLon))
        addMapMarker(mapWidget, chosenLat, chosenLon)
        val mapEvents = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                setLocation(p.latitude, p.longitude)
                addMapMarker(mapWidget, p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                setLocation(p.latitude, p.longitude)
                addMapMarker(mapWidget, p.latitude, p.longitude)
                return true
            }
        }
        mapWidget.overlays.add(MapEventsOverlay(mapEvents))
        mapWidget.invalidate()

        // Floating map controls: Street/Satellite toggle + zoom
        val layerButton = dialogView.findViewById<View>(R.id.mapLayerButton)
        val layerLabel = dialogView.findViewById<TextView>(R.id.mapLayerLabel)
        fun refreshLayerLabel() { layerLabel.text = if (satelliteMap) "Satellite" else "Street" }
        refreshLayerLabel()
        layerButton.setOnClickListener {
            satelliteMap = !satelliteMap
            mapWidget.setTileSource(if (satelliteMap) SATELLITE_TILES else STREET_TILES)
            refreshLayerLabel()
            mapWidget.invalidate()
        }
        dialogView.findViewById<View>(R.id.mapZoomIn).setOnClickListener { mapWidget.controller.zoomIn() }
        dialogView.findViewById<View>(R.id.mapZoomOut).setOnClickListener { mapWidget.controller.zoomOut() }
        val fontSizeControl = dialogView.findViewById<SeekBar>(R.id.stampFontSize)
        fontSizeControl.progress = (stampFontSize - 14f).toInt().coerceIn(0, 22)
        dialogView.findViewById<TextView>(R.id.fontSizeValue).text = "${stampFontSize.toInt()} sp"
        fontSizeControl.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                stampFontSize = (14 + progress).toFloat()
                dialogView.findViewById<TextView>(R.id.fontSizeValue).text = "${stampFontSize.toInt()} sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val mapSizeControl = dialogView.findViewById<SeekBar>(R.id.stampMapSize)
        mapSizeControl.progress = (stampMapSizeDp - 90f).toInt().coerceIn(0, 110)
        dialogView.findViewById<TextView>(R.id.mapSizeValue).text = "${stampMapSizeDp.toInt()} dp"
        mapSizeControl.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                stampMapSizeDp = (90 + progress).toFloat()
                dialogView.findViewById<TextView>(R.id.mapSizeValue).text = "${stampMapSizeDp.toInt()} dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val colorSpinner = dialogView.findViewById<Spinner>(R.id.stampFontColor)
        val colors = listOf("White", "Yellow", "Cyan", "Green", "Orange", "Pink")
        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)
        colorSpinner.setSelection(listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, 0xFFFFA500.toInt(), Color.MAGENTA).indexOf(stampFontColor).coerceAtLeast(0))
        colorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                stampFontColor = listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, 0xFFFFA500.toInt(), Color.MAGENTA)[position]
            }
        }
        dialogView.findViewById<CheckBox>(R.id.showAddressLine).isChecked = showAddressLine
        dialogView.findViewById<CheckBox>(R.id.showCoordinates).isChecked = showCoordinates
        dialogView.findViewById<CheckBox>(R.id.showDateTime).isChecked = showDateTime
        dialogView.findViewById<CheckBox>(R.id.showAddressLine).setOnCheckedChangeListener { _, checked -> showAddressLine = checked }
        dialogView.findViewById<CheckBox>(R.id.showCoordinates).setOnCheckedChangeListener { _, checked -> showCoordinates = checked }
        dialogView.findViewById<CheckBox>(R.id.showDateTime).setOnCheckedChangeListener { _, checked -> showDateTime = checked }

        val dateSummary = dialogView.findViewById<TextView>(R.id.dateTimeSummary)
        val locationSummary = dialogView.findViewById<TextView>(R.id.locationSummary)
        fun refreshDate() { dateSummary.text = formatDate(chosenDate.time) }
        refreshDate()
        locationSummary.text = "$currentAddress\nLat $chosenLat, Long $chosenLon"
        dialogView.findViewById<View>(R.id.searchPlaceButton).setOnClickListener {
            val input = EditText(this).apply { hint = "Place or address"; setSingleLine() }
            AlertDialog.Builder(this).setTitle("Search address").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotBlank()) lifecycleScope.launch {
                        toast("Searching…")
                        val hits = withContext(Dispatchers.IO) { searchPlaces(query) }
                        when {
                            hits == null -> toast("Search failed. Check your internet connection and try again.")
                            hits.isEmpty() -> toast("Address not found. Try a more specific search.")
                            hits.size == 1 -> applySearchHit(mapWidget, hits[0])
                            else -> AlertDialog.Builder(this@MainActivity).setTitle("Choose a result")
                                .setItems(hits.map { it.label }.toTypedArray()) { _, i -> applySearchHit(mapWidget, hits[i]) }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }
                }.show()
        }
        dialogView.findViewById<View>(R.id.openGoogleMapsButton).setOnClickListener { openGoogleMaps() }
        dialogView.findViewById<View>(R.id.pasteLinkButton).setOnClickListener { pasteLocationFromClipboard() }
        dialogView.findViewById<View>(R.id.useGpsButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 900)
            } else fetchGpsLocation()
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
            fetchMapSnapshot(chosenLat, chosenLon)
        }
        optionsDialog?.show()
    }

    private fun addMapMarker(widget: MapView, lat: Double, lon: Double) {
        widget.overlays.removeAll { it is Marker }
        val marker = Marker(widget).apply {
            position = GeoPoint(lat, lon)
            title = "Selected location"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        widget.overlays.add(marker)
        widget.invalidate()
    }

    private fun applySearchHit(widget: MapView, hit: PlaceHit) {
        setLocation(hit.lat, hit.lon)
        widget.controller.setZoom(16.0)
        widget.controller.setCenter(GeoPoint(hit.lat, hit.lon))
        addMapMarker(widget, hit.lat, hit.lon)
    }

    @SuppressLint("MissingPermission")
    private fun fetchGpsLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null) setLocation(loc.latitude, loc.longitude)
                else client.lastLocation.addOnSuccessListener { last ->
                    if (last != null) setLocation(last.latitude, last.longitude) else toast("Current location is not available. Turn on GPS and try again.")
                }
            }
            .addOnFailureListener { toast("Could not get GPS location: ${it.message}") }
    }

    private fun setLocation(lat: Double, lon: Double) {
        chosenLat = lat; chosenLon = lon
        mapView?.let {
            it.controller.animateTo(GeoPoint(lat, lon))
            addMapMarker(it, lat, lon)
        }
        fetchMapSnapshot(lat, lon)
        optionsDialog?.findViewById<TextView>(R.id.locationSummary)?.text = "Finding address…\nLat $lat, Long $lon"
        geocodeJob?.cancel()
        geocodeJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { reverseGeocode(lat, lon) }
            currentAddress = data.first
            addressLine = data.second
            optionsDialog?.findViewById<TextView>(R.id.locationSummary)?.text = "$currentAddress\nLat $lat, Long $lon"
            buildStampText()
            updateStampViews()
        }
    }

    // ---------- Google Maps link import (no API key, no billing) ----------

    /** Opens the Google Maps app (or the website if the app is missing) so the person can search there. */
    private fun openGoogleMaps() {
        toast("Search a place in Google Maps, tap Share, then choose GPS Map Camera.")
        val geo = Uri.parse("geo:$chosenLat,$chosenLon?z=15")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, geo).setPackage("com.google.android.apps.maps"))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@$chosenLat,$chosenLon,15z")))
            } catch (_: ActivityNotFoundException) {
                toast("No Google Maps app or browser found on this phone.")
            }
        }
    }

    private fun pasteLocationFromClipboard() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) {
            toast("Nothing copied yet. In Google Maps open a place, tap Share > Copy, then come back and tap Paste.")
        } else importLocationFromText(text)
    }

    private fun handleIncomingIntent(i: Intent?) {
        if (i == null) return
        val text = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        }
        if (text.isNullOrBlank()) return
        intent = Intent() // consume it so a screen rotation does not import it again
        importLocationFromText(text)
    }

    private fun importLocationFromText(raw: String) {
        toast("Reading location from Google Maps…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { resolveSharedLocation(raw) } catch (_: Exception) { null }
            }
            if (result == null) {
                toast("Could not find a location in that link. In Google Maps open a place, tap Share, then choose GPS Map Camera.")
                return@launch
            }
            setLocation(result.first, result.second)
            toast("Location set from Google Maps")
            if (optionsDialog?.isShowing != true) showOptions()
        }
    }

    private fun validLatLon(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** Finds coordinates inside a Google Maps link / shared text. [allowPlain] also accepts "25.36, 68.50". */
    private fun parseLatLon(text: String, allowPlain: Boolean): Pair<Double, Double>? {
        val num = "(-?\\d{1,3}\\.\\d+)"
        val patterns = mutableListOf(
            "!3d$num!4d$num",                               // exact place pin inside long Google links
            "@$num,\\s*$num",                               // map centre in the address bar
            "[?&](?:q|query|ll|center|destination|daddr)=$num(?:,|%2C)\\s*$num",
            "geo:$num,$num"
        )
        if (allowPlain) patterns.add("$num\\s*,\\s*$num")
        for (pattern in patterns) {
            val m = Regex(pattern).find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (validLatLon(lat, lon)) return lat to lon
        }
        return null
    }

    /** Follows short links (maps.app.goo.gl ...) and returns every URL on the way, plus the final page text. */
    private fun followLink(start: String): Pair<List<String>, String> {
        val urls = mutableListOf<String>()
        var current = start
        var body = ""
        for (hop in 0 until 8) {
            urls.add(current)
            runCatching { urls.add(URLDecoder.decode(current, "UTF-8")) }
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                conn.setRequestProperty("Accept-Language", "en")
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code == 200) {
                    val sb = StringBuilder()
                    conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (sb.length < 300_000) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
                    }
                    body = sb.toString()
                }
                break
            } finally { conn.disconnect() }
        }
        return urls to body
    }

    private fun resolveSharedLocation(raw: String): Pair<Double, Double>? {
        // 1) coordinates written directly in the text / link
        parseLatLon(raw, allowPlain = true)?.let { return it }

        val link = Regex("https?://\\S+").find(raw)?.value?.trimEnd('.', ',', ')', ']', '>')
        val nameCandidates = mutableListOf<String>()
        if (link != null) {
            // 2) open the short link and look for coordinates in every redirect + the page itself
            val (urls, body) = try { followLink(link) } catch (_: Exception) { emptyList<String>() to "" }
            for (u in urls) parseLatLon(u, allowPlain = false)?.let { return it }
            if (body.isNotEmpty()) parseLatLon(body, allowPlain = false)?.let { return it }
            // 3) fall back to the place name inside the link: /maps/place/Some+Place/
            for (u in urls) {
                Regex("/maps/(?:place|search)/([^/@?]+)").find(u)?.groupValues?.get(1)?.let {
                    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()?.takeIf { n -> n.isNotBlank() }?.let { n -> nameCandidates.add(n) }
                }
            }
        }
        // 4) Google Maps also puts the place name / address above the link in the shared text
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("http") }.take(2).forEach { nameCandidates.add(it) }

        for (name in nameCandidates.distinct()) {
            val hit = searchPlaces(name)?.firstOrNull()
            if (hit != null) return hit.lat to hit.lon
        }
        return null
    }

    // ---------- Geocoding: OpenStreetMap Nominatim -> Photon -> Android Geocoder (fallbacks) ----------

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** Returns null if every provider failed (network error), empty list if nothing was found. */
    private fun searchPlaces(query: String): List<PlaceHit>? {
        val q = URLEncoder.encode(query, "UTF-8")
        var anySuccess = false
        try {
            val arr = JSONArray(httpGet("https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=5"))
            anySuccess = true
            val hits = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PlaceHit(o.getString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble())
            }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }
        try {
            val features = JSONObject(httpGet("https://photon.komoot.io/api/?q=$q&limit=5")).getJSONArray("features")
            anySuccess = true
            val hits = (0 until features.length()).map {
                val f = features.getJSONObject(it)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val pr = f.getJSONObject("properties")
                val label = listOf("name", "street", "city", "state", "country").mapNotNull { k -> pr.optString(k).ifBlank { null } }.distinct().joinToString(", ")
                PlaceHit(label, c.getDouble(1), c.getDouble(0))
            }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(this, Locale.getDefault()).getFromLocationName(query, 5)
            anySuccess = true
            val hits = list.orEmpty().map { PlaceHit(it.getAddressLine(0) ?: query, it.latitude, it.longitude) }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }
        return if (anySuccess) emptyList() else null
    }

    private fun reverseGeocode(lat: Double, lon: Double): Pair<String, String> {
        try {
            val json = JSONObject(httpGet("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=$lat&lon=$lon"))
            val a = json.optJSONObject("address")
            if (a != null) {
                fun f(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).ifBlank { null } } ?: ""
                val city = f("city", "town", "village", "municipality", "city_district", "suburb", "county", "state_district")
                val state = f("state", "region")
                val country = f("country")
                val street = f("road", "pedestrian", "neighbourhood", "suburb", "hamlet")
                val title = listOf(city, state, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
                val line = listOf(street, city, state, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
                if (title.isNotBlank()) return Pair(title, line)
            }
            json.optString("display_name").ifBlank { null }?.let { return Pair(it, it) }
        } catch (_: Exception) { }
        return geocoderReverse(lat, lon)
    }

    private fun geocoderReverse(lat: Double, lon: Double): Pair<String, String> {
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
        val parts = currentAddress.split(",").map { it.trim() }
        val country = parts.lastOrNull().orEmpty()
        val flag = countryFlag(country)
        stampTitle = if (flag.isNotEmpty()) "$currentAddress $flag" else currentAddress

        val plus = try {
            val full = OpenLocationCode(chosenLat, chosenLon).code
            val idx = full.indexOf('+')
            if (idx >= 4) full.substring(idx - 4).lowercase(Locale.ROOT) else full.lowercase(Locale.ROOT)
        } catch (_: Exception) { "" }

        stampAddress = if (plus.isNotBlank() && addressLine.isNotBlank()) {
            "$plus $addressLine"
        } else if (plus.isNotBlank()) {
            plus
        } else {
            addressLine
        }

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
        val isLoaded = photoBitmap != null
        stampPreview1.visibility = if (isLoaded && selectedStampStyle == STAMP_STYLE_DUAL_BOX) View.VISIBLE else View.GONE
        stampPreview2.visibility = if (isLoaded && selectedStampStyle == STAMP_STYLE_BOTTOM_BAND) View.VISIBLE else View.GONE

        // Update Style 1 views
        stampPreview1.findViewById<TextView>(R.id.stampTitle)?.apply {
            text = stampTitle
            setTextColor(stampFontColor)
            textSize = stampFontSize
        }
        val bodySize = stampFontSize * 0.64f
        stampPreview1.findViewById<TextView>(R.id.stampAddress)?.apply {
            text = stampAddress
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showAddressLine && stampAddress.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview1.findViewById<TextView>(R.id.stampCoordinates)?.apply {
            text = stampCoords
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showCoordinates && stampCoords.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview1.findViewById<TextView>(R.id.stampDateTime)?.apply {
            text = stampDate
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showDateTime && stampDate.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview1.findViewById<TextView>(R.id.stampNote)?.apply {
            text = "Note : Captured by GPS Map Camera"
            setTextColor(stampFontColor)
            textSize = bodySize
        }
        stampPreview1.findViewById<ImageView>(R.id.stampMapImage)?.setImageBitmap(mapSnapshot)
        stampPreview1.findViewById<TextView>(R.id.stampMapCredit)?.text = mapAttribution
        val mapCard = stampPreview1.findViewById<View>(R.id.stampMapCard)
        mapCard?.layoutParams?.let {
            val sizePx = (stampMapSizeDp * resources.displayMetrics.density).toInt()
            it.width = sizePx
            it.height = sizePx
            mapCard.layoutParams = it
        }

        // Update Style 2 views (Style spec from gps_stamp_style.md)
        stampPreview2.findViewById<TextView>(R.id.stampTitle2)?.text = stampTitle
        stampPreview2.findViewById<TextView>(R.id.stampAddress2)?.apply {
            text = stampAddress
            visibility = if (showAddressLine && stampAddress.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview2.findViewById<TextView>(R.id.stampLatLng2)?.apply {
            text = stampCoords
            visibility = if (showCoordinates && stampCoords.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview2.findViewById<TextView>(R.id.stampTime2)?.apply {
            text = stampDate
            visibility = if (showDateTime && stampDate.isNotBlank()) View.VISIBLE else View.GONE
        }
        stampPreview2.findViewById<ImageView>(R.id.stampMap2)?.setImageBitmap(mapSnapshot)
        stampPreview2.findViewById<TextView>(R.id.stampMapCredit2)?.text = mapAttribution
    }

    private fun getBadgeIconBitmap(sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = ContextCompat.getDrawable(this, R.drawable.ic_gps_camera_lens_logo)
            ?: ContextCompat.getDrawable(this, R.drawable.ic_gps_badge_logo)
        d?.setBounds(0, 0, sizePx, sizePx)
        d?.draw(c)
        return bmp
    }

    /** Style 2: Exact implementation from gps_stamp_style.md */
    private fun drawGpsStampStyle2(src: Bitmap, d: StampData): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val s = out.width / 1200f
        val H = out.height.toFloat()
        val W = out.width.toFloat()

        // A) bottom band (345 px tall, anchored to the bottom)
        val bandH = 345f * s
        val bandTop = H - bandH
        c.drawRect(0f, bandTop, W, H, Paint().apply { color = 0x80000000.toInt() })

        // B) map thumbnail 285x285, flush left, top of band
        val mapSize = 285f * s
        val mapRect = RectF(0f, bandTop, mapSize, bandTop + mapSize)
        val currentSnapshot = d.mapBitmap ?: generateFallbackMapBitmap(d.lat, d.lng)
        c.drawBitmap(currentSnapshot, null, mapRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        // Center red pin on map (about 40 px tall, color #E53935)
        val pinCenterX = mapSize / 2f
        val pinCenterY = bandTop + mapSize / 2f
        val pinHeight = 40f * s
        val pinWidth = pinHeight * 0.75f
        val pinRadius = pinWidth / 2f
        val pinTipY = pinCenterY + pinHeight * 0.40f
        val headCenterY = pinTipY - pinHeight + pinRadius

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        val pinShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
        }
        c.drawOval(
            RectF(pinCenterX - pinRadius * 0.7f, pinTipY - pinRadius * 0.15f, pinCenterX + pinRadius * 0.7f, pinTipY + pinRadius * 0.35f),
            pinShadowPaint
        )
        val pinPath = Path().apply {
            moveTo(pinCenterX, pinTipY)
            cubicTo(
                pinCenterX + pinRadius * 0.9f, pinTipY - pinHeight * 0.35f,
                pinCenterX + pinRadius, headCenterY + pinRadius * 0.5f,
                pinCenterX + pinRadius, headCenterY
            )
            arcTo(
                RectF(pinCenterX - pinRadius, headCenterY - pinRadius, pinCenterX + pinRadius, headCenterY + pinRadius),
                0f, -180f, false
            )
            cubicTo(
                pinCenterX - pinRadius, headCenterY + pinRadius * 0.5f,
                pinCenterX - pinRadius * 0.9f, pinTipY - pinHeight * 0.35f,
                pinCenterX, pinTipY
            )
            close()
        }
        c.drawPath(pinPath, pinPaint)
        c.drawCircle(pinCenterX, headCenterY, pinRadius * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // Map attribution / logo: bottom-left with 12 px margin
        if (mapAttribution.isNotBlank()) {
            val attrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 22f * s
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(3f * s, 1f * s, 1f * s, Color.argb(180, 0, 0, 0))
            }
            c.drawText(mapAttribution, 12f * s, bandTop + mapSize - 12f * s, attrPaint)
        }

        // C) text block
        val shadow = { p: TextPaint -> p.setShadowLayer(2f * s, 1f * s, 1f * s, 0x66000000) }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 54f * s
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); shadow(this)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 36f * s
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); shadow(this)
        }
        val x = 307f * s
        val textW = (W - x - 20f * s).toInt().coerceAtLeast(10)
        val y = bandTop + 75f * s                      // title baseline (1330 - 1255)
        c.drawText(d.title, x, y, titlePaint)

        // address (wraps, max 2 lines)
        if (d.address.isNotBlank()) {
            val layout = StaticLayout.Builder
                .obtain(d.address, 0, d.address.length, bodyPaint, textW)
                .setLineSpacing(0f, 1.1f).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).build()
            c.save()
            c.translate(x, bandTop + 75f * s + 12f * s)    // top of address block
            layout.draw(c)
            c.restore()
        }

        val dateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a 'GMT' xxx", Locale.ENGLISH)
        dateFmt.timeZone = TimeZone.getDefault()
        val gmt = dateFmt.format(d.time)
        c.drawText("Lat ${"%.6f".format(Locale.US, d.lat)}° Long ${"%.5f".format(Locale.US, d.lng)}°",
            x, bandTop + 217f * s, bodyPaint)
        c.drawText(gmt, x, bandTop + 262f * s, bodyPaint)

        // D) top-right badge "GPS Map Camera"
        val bx1 = W; val bx0 = W - 232f * s
        val by1 = bandTop; val by0 = bandTop - 55f * s
        val path = Path().apply {
            addRoundRect(RectF(bx0, by0, bx1, by1),
                floatArrayOf(14f * s, 14f * s, 0f, 0f, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() })
        d.badgeIcon?.let {
            c.drawBitmap(it, null, RectF(bx0 + 12f * s, by0 + 10f * s,
                bx0 + 46f * s, by0 + 44f * s), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 22f * s
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText("GPS Map Camera", bx0 + 54f * s, by0 + 36f * s, badgeText)

        return out
    }

    /** Style 1: Preserved original floating dual box stamp */
    private fun drawGpsStampStyle1(source: Bitmap): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val w = output.width.toFloat()
        val h = output.height.toFloat()

        // Proportional spacing calibrated exactly to the reference photo
        val marginX = w * 0.038f
        val marginY = h * 0.032f
        val gap = w * 0.024f
        val baselineBottom = h - marginY

        // Box 1: Square Map Box (1:1 ratio)
        val mapSize = w * 0.285f
        val mapRect = RectF(marginX, baselineBottom - mapSize, marginX + mapSize, baselineBottom)

        // Box 2: Location and Info Box (Taller, sits beside the map box)
        val infoLeft = marginX + mapSize + gap
        val infoRight = w - marginX
        val infoWidth = infoRight - infoLeft

        // Corner radius for both boxes
        val cornerRadius = w * 0.022f

        // Internal padding of Info Box
        val padX = infoWidth * 0.060f
        val padY = infoWidth * 0.052f
        val contentWidth = infoWidth - padX * 2

        val titleSize = infoWidth * 0.066f
        val bodySize = infoWidth * 0.043f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stampFontColor
            textSize = titleSize
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(2f, 0f, 1f, Color.argb(120, 0, 0, 0))
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stampFontColor
            textSize = bodySize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(2f, 0f, 1f, Color.argb(120, 0, 0, 0))
        }

        fun ellipsizeText(text: String, paint: Paint, maxW: Float): String {
            if (paint.measureText(text) <= maxW) return text
            var t = text
            while (t.isNotEmpty() && paint.measureText("$t…") > maxW) {
                t = t.dropLast(1)
            }
            return if (t.isEmpty()) "" else "$t…"
        }

        // Prepare visible text lines
        val linesToDraw = mutableListOf<Pair<String, Paint>>()
        linesToDraw.add(Pair(stampTitle, titlePaint))
        if (showAddressLine && stampAddress.isNotBlank()) {
            linesToDraw.add(Pair(stampAddress, bodyPaint))
        }
        if (showCoordinates && stampCoords.isNotBlank()) {
            linesToDraw.add(Pair(stampCoords, bodyPaint))
        }
        if (showDateTime && stampDate.isNotBlank()) {
            linesToDraw.add(Pair(stampDate, bodyPaint))
        }
        linesToDraw.add(Pair("Note : Captured by GPS Map Camera", bodyPaint))

        // Calculate height required for Info Box
        val lineSpacing = bodySize * 0.32f
        var totalTextHeight = titleSize
        for (i in 1 until linesToDraw.size) {
            totalTextHeight += lineSpacing + bodySize
        }
        val infoHeight = totalTextHeight + padY * 2
        val infoRect = RectF(infoLeft, baselineBottom - infoHeight, infoRight, baselineBottom)

        // Attached Top Badge: directly flush with location box (NO space between them)
        val badgeHeight = infoWidth * 0.13f
        val badgePaddingX = badgeHeight * 0.40f
        val badgeBottom = infoRect.top // ZERO GAP: flush on top of location box
        val badgeTop = badgeBottom - badgeHeight

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = badgeHeight * 0.46f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            color = Color.WHITE
        }
        val badgeText = "GPS Map Camera"
        val badgeTextWidth = badgePaint.measureText(badgeText)
        val iconSize = badgeHeight * 0.62f
        val iconGap = badgeHeight * 0.22f
        val badgeWidth = badgePaddingX * 2 + iconSize + iconGap + badgeTextWidth
        val badgeLeft = infoRect.right - badgeWidth
        val badgeRight = infoRect.right
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
        val badgeRadius = badgeHeight * 0.30f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw ONE unified box: Location Box body + flush Top Badge tab
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(184, 21, 23, 27) // ~72% opacity dark charcoal
        canvas.drawRoundRect(infoRect, cornerRadius, cornerRadius, paint)
        val badgeTabPath = Path().apply {
            addRoundRect(
                badgeRect,
                floatArrayOf(badgeRadius, badgeRadius, badgeRadius, badgeRadius, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
        canvas.drawPath(badgeTabPath, paint)

        // Draw Camera Lens Logo inside badge (matching user's reference image 1)
        val iconCenterX = badgeRect.left + badgePaddingX + iconSize / 2f
        val iconCenterY = badgeRect.centerY()
        val r = iconSize / 2f

        // 1. Golden Yellow outer camera ring
        paint.color = Color.rgb(255, 184, 0)
        canvas.drawCircle(iconCenterX, iconCenterY, r, paint)

        // 2. Slate blue-grey lens bezel
        paint.color = Color.rgb(69, 77, 96)
        canvas.drawCircle(iconCenterX, iconCenterY, r * 0.77f, paint)

        // 3. Dark navy inner lens body
        paint.color = Color.rgb(38, 42, 54)
        canvas.drawCircle(iconCenterX, iconCenterY, r * 0.58f, paint)

        // 4. Deep black center pupil/sensor
        paint.color = Color.rgb(17, 19, 24)
        canvas.drawCircle(iconCenterX, iconCenterY, r * 0.38f, paint)

        // 5. Specular highlight dots
        paint.color = Color.WHITE
        canvas.drawCircle(iconCenterX + r * 0.22f, iconCenterY - r * 0.22f, r * 0.16f, paint)
        canvas.drawCircle(iconCenterX + r * 0.38f, iconCenterY + r * 0.10f, r * 0.08f, paint)

        // Draw badge text
        val badgeTextBaseline = badgeRect.centerY() - (badgePaint.descent() + badgePaint.ascent()) / 2f
        canvas.drawText(badgeText, badgeRect.left + badgePaddingX + iconSize + iconGap, badgeTextBaseline, badgePaint)

        // Draw Box 1 (Map Box with rounded corners)
        val mapClipPath = Path().apply {
            addRoundRect(mapRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(mapClipPath)
        val currentSnapshot = mapSnapshot ?: generateFallbackMapBitmap(chosenLat, chosenLon)
        canvas.drawBitmap(currentSnapshot, null, mapRect, paint)

        // Draw center red pin on Map Box
        val pinCenterX = mapRect.centerX()
        val pinCenterY = mapRect.centerY()
        val pinHeight = mapSize * 0.22f
        val pinWidth = pinHeight * 0.75f
        val pinRadius = pinWidth / 2f
        val pinTipY = pinCenterY + pinHeight * 0.40f
        val headCenterY = pinTipY - pinHeight + pinRadius

        // Soft drop shadow beneath pin tip
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(
            RectF(pinCenterX - pinRadius * 0.7f, pinTipY - pinRadius * 0.15f, pinCenterX + pinRadius * 0.7f, pinTipY + pinRadius * 0.35f),
            paint
        )

        // Pin body
        val pinPath = Path().apply {
            moveTo(pinCenterX, pinTipY)
            cubicTo(
                pinCenterX + pinRadius * 0.9f, pinTipY - pinHeight * 0.35f,
                pinCenterX + pinRadius, headCenterY + pinRadius * 0.5f,
                pinCenterX + pinRadius, headCenterY
            )
            arcTo(
                RectF(pinCenterX - pinRadius, headCenterY - pinRadius, pinCenterX + pinRadius, headCenterY + pinRadius),
                0f, -180f, false
            )
            cubicTo(
                pinCenterX - pinRadius, headCenterY + pinRadius * 0.5f,
                pinCenterX - pinRadius * 0.9f, pinTipY - pinHeight * 0.35f,
                pinCenterX, pinTipY
            )
            close()
        }
        paint.color = Color.rgb(234, 67, 53) // Red
        paint.style = Paint.Style.FILL
        canvas.drawPath(pinPath, paint)

        // Pin inner white dot
        paint.color = Color.WHITE
        canvas.drawCircle(pinCenterX, headCenterY, pinRadius * 0.42f, paint)

        // Map attribution at bottom-left of Map Box
        if (mapAttribution.isNotBlank() && mapSnapshot != null) {
            paint.color = Color.WHITE
            paint.textSize = mapSize * 0.09f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.setShadowLayer(3f, 1f, 1f, Color.argb(180, 0, 0, 0))
            canvas.drawText(mapAttribution, mapRect.left + mapSize * 0.06f, mapRect.bottom - mapSize * 0.06f, paint)
            paint.clearShadowLayer()
        }
        canvas.restore()

        // Draw Text Lines in Box 2
        var currentY = infoRect.top + padY + titleSize * 0.88f
        for (i in linesToDraw.indices) {
            val (text, p) = linesToDraw[i]
            val truncated = ellipsizeText(text, p, contentWidth)
            canvas.drawText(truncated, infoRect.left + padX, currentY, p)
            if (i < linesToDraw.size - 1) {
                val nextSize = linesToDraw[i + 1].second.textSize
                currentY += nextSize + lineSpacing
            }
        }

        return output
    }

    private fun saveStampedPhoto() {
        val source = photoBitmap ?: return toast("Choose a photo first.")
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        buildStampText()

        val output: Bitmap = if (selectedStampStyle == STAMP_STYLE_BOTTOM_BAND) {
            val scale = source.width / 1200f
            val badgeBmp = getBadgeIconBitmap((68 * scale).toInt().coerceAtLeast(34))
            val stampData = StampData(
                title = stampTitle,
                address = if (showAddressLine) stampAddress else "",
                lat = chosenLat,
                lng = chosenLon,
                time = chosenDate.time,
                mapBitmap = mapSnapshot ?: generateFallbackMapBitmap(chosenLat, chosenLon),
                badgeIcon = badgeBmp
            )
            drawGpsStampStyle2(source, stampData)
        } else {
            drawGpsStampStyle1(source)
        }

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

    private fun fetchMapSnapshot(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tile = downloadSatelliteMapTile(lat, lon) ?: generateFallbackMapBitmap(lat, lon).also { mapAttribution = "" }
            withContext(Dispatchers.Main) {
                mapSnapshot = tile
                updateStampViews()
            }
        }
    }

    private fun downloadSatelliteMapTile(lat: Double, lon: Double): Bitmap? {
        val zoom = 16
        val n = 1 shl zoom
        val xExact = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val yExact = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n
        val tileX = xExact.toInt()
        val tileY = yExact.toInt()

        val urls = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$tileY/$tileX.jpg" to "© Esri",
            "https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png" to "© OpenStreetMap"
        )

        for ((urlString, credit) in urls) {
            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                if (conn.responseCode == 200) {
                    val stream = conn.inputStream
                    val original = BitmapFactory.decodeStream(stream)
                    stream.close()
                    conn.disconnect()
                    if (original != null) {
                        mapAttribution = credit
                        return original
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun generateFallbackMapBitmap(lat: Double, lon: Double): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.rgb(36, 44, 38)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)

        p.color = Color.rgb(46, 58, 48)
        c.drawRect(20f, 15f, 110f, 120f, p)
        c.drawRect(130f, 30f, 240f, 100f, p)
        c.drawRect(15f, 140f, 125f, 245f, p)
        c.drawRect(145f, 120f, 245f, 240f, p)

        p.color = Color.rgb(75, 85, 78)
        p.strokeWidth = 3f
        p.style = Paint.Style.STROKE
        c.drawLine(0f, 130f, 256f, 115f, p)
        c.drawLine(125f, 0f, 135f, 256f, p)
        p.style = Paint.Style.FILL

        return bmp
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
            fetchGpsLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        // osmdroid MapView does not provide Google Maps-style onDestroy().
        mapView?.onDetach()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }
}
