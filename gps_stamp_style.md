# GPS Map Camera Stamp: Style Spec + Android Code

Reference photo: 1200 x 1600 px. Every size below is in px at 1200 px width.
Scale rule: `s = photoWidth / 1200f`, then multiply every value by `s`.

## 1. Layout spec

### A) Bottom info band
| Property | Value |
|---|---|
| Position | Full width, bottom of photo |
| Height | about 345 px (about 21.5% of photo height) |
| Top edge | y = 1255 (of 1600) |
| Background | Black, 50% opacity: `#80000000` (photo shows through) |
| Corners | Square |

### B) Map thumbnail (left side of band)
| Property | Value |
|---|---|
| Size | about 285 x 285 px, square |
| Position | x = 0, y = 1255 (flush left, top of band) |
| Content | Google satellite map snapshot, zoom about 17-18 |
| Red pin | Centered, about 40 px tall, color `#E53935` |
| Blue overlay | Translucent blue polygon (`#663F51B5`) near the center, optional |
| "Google" logo | White, bold, about 40 px, bottom-left with 12 px margin (Google requires it with Maps imagery) |

### C) Text block (right of the map)
| Property | Value |
|---|---|
| Left padding | x = 307 (22 px gap after map) |
| Font | Roboto (Android default sans-serif) |
| Color | Pure white `#FFFFFF`, with a slight shadow: radius 2, dx 1, dy 1, `#66000000` |
| Line 1 (place title) | Roboto Medium, 54 px, baseline y = 1330. Format: `City, State, Country` + country flag emoji |
| Line 2-3 (address) | Roboto Regular, 36 px, baselines y = 1380 and y = 1425. Wrap the address at the right edge |
| Line 4 (coordinates) | Roboto Regular, 36 px, baseline y = 1472. Format: `Lat 26.411997° Long 67.86426°` |
| Line 5 (date/time) | Roboto Regular, 36 px, baseline y = 1517. Format: `Wednesday, 20/05/2026 10:16 AM GMT +05:00` |
| Line spacing | about 45-47 px between body lines |

### D) "GPS Map Camera" badge (top-right, above the band)
| Property | Value |
|---|---|
| Box | x from 968 to 1200, y from 1200 to 1255 (232 x 55 px), touching the right edge and the top of the band |
| Background | Black 55% opacity `#8C000000`, rounded top-left corner 14 px, other corners square |
| Icon | About 34 px circle, orange/yellow `#F9A825` with a white location pin, at x = 980 |
| Text | "GPS Map Camera", white, Roboto Regular, 22 px, starts at x = 1022, vertically centered |

## 2. Kotlin: stamp a captured Bitmap

```kotlin
import android.graphics.*
import android.text.*
import java.text.SimpleDateFormat
import java.util.*

data class StampData(
    val title: String,          // "Sehwān, Sindh, Pakistan 🇵🇰"
    val address: String,        // "Cv67+jgc, Hyderabad Rd, Sehwān, Sindh , Pakistan"
    val lat: Double,
    val lng: Double,
    val time: Date = Date(),
    val mapBitmap: Bitmap?,     // satellite snapshot with pin
    val badgeIcon: Bitmap?      // small app icon (optional)
)

fun drawGpsStamp(src: Bitmap, d: StampData): Bitmap {
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
    d.mapBitmap?.let {
        c.drawBitmap(it, null, RectF(0f, bandTop, 285f * s, bandTop + 285f * s), null)
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
    val textW = (W - x - 20f * s).toInt()
    var y = bandTop + 75f * s                      // title baseline (1330 - 1255)
    c.drawText(d.title, x, y, titlePaint)

    // address (wraps, max 2 lines)
    val layout = StaticLayout.Builder
        .obtain(d.address, 0, d.address.length, bodyPaint, textW)
        .setLineSpacing(0f, 1.1f).setMaxLines(2)
        .setEllipsize(TextUtils.TruncateAt.END).build()
    c.save()
    c.translate(x, bandTop + 75f * s + 12f * s)    // top of address block
    layout.draw(c)
    c.restore()

    val dateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a 'GMT' xxx", Locale.ENGLISH)
    val gmt = dateFmt.format(d.time).replace("+05:00", "+05:00")
    c.drawText("Lat ${"%.6f".format(d.lat)}° Long ${"%.5f".format(d.lng)}°",
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
            bx0 + 46f * s, by0 + 44f * s), null)
    }
    val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f * s
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    c.drawText("GPS Map Camera", bx0 + 54f * s, by0 + 36f * s, badgeText)

    return out
}
```

## 3. Optional: live camera preview overlay (XML)

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="wrap_content">

    <!-- Bottom band -->
    <LinearLayout
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_gravity="bottom" android:orientation="horizontal"
        android:background="#80000000">

        <ImageView android:id="@+id/stampMap"
            android:layout_width="95dp" android:layout_height="95dp"
            android:scaleType="centerCrop"/>

        <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:orientation="vertical"
            android:paddingStart="7dp" android:paddingTop="6dp">
            <TextView android:id="@+id/stampTitle" android:textColor="#FFFFFF"
                android:textSize="18sp" android:fontFamily="sans-serif-medium"
                android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <TextView android:id="@+id/stampAddress" android:textColor="#FFFFFF"
                android:textSize="12sp" android:maxLines="2"
                android:layout_width="match_parent" android:layout_height="wrap_content"/>
            <TextView android:id="@+id/stampLatLng" android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <TextView android:id="@+id/stampTime" android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:layout_width="wrap_content" android:layout_height="wrap_content"/>
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
```
(dp values assume a 360 dp wide screen: 1200 px = 360 dp, so 1 dp = 3.33 px.)

## 4. Notes for the developer
- Get the address with `Geocoder`; the "Cv67+jgc" part is a **Plus Code** (Open Location Code library).
- Map thumbnail: Google Static Maps API, `maptype=satellite&zoom=18&size=285x285&markers=color:red|lat,lng`.
- Keep the Google logo visible on the map (license requirement).
- Flag emoji: use the country code to build the emoji and render with the system emoji font.
- Time zone: format with `TimeZone.getDefault()` so it shows GMT +05:00.
- Measurements were estimated by eye from the photo, so fine-tune by +/- 2-3 px if needed.
