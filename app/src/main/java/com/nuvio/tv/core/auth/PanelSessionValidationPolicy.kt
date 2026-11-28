package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.PanelSession
import com.nuvio.tv.data.remote.panel.PanelPortalDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal enum class XtreamAccountValidationResult {
    ACTIVE,
    INVALID_OR_EXPIRED,
    TRANSIENT_ERROR
}

internal fun isXtreamPanelSession(session: PanelSession): Boolean =
    session.userId.startsWith("xtream:", ignoreCase = true)

internal fun normalizePanelServerUrl(rawUrl: String?): String =
    rawUrl.orEmpty().trim().trimEnd('/')

internal fun isPanelServiceActiveForSession(
    session: PanelSession,
    portals: List<PanelPortalDto>
): Boolean {
    if (!isXtreamPanelSession(session)) return true
    if (portals.isEmpty()) return true
    val serviceId = session.serviceId
    if (serviceId != null && serviceId > 0) {
        return portals.any { it.id == serviceId }
    }
    val normalizedSessionUrl = normalizePanelServerUrl(session.serverUrl)
    if (normalizedSessionUrl.isNotBlank()) {
        return portals.any {
            it.id > 0 && normalizePanelServerUrl(it.url).equals(normalizedSessionUrl, ignoreCase = true)
        }
    }
    return true
}

internal fun evaluateXtreamPlayerApiResponse(
    statusCode: Int,
    responseBody: String?,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
): XtreamAccountValidationResult {
    if (statusCode == 401 || statusCode == 403) {
        return XtreamAccountValidationResult.INVALID_OR_EXPIRED
    }
    if (statusCode !in 200..299) {
        if (statusCode in 400..499) {
            val userInfo = parseXtreamUserInfo(responseBody)
            if (userInfo != null && !isXtreamAuthAuthorized(userInfo)) {
                return XtreamAccountValidationResult.INVALID_OR_EXPIRED
            }
        }
        return XtreamAccountValidationResult.TRANSIENT_ERROR
    }

    val userInfo = parseXtreamUserInfo(responseBody)
        ?: return XtreamAccountValidationResult.TRANSIENT_ERROR
    if (!isXtreamAuthAuthorized(userInfo)) {
        return XtreamAccountValidationResult.INVALID_OR_EXPIRED
    }

    val status = userInfo["status"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!status.isNullOrBlank() && !status.equals("active", ignoreCase = true)) {
        return XtreamAccountValidationResult.INVALID_OR_EXPIRED
    }

    val expDateRaw = userInfo["exp_date"]?.jsonPrimitive?.contentOrNull?.trim()
    val expDate = expDateRaw?.toLongOrNull() ?: expDateRaw?.toDoubleOrNull()?.toLong()
    if (expDate != null && expDate > 0L && expDate < nowEpochSeconds) {
        return XtreamAccountValidationResult.INVALID_OR_EXPIRED
    }

    return XtreamAccountValidationResult.ACTIVE
}

private fun parseXtreamUserInfo(responseBody: String?): JsonObject? {
    val trimmed = responseBody.orEmpty().trim()
    if (!trimmed.startsWith("{")) return null
    val root = runCatching { xtreamPolicyJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        ?: return null
    return root["user_info"] as? JsonObject
}

private fun isXtreamAuthAuthorized(userInfo: JsonObject): Boolean {
    val auth = runCatching { userInfo["auth"]?.jsonPrimitive?.contentOrNull?.trim() }.getOrNull()
    return auth == "1" || auth.equals("true", ignoreCase = true)
}

internal fun isPanelSessionInvalidResponse(
    statusCode: Int,
    errorBody: String?
): Boolean {
    if (statusCode in PANEL_INVALID_STATUS_CODES) return true
    if (statusCode !in 400..499) return false
    val normalizedBody = errorBody.orEmpty().lowercase()
    if (normalizedBody.isBlank()) return false
    return PANEL_INVALID_BODY_MARKERS.any(normalizedBody::contains)
}

private val xtreamPolicyJson = Json { ignoreUnknownKeys = true }

private val PANEL_INVALID_STATUS_CODES = setOf(401, 402, 403, 410, 423)

private val PANEL_INVALID_BODY_MARKERS = listOf(
    "expired",
    "disabled",
    "suspended",
    "revoked",
    "inactive",
    "banned",
    "deleted",
    "not active",
    "unauthorized",
    "access denied",
    "invalid token",
    "invalid session",
    "session not found",
    "session_not_found",
    "user not found",
    "account not found",
    "line not found",
    "invalid credentials"
)
