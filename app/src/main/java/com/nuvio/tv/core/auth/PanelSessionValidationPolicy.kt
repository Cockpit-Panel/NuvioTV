package com.nuvio.tv.core.auth

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
