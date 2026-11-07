package com.ascend.mavlab.core.common

import android.content.Context
import com.ascend.mavlab.simulation.mission.MissionItem
import com.ascend.mavlab.simulation.mission.MissionSnapshotCodec
import com.ascend.mavlab.simulation.mission.MissionOrigin

data class StoredMission(val items: List<MissionItem>, val origin: MissionOrigin, val error:String?=null)

internal fun decodeStoredMission(snapshot: String?, origin: String?): StoredMission {
    val items=MissionSnapshotCodec.decode(snapshot)
    return StoredMission(items,
        if(origin==MissionOrigin.GCS_UPLOAD.name) MissionOrigin.GCS_UPLOAD else MissionOrigin.UNVERIFIED,
        if(!snapshot.isNullOrBlank() && items.isEmpty()) "Saved mission format cannot be read; original retained, re-upload from QGC" else null)
}

object MissionPersistence {
    fun load(context: Context): StoredMission {
        val preferences = prefs(context)
        return decodeStoredMission(preferences.getString(KeyMissionItems, null), preferences.getString(KeyMissionOrigin, null))
    }

    fun save(context: Context, items: List<MissionItem>, origin: MissionOrigin) {
        if (origin != MissionOrigin.GCS_UPLOAD) return
        if (items.isEmpty()) {
            clear(context)
            return
        }
        prefs(context)
            .edit()
            .putString(KeyMissionItems, MissionSnapshotCodec.encode(items))
            .putString(KeyMissionOrigin, origin.name)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context)
            .edit()
            .remove(KeyMissionItems)
            .remove(KeyMissionOrigin)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private const val PrefsName = "mavlab_mission"
    private const val KeyMissionItems = "mission_items"
    private const val KeyMissionOrigin = "mission_origin"
}
