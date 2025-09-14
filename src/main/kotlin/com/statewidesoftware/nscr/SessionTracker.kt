package com.statewidesoftware.nscr

import java.util.*

@JvmInline
value class SessionID(val id: String)

class SessionTracker {
    fun newSession(): SessionID {
        return SessionID(UUID.randomUUID().toString())
    }
}