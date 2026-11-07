package com.ascend.mavlab.simulation.mission

object MissionSnapshotCodec {
    fun encode(items: List<MissionItem>): String {
        if (items.isEmpty()) return ""
        return buildString {
            appendLine(Version)
            items.sortedBy { it.sequence }.forEach { item ->
                appendLine(
                    listOf(
                        item.sequence.toString(),
                        item.command.name,
                        item.latitudeDeg.toString(),
                        item.longitudeDeg.toString(),
                        item.altitudeAglMeters.toString(),
                        item.acceptanceRadiusMeters.toString(),
                        item.autocontinue.toString(),
                        item.localNorthMeters?.toString().orEmpty(),
                        item.localEastMeters?.toString().orEmpty(),
                        item.speedMetersPerSecond?.toString().orEmpty(),
                        item.altitudeIsMsl.toString(),
                        item.wire?.let { listOf(it.frame,it.p1,it.p2,it.p3,it.p4,it.x,it.y,it.z).joinToString(",") }.orEmpty(),
                    ).joinToString(FieldSeparator),
                )
            }
        }
    }

    fun decode(snapshot: String?): List<MissionItem> {
        val lines = snapshot
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?: return emptyList()
        if (lines.firstOrNull() !in setOf(Version, "MAVLAB_MISSION_V2", LegacyVersion)) return emptyList()

        return runCatching {
            lines.drop(1).map { line ->
                val fields = line.split(FieldSeparator, limit = FieldCount)
                if (fields.size !in LegacyFieldCount..FieldCount) error("Invalid mission snapshot line")
                MissionItem(
                    sequence = fields[0].toInt(),
                    command = MissionCommand.valueOf(fields[1]),
                    latitudeDeg = fields[2].toDouble(),
                    longitudeDeg = fields[3].toDouble(),
                    altitudeAglMeters = fields[4].toFloat(),
                    acceptanceRadiusMeters = fields[5].toFloat(),
                    autocontinue = fields[6].toBooleanStrict(),
                    localNorthMeters = fields[7].toFloatOrNull(),
                    localEastMeters = fields[8].toFloatOrNull(),
                    speedMetersPerSecond = fields.getOrNull(9)?.toFloatOrNull(),
                    altitudeIsMsl = fields.getOrNull(10)?.toBooleanStrictOrNull() ?: false,
                    wire = fields.getOrNull(11)?.takeIf { it.isNotBlank() }?.split(",")?.let {
                        require(it.size==8)
                        MissionWireData(it[0].toInt(),it[1].toFloat(),it[2].toFloat(),it[3].toFloat(),it[4].toFloat(),it[5].toDouble(),it[6].toDouble(),it[7].toFloat())
                    },
                )
            }
        }.getOrElse { emptyList() }
    }

    private const val Version = "MAVLAB_MISSION_V3"
    private const val LegacyVersion = "MAVLAB_MISSION_V1"
    private const val FieldSeparator = "\t"
    private const val LegacyFieldCount = 9
    private const val FieldCount = 12
}
