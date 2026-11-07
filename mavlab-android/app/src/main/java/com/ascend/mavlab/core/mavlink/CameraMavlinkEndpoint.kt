package com.ascend.mavlab.core.mavlink

import com.ascend.mavlab.simulation.camera.VirtualCameraController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Independent component routing. It never changes flight state or writes files on UDP. */
internal class CameraMavlinkEndpoint(
    private val systemId:Int,private val camera:VirtualCameraController,
    private val send:(ByteArray,UdpDestination?)->Unit,
) {
    private val builder=CameraMessageBuilder(systemId)
    private val startedNanos=System.nanoTime()
    private var lastHeartbeatNanos=0L
    private var cachedEpoch=-1L
    private var running=true
    private data class Key(val system:Int,val component:Int,val sequence:Int)
    private data class Capture(val params:List<Int>,val ack:Int,var event:ByteArray?=null)
    private val captures=linkedMapOf<Key,Capture>() // bounded last 32 requests, no media bytes
    private val history=linkedMapOf<Int,ByteArray>()
    private var zeroSequenceKey:Key?=null
    private var zeroSequenceAt=0L
    private fun bootMs()=((System.nanoTime()-startedNanos)/1_000_000).toInt()
    @Synchronized fun stop() { running=false;captures.clear();history.clear();lastHeartbeatNanos=0 }
    @Synchronized fun start() { running=true }
    @Synchronized fun periodic():List<ByteArray> {
        if(!running || !camera.isEnabled) return emptyList()
        if(cachedEpoch!=camera.epoch) { captures.clear();history.clear();zeroSequenceKey=null;cachedEpoch=camera.epoch }
        val events=camera.drainMissionEvents().mapNotNull { result ->
            val snapshot=result.pose?:return@mapNotNull null
            val event=builder.captured(((snapshot.monotonicNanos-startedNanos)/1_000_000).toInt(),result.utcMicros,
                if(result.index>=0) result.index else camera.totalSaved,result.success,snapshot,
                result.image?.let { "mavlab/camera/${it.parentFile?.name}/${it.name}" }?:"")
            if(result.success) { history[result.index]=event;while(history.size>32) history.remove(history.keys.first()) }
            event
        }
        val now=System.nanoTime()
        if(now-lastHeartbeatNanos<1_000_000_000) return events
        lastHeartbeatNanos=now
        return events+listOf(builder.heartbeat(),status())
    }
    private fun status()=builder.captureStatus(bootMs(),camera.status.value.phase==VirtualCameraController.Phase.CAPTURING,camera.totalSaved,camera.storage.freeMiB)
    private fun integer(value:Float)=value.isFinite() && value>=0 && value<=16_777_215 && value==floor(value)
    private fun reserved(params:List<Float>,from:Int)=params.drop(from).all { it==0f || it.isNaN() }

    /** Returns true only for camera-directed commands, or recognized broadcast camera requests. */
    @Synchronized fun handle(packet:MavlinkPacket,peer:UdpDestination?):Boolean {
        if(packet.messageId !in listOf(75,76)) return false
        val p=packet.payload
        val targetOffset=if(packet.messageId==76) 30 else 30
        if(p.size<targetOffset+2) return false
        val targetSystem=p[targetOffset].toInt() and 255
        val component=p[targetOffset+1].toInt() and 255
        if(targetSystem!=0 && targetSystem!=systemId) return false
        val b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val command=b.getShort(28).toInt() and 65535
        val params=(0..6).map { b.getFloat(it*4) }
        val cameraRequest=command in listOf(521,522,525,527,530,2000,2001) || (command==512 && params[0] in listOf(259f,260f,261f,262f,263f))
        if(component!=100 && !(component==0 && cameraRequest)) return false
        fun ack(result:Int) { send(builder.ack(command,result,packet),peer) }
        if(packet.messageId!=76) { ack(7);return true } // COMMAND_LONG_ONLY
        if(!running || !camera.isEnabled) { ack(1);return true }
        if(cachedEpoch!=camera.epoch) { captures.clear();history.clear();zeroSequenceKey=null;cachedEpoch=camera.epoch }
        when(command) {
            512 -> {
                if(!integer(params[0])) { ack(2);return true }
                val valid=when(params[0].toInt()) {
                    263 -> integer(params[1]) && reserved(params,2)
                    261 -> params[1] in listOf(0f,1f) && reserved(params,2)
                    else -> reserved(params,1)
                }
                if(!valid) { ack(3);return true }
                val response=when(params[0].toInt()) {
                    259 -> builder.information(bootMs())
                    260 -> builder.settings(bootMs())
                    261 -> builder.storage(bootMs(),camera.storage.totalMiB,camera.storage.freeMiB)
                    262 -> status()
                    263 -> if(integer(params[1])) history[params[1].toInt()] else null
                    else -> null
                }
                if(response==null) ack(3) else { ack(0);send(response,peer) }
            }
            521,522,525,527 -> {
                val valid=when(command) {
                    525 -> params[0] in listOf(0f,1f) && params[1]==1f && reserved(params,2)
                    else -> params[0]==1f && reserved(params,1)
                }
                if(!valid) { ack(2);return true }
                ack(0)
                send(when(command) {
                    521 -> builder.information(bootMs())
                    522 -> builder.settings(bootMs())
                    525 -> builder.storage(bootMs(),camera.storage.totalMiB,camera.storage.freeMiB)
                    else -> status()
                },peer)
            }
            530 -> {
                if(params[0] !in listOf(0f,100f) || params[1]!=0f || !reserved(params,2)) ack(3)
                else { ack(0);send(builder.settings(bootMs()),peer) }
            }
            2001 -> { // single-shot work cannot be recalled after exposure; no periodic service yet
                if(params[0] !in listOf(0f,100f) || !reserved(params,1)) ack(3)
                else { ack(0);send(status(),peer) }
            }
            2000 -> {
                if(params[0] !in listOf(0f,100f) || params[1]!=0f || params[2]!=1f || !integer(params[3]) || !reserved(params,4)) {
                    ack(3);return true
                }
                val sequence=params[3].toInt()
                val confirmation=p[32].toInt() and 255
                val now=System.nanoTime()
                val key=Key(packet.systemId,packet.componentId,sequence)
                if(sequence==0 && confirmation==0) { captures.remove(key);zeroSequenceKey=null }
                // QGC a76f22939 sends seq=0. Only confirmation>0 denotes a retry in that
                // legacy path. A fresh seq=0/confirmation=0 press remains a new request.
                val previous=if(sequence>0) captures[key] else if(confirmation>0 && zeroSequenceKey==key && now-zeroSequenceAt<30_000_000_000L) captures[key] else null
                val bits=params.map { it.toRawBits() }
                if(previous!=null) {
                    if(previous.params!=bits) ack(2) else { ack(previous.ack);previous.event?.let { send(it,peer) } }
                    return true
                }
                val epoch=camera.epoch
                // Install the request record before a callback can acquire this endpoint's monitor.
                val entry=Capture(bits,0)
                val admission=camera.requestCapture("QGC ${packet.systemId}/${packet.componentId}") { result ->
                    synchronized(this) {
                        if(running && camera.epoch==epoch) {
                            val snapshot=result.pose
                            if(snapshot!=null) {
                                val event=builder.captured(((snapshot.monotonicNanos-startedNanos)/1_000_000).toInt(),result.utcMicros,
                                    if(result.index>=0) result.index else camera.totalSaved,result.success,snapshot,
                                    result.image?.let { "mavlab/camera/${it.parentFile?.name}/${it.name}" }?:"")
                                entry.event=event
                                if(result.success) {
                                    history[result.index]=event
                                    while(history.size>32) history.remove(history.keys.first())
                                }
                                send(event,peer);send(status(),peer)
                            }
                        }
                    }
                }
                val code=when(admission) {
                    VirtualCameraController.Admission.ACCEPTED -> 0
                    VirtualCameraController.Admission.BUSY,VirtualCameraController.Admission.UNAVAILABLE -> 1
                    VirtualCameraController.Admission.INVALID -> 2
                }
                ack(code)
                if(code==0) {
                    captures[key]=entry
                    while(captures.size>32) captures.remove(captures.keys.first())
                    if(sequence==0) { zeroSequenceKey=key;zeroSequenceAt=now }
                    send(status(),peer)
                }
            }
            else -> ack(3)
        }
        return true
    }
}
