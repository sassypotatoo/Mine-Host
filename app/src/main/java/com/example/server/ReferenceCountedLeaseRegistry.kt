package com.example.server

/**
 * Thread-safe in-process lease state used by foreground-service controllers.
 * Keeping this class Android-free makes the ownership rules directly testable.
 */
internal class ReferenceCountedLeaseRegistry {
    private val lock = Any()
    private val leases = linkedMapOf<String, String>()

    fun acquire(id: String, label: String): Snapshot = synchronized(lock) {
        leases[id] = label
        snapshotLocked()
    }

    fun update(id: String, label: String): Snapshot? = synchronized(lock) {
        if (!leases.containsKey(id)) return@synchronized null
        leases[id] = label
        snapshotLocked()
    }

    fun release(id: String): Snapshot = synchronized(lock) {
        leases.remove(id)
        snapshotLocked()
    }

    fun clear(): Snapshot = synchronized(lock) {
        val previous = snapshotLocked()
        leases.clear()
        previous
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): Snapshot = Snapshot(
        entries = leases.toMap(),
        labels = leases.values.sorted(),
    )

    data class Snapshot(
        val entries: Map<String, String>,
        val labels: List<String>,
    ) {
        val count: Int get() = entries.size
        val isEmpty: Boolean get() = entries.isEmpty()
        val ids: Set<String> get() = entries.keys
    }
}
