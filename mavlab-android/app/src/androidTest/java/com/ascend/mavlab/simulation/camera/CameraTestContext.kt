package com.ascend.mavlab.simulation.camera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import java.io.File

/** Separate files and preferences: tests must never change the user's selected camera mode. */
class CameraTestContext(base:Context,private val root:File,private val forbidAssets:Boolean=false):ContextWrapper(base),AutoCloseable {
    private val prefix="camera-test-${root.name}-"
    override fun getApplicationContext():Context=this
    override fun getFilesDir():File=root
    override fun getAssets():AssetManager {
        check(!forbidAssets) { "Events-only camera must not open bundled map assets" }
        return super.getAssets()
    }
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
    override fun close() {
        super.getSharedPreferences(prefix+"mavlab_camera",Context.MODE_PRIVATE).edit().clear().commit()
        baseContext.deleteSharedPreferences(prefix+"mavlab_camera")
    }
}
