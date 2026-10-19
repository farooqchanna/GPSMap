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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import com.google.openlocationcode.OpenLocationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var photoPreview: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var stampPreview: View
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
    private var stampTitle = "Pahore, Sindh, Pakistan 🇵🇰"
    private var stampAddress = ""
    private var stampCoords = ""
    private var stampDate = ""
    private var stampFontSize = 18f
    private var stampFontColor = Color.WHITE
    private var stampMapSizeDp = 122f
    private var showAddressLine = true
    private var showCoordinates = true
    private var showDateTime = true
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("Camera permission is required to take a photo.")
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { loadPhoto(it); showOptions() }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photoUri = it; loadPhoto(it); showOptions() }
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
        Configuration.getInstance().userAgentValue = packageName
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
            stampPreview.visibility = View.VISIBLE
            findViewById<Button>(R.id.configureButton).isEnabled = true
            findViewById<Button>(R.id.saveButton).isEnabled = true
        } catch (e: Exception) { toast("Unable to open this image.") }
    }

    private fun showOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_stamp_options, null)
        val mapWidget = dialogView.findViewById<MapView>(R.id.locationMap)
        mapView = mapWidget
        mapWidget.setTileSource(TileSourceFactory.MAPNIK)
        mapWidget.setMultiTouchControls(true)
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
        dialogView.findViewById<Button>(R.id.searchPlaceButton).setOnClickListener {
            val input = EditText(this).apply { hint = "Place or address" }
            AlertDialog.Builder(this).setTitle("Search address").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotBlank()) lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { Geocoder(this@MainActivity, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull() }
                            catch (_: Exception) { null }
                        }
                        if (result != null) {
                            setLocation(result.latitude, result.longitude)
                            mapWidget.controller.setCenter(GeoPoint(result.latitude, result.longitude))
                            addMapMarker(mapWidget, result.latitude, result.longitude)
                        } else toast("Address not found. Try a more specific search.")
                    }
                }.show()
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

    private fun setLocation(lat: Double, lon: Double) {
        chosenLat = lat; chosenLon = lon
        mapView?.let {
            it.controller.animateTo(GeoPoint(lat, lon))
            addMapMarker(it, lat, lon)
        }
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
        val root = stampPreview
        root.findViewById<TextView>(R.id.stampTitle)?.apply {
            text = stampTitle
            setTextColor(stampFontColor)
            textSize = stampFontSize
        }
        val bodySize = stampFontSize * 0.64f
        root.findViewById<TextView>(R.id.stampAddress)?.apply {
            text = stampAddress
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showAddressLine && stampAddress.isNotBlank()) View.VISIBLE else View.GONE
        }
        root.findViewById<TextView>(R.id.stampCoordinates)?.apply {
            text = stampCoords
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showCoordinates && stampCoords.isNotBlank()) View.VISIBLE else View.GONE
        }
        root.findViewById<TextView>(R.id.stampDateTime)?.apply {
            text = stampDate
            setTextColor(stampFontColor)
            textSize = bodySize
            visibility = if (showDateTime && stampDate.isNotBlank()) View.VISIBLE else View.GONE
        }
        root.findViewById<TextView>(R.id.stampNote)?.apply {
            text = "Note : Captured by GPS Map Camera"
            setTextColor(stampFontColor)
            textSize = bodySize
        }
        root.findViewById<ImageView>(R.id.stampMapImage)?.setImageBitmap(mapSnapshot)
        val mapCard = root.findViewById<View>(R.id.stampMapCard)
        mapCard?.layoutParams?.let {
            val sizePx = (stampMapSizeDp * resources.displayMetrics.density).toInt()
            it.width = sizePx
            it.height = sizePx
            mapCard.layoutParams = it
        }
    }

    private fun requestMapSnapshot() {
        val view = mapView ?: return
        try {
            val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            mapSnapshot = bitmap
            updateStampViews()
        } catch (_: Exception) { /* The stamp still works without a map thumbnail. */ }
    }

    private fun saveStampedPhoto() {
        val source = photoBitmap ?: return toast("Choose a photo first.")
        buildStampText()
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

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Box 2 (Location and Info Box background)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(184, 21, 23, 27) // ~72% opacity dark charcoal
        canvas.drawRoundRect(infoRect, cornerRadius, cornerRadius, paint)

        // Draw Box 1 (Map Box with rounded corners)
        val mapClipPath = Path().apply {
            addRoundRect(mapRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(mapClipPath)
        if (mapSnapshot != null) {
            canvas.drawBitmap(mapSnapshot!!, null, mapRect, paint)
        } else {
            paint.color = Color.rgb(33, 36, 41)
            canvas.drawRect(mapRect, paint)
        }

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
        paint.color = Color.rgb(234, 67, 53) // Google Maps Red
        paint.style = Paint.Style.FILL
        canvas.drawPath(pinPath, paint)

        // Pin inner white dot
        paint.color = Color.WHITE
        canvas.drawCircle(pinCenterX, headCenterY, pinRadius * 0.42f, paint)

        // Google watermark text at bottom-left of Map Box
        paint.color = Color.WHITE
        paint.textSize = mapSize * 0.13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.setShadowLayer(3f, 1f, 1f, Color.argb(180, 0, 0, 0))
        canvas.drawText(
            "Google",
            mapRect.left + mapSize * 0.08f,
            mapRect.bottom - mapSize * 0.07f,
            paint
        )
        paint.clearShadowLayer()
        canvas.restore()

        // Draw Floating Badge atop Box 2 (aligned to infoRect.right)
        val badgeHeight = infoWidth * 0.14f
        val badgePaddingX = badgeHeight * 0.36f
        val badgeBottom = infoRect.top - h * 0.005f
        val badgeTop = badgeBottom - badgeHeight

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = badgeHeight * 0.46f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            color = Color.WHITE
        }
        val badgeText = "GPS Map Camera"
        val badgeTextWidth = badgePaint.measureText(badgeText)
        val iconSize = badgeHeight * 0.56f
        val iconGap = badgeHeight * 0.22f
        val badgeWidth = badgePaddingX * 2 + iconSize + iconGap + badgeTextWidth
        val badgeLeft = infoRect.right - badgeWidth
        val badgeRight = infoRect.right
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
        val badgeRadius = badgeHeight * 0.28f

        paint.color = Color.argb(205, 24, 26, 29)
        canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, paint)

        // Badge Icon
        val iconLeft = badgeRect.left + badgePaddingX
        val iconTop = badgeRect.centerY() - iconSize / 2f
        val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        paint.color = Color.rgb(52, 168, 83)
        canvas.drawRoundRect(iconRect, iconSize * 0.22f, iconSize * 0.22f, paint)
        paint.color = Color.rgb(251, 188, 4)
        canvas.drawCircle(iconRect.centerX(), iconRect.centerY() - iconSize * 0.06f, iconSize * 0.24f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(iconRect.centerX(), iconRect.centerY() - iconSize * 0.06f, iconSize * 0.10f, paint)

        val badgeTextBaseline = badgeRect.centerY() - (badgePaint.descent() + badgePaint.ascent()) / 2f
        canvas.drawText(badgeText, iconLeft + iconSize + iconGap, badgeTextBaseline, badgePaint)

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
