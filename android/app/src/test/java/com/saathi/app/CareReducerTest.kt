package com.saathi.app

import com.saathi.app.features.care.CareEvent
import com.saathi.app.features.care.CarePhase
import com.saathi.app.features.care.CareReducer
import com.saathi.app.features.care.CareUi
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareReducerTest {
    private fun ev(seq: Int, type: String, data: String) =
        CareEvent(seq, type, SaathiJson.parseToJsonElement(data) as JsonObject)

    private fun fold(vararg events: CareEvent) = events.fold(CareUi()) { ui, e -> CareReducer.apply(ui, e) }

    @Test
    fun liveBoardFillsAndRecommends() {
        val ui = fold(
            ev(1, "care_status", """{"stage":"searching"}"""),
            ev(2, "hospitals_found", """{"hospitals":[{"id":"a","name":"City Care"},{"id":"b","name":"Sunrise"}],"calling":["a"]}"""),
            ev(3, "call_status", """{"hospital_id":"a","status":"in-progress"}"""),
            ev(4, "call_transcript", """{"hospital_id":"a","speaker":"hospital","text":"Haan, kal 11 baje"}"""),
            ev(5, "call_result", """{"hospital_id":"a","hospital_name":"City Care","doctor_name":"Dr. Meera","slots":[{"start":"2026-11-01T11:00:00+05:30"}]}"""),
            ev(6, "recommendation", """{"primary":{"hospital_id":"a","hospital_name":"City Care"},"slot":{"start":"2026-11-01T11:00:00+05:30"},"spoken_text":"Best option… Should I book?"}"""),
        )
        assertEquals(CarePhase.RECOMMEND, ui.phase)
        val row = ui.hospitals.first { it.hospital.id == "a" }
        assertTrue(row.calling)
        assertEquals("in-progress", row.status)
        assertTrue(row.lines.single().fromUser) // hospital lines are drawn on the other side
        assertEquals("Dr. Meera", row.result?.doctorName)
        assertTrue(!ui.hospitals.first { it.hospital.id == "b" }.calling)
        assertEquals("Best option… Should I book?", ui.recommendation?.spokenText)
    }

    @Test
    fun bookingAndRepeatsAreHandled() {
        val confirmed = ev(2, "booking_status",
            """{"status":"confirmed","booking_ref":"CC-2291","calendar":{"title":"Dr. Meera","start_iso":"2026-11-01T11:00:00+05:30"}}""")
        val ui = fold(ev(1, "booking_status", """{"status":"calling"}"""), confirmed, confirmed)
        assertEquals(CarePhase.BOOKED, ui.phase)
        assertEquals("CC-2291", ui.booking?.bookingRef)
        assertEquals(2, ui.lastSeq) // an already-seen event changes nothing
        val failed = fold(ev(1, "error", """{"stage":"B3","message":"Hospital calls are not set up"}"""),
            ev(2, "care_fallback", """{"hospitals":[{"id":"a","name":"City Care","phone":"+91 1"}]}"""))
        assertEquals(CarePhase.NO_OPTIONS, failed.phase)
        assertEquals("Hospital calls are not set up", failed.message)
        assertEquals("+91 1", failed.fallback.single().phone)
    }
}
