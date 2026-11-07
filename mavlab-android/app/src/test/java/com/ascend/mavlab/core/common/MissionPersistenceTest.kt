package com.ascend.mavlab.core.common

import com.ascend.mavlab.simulation.mission.*
import kotlin.test.*

class MissionPersistenceTest {
    private val items = listOf(MissionItem(0, MissionCommand.WAYPOINT, -1.0, 36.0, 10f, altitudeIsMsl = true))
    @Test fun legacyItemsAreRetainedWithoutInventingSource() {
        val restored = decodeStoredMission(MissionSnapshotCodec.encode(items), null)
        assertEquals(items, restored.items)
        assertEquals(MissionOrigin.UNVERIFIED, restored.origin)
    }
    @Test fun onlyExplicitGcsSourceCanRestoreReceivedStatus() {
        for (source in listOf(null, "", "future", MissionOrigin.INTERNAL_FIXTURE.name)) {
            assertEquals(MissionOrigin.UNVERIFIED, decodeStoredMission(MissionSnapshotCodec.encode(items), source).origin)
        }
        assertEquals(MissionOrigin.GCS_UPLOAD, decodeStoredMission(MissionSnapshotCodec.encode(items), MissionOrigin.GCS_UPLOAD.name).origin)
    }
    @Test fun codecReadsPriorSnapshotFieldsAndPreservesMslOnNewSnapshots() {
        val new = MissionSnapshotCodec.encode(items)
        assertEquals(items, MissionSnapshotCodec.decode(new))
        val v2="MAVLAB_MISSION_V2\n0\tWAYPOINT\t-1.0\t36.0\t10.0\t2.0\ttrue\t\t\t\ttrue"
        assertEquals(items, MissionSnapshotCodec.decode(v2))
        val v1="MAVLAB_MISSION_V1\n0\tWAYPOINT\t-1.0\t36.0\t10.0\t2.0\ttrue\t\t"
        assertEquals(items.map { it.copy(altitudeIsMsl = false) }, MissionSnapshotCodec.decode(v1))
    }
    @Test fun unreadableSnapshotHasVisibleError() {
        val restored=decodeStoredMission("MAVLAB_MISSION_V99\noriginal",MissionOrigin.GCS_UPLOAD.name)
        assertTrue(restored.items.isEmpty())
        assertNotNull(restored.error)
    }
}
