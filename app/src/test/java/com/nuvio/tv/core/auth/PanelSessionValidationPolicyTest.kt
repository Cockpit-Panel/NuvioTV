package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.PanelSession
import com.nuvio.tv.data.remote.panel.PanelPortalDto
import org.junit.Assert.assertEquals
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

    @Test
    fun `evaluateXtreamPlayerApiResponse returns ACTIVE for active lines`() {
        val now = 1_750_000_000L
        assertEquals(
            XtreamAccountValidationResult.ACTIVE,
            evaluateXtreamPlayerApiResponse(
                200,
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000"}}""",
                nowEpochSeconds = now
            )
        )
        assertEquals(
            XtreamAccountValidationResult.ACTIVE,
            evaluateXtreamPlayerApiResponse(
                200,
                """{"user_info":{"auth":"1","status":"Active","exp_date":null}}""",
                nowEpochSeconds = now
            )
        )
        assertEquals(
            XtreamAccountValidationResult.ACTIVE,
            evaluateXtreamPlayerApiResponse(
                200,
                """{"user_info":{"auth":1,"status":"Active","exp_date":"0"}}""",
                nowEpochSeconds = now
            )
        )
        assertEquals(
            XtreamAccountValidationResult.ACTIVE,
            evaluateXtreamPlayerApiResponse(
                200,
                """{"user_info":{"auth":1,"status":"Active","exp_date":""}}""",
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `evaluateXtreamPlayerApiResponse returns INVALID_OR_EXPIRED for unauthorized disabled or expired lines`() {
        val now = 1_750_000_000L
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(401, "Unauthorized", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(403, "Forbidden", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(404, """{"user_info":{"auth":0}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":0,"status":"Active"}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":"0","status":"Active"}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":1,"status":"Expired","exp_date":"1800000000"}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":1,"status":"Disabled"}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":1,"status":"Banned"}}""", now)
        )
        assertEquals(
            XtreamAccountValidationResult.INVALID_OR_EXPIRED,
            evaluateXtreamPlayerApiResponse(200, """{"user_info":{"auth":1,"status":"Active","exp_date":"1700000000"}}""", now)
        )
    }

    @Test
    fun `evaluateXtreamPlayerApiResponse returns TRANSIENT_ERROR on server or non-json responses`() {
        val now = 1_750_000_000L
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(500, "Internal Server Error", now)
        )
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(503, "Service Unavailable", now)
        )
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(429, "Too Many Requests", now)
        )
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(404, "<html>Not Found</html>", now)
        )
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(200, "<html>Cloudflare challenge</html>", now)
        )
        assertEquals(
            XtreamAccountValidationResult.TRANSIENT_ERROR,
            evaluateXtreamPlayerApiResponse(200, "{broken-json", now)
        )
    }

    @Test
    fun `isPanelServiceActiveForSession validates xtream service membership`() {
        val portals = listOf(
            PanelPortalDto(id = 0, name = "Cockpit Panel", url = ""),
            PanelPortalDto(id = 2, name = "Main IPTV", url = "http://iptv.example.com/")
        )
        val activeSession = PanelSession(
            accessToken = "tok",
            userId = "xtream:abc",
            email = "user1",
            serverUrl = "http://iptv.example.com",
            serviceId = 2
        )
        val removedServiceSession = activeSession.copy(serviceId = 3)
        val onlyPanelPortals = listOf(PanelPortalDto(id = 0, name = "Cockpit Panel", url = ""))
        val urlOnlySession = activeSession.copy(serviceId = null)
        val panelUserSession = activeSession.copy(userId = "panel:7", serviceId = 0, serverUrl = null)

        assertTrue(isPanelServiceActiveForSession(activeSession, portals))
        assertFalse(isPanelServiceActiveForSession(removedServiceSession, portals))
        assertFalse(isPanelServiceActiveForSession(activeSession, onlyPanelPortals))
        assertTrue(isPanelServiceActiveForSession(urlOnlySession, portals))
        assertFalse(isPanelServiceActiveForSession(urlOnlySession, onlyPanelPortals))
        assertTrue(isPanelServiceActiveForSession(panelUserSession, onlyPanelPortals))
        assertTrue(isPanelServiceActiveForSession(activeSession, emptyList()))
    }
}
