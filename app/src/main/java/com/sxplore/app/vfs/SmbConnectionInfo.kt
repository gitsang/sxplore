package com.sxplore.app.vfs

import kotlinx.serialization.Serializable

/**
 * SMB connection settings. id is used for persistence and deduplication.
 */
@Serializable
data class SmbConnectionInfo(
    val id: String = "",
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val displayHost: String get() = if (port == 445) host else "$host:$port"
}
