package io.github.akiig.dose

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PCLinkTest {
    @Test
    fun titleComesFromTheNameOrTheAddress() {
        assertEquals("Mac", PCLink(name = " Mac ", server = "pop-os.local:8766").title)
        assertEquals("pop-os", PCLink(server = "pop-os.local:8766").title)
        assertEquals("pop-os", PCLink(server = "pop-os.local:8766").title)
        assertEquals("192.168.1.20", PCLink(server = "192.168.1.20:8766").title)
        assertEquals("New PC", PCLink().title)
    }

    @Test
    fun configuredNeedsAddressAndToken() {
        assertFalse(PCLink(server = "pop-os.local:8765").isConfigured)
        assertTrue(PCLink(server = "pop-os.local:8765", token = "abc").isConfigured)
    }

    @Test
    fun roundTrip() {
        val pc = PCLink(name = "Mac", server = "mac.local:8766", token = "t")
        assertEquals(pc, PCLink.from(JSONObject(pc.toJson().toString())))
    }
}
