package com.saathi.app

import com.saathi.app.core.guard.PriceGuard
import com.saathi.app.core.guard.PriceVerdict
import com.saathi.app.core.guard.RideClass
import com.saathi.app.core.guard.TextBox
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs fixtures/price_guard/cases.json, the same cases the server's Python twin must pass. */
class PriceGuardTest {
    private val doc = SaathiJson.parseToJsonElement(File(Fixtures.root, "price_guard/cases.json").readText()).jsonObject

    private fun layout(name: String) = doc["layouts"]!!.jsonObject[name]!!.jsonArray.map { row ->
        val r = row.jsonArray
        TextBox(r[0].jsonPrimitive.content, r[1].jsonPrimitive.int, r[2].jsonPrimitive.int, r[3].jsonPrimitive.int, r[4].jsonPrimitive.int)
    }

    @Test
    fun sharedCases() {
        val cases = doc["cases"] as JsonArray
        for (c in cases.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val v = PriceGuard.check(layout(c["layout"]!!.jsonPrimitive.content),
                RideClass.valueOf(c["wanted"]!!.jsonPrimitive.content), c["summary"]!!.jsonPrimitive.content)
            val e = c["expect"] as JsonObject
            assertEquals(name, e["verdict"]!!.jsonPrimitive.content, v::class.simpleName)
            when (v) {
                is PriceVerdict.Ok -> assertEquals(name, e["option"]!!.jsonPrimitive.content, v.chosen?.name)
                is PriceVerdict.CheaperSameType -> {
                    assertEquals(name, e["quoted"]!!.jsonPrimitive.int, v.quoted)
                    assertEquals(name, e["option"]!!.jsonPrimitive.content, v.cheapest.name)
                    assertEquals(name, e["price"]!!.jsonPrimitive.int, v.cheapest.price)
                }
                is PriceVerdict.CheaperOtherType -> {
                    assertEquals(name, e["quoted"]!!.jsonPrimitive.int, v.quoted)
                    assertEquals(name, e["option"]!!.jsonPrimitive.content, v.other.name)
                    assertEquals(name, e["price"]!!.jsonPrimitive.int, v.other.price)
                }
                PriceVerdict.Unread -> Unit
            }
        }
    }

    @Test
    fun rideTypes() {
        assertEquals(RideClass.AUTO, PriceGuard.classOf("Uber Auto"))
        assertEquals(RideClass.BIKE, PriceGuard.classOf("Moto"))
        assertEquals(RideClass.CAR, PriceGuard.classOf("Go Sedan"))
        assertEquals(1250, PriceGuard.price("Rs.1,250.00"))
    }
}
