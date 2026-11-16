package com.raditejp.islandkirim

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

data class HistoryItem(
    val id: String,
    val arah: String, // "kirim" | "terima"
    val nama: String,
    val ukuran: Long,
    val waktuMs: Long,
    val perangkat: String,
    val status: String, // "selesai" | "gagal" | "dibatalkan" | "ditolak"
    val penyebab: String = "",
    val persenGagal: Int = 0,
    val uriStr: String = ""
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

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history = _history.asStateFlow()

    private val _saveFolderUri = MutableStateFlow("")
    val saveFolderUri = _saveFolderUri.asStateFlow()

    private val _saveFolderLabel = MutableStateFlow("Downloads/Island Kirim (bawaan)")
    val saveFolderLabel = _saveFolderLabel.asStateFlow()

    private val _viewMode = MutableStateFlow(2) // 0=Ringkas,1=Berkolom,2=Rinci,3=Mengalir,4=Minimal,5=Kisi,6=Galeri
    val viewMode = _viewMode.asStateFlow()

    fun loadPrefsAndHistory(ctx: Context) {
        val sp = ctx.getSharedPreferences("island_kirim", Context.MODE_PRIVATE)
        _saveFolderUri.value = sp.getString("save_folder_uri", "") ?: ""
        _saveFolderLabel.value = sp.getString("save_folder_label", "Downloads/Island Kirim (bawaan)") ?: "Downloads/Island Kirim (bawaan)"
        _viewMode.value = sp.getInt("view_mode", 2).coerceIn(0, 6)

        try {
            val f = File(ctx.filesDir, "kirim_history.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = o.optString("id"),
                        arah = o.optString("arah", "kirim"),
                        nama = o.optString("nama"),
                        ukuran = o.optLong("ukuran"),
                        waktuMs = o.optLong("waktuMs"),
                        perangkat = o.optString("perangkat", "PC"),
                        status = o.optString("status", "selesai"),
                        penyebab = o.optString("penyebab"),
                        persenGagal = o.optInt("persenGagal"),
                        uriStr = o.optString("uriStr")
                    )
                )
            }
            _history.value = list
        } catch (_: Throwable) {}
    }

    fun loadSaveFolder(ctx: Context) = loadPrefsAndHistory(ctx)

    fun setSaveFolder(ctx: Context, uri: String, label: String) {
        _saveFolderUri.value = uri
        _saveFolderLabel.value = label
        ctx.getSharedPreferences("island_kirim", Context.MODE_PRIVATE).edit()
            .putString("save_folder_uri", uri)
            .putString("save_folder_label", label)
            .apply()
    }

    fun setViewMode(ctx: Context, mode: Int) {
        val m = mode.coerceIn(0, 6)
        _viewMode.value = m
        ctx.getSharedPreferences("island_kirim", Context.MODE_PRIVATE).edit()
            .putInt("view_mode", m)
            .apply()
    }

    fun addHistory(ctx: Context, item: HistoryItem) {
        val updated = (listOf(item) + _history.value).take(5000)
        _history.value = updated
        saveHistory(ctx, updated)
    }

    fun removeHistoryIds(ctx: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val updated = _history.value.filterNot { ids.contains(it.id) }
        _history.value = updated
        saveHistory(ctx, updated)
    }

    private fun saveHistory(ctx: Context, list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            for (h in list) {
                arr.put(JSONObject().apply {
                    put("id", h.id)
                    put("arah", h.arah)
                    put("nama", h.nama)
                    put("ukuran", h.ukuran)
                    put("waktuMs", h.waktuMs)
                    put("perangkat", h.perangkat)
                    put("status", h.status)
                    put("penyebab", h.penyebab)
                    put("persenGagal", h.persenGagal)
                    put("uriStr", h.uriStr)
                })
            }
            File(ctx.filesDir, "kirim_history.json").writeText(arr.toString())
        } catch (_: Throwable) {}
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

    fun getCategory(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> "Gambar"
            "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp" -> "Video"
            "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus" -> "Audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "log", "rtf" -> "Dokumen"
            else -> "Lainnya"
        }
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
