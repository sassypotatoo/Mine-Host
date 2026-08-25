package com.example.server.players

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PlayerState(
    val onlineNames: List<String> = emptyList(),
    val whitelist: List<String> = emptyList(),
    val banned: List<BannedPlayerEntry> = emptyList(),
    val isTrackingAvailable: Boolean = false,
    val lastEvidenceAt: Long = 0L
)

/**
 * Centrally manages the player states for all active server profiles.
 * Replaces the parsing-only logic in ServerManager with a structured state provider.
 */
object PlayerStateProvider {
    private val _states = ConcurrentHashMap<String, MutableStateFlow<PlayerState>>()

    fun getState(serverId: String): StateFlow<PlayerState> {
        return _states.getOrPut(serverId) { MutableStateFlow(PlayerState()) }.asStateFlow()
    }

    fun updateOnlinePlayers(serverId: String, names: List<String>, authoritative: Boolean = true) {
        val flow = _states.getOrPut(serverId) { MutableStateFlow(PlayerState()) }
        val now = System.currentTimeMillis()
        flow.value = flow.value.copy(
            onlineNames = if (authoritative) names else (flow.value.onlineNames + names).distinct(),
            lastEvidenceAt = now,
            isTrackingAvailable = true
        )
    }

    fun playerJoined(serverId: String, name: String) {
        val flow = _states.getOrPut(serverId) { MutableStateFlow(PlayerState()) }
        flow.value = flow.value.copy(
            onlineNames = (flow.value.onlineNames + name).distinct(),
            lastEvidenceAt = System.currentTimeMillis(),
            isTrackingAvailable = true
        )
    }

    fun playerLeft(serverId: String, name: String) {
        val flow = _states.getOrPut(serverId) { MutableStateFlow(PlayerState()) }
        flow.value = flow.value.copy(
            onlineNames = flow.value.onlineNames.filter { !it.equals(name, ignoreCase = true) },
            lastEvidenceAt = System.currentTimeMillis(),
            isTrackingAvailable = true
        )
    }

    fun updateAccessLists(serverId: String, whitelist: List<String>, banned: List<BannedPlayerEntry>) {
        val flow = _states.getOrPut(serverId) { MutableStateFlow(PlayerState()) }
        flow.value = flow.value.copy(
            whitelist = whitelist,
            banned = banned
        )
    }

    fun clear(serverId: String) {
        _states.remove(serverId)
    }
    
    fun setTrackingUnavailable(serverId: String) {
        val flow = _states[serverId] ?: return
        flow.value = flow.value.copy(isTrackingAvailable = false)
    }
}
