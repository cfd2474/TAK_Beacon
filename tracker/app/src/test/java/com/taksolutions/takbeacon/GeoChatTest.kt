package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.chat.GeoChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures in test/resources/geochat were captured from ATAK-CIV 5.8 through a relay. */
class GeoChatTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("geochat/$name")!!.readText().trim()

    private val atakUid = "ANDROID-a2af36804930aa6c"
    private val beaconUid = "ANDROID-e1f2ede97b524e14"

    @Test fun parsesAtakDirectMessage() {
        val m = GeoChat.parse(fixture("atak_direct.xml")) as GeoChat.Message
        assertEquals("891fcc6e-33b0-4503-bba2-51d5b6bf4553", m.messageId)
        assertEquals(atakUid, m.senderUid)
        assertEquals("FALLON", m.senderCallsign)
        assertEquals(beaconUid, m.roomId) // a direct message's room is the recipient's UID
        assertEquals(GeoChat.PARENT_ROOT, m.parent)
        assertEquals("Hello beacon from ATAK", m.text)
        assertTrue(m.timeMs > 0)
    }

    @Test fun parsesAllChatRoomsAndTeam() {
        val all = GeoChat.parse(fixture("atak_all_rooms.xml")) as GeoChat.Message
        assertEquals(GeoChat.ALL_CHAT_ROOMS, all.roomId)
        assertEquals("Group check all rooms", all.text)
        val team = GeoChat.parse(fixture("atak_team.xml")) as GeoChat.Message
        assertEquals(GeoChat.PARENT_TEAMS, team.parent)
        assertEquals("Cyan", team.roomId)
        assertEquals(listOf(atakUid, beaconUid), team.groupUids)
    }

    @Test fun parsesReceipts() {
        val d = GeoChat.parse(fixture("atak_delivered.xml")) as GeoChat.Receipt
        assertEquals("c737c302-4b05-45a5-ab28-e76abf9c35eb", d.messageId)
        assertFalse(d.read)
        assertEquals(atakUid, d.fromUid)
        assertTrue((GeoChat.parse(fixture("atak_read.xml")) as GeoChat.Receipt).read)
    }

    @Test fun ourMessagesRoundTrip() {
        val me = GeoChat.Me(beaconUid, "E7 <one> & \"two\"")
        val direct = GeoChat.parse(GeoChat.direct(me, atakUid, "FALLON", "Copy & en route <5 min>", messageId = "m1")) as GeoChat.Message
        assertEquals("m1", direct.messageId)
        assertEquals(atakUid, direct.roomId)
        assertEquals(beaconUid, direct.senderUid)
        assertEquals("E7 <one> & \"two\"", direct.senderCallsign)
        assertEquals("Copy & en route <5 min>", direct.text)

        val all = GeoChat.allRooms(me, "hi all")
        assertFalse(all.contains("<marti>")) // everyone: no destination
        assertEquals(GeoChat.ALL_CHAT_ROOMS, (GeoChat.parse(all) as GeoChat.Message).roomId)

        val team = GeoChat.team(me, "Cyan", listOf(atakUid, beaconUid, "X"), "team")
        assertTrue(team.contains("""<dest uid="$atakUid"/>""") && team.contains("""<dest uid="X"/>"""))
        assertFalse(team.contains("""<dest uid="$beaconUid"/>"""))
        assertEquals(GeoChat.PARENT_TEAMS, (GeoChat.parse(team) as GeoChat.Message).parent)

        val r = GeoChat.parse(GeoChat.receipt(me, "m9", read = true, toUid = atakUid, toCallsign = "FALLON")) as GeoChat.Receipt
        assertEquals("m9", r.messageId)
        assertTrue(r.read)
        assertEquals(beaconUid, r.fromUid)
    }

    @Test fun hostileInputIsHarmless() {
        assertNull(GeoChat.parse("<event type=\"a-f-G-U-C\" uid=\"x\"/>"))
        assertNull(GeoChat.parse("x".repeat(GeoChat.MAX_EVENT_CHARS + 1)))
        // Entities beyond the predefined five are left as text, never expanded.
        val xxe = fixture("atak_direct.xml").replace("Hello beacon from ATAK", "&xxe; &#65; &#x1F600; &#0;")
        assertEquals("&xxe; A 😀", (GeoChat.parse(xxe) as GeoChat.Message).text)
        // No remarks: not a message.
        assertNull(GeoChat.parse(fixture("atak_direct.xml").replace(Regex("<remarks.*</remarks>"), "")))
    }
}

class ChatPlumbingTest {
    @Test fun streamIsSplitIntoEvents() {
        val got = mutableListOf<String>()
        val sb = StringBuilder("<?xml version=\"1.0\"?><event uid=\"a\" type=\"b-t-f\"><detail/></event><event uid=\"b\"")
        com.taksolutions.takbeacon.net.TakConnection.splitEvents(sb) { got += it }
        assertEquals(1, got.size)
        assertTrue(got[0].startsWith("<event uid=\"a\""))
        assertEquals("<event uid=\"b\"", sb.toString()) // the partial event waits for more data
        sb.append(" type=\"a-f-G\"></event>")
        com.taksolutions.takbeacon.net.TakConnection.splitEvents(sb) { got += it }
        assertEquals(2, got.size)
        assertEquals(0, sb.length)
        // Endless garbage without an end tag doesn't grow without bound.
        val junk = StringBuilder("x".repeat(300 * 1024))
        com.taksolutions.takbeacon.net.TakConnection.splitEvents(junk) { got += it }
        assertEquals(0, junk.length)
    }

    @Test fun storeKeepsNewestWithinLimits() {
        val now = System.currentTimeMillis()
        val msgs = (1..600).map {
            com.taksolutions.takbeacon.chat.ChatMessage("m$it", "all", "u", "U", "t", now - (600 - it) * 1000L, outgoing = false)
        } + com.taksolutions.takbeacon.chat.ChatMessage("old", "dm:x", "x", "X", "t", now - 31L * 24 * 3600_000, outgoing = false)
        val d = com.taksolutions.takbeacon.chat.ChatData(
            listOf(com.taksolutions.takbeacon.chat.ChatThread("all", "All Chat Rooms", com.taksolutions.takbeacon.chat.ThreadKind.ALL_ROOMS),
                com.taksolutions.takbeacon.chat.ChatThread("dm:x", "X", com.taksolutions.takbeacon.chat.ThreadKind.DIRECT)),
            msgs,
        ).pruned(now)
        assertEquals(500, d.messages.size)
        assertEquals("m600", d.messages.last().id)
        assertNull(d.thread("dm:x")) // its only message expired
        assertEquals(500, d.unread())
    }
}
