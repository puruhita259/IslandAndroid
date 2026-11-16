package com.raditejp.islandkirim

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class PcFileItem(
    val id: String,
    val name: String,
    val size: Long,
    val progressPct: Int = 0,
    val downloading: Boolean = false,
    val statusText: String = ""
)

data class UploadTaskItem(
    val localId: String,
    val uri: Uri,
    val name: String,
    val size: Long,
    val offerId: String? = null,
    val pct: Int = 0,
    val speedBps: Double = 0.0,
    val status: String = "siap", // siap | menunggu | mengirim | terkirim | gagal | dibatalkan | ditolak
    val message: String = "Siap dikirim"
)

object KirimSessionStore {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl = _baseUrl.asStateFlow()

    private val _cookieSid = MutableStateFlow("")
    val cookieSid = _cookieSid.asStateFlow()

    private val _pcName = MutableStateFlow("Belum terhubung")
    val pcName = _pcName.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _remainingSec = MutableStateFlow(0)
    val remainingSec = _remainingSec.asStateFlow()

    private val _pcFiles = MutableStateFlow<List<PcFileItem>>(emptyList())
    val pcFiles = _pcFiles.asStateFlow()

    private val _uploads = MutableStateFlow<List<UploadTaskItem>>(emptyList())
    val uploads = _uploads.asStateFlow()


    private val _saveFolderUri = MutableStateFlow("")
    val saveFolderUri = _saveFolderUri.asStateFlow()

    private val _saveFolderLabel = MutableStateFlow("Downloads/Island Kirim (bawaan)")
    val saveFolderLabel = _saveFolderLabel.asStateFlow()

    fun loadSaveFolder(ctx: Context) {
        val sp = ctx.getSharedPreferences("island_kirim", Context.MODE_PRIVATE)
        _saveFolderUri.value = sp.getString("save_folder_uri", "") ?: ""
        _saveFolderLabel.value = sp.getString("save_folder_label", "Downloads/Island Kirim (bawaan)") ?: "Downloads/Island Kirim (bawaan)"
    }

    fun setSaveFolder(ctx: Context, uri: String, label: String) {
        _saveFolderUri.value = uri
        _saveFolderLabel.value = label
        ctx.getSharedPreferences("island_kirim", Context.MODE_PRIVATE).edit()
            .putString("save_folder_uri", uri)
            .putString("save_folder_label", label)
            .apply()
    }

    fun setSession(url: String, sid: String) {
        _baseUrl.value = url.trimEnd('/')
        _cookieSid.value = sid
        _connected.value = true
    }

    fun updateState(pc: String, rem: Int, files: List<PcFileItem>) {
        _pcName.value = pc
        _remainingSec.value = rem
        val currentMap = _pcFiles.value.associateBy { it.id }
        _pcFiles.value = files.map { f ->
            val prev = currentMap[f.id]
            if (prev != null && (prev.downloading || prev.statusText.isNotEmpty())) {
                f.copy(progressPct = prev.progressPct, downloading = prev.downloading, statusText = prev.statusText)
            } else f
        }
        _connected.value = true
    }

    fun disconnect() {
        _connected.value = false
    }

    fun updatePcFile(id: String, transform: (PcFileItem) -> PcFileItem) {
        _pcFiles.value = _pcFiles.value.map { if (it.id == id) transform(it) else it }
    }

    fun addUploads(items: List<UploadTaskItem>) {
        _uploads.value = items + _uploads.value
    }

    fun updateUpload(localId: String, transform: (UploadTaskItem) -> UploadTaskItem) {
        _uploads.value = _uploads.value.map { if (it.localId == localId) transform(it) else it }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
