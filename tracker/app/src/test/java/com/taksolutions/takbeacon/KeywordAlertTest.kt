package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.chat.KeywordAlert
import com.taksolutions.takbeacon.mdm.ManagedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordAlertTest {

    @Test fun parsesCommaSemicolonAndLineSeparatedLists() {
        assertEquals(listOf("mayday", "shots fired", "evacuate"), KeywordAlert.parse(" mayday,  shots   fired ;\nevacuate,,"))
        assertEquals(listOf("Mayday"), KeywordAlert.parse("Mayday, mayday, MAYDAY"))
        assertEquals(emptyList<String>(), KeywordAlert.parse(" , ;\n"))
        assertEquals(KeywordAlert.MAX_KEYWORDS, KeywordAlert.parse((1..80).joinToString(",") { "k$it" }).size)
    }

    @Test fun matchesWholeWordsIgnoringCase() {
        val k = listOf("fire", "shots fired", "10-33", "#help")
        assertEquals("fire", KeywordAlert.match("Structure FIRE on 5th", k))
        assertEquals("fire", KeywordAlert.match("fire!", k))
        assertNull(KeywordAlert.match("check the firewall", k))
        assertNull(KeywordAlert.match("campfire tonight", k))
        assertEquals("shots fired", KeywordAlert.match("Shots\n  fired near gate 2", k))
        assertEquals("10-33", KeywordAlert.match("Traffic 10-33, all units", k))
        assertNull(KeywordAlert.match("call 210-334", k))
        assertEquals("#help", KeywordAlert.match("need #help now", k))
        assertNull(KeywordAlert.match("anything", emptyList()))
    }

    @Test fun matchesBeyondAscii() {
        assertEquals("évacuer", KeywordAlert.match("ÉVACUER maintenant", listOf("évacuer")))
    }

    @Test fun mdmKeywords() {
        val c = ManagedConfig.parse(mapOf("alert_keywords" to "mayday, evacuate")) { null }!!
        assertEquals(listOf("mayday", "evacuate"), c.alertKeywords)
        assertTrue(ManagedConfig.FIELD_ALERT_KEYWORDS in c.managedFields)
        // An empty list isn't a managed setting.
        assertNull(ManagedConfig.parse(mapOf("alert_keywords" to " , ")) { null })
    }
}
