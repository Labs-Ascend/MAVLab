package com.ascend.mavlab.simulation.camera

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.ascend.mavlab.core.maps.OfflineMapPack
import com.ascend.mavlab.simulation.coordinates.PhysicalPoseSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runtime owned, independent of Compose and flight recording. All disk/bitmap work is on IO. */
class VirtualCameraController(private val scope: CoroutineScope, private val pose: () -> PhysicalPoseSnapshot) {
    enum class Phase { OFF, LOADING, READY, CAPTURING, ERROR }
    data class Status(val phase:Phase=Phase.OFF,val message:String="Virtual camera off",val saved:Int=0,val attempted:Int=0,val failed:Int=0,
        val sessionId:String?=null,val latestImage:String?=null,val cachedBytes:Int=0,val exportBusy:Boolean=false)
    data class Result(val success:Boolean,val index:Int,val image:File?=null,val pose:PhysicalPoseSnapshot?=null,val error:String?=null,val utcMicros:Long=0)
    enum class Admission { ACCEPTED, UNAVAILABLE, BUSY, INVALID }
    private data class Request(val generation:Long,val snapshot:PhysicalPoseSnapshot,val trigger:String,val utc:String,val callback:(Result)->Unit,val folder:File,val map:OfflineMapPack)
    private val mutableStatus=MutableStateFlow(Status())
    val status:StateFlow<Status> = mutableStatus.asStateFlow()
    val profile=VirtualCameraProfile()
    private val guard=Any()
    private val ioMutex=Mutex()
    private val queue=Channel<Request>(1)
    private var context:Context?=null
    @Volatile private var generation=0L
    @Volatile private var enabled=false
    @Volatile private var pack:OfflineMapPack?=null
    private var session:File?=null // assigned on IO before pack publication; admission copies its identity
    private var nextIndex=0
    @Volatile var totalSaved=0
        private set
    private var lastAcceptedAt=Long.MIN_VALUE
    private var outstanding=0
    data class Storage(val totalMiB:Float=0f,val freeMiB:Float=0f)
    @Volatile var storage=Storage()
        private set
    val epoch get() = generation
    private fun refreshStorage(app:Context) { storage=Storage(app.filesDir.totalSpace/(1024f*1024f),app.filesDir.usableSpace/(1024f*1024f)) }
    init {
        scope.launch(Dispatchers.IO) {
            for (request in queue) {
                var result:Result
                try {
                    result=ioMutex.withLock {
                        try { capture(request) } catch(e:Exception) {
                            if(e is CancellationException) throw e
                            runCatching { recordFailure(request,e) }
                            Result(false,-1,pose=request.snapshot,error=e.message?:"Capture failed")
                        }
                    }
                } catch (e:CancellationException) { throw e }
                synchronized(guard) {
                    if (request.generation!=generation || !enabled) result=Result(false,-1,error="Capture cancelled: camera stopped")
                    outstanding=(outstanding-1).coerceAtLeast(0)
                    if (request.generation==generation && enabled) {
                        val previous=mutableStatus.value
                        mutableStatus.value=previous.copy(phase=if(result.success) { if(outstanding>0) Phase.CAPTURING else Phase.READY } else Phase.ERROR,
                            message=if(result.success) "Saved virtual map capture ${result.index}" else result.error?:"Capture failed",
                            saved=if(result.success) previous.saved+1 else previous.saved,
                            failed=if(result.success) previous.failed else previous.failed+1,
                            latestImage=result.image?.path?:previous.latestImage,cachedBytes=pack?.cachedBytes?:0,
                            sessionId=session?.name)
                    }
                }
                runCatching { request.callback(result) }
            }
        }
    }
    private val missionEvents=ArrayDeque<Result>()
    internal fun drainMissionEvents():List<Result> = synchronized(guard) { missionEvents.toList().also { missionEvents.clear() } }
    val isEnabled get() = enabled && pack!=null && status.value.phase!=Phase.LOADING
    fun initialize(context:Context) { this.context=context.applicationContext }
    fun enable() {
        val app=context?:return
        val token=synchronized(guard) {
            if(enabled || mutableStatus.value.exportBusy) return
            enabled=true; generation++; lastAcceptedAt=Long.MIN_VALUE
            mutableStatus.value=Status(Phase.LOADING,"Opening offline map…")
            generation
        }
        scope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                try {
                    pack?.close(); pack=null
                    val maps=File(app.filesDir,"mavlab/maps").apply { mkdirs() }
                    val mapFile=File(maps,"active.mavmap")
                    if (!mapFile.exists()) {
                        val stage=File(maps,"bundled.tmp")
                        app.assets.open("maps/dandora.mavmap").use { input -> FileOutputStream(stage).use { output -> input.copyTo(output); output.fd.sync() } }
                        OfflineMapPack(stage).use { }
                        check(stage.renameTo(mapFile)) { "Cannot activate bundled map" }
                    }
                    val opened=OfflineMapPack(mapFile)
                    refreshStorage(app)
                    val savedIndex=File(app.filesDir,"mavlab/camera").walkTopDown()
                        .filter { it.isFile && it.name.matches(Regex("[0-9]{6}\\.json")) && File(it.parentFile,it.nameWithoutExtension+".jpg").isFile }
                        .map { it.nameWithoutExtension.toInt() }.maxOrNull()?.plus(1)?:0
                    nextIndex=savedIndex;totalSaved=savedIndex
                    synchronized(guard) {
                        if(token!=generation || !enabled) { opened.close(); return@withLock }
                        pack=opened;session=File(app.filesDir,"mavlab/camera/${System.currentTimeMillis()}-${UUID.randomUUID()}")
                        mutableStatus.value=Status(Phase.READY,"${opened.name} · set matching origin, then take off above 0.5 m")
                    }
                } catch(e:Exception) {
                    synchronized(guard) { if(token==generation && enabled) mutableStatus.value=Status(Phase.ERROR,e.message?:"Map could not open") }
                }
            }
        }
    }
    fun disable() {
        val token=synchronized(guard) {
            enabled=false;generation++;missionEvents.clear()
            mutableStatus.value=mutableStatus.value.copy(phase=Phase.OFF,message="Virtual camera off",cachedBytes=0)
            generation
        }
        scope.launch(Dispatchers.IO) { ioMutex.withLock {
            if(token==generation && !enabled) { pack?.close();pack=null;session=null }
        } }
    }
    /** Nonblocking admission for UI or UDP. Neither engine nor UDP waits on disk work. */
    fun requestCapture(trigger:String,callback:(Result)->Unit={}):Admission {
        return requestCaptureAt(pose(),trigger,callback)
    }
    internal fun requestCaptureAt(snapshot:PhysicalPoseSnapshot,trigger:String,callback:(Result)->Unit={}):Admission {
        return synchronized(guard) {
        val map=pack
        if(!enabled || map==null || mutableStatus.value.phase in listOf(Phase.LOADING,Phase.OFF)) return Admission.UNAVAILABLE
        if(mutableStatus.value.attempted>=MaximumRequestsPerSession) {
            mutableStatus.value=mutableStatus.value.copy(phase=Phase.ERROR,message="Session limit reached; turn camera off and on to start a new session")
            return Admission.INVALID
        }
        if(outstanding>=2) return Admission.BUSY
        val now=SystemClock.elapsedRealtime()
        if(lastAcceptedAt!=Long.MIN_VALUE && now-lastAcceptedAt<MinimumIntervalMs) return Admission.BUSY
        try { map.validateFootprint(map.camera(snapshot,profile)) } catch(e:IllegalArgumentException) {
            mutableStatus.value=mutableStatus.value.copy(phase=Phase.ERROR,message=e.message?:"Camera not ready")
            return Admission.INVALID
        }
        val folder=session?:return Admission.UNAVAILABLE
        if(!queue.trySend(Request(generation,snapshot,trigger.take(100),Instant.now().toString(),callback,folder,map)).isSuccess) return Admission.BUSY
        outstanding++;lastAcceptedAt=now
        mutableStatus.value=mutableStatus.value.copy(phase=Phase.CAPTURING,message="Saving virtual map capture…",attempted=mutableStatus.value.attempted+1)
        Admission.ACCEPTED
        }
    }
    val missionLink:MissionCamera=object:MissionCamera {
        override fun preflight(items:List<com.ascend.mavlab.simulation.mission.MissionItem>,pose:PhysicalPoseSnapshot,speed:Float):String? {
            val map=pack
            if(!isEnabled || map==null) return "Enable the virtual camera in Ops"
            if(kotlin.math.abs(pose.frame.latitudeDeg-map.frame.latitudeDeg)>1e-8 || kotlin.math.abs(pose.frame.longitudeDeg-map.frame.longitudeDeg)>1e-8)
                return "Simulation origin does not match the selected map"
            if(storage.freeMiB<32f) return "Camera needs at least 32 MiB free storage"
            val nav=items.filter { it.command.isNav && it.command!=com.ascend.mavlab.simulation.mission.MissionCommand.RTL }
            val maxAltitude=nav.maxOfOrNull { it.altitudeAglMeters.toDouble() }?:0.0
            if(maxAltitude<0.5) return "Photo mission needs a positive flight altitude"
            var distance=0.0;var shots=0.0;var lastN=pose.northMeters;var lastE=pose.eastMeters
            var plannedSpeed=speed.toDouble()
            for(item in items) {
                if(item.command==com.ascend.mavlab.simulation.mission.MissionCommand.CHANGE_SPEED)
                    plannedSpeed=item.speedMetersPerSecond?.toDouble()?:plannedSpeed
                if(item.command==com.ascend.mavlab.simulation.mission.MissionCommand.CAMERA_TRIGGER_DISTANCE) {
                    distance=item.wire?.p1?.toDouble()?:0.0
                    if(distance>0 && distance/plannedSpeed.coerceAtLeast(0.1)<MinimumIntervalMs/1000.0)
                        return "Photo trigger demand exceeds one image per three seconds; reduce speed or increase distance"
                    if(item.wire?.p3==1f) shots++
                }
                if(item.command.isNav) {
                    val n=item.localNorthMeters?.toDouble()?:continue
                    val e=item.localEastMeters?.toDouble()?:continue
                    if(distance>0) shots+=kotlin.math.ceil(kotlin.math.hypot(n-lastN,e-lastE)/distance)
                    lastN=n;lastE=e
                    val h=item.altitudeAglMeters.toDouble().coerceAtLeast(maxAltitude)
                    val radius=h*kotlin.math.hypot(profile.sensorWidthMm,profile.sensorHeightMm)/(2*profile.focalLengthMm)+8.0
                    if(e-radius<map.validWest || e+radius>map.validEast || n-radius<map.validSouth || n+radius>map.validNorth)
                        return "Mission footprint is outside the selected map coverage"
                }
            }
            if(shots>MaximumRequestsPerSession-status.value.attempted) return "Mission exceeds the remaining camera-session budget of ${MaximumRequestsPerSession-status.value.attempted} captures"
            return null
        }
        override fun capture(pose:PhysicalPoseSnapshot,trigger:String,completed:(String?)->Unit):MissionCamera.Admission =
            when(requestCaptureAt(pose,trigger) { result ->
                synchronized(guard) { if (enabled) { if(missionEvents.size==32) missionEvents.removeFirst();missionEvents.addLast(result) } }
                completed(if(result.success) null else result.error?:"Photo save failed")
            }) {
                Admission.ACCEPTED -> MissionCamera.Admission.ACCEPTED
                Admission.BUSY -> MissionCamera.Admission.BUSY
                else -> MissionCamera.Admission.REJECTED
            }
    }

    private fun capture(request:Request):Result {
        check(request.generation==generation && enabled) { "Capture cancelled: camera stopped" }
        val app=checkNotNull(context);val map=request.map
        writeManifest(request)
        require(app.filesDir.usableSpace>32L*1024*1024) { "Low storage: keep at least 32 MiB free" }
        val camera=map.camera(request.snapshot,profile)
        val bitmap=map.render(camera)
        var stage:File?=null;var image:File?=null;var record:File?=null
        try {
            val index=nextIndex
            require(index<1_000_000) { "Camera image index limit reached" }
            val folder=request.folder;val stem="%06d".format(java.util.Locale.ROOT,index)
            image=File(folder,"$stem.jpg");stage=File(folder,"$stem.jpg.tmp");record=File(folder,"$stem.json")
            FileOutputStream(stage).use { stream -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,stream));stream.fd.sync() }
            val hash=sha256(stage)
            val snapshot=request.snapshot
            val entry=JSONObject().put("schema",1).put("success",true).put("index",index).put("image",image.name).put("bytes",stage.length())
                .put("sha256",hash).put("map_id",map.id).put("trigger",request.trigger).put("exposure_utc",request.utc)
                .put("exposure_monotonic_nanos",snapshot.monotonicNanos).put("completed_utc",Instant.now().toString())
                .put("frame",com.ascend.mavlab.simulation.coordinates.SimulationCoordinateFrame.Id)
                .put("origin_lat",snapshot.frame.latitudeDeg).put("origin_lon",snapshot.frame.longitudeDeg)
                .put("east_m",snapshot.eastMeters).put("north_m",snapshot.northMeters).put("altitude_agl_m",snapshot.altitudeAglMeters)
                .put("heading_rad",snapshot.yawRadians).put("latitude",snapshot.frame.latitude(snapshot.northMeters))
                .put("longitude",snapshot.frame.longitude(snapshot.eastMeters)).put("virtual",true)
            check(request.generation==generation && enabled) { "Capture cancelled: camera stopped" }
            check(stage.renameTo(image)) { "Cannot publish JPEG" }
            atomicJson(record,entry) // commit marker; orphan JPEG is never a completed capture
            check(request.generation==generation && enabled) { "Capture cancelled: camera stopped" }
            nextIndex++;totalSaved=nextIndex
            refreshStorage(app)
            val instant=Instant.parse(request.utc)
            return Result(true,index,image,snapshot,utcMicros=instant.epochSecond*1_000_000+instant.nano/1000)
        } catch(e:Exception) {
            stage?.delete();image?.delete();record?.delete()
            throw e
        } finally { bitmap.recycle() }
    }
    private fun writeManifest(request:Request) {
        val folder=request.folder;val map=request.map
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create capture session" }
        if(File(folder,"manifest.json").isFile) return
        atomicJson(File(folder,"manifest.json"),JSONObject().put("schema",1).put("type","virtual-map-captures")
            .put("map_id",map.id).put("map_sha256",map.sha256).put("map_metadata",JSONObject(map.metadata)).put("attribution",map.attribution)
            .put("width",profile.width).put("height",profile.height).put("sensor_width_mm",profile.sensorWidthMm)
            .put("sensor_height_mm",profile.sensorHeightMm).put("focal_length_mm",profile.focalLengthMm)
            .put("jpeg_quality",80).put("created_utc",request.utc).put("gps_exif",false))
    }
    private fun recordFailure(request:Request,error:Exception) {
        writeManifest(request)
        atomicJson(File(request.folder,"failed-${request.snapshot.monotonicNanos}.json"),JSONObject()
            .put("schema",1).put("success",false).put("trigger",request.trigger).put("map_id",request.map.id)
            .put("generation",request.generation).put("exposure_utc",request.utc)
            .put("exposure_monotonic_nanos",request.snapshot.monotonicNanos).put("completed_utc",Instant.now().toString())
            .put("error",error.message?:"Capture failed"))
    }
    fun exportLatest(uri:Uri) {
        val app=context?:return
        synchronized(guard) {
            if(enabled || mutableStatus.value.exportBusy) { mutableStatus.value=mutableStatus.value.copy(message="Turn camera off before export");return }
            mutableStatus.value=mutableStatus.value.copy(exportBusy=true,message="Exporting saved captures…")
        }
        scope.launch(Dispatchers.IO) {
            try {
                val folder=latestSession(app)?:error("No completed capture session to export")
                val records=folder.listFiles { f -> (f.name.matches(Regex("[0-9]{6}\\.json")) || f.name.matches(Regex("failed-[0-9]+\\.json"))) }?.sortedBy { it.name }.orEmpty()
                require(records.isNotEmpty()) { "No completed captures" }
                require(records.size<=MaximumRequestsPerSession) { "Capture session exceeds export budget" }
                app.contentResolver.openOutputStream(uri,"w").use { target ->
                    checkNotNull(target) { "Cannot open export destination" }
                    ZipOutputStream(target.buffered()).use { zip ->
                        fun add(file:File) { zip.putNextEntry(ZipEntry(file.name));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
                        add(File(folder,"manifest.json"))
                        for(record in records) {
                            require(record.length()<=16*1024) { "Oversized capture record" }
                            val entry=JSONObject(record.readText())
                            if(record.name.startsWith("failed-")) { add(record);continue }
                            val name=entry.getString("image")
                            require(name.matches(Regex("[0-9]{6}\\.jpg")))
                            val file=File(folder,name)
                            check(file.isFile && sha256(file)==entry.getString("sha256")) { "Capture integrity check failed" }
                            add(record);add(file)
                        }
                    }
                }
                mutableStatus.value=mutableStatus.value.copy(message="Exported ${records.count { !it.name.startsWith("failed-") }} captures and ${records.count { it.name.startsWith("failed-") }} failure records",exportBusy=false)
            } catch(e:Exception) { mutableStatus.value=mutableStatus.value.copy(message=e.message?:"Export failed; originals retained",exportBusy=false) }
        }
    }
    /** Stage an explicit user-selected pack; previous pack survives all validation failures. */
    fun importPack(uri:Uri,disarmed:Boolean) {
        val app=context?:return
        synchronized(guard) {
            if(!disarmed || enabled || mutableStatus.value.exportBusy) { mutableStatus.value=mutableStatus.value.copy(message="Disarm and turn camera off before import");return }
            mutableStatus.value=mutableStatus.value.copy(exportBusy=true,message="Validating offline map…")
        }
        scope.launch(Dispatchers.IO) { ioMutex.withLock {
            val maps=File(app.filesDir,"mavlab/maps").apply { mkdirs() };val stage=File(maps,"import.tmp")
            try {
                app.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input)
                    FileOutputStream(stage).use { output ->
                        val buffer=ByteArray(16*1024);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0) break;total+=n;require(total<=64L*1024*1024) { "Map exceeds 64 MiB" };output.write(buffer,0,n) }
                        output.fd.sync()
                    }
                }
                OfflineMapPack(stage).use { map ->
                    map.verifyTiles()
                }
                check(stage.renameTo(File(maps,"active.mavmap"))) { "Cannot activate imported map" }
                mutableStatus.value=mutableStatus.value.copy(message="Map imported; enable camera to use it",exportBusy=false)
            } catch(e:Exception) { stage.delete();mutableStatus.value=mutableStatus.value.copy(message=e.message?:"Import failed; previous map retained",exportBusy=false) }
        } }
    }
    companion object {
        const val MinimumIntervalMs=3000L
        const val MaximumRequestsPerSession=1000
        private fun latestSession(app:Context):File?=File(app.filesDir,"mavlab/camera").listFiles { f -> f.isDirectory && File(f,"manifest.json").isFile }
            ?.sortedByDescending { it.name }?.firstOrNull { folder -> folder.listFiles { f -> (f.name.matches(Regex("[0-9]{6}\\.json")) || f.name.matches(Regex("failed-[0-9]+\\.json"))) }?.isNotEmpty()==true }
        private fun atomicJson(file:File,json:JSONObject) {
            val stage=File(file.path+".tmp")
            FileOutputStream(stage).use { output -> output.write((json.toString()+"\n").toByteArray());output.fd.sync() }
            check(stage.renameTo(file)) { "Cannot publish capture metadata" }
        }
        private fun sha256(file:File):String {
            val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use { input ->
                val buffer=ByteArray(16*1024);while(true) { val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n) }
            };return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
