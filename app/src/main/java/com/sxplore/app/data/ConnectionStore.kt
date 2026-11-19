package com.sxplore.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sxplore.app.vfs.SmbConnectionInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * SMB 连接持久化：EncryptedSharedPreferences（AES256-GCM），
 * 凭据不以明文落盘。
 */
class ConnectionStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "smb_connections",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun list(): List<SmbConnectionInfo> = runCatching {
        json.decodeFromString<List<SmbConnectionInfo>>(prefs.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun upsert(info: SmbConnectionInfo): SmbConnectionInfo {
        val withId = if (info.id.isBlank()) info.copy(id = UUID.randomUUID().toString()) else info
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == withId.id }
        if (idx >= 0) current[idx] = withId else current.add(withId)
        prefs.edit().putString(KEY, json.encodeToString(current)).apply()
        return withId
    }

    fun remove(id: String) {
        val current = list().filterNot { it.id == id }
        prefs.edit().putString(KEY, json.encodeToString(current)).apply()
    }

    companion object {
        private const val KEY = "connections"
    }
}
