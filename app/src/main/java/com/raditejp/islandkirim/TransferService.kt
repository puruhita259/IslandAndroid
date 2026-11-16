package com.raditejp.islandkirim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TransferService : Service() {

    companion object {
        const val CHANNEL_ID = "island_kirim_transfer"
        const val NOTIF_ID = 2026
        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_UPLOAD = "UPLOAD"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
        const val ACTION_CANCEL = "CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_LOCAL_ID = "local_id"
        const val EXTRA_FILE_ID = "file_id"
        private const val CHUNK_SIZE = 4L * 1024L * 1024L // 4 MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pollJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Island Kirim aktif di latar belakang"))
        acquireLocks()

        when (intent?.action) {
            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                if (url.isNotEmpty()) scope.launch { connectSession(url) }
            }
            ACTION_UPLOAD -> {
                val localId = intent.getStringExtra(EXTRA_LOCAL_ID) ?: ""
                if (localId.isNotEmpty()) {
                    cancelledIds.remove(localId)
                    scope.launch { runUpload(localId) }
                }
            }
            ACTION_DOWNLOAD -> {
                val fid = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
                if (fid.isNotEmpty()) {
                    cancelledIds.remove(fid)
                    scope.launch { runDownload(fid) }
                }
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_LOCAL_ID) ?: intent.getStringExtra(EXTRA_FILE_ID) ?: ""
                if (id.isNotEmpty()) {
                    cancelledIds.add(id)
                    scope.launch { cancelRemote(id) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IslandKirim::TransferWake")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(60 * 60 * 1000L)

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "IslandKirim::WifiLock")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Throwable) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
    }

    private suspend fun connectSession(rawUrl: String) {
        val cleanUrl = rawUrl.trim().trimEnd('/')
        try {
            val req = Request.Builder().url("$cleanUrl/").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    KirimSessionStore.disconnect()
                    return
                }
                val setCookie = resp.header("Set-Cookie") ?: ""
                val sid = Regex("kirim_sid=([^;]+)").find(setCookie)?.groupValues?.get(1) ?: ""
                if (sid.isNotEmpty()) {
                    KirimSessionStore.setSession(cleanUrl, sid)
                } else if (KirimSessionStore.cookieSid.value.isNotEmpty()) {
                    KirimSessionStore.setSession(cleanUrl, KirimSessionStore.cookieSid.value)
                }
                startPollingState()
            }
        } catch (_: Throwable) {
            KirimSessionStore.disconnect()
        }
    }

    private fun startPollingState() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val base = KirimSessionStore.baseUrl.value
                val sid = KirimSessionStore.cookieSid.value
                if (base.isEmpty() || sid.isEmpty()) break
                try {
                    val req = Request.Builder()
                        .url("$base/state")
                        .header("Cookie", "kirim_sid=$sid")
                        .get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            val pc = json.optString("pcName", "PC")
                            val rem = json.optInt("remainingSeconds", 0)
                            val arr = json.optJSONArray("files")
                            val list = mutableListOf<PcFileItem>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    list.add(PcFileItem(o.getString("id"), o.getString("name"), o.getLong("size")))
                                }
                            }
                            KirimSessionStore.updateState(pc, rem, list)
                        } else if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                            KirimSessionStore.disconnect()
                            break
                        }
                    }
                } catch (_: Throwable) {}
                delay(2000)
            }
        }
    }

    private suspend fun runUpload(localId: String) {
        val item = KirimSessionStore.uploads.value.firstOrNull { it.localId == localId } ?: return
        val base = KirimSessionStore.baseUrl.value
        val sid = KirimSessionStore.cookieSid.value
        if (base.isEmpty() || sid.isEmpty()) {
            KirimSessionStore.updateUpload(localId) { it.copy(status = "gagal", message = "Belum terhubung ke PC") }
            return
        }

        try {
            var offerId = item.offerId
            if (offerId == null) {
                KirimSessionStore.updateUpload(localId) { it.copy(status = "menunggu", message = "Menunggu persetujuan di PC...") }
                val bodyJson = JSONObject().put("name", item.name).put("size", item.size).toString()
                val offerReq = Request.Builder()
                    .url("$base/offer")
                    .header("Cookie", "kirim_sid=$sid")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(offerReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val msg = resp.body?.string() ?: "Ditolak"
                        KirimSessionStore.updateUpload(localId) { it.copy(status = "ditolak", message = msg) }
                        return
                    }
                    offerId = JSONObject(resp.body?.string() ?: "{}").getString("id")
                    KirimSessionStore.updateUpload(localId) { it.copy(offerId = offerId) }
                }
            }

            // Tunggu persetujuan PC
            while (!cancelledIds.contains(localId)) {
                val stReq = Request.Builder()
                    .url("$base/offer/$offerId")
                    .header("Cookie", "kirim_sid=$sid")
                    .get().build()
                var accepted = false
                client.newCall(stReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        KirimSessionStore.updateUpload(localId) { it.copy(status = "gagal", message = "Sesi berakhir") }
                        return
                    }
                    val st = JSONObject(resp.body?.string() ?: "{}").optString("status")
                    if (st == "diterima") accepted = true
                    else if (st == "ditolak") {
                        KirimSessionStore.updateUpload(localId) { it.copy(status = "ditolak", message = "Ditolak di PC") }
                        return
                    }
                }
                if (accepted) break
                delay(800)
            }
            if (cancelledIds.contains(localId)) return

            var pos = queryReceivedOffset(base, sid, offerId!!)
            var retries = 0
            KirimSessionStore.updateUpload(localId) { it.copy(status = "mengirim", message = "Mengirim...") }

            while (pos < item.size && !cancelledIds.contains(localId)) {
                val end = minOf(item.size, pos + CHUNK_SIZE)
                try {
                    sendStreamChunk(base, sid, offerId!!, item.uri, pos, end, item.size, localId, item.name)
                    pos = end
                    retries = 0
                } catch (e: Throwable) {
                    if (cancelledIds.contains(localId)) return
                    if (retries >= 30) {
                        KirimSessionStore.updateUpload(localId) { it.copy(status = "gagal", message = e.message ?: "Koneksi terputus") }
                        updateNotif("Gagal mengirim ${item.name}")
                        return
                    }
                    retries++
                    KirimSessionStore.updateUpload(localId) { it.copy(message = "Menyambung ulang ($retries/30)...") }
                    delay(minOf(4000L, retries * 1000L))
                    val rec = queryReceivedOffset(base, sid, offerId!!)
                    if (rec in pos until item.size) pos = rec
                }
            }

            if (cancelledIds.contains(localId)) return
            KirimSessionStore.updateUpload(localId) { it.copy(status = "terkirim", pct = 100, message = "Terkirim") }
            updateNotif("Terkirim: ${item.name}")
        } catch (e: Throwable) {
            if (!cancelledIds.contains(localId)) {
                KirimSessionStore.updateUpload(localId) { it.copy(status = "gagal", message = e.message ?: "Gagal") }
            }
        }
    }

    private fun queryReceivedOffset(base: String, sid: String, offerId: String): Long {
        return try {
            val req = Request.Builder().url("$base/u/$offerId").header("Cookie", "kirim_sid=$sid").head().build()
            client.newCall(req).execute().use { resp ->
                resp.header("X-Received")?.toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { 0L }
    }

    private fun sendStreamChunk(
        base: String, sid: String, offerId: String, uri: Uri,
        start: Long, end: Long, totalSize: Long, localId: String, fileName: String
    ) {
        val len = end - start
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = len
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.use { input ->
                    var skipped = 0L
                    while (skipped < start) {
                        val s = input.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val buf = ByteArray(65536)
                    var sent = 0L
                    var lastUi = 0L
                    val t0 = System.currentTimeMillis()
                    while (sent < len && !cancelledIds.contains(localId)) {
                        val toRead = minOf(buf.size.toLong(), len - sent).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 250 || sent == len) {
                            lastUi = now
                            val done = start + sent
                            val pct = ((done * 100.0) / totalSize.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                            val dt = (now - t0).coerceAtLeast(1)
                            val bps = sent * 1000.0 / dt
                            KirimSessionStore.updateUpload(localId) {
                                it.copy(pct = pct, speedBps = bps, message = "Mengirim $pct%")
                            }
                            updateNotif("Mengirim $fileName ($pct%)")
                        }
                    }
                } ?: throw IllegalStateException("Tidak bisa membaca file")
            }
        }

        val req = Request.Builder()
            .url("$base/u/$offerId")
            .header("Cookie", "kirim_sid=$sid")
            .header("X-Offset", start.toString())
            .put(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        }
    }

    private suspend fun runDownload(fileId: String) {
        val fileItem = KirimSessionStore.pcFiles.value.firstOrNull { it.id == fileId } ?: return
        val base = KirimSessionStore.baseUrl.value
        val sid = KirimSessionStore.cookieSid.value
        if (base.isEmpty() || sid.isEmpty()) return

        KirimSessionStore.updatePcFile(fileId) { it.copy(downloading = true, progressPct = 0, statusText = "Mengunduh...") }
        try {
            val req = Request.Builder().url("$base/f/$fileId").header("Cookie", "kirim_sid=$sid").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    KirimSessionStore.updatePcFile(fileId) { it.copy(downloading = false, statusText = "Gagal") }
                    return
                }
                val stream = resp.body?.byteStream() ?: return
                val outStream = openDownloadOutputStream(fileItem.name)
                stream.use { input ->
                    outStream.use { out ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var lastUi = 0L
                        while (!cancelledIds.contains(fileId)) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 250 || done >= fileItem.size) {
                                lastUi = now
                                val pct = ((done * 100.0) / fileItem.size.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                                KirimSessionStore.updatePcFile(fileId) { it.copy(progressPct = pct, statusText = "Mengunduh $pct%") }
                                updateNotif("Mengunduh ${fileItem.name} ($pct%)")
                            }
                        }
                    }
                }
                if (cancelledIds.contains(fileId)) {
                    KirimSessionStore.updatePcFile(fileId) { it.copy(downloading = false, statusText = "Dibatalkan") }
                } else {
                    KirimSessionStore.updatePcFile(fileId) { it.copy(downloading = false, progressPct = 100, statusText = "Tersimpan di ${KirimSessionStore.saveFolderLabel.value}") }
                    updateNotif("Tersimpan: ${fileItem.name}")
                }
            }
        } catch (_: Throwable) {
            KirimSessionStore.updatePcFile(fileId) { it.copy(downloading = false, statusText = "Gagal") }
        }
    }

    private fun openDownloadOutputStream(fileName: String): java.io.OutputStream {
        KirimSessionStore.loadSaveFolder(this)
        val treeStr = KirimSessionStore.saveFolderUri.value
        if (treeStr.isNotEmpty()) {
            try {
                val treeUri = Uri.parse(treeStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val created = DocumentsContract.createDocument(contentResolver, parentUri, "application/octet-stream", fileName)
                if (created != null) {
                    val out = contentResolver.openOutputStream(created)
                    if (out != null) return out
                }
            } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Island Kirim")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) return contentResolver.openOutputStream(uri)!!
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Island Kirim")
        dir.mkdirs()
        return FileOutputStream(File(dir, fileName))
    }

    private suspend fun cancelRemote(localOrOfferId: String) {
        val base = KirimSessionStore.baseUrl.value
        val sid = KirimSessionStore.cookieSid.value
        val item = KirimSessionStore.uploads.value.firstOrNull { it.localId == localOrOfferId }
        val oid = item?.offerId
        KirimSessionStore.updateUpload(localOrOfferId) { it.copy(status = "dibatalkan", message = "Dibatalkan") }
        if (base.isNotEmpty() && sid.isNotEmpty() && !oid.isNullOrEmpty()) {
            try {
                val req = Request.Builder()
                    .url("$base/cancel/$oid")
                    .header("Cookie", "kirim_sid=$sid")
                    .post("".toRequestBody())
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Throwable) {}
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Transfer File", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Island Kirim")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}
