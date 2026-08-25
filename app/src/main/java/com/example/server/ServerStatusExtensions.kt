package com.example.server

fun ServerStatus.canStart(): Boolean =
    this == ServerStatus.STOPPED ||
    this == ServerStatus.FAILED ||
    this == ServerStatus.CRASHED ||
    this == ServerStatus.WORLD_LOAD_FAILED ||
    this == ServerStatus.PORT_MISMATCH ||
    this == ServerStatus.PROTOCOL_MISMATCH

/**
 * Returns true if the server is in a transitional state where a loading spinner should be shown.
 * This EXCLUDES the ONLINE state.
 */
fun ServerStatus.isTransitioning(): Boolean =
    this == ServerStatus.PREPARING ||
    this == ServerStatus.DOWNLOADING ||
    this == ServerStatus.STARTING ||
    this == ServerStatus.PROCESS_STARTED ||
    this == ServerStatus.NETWORK_READY ||
    this == ServerStatus.ENGINE_READY ||
    this == ServerStatus.WORLD_PROVISIONALLY_LOADED ||
    this == ServerStatus.WORLD_VERIFIED ||
    this == ServerStatus.STOPPING

/**
 * Returns true if the server is in a state that should block other servers from starting
 * or block profile switching. This includes being ONLINE.
 */
fun ServerStatus.isBlocking(): Boolean =
    this == ServerStatus.PREPARING ||
    this == ServerStatus.DOWNLOADING ||
    this == ServerStatus.STARTING ||
    this == ServerStatus.PROCESS_STARTED ||
    this == ServerStatus.NETWORK_READY ||
    this == ServerStatus.ENGINE_READY ||
    this == ServerStatus.WORLD_PROVISIONALLY_LOADED ||
    this == ServerStatus.WORLD_VERIFIED ||
    this == ServerStatus.ONLINE ||
    this == ServerStatus.STOPPING

// Alias for transition states (Busy Spinner)
fun ServerStatus.isActiveOperation(): Boolean = isTransitioning()
