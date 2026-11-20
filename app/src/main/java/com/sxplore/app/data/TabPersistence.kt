package com.sxplore.app.data

import kotlinx.serialization.Serializable

/** Serializable snapshot of the tab layout for restore-on-launch. */
@Serializable
data class SavedTab(
    val kind: String,               // "local" | "smb"
    val connectionId: String = "",  // saved SMB connection id (smb tabs)
    val title: String,
    val expanded: List<String> = emptyList(),
    val currentDir: String = "/",
)

@Serializable
data class SavedTabs(
    val tabs: List<SavedTab>,
    val activeIndex: Int = 0,
)
