package com.ascend.mavlab.core.maps

import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import android.util.LruCache
import com.ascend.mavlab.simulation.camera.*
import com.ascend.mavlab.simulation.coordinates.*
import org.json.JSONTokener
import java.io.Closeable
import java.io.File
import kotlin.math.*

/** Read-only custom MAVLab schema, deliberately not MBTiles. Call only on the camera IO worker. */
class OfflineMapPack(file: File) : Closeable {
    private val db: SQLiteDatabase
    val sha256: String
    val metadata: Map<String,String>
    val frame: SimulationCoordinateFrame
    val id: String
    val name: String
    val attribution: String
    val codec: String
    val sourceDate: String
    val width: Int
    val height: Int
    val levels: Int
    val resolution: Double
    val west: Double
    val north: Double
    val validWest: Double
    val validEast: Double
    val validSouth: Double
    val validNorth: Double
    private val cache = object : LruCache<String,Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, replacement: Bitmap?) {
            if (old !== replacement) old.recycle() // one worker; software Canvas has finished using it
        }
    }
    val cachedBytes get() = cache.size()

    init {
        require(file.length() in 1..64L*1024*1024) { "Map must be at most 64 MiB" }
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(16*1024)
            while(true) { val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n) }
        }
        sha256=hash.digest().joinToString("") { "%02x".format(it) }
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            require(db.version == 1) { "Unsupported map version" }
            val expectedSchema = mapOf(
                "metadata" to "CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "tiles" to "CREATE TABLE tiles(level INTEGER NOT NULL, col INTEGER NOT NULL, row INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(level,col,row)) WITHOUT ROWID",
            )
            fun normalized(sql: String) = sql.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), "")
            val seen = mutableSetOf<String>()
            db.rawQuery("SELECT name,type,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' LIMIT 3",null).use {
                while(it.moveToNext()) {
                    val name=it.getString(0)
                    require(it.getString(1)=="table" && expectedSchema[name]?.let { sql -> normalized(sql)==normalized(it.getString(2)) }==true) { "Unsupported map schema" }
                    seen.add(name)
                }
            }
            require(seen==expectedSchema.keys) { "Missing map tables" }
            db.rawQuery("PRAGMA cache_size=-512", null).use { it.moveToFirst() }
            val values = linkedMapOf<String,String>()
            db.rawQuery("SELECT key,value FROM metadata LIMIT 65", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0); val raw = cursor.getString(1)
                    require(key.length <= 80 && raw.length <= 2048 && values.size < 64) { "Oversized metadata" }
                    require(key !in values)
                    values[key] = JSONTokener(raw).nextValue().toString()
                }
            }
            metadata = values.toMap()
            fun str(k: String) = requireNotNull(metadata[k]) { "Missing map $k" }
            fun num(k: String) = str(k).toDouble().also { require(it.isFinite()) { "Invalid map $k" } }
            fun int(k: String) = num(k).also { require(it == floor(it)) }.toInt()
            require(int("schema") == 1 && str("frame") == SimulationCoordinateFrame.Id && int("tile_size") == 256)
            codec=str("codec")
            require(codec in listOf("png","webp"))
            id = str("id").also { require(it.matches(Regex("[a-z0-9-]{1,80}"))) }
            name = str("name"); attribution = str("attribution"); sourceDate = str("source_date")
            require(attribution.isNotBlank() && name.isNotBlank())
            frame = SimulationCoordinateFrame(num("origin_lat"),num("origin_lon"))
            require(abs(frame.latitudeDeg) <= 80.0) { "Polar maps are unsupported" }
            width = int("width"); height = int("height"); levels = int("levels")
            require(width in 256..16384 && height in 256..16384 && levels in 1..7)
            require(width % (256 shl (levels-1)) == 0 && height % (256 shl (levels-1)) == 0)
            resolution = num("meters_per_pixel").also { require(it in 0.05..100.0) }
            west = num("west"); north = num("north")
            validWest=num("valid_west"); validEast=num("valid_east"); validSouth=num("valid_south"); validNorth=num("valid_north")
            require(listOf(west,north,west+width*resolution,north-height*resolution).all { abs(it) <= SimulationCoordinateFrame.MapExtentMeters })
            require(validWest < validEast && validSouth < validNorth && validWest >= west && validEast <= west+width*resolution && validNorth <= north && validSouth >= north-height*resolution)
            var expected = 0L
            for (level in 0 until levels) expected += (width shr level).toLong() * (height shr level) / (256*256)
            require(expected <= 4096) { "Too many map tiles" }
            db.rawQuery("SELECT count(*), max(length(data)), min(length(data)) FROM tiles",null).use {
                require(it.moveToFirst() && it.getLong(0)==expected && it.getLong(1)<=256*1024 && it.getLong(2)>0) { "Invalid map tile inventory" }
            }
            // Validate the full bounded key set without decoding the whole map.
            db.rawQuery("SELECT level,col,row FROM tiles", null).use {
                while (it.moveToNext()) {
                    val level=it.getInt(0); val col=it.getInt(1); val row=it.getInt(2)
                    require(level in 0 until levels && col in 0 until ((width shr level)/256) && row in 0 until ((height shr level)/256)) { "Invalid tile address" }
                }
            }
        } catch (error: Exception) { db.close(); throw error }
    }

    fun camera(snapshot: PhysicalPoseSnapshot, profile: VirtualCameraProfile = VirtualCameraProfile()): PlanarCamera {
        require(abs(snapshot.frame.latitudeDeg-frame.latitudeDeg)<1e-8 && abs(snapshot.frame.longitudeDeg-frame.longitudeDeg)<1e-8) {
            "Set simulation origin to this map while disarmed" }
        return PlanarCamera(profile, CameraPose(snapshot.eastMeters,snapshot.northMeters,snapshot.altitudeAglMeters,snapshot.yawRadians))
    }

    fun validateFootprint(camera: PlanarCamera) {
        require(camera.corners.all { it.east >= validWest+resolution && it.east <= validEast-resolution && it.north >= validSouth+resolution && it.north <= validNorth-resolution }) {
            "Camera footprint is outside usable map coverage" }
    }

    /** No full-map or footprint mosaic. Each tile draws straight into one software output. */
    fun render(camera: PlanarCamera): Bitmap {
        validateFootprint(camera)
        val level = floor(log2(camera.metersPerPixel/resolution)).toInt().coerceIn(0,levels-1)
        val scale = resolution * (1 shl level)
        val corners = camera.corners
        val cols = floor((corners.minOf { it.east }-west)/scale/256).toInt()..floor((corners.maxOf { it.east }-west)/scale/256).toInt()
        val rows = floor((north-corners.maxOf { it.north })/scale/256).toInt()..floor((north-corners.minOf { it.north })/scale/256).toInt()
        require(cols.count()*rows.count() <= 256) { "Footprint exceeds capture work budget" }
        val output = Bitmap.createBitmap(camera.profile.width,camera.profile.height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(238,234,222))
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (row in rows) for (col in cols) {
                val e = west+col*256*scale; val n = north-row*256*scale
                val tl=camera.imageAt(e,n); val tr=camera.imageAt(e+256*scale,n); val bl=camera.imageAt(e,n-256*scale)
                val matrix=Matrix().apply { setPolyToPoly(floatArrayOf(0f,0f,256f,0f,0f,256f),0,
                    floatArrayOf(tl.x.toFloat(),tl.y.toFloat(),tr.x.toFloat(),tr.y.toFloat(),bl.x.toFloat(),bl.y.toFloat()),0,3) }
                canvas.drawBitmap(tile(level,col,row),matrix,paint)
            }
            val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(225,255,255,255) }
            canvas.drawRect(0f,(output.height-32).toFloat(),output.width.toFloat(),output.height.toFloat(),footer)
            footer.color=Color.BLACK; footer.textSize=11f
            canvas.drawText("VIRTUAL MAP CAPTURE · $attribution",6f,(output.height-18).toFloat(),footer)
            canvas.drawText("openstreetmap.org/copyright · flat simulated ground",6f,(output.height-5).toFloat(),footer)
            return output
        } catch(error:Exception) { output.recycle(); throw error }
    }

    private fun tile(level:Int,col:Int,row:Int): Bitmap {
        val key="$level/$col/$row"
        cache.get(key)?.let { return it }
        val data=db.rawQuery("SELECT data FROM tiles WHERE level=? AND col=? AND row=?",arrayOf("$level","$col","$row")).use {
            require(it.moveToFirst()) { "Missing map tile $key" }; it.getBlob(0)
        }
        require(data.size in 1..256*1024)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        require(bounds.outWidth==256 && bounds.outHeight==256 && bounds.outMimeType=="image/$codec") { "Invalid tile dimensions or codec" }
        val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888; inScaled=false }
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(data,0,data.size,options)) { "Tile decode failed" }
        require(bitmap.allocationByteCount<=256*256*4)
        cache.put(key,bitmap)
        return bitmap
    }
    fun verifyTiles() {
        for (level in 0 until levels) for (row in 0 until ((height shr level)/256))
            for (col in 0 until ((width shr level)/256)) tile(level,col,row)
    }
    override fun close() { cache.evictAll(); db.close() }
}
