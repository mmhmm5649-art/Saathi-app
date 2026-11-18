package com.example.security

import com.example.BuildConfig

object DebugGuestPolicy {
    const val DEBUG_GUEST_USER_ID = "debug_guest_tester"
    const val DEBUG_GUEST_DISPLAY_NAME = "Guest (Testing Mode)"
    const val DEBUG_GUEST_EMAIL = "Temporary debug session • Cloud & Auth off"

    /**
     * Skip / Guest mode is strictly permitted ONLY in debug builds (`BuildConfig.DEBUG == true`).
     * In release builds (`BuildConfig.DEBUG == false`), this always evaluates to false.
     */
    fun isSkipOptionVisible(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean =
        BuildConfig.DEBUG && isDebugBuild

    fun isGuestSessionAllowed(
        isDebugBuild: Boolean = BuildConfig.DEBUG,
        guestRequested: Boolean
    ): Boolean = BuildConfig.DEBUG && isDebugBuild && guestRequested
}
