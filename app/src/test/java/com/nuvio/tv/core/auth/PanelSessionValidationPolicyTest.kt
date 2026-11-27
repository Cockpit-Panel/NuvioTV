package com.nuvio.tv.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSessionValidationPolicyTest {

    @Test
    fun `invalidates terminal auth status codes`() {
        assertTrue(isPanelSessionInvalidResponse(401, null))
        assertTrue(isPanelSessionInvalidResponse(402, null))
        assertTrue(isPanelSessionInvalidResponse(403, ""))
        assertTrue(isPanelSessionInvalidResponse(410, null))
        assertTrue(isPanelSessionInvalidResponse(423, null))
    }

    @Test
    fun `invalidates 4xx responses with explicit expiry or revocation body`() {
        assertTrue(isPanelSessionInvalidResponse(400, """{"error":"Account expired"}"""))
        assertTrue(isPanelSessionInvalidResponse(400, """{"message":"Line disabled"}"""))
        assertTrue(isPanelSessionInvalidResponse(404, """{"error":"User not found"}"""))
        assertTrue(isPanelSessionInvalidResponse(400, "Invalid token"))
        assertTrue(isPanelSessionInvalidResponse(400, "Access denied for this line"))
    }

    @Test
    fun `keeps session for non-terminal or transient failures`() {
        assertFalse(isPanelSessionInvalidResponse(200, "ok"))
        assertFalse(isPanelSessionInvalidResponse(408, "Request timeout"))
        assertFalse(isPanelSessionInvalidResponse(429, "Too many requests"))
        assertFalse(isPanelSessionInvalidResponse(500, "Internal server error"))
        assertFalse(isPanelSessionInvalidResponse(502, "Bad gateway"))
        assertFalse(isPanelSessionInvalidResponse(503, "Account expired"))
        assertFalse(isPanelSessionInvalidResponse(400, "Bad query parameter"))
        assertFalse(isPanelSessionInvalidResponse(404, ""))
    }
}
