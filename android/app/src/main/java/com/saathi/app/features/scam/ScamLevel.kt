package com.saathi.app.features.scam

import com.saathi.app.core.overlay.AlertLevel

/** Ordered risk levels shared by the scanner, the call guard and the stored events. */
enum class ScamLevel(val wire: String) {
    NONE("none"), INFO("info"), AMBER("amber"), RED("red");

    fun max(other: ScamLevel): ScamLevel = if (other.ordinal > ordinal) other else this

    fun toAlertLevel(): AlertLevel = when (this) {
        RED -> AlertLevel.RED
        AMBER -> AlertLevel.AMBER
        else -> AlertLevel.INFO
    }

    companion object {
        fun from(wire: String?): ScamLevel = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}
