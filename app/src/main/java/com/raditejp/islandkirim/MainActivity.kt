package com.raditejp.islandkirim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val modeLabels = listOf(
        "Ringkas (Compact)",
        "Berkolom (Columned)",
        "Rinci (Detailed)",
        "Mengalir (Wrapped)",
        "Minimal (Minimal)",
        "Kisi (Grid)",
        "Galeri (Gallery)"
    )
    private val modeShort = listOf("Ringkas", "Berkolom", "Rinci", "Mengalir", "Minimal", "Kisi", "Galeri")
    private val sortLabels = listOf("Terbaru", "Terlama", "Nama A-Z", "Nama Z-A", "Terbesar", "Terkecil", "Jenis")
    private val filterChips = listOf("Semua", "Terkirim", "Diterima", "Gambar", "Video", "Audio", "Dokumen", "Lainnya", "Gagal")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KirimSessionStore.loadPrefsAndHistory(this)
        handleShareIntent(intent)
        setContent { IslandKirimScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) uris.addAll(list)
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.data
                val sessionUrl = when {
                    dataUri == null -> null
                    dataUri.scheme == "islandkirim" && dataUri.host == "connect" -> dataUri.getQueryParameter("url")
                    dataUri.scheme == "http" && dataUri.path?.startsWith("/k/") == true -> dataUri.toString()
                    else -> null
                }
                if (!sessionUrl.isNullOrBlank() && sessionUrl.contains("/k/")) {
                    val svc = Intent(this, TransferService::class.java).apply {
                        action = TransferService.ACTION_CONNECT
                        putExtra(TransferService.EXTRA_URL, sessionUrl)
                    }
                    ContextCompat.startForegroundService(this, svc)
                }
            }
        }
        if (uris.isNotEmpty()) enqueueUris(uris)
    }

    private fun enqueueUris(uris: List<Uri>) {
        val items = uris.mapNotNull { uri ->
            var name = "berkas"
            var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: "berkas"
                    if (si >= 0) size = c.getLong(si)
                }
            }
            UploadTaskItem(localId = UUID.randomUUID().toString(), uri = uri, name = name, size = size)
        }
        if (items.isNotEmpty()) {
            KirimSessionStore.addUploads(items)
            if (KirimSessionStore.connected.value) {
                items.forEach { startUploadService(it.localId) }
            }
        }
    }

    private fun startUploadService(localId: String) {
        val i = Intent(this, TransferService::class.java).apply {
            action = TransferService.ACTION_UPLOAD
            putExtra(TransferService.EXTRA_LOCAL_ID, localId)
        }
        ContextCompat.startForegroundService(this, i)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun IslandKirimScreen() {
        val ctx = LocalContext.current
        val connected by KirimSessionStore.connected.collectAsState()
        val pcName by KirimSessionStore.pcName.collectAsState()
        val remSec by KirimSessionStore.remainingSec.collectAsState()
        val pcFiles by KirimSessionStore.pcFiles.collectAsState()
        val uploads by KirimSessionStore.uploads.collectAsState()
        val history by KirimSessionStore.history.collectAsState()
        val saveFolderLabel by KirimSessionStore.saveFolderLabel.collectAsState()
        val viewMode by KirimSessionStore.viewMode.collectAsState()

        var selectedTab by remember { mutableStateOf(0) } // 0=Transfer, 1=Dari PC, 2=Kirim, 3=Riwayat
        var showScanner by remember { mutableStateOf(false) }

        // State Riwayat (Search, Mode, Urutkan, Filter, Pilih, Preview)
        var searchQuery by remember { mutableStateOf("") }
        var activeChip by remember { mutableStateOf("Semua") }
        var sortMode by remember { mutableStateOf(0) }
        var showModeMenu by remember { mutableStateOf(false) }
        var showSortMenu by remember { mutableStateOf(false) }
        var multiSelect by remember { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf(setOf<String>()) }
        var previewItem by remember { mutableStateOf<HistoryItem?>(null) }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            showScanner = true
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) enqueueUris(uris)
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
                } catch (_: Throwable) {}
                val label = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Folder pilihan"
                KirimSessionStore.setSaveFolder(ctx, treeUri.toString(), label)
            }
        }

        val bgGradient = Brush.linearGradient(
            colors = listOf(Color(0xFF07232E), Color(0xFF0F4C5C), Color(0xFF187C81), Color(0xFF0A323F))
        )

        Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Kaca Frosted
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.36f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Island Kirim", color = Color(0xFFF7FFFD), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (connected) Color(0xFF34D399) else Color(0xFFFF8A8A))
                            )
                            Text(
                                text = if (connected) "$pcName · ${remSec / 60}:${(remSec % 60).toString().padStart(2, '0')}" else "Belum terhubung",
                                color = Color(0xFFA8DADC),
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val needed = mutableListOf(Manifest.permission.CAMERA)
                            if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
                            val missing = needed.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                            if (missing) permLauncher.launch(needed.toTypedArray()) else showScanner = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Pindai QR", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Bilah Pil Segmented (Transfer, Dari PC, Kirim, Riwayat)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Transfer", "Dari PC (${pcFiles.size})", "Kirim (${uploads.size})", "Riwayat (${history.size})").forEachIndexed { idx, label ->
                        val active = selectedTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (active) Color(0xFF0F172A) else Color.Transparent)
                                .clickable { selectedTab = idx }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (active) Color.White else Color(0xFFE0F7F4),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (selectedTab != 3) {
                    // Menu Folder Simpan di HP (Glass Bar)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.13f))
                            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Folder simpan di HP", color = Color(0xFFA8DADC), fontSize = 11.sp)
                            Text(saveFolderLabel, color = Color(0xFFF7FFFD), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (saveFolderLabel != "Downloads/Island Kirim (bawaan)") {
                                TextButton(
                                    onClick = { KirimSessionStore.setSaveFolder(ctx, "", "Downloads/Island Kirim (bawaan)") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("Reset", color = Color(0xFFA8DADC), fontSize = 12.sp) }
                            }
                            Button(
                                onClick = { folderPicker.launch(null) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) { Text("Ubah", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }

                    // Tombol Pilih File Besar (Glass CTA)
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A9D8F), contentColor = Color(0xFF042228))
                    ) {
                        Text("Pilih File untuk Dikirim ke PC", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Daftar Transfer Aktif
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedTab == 0 || selectedTab == 1) {
                            items(pcFiles, key = { "pc_${it.id}" }) { f ->
                                FrostedCard {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(f.name, color = Color(0xFF0F4C5C), fontWeight = FontWeight.Bold, fontSize = 14.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.width(8.dp))
                                        Text(KirimSessionStore.formatSize(f.size), color = Color(0xFF136A70), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (f.downloading) {
                                        LinearProgressIndicator(
                                            progress = { f.progressPct / 100f },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp).clip(CircleShape),
                                            color = Color(0xFF187C81),
                                            trackColor = Color(0x220F4C5C)
                                        )
                                    }
                                    if (f.statusText.isNotEmpty()) {
                                        Text(f.statusText, color = Color(0xFF136A70), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!f.downloading) {
                                            Button(
                                                onClick = {
                                                    val i = Intent(ctx, TransferService::class.java).apply {
                                                        action = TransferService.ACTION_DOWNLOAD
                                                        putExtra(TransferService.EXTRA_FILE_ID, f.id)
                                                    }
                                                    ContextCompat.startForegroundService(ctx, i)
                                                },
                                                modifier = Modifier.weight(1f).height(42.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White)
                                            ) { Text("Simpan ke HP", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    val i = Intent(ctx, TransferService::class.java).apply {
                                                        action = TransferService.ACTION_CANCEL
                                                        putExtra(TransferService.EXTRA_FILE_ID, f.id)
                                                    }
                                                    ContextCompat.startForegroundService(ctx, i)
                                                },
                                                modifier = Modifier.weight(1f).height(42.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            ) { Text("Batal", color = Color(0xFFB4142F), fontWeight = FontWeight.SemiBold) }
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedTab == 0 || selectedTab == 2) {
                            items(uploads, key = { it.localId }) { u ->
                                FrostedCard {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(u.name, color = Color(0xFF0F4C5C), fontWeight = FontWeight.Bold, fontSize = 14.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.width(8.dp))
                                        Text(KirimSessionStore.formatSize(u.size), color = Color(0xFF136A70), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { u.pct / 100f },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp).clip(CircleShape),
                                        color = if (u.status == "gagal" || u.status == "ditolak") Color(0xFFB4142F) else Color(0xFF187C81),
                                        trackColor = Color(0x220F4C5C)
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            u.message,
                                            color = if (u.status == "gagal" || u.status == "ditolak") Color(0xFFB4142F) else Color(0xFF136A70),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (u.status == "siap") {
                                                Button(
                                                    onClick = { startUploadService(u.localId) },
                                                    shape = RoundedCornerShape(10.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                                ) { Text("Kirim", fontSize = 12.sp) }
                                            }
                                            if (u.status == "siap" || u.status == "menunggu" || u.status == "mengirim") {
                                                OutlinedButton(
                                                    onClick = {
                                                        val i = Intent(ctx, TransferService::class.java).apply {
                                                            action = TransferService.ACTION_CANCEL
                                                            putExtra(TransferService.EXTRA_LOCAL_ID, u.localId)
                                                        }
                                                        ContextCompat.startForegroundService(ctx, i)
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) { Text("Batal", color = Color(0xFFB4142F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                            }
                                            if (u.status == "gagal" || u.status == "dibatalkan" || u.status == "ditolak" || u.status == "terkirim") {
                                                Button(
                                                    onClick = {
                                                        if (u.status != "gagal") {
                                                            KirimSessionStore.updateUpload(u.localId) { it.copy(offerId = null, pct = 0) }
                                                        }
                                                        startUploadService(u.localId)
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) { Text(if (u.status == "gagal") "Coba lagi" else "Kirim ulang", fontSize = 12.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // TAB 3: RIWAYAT KIRIM (Search, 7 Mode Tampilan, 7 Urutkan, Filter Chip, Pilih, Preview)
                    val filtered = remember(history, searchQuery, activeChip, sortMode) {
                        val q = searchQuery.trim().lowercase(Locale.US)
                        var list = history.filter { item ->
                            val chipOk = when (activeChip) {
                                "Semua" -> true
                                "Terkirim" -> item.arah == "kirim"
                                "Diterima" -> item.arah == "terima"
                                "Gagal" -> item.status == "gagal" || item.status == "dibatalkan" || item.status == "ditolak"
                                else -> KirimSessionStore.getCategory(item.nama) == activeChip
                            }
                            if (!chipOk) return@filter false
                            if (q.isEmpty()) return@filter true
                            val tokens = q.split(" ").filter { it.isNotBlank() }
                            tokens.all { tok ->
                                when {
                                    tok.startsWith("jenis:") -> KirimSessionStore.getCategory(item.nama).lowercase(Locale.US).contains(tok.removePrefix("jenis:"))
                                    tok.startsWith("dari:") -> item.perangkat.lowercase(Locale.US).contains(tok.removePrefix("dari:"))
                                    tok.startsWith("arah:") -> item.arah.lowercase(Locale.US).contains(tok.removePrefix("arah:"))
                                    tok.startsWith("status:") -> item.status.lowercase(Locale.US).contains(tok.removePrefix("status:"))
                                    else -> item.nama.lowercase(Locale.US).contains(tok) ||
                                        item.perangkat.lowercase(Locale.US).contains(tok) ||
                                        KirimSessionStore.getCategory(item.nama).lowercase(Locale.US).contains(tok)
                                }
                            }
                        }
                        list = when (sortMode) {
                            1 -> list.sortedBy { it.waktuMs }
                            2 -> list.sortedBy { it.nama.lowercase(Locale.US) }
                            3 -> list.sortedByDescending { it.nama.lowercase(Locale.US) }
                            4 -> list.sortedByDescending { it.ukuran }
                            5 -> list.sortedBy { it.ukuran }
                            6 -> list.sortedBy { KirimSessionStore.getCategory(it.nama) }
                            else -> list.sortedByDescending { it.waktuMs }
                        }
                        list
                    }

                    // Kolom Cari + Tombol Mode, Urutkan, Pilih
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Cari nama, perangkat, jenis:gambar...", color = Color(0x99E0F7F4), fontSize = 12.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF2A9D8F),
                                unfocusedBorderColor = Color(0x55F7FFFD),
                                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.10f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val glassMenuScheme = darkColorScheme(
                            surface = Color(0xEB0A2D38),
                            onSurface = Color(0xFFF7FFFD)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                                        .clickable { showModeMenu = true }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) { Text("${modeShort[viewMode]} ▾", color = Color(0xFFF7FFFD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                MaterialTheme(colorScheme = glassMenuScheme) {
                                    DropdownMenu(
                                        expanded = showModeMenu,
                                        onDismissRequest = { showModeMenu = false },
                                        modifier = Modifier
                                            .background(Color(0xEB0A2D38), RoundedCornerShape(16.dp))
                                            .border(1.dp, Color(0x66F7FFFD), RoundedCornerShape(16.dp))
                                    ) {
                                        modeLabels.forEachIndexed { idx, lbl ->
                                            val active = viewMode == idx
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (active) "✓ $lbl" else lbl,
                                                        color = if (active) Color(0xFF5EEAD4) else Color(0xFFF7FFFD),
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 13.sp
                                                    )
                                                },
                                                modifier = Modifier
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (active) Color(0x332A9D8F) else Color.Transparent),
                                                onClick = {
                                                    KirimSessionStore.setViewMode(ctx, idx)
                                                    showModeMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                                        .clickable { showSortMenu = true }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) { Text("${sortLabels[sortMode]} ▾", color = Color(0xFFF7FFFD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                MaterialTheme(colorScheme = glassMenuScheme) {
                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false },
                                        modifier = Modifier
                                            .background(Color(0xEB0A2D38), RoundedCornerShape(16.dp))
                                            .border(1.dp, Color(0x66F7FFFD), RoundedCornerShape(16.dp))
                                    ) {
                                        sortLabels.forEachIndexed { idx, lbl ->
                                            val active = sortMode == idx
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (active) "✓ $lbl" else lbl,
                                                        color = if (active) Color(0xFF5EEAD4) else Color(0xFFF7FFFD),
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 13.sp
                                                    )
                                                },
                                                modifier = Modifier
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (active) Color(0x332A9D8F) else Color.Transparent),
                                                onClick = { sortMode = idx; showSortMenu = false }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            Button(
                                onClick = {
                                    multiSelect = !multiSelect
                                    if (!multiSelect) selectedIds = emptySet()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (multiSelect) Color(0xFF2A9D8F) else Color(0xFF0F172A),
                                    contentColor = if (multiSelect) Color(0xFF042228) else Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { Text(if (multiSelect) "Selesai" else "Pilih", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }

                        // Baris Chip Filter Horizontal
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            filterChips.forEach { chip ->
                                val active = activeChip == chip
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (active) Color(0xFF0F172A) else Color.White.copy(alpha = 0.16f))
                                        .border(1.dp, Color.White.copy(alpha = if (active) 0.7f else 0.25f), CircleShape)
                                        .clickable { activeChip = chip }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(chip, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Isi Daftar Riwayat sesuai 7 Mode Tampilan
                    Box(modifier = Modifier.weight(1f)) {
                        if (filtered.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Belum ada riwayat sesuai filter.", color = Color(0xFFA8DADC), fontSize = 13.5.sp)
                            }
                        } else if (viewMode == 5 || viewMode == 6) {
                            // Mode Kisi (5) & Galeri (6)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(if (viewMode == 6) 1 else 2),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filtered, key = { it.id + it.waktuMs }) { item ->
                                    HistoryTileCard(
                                        item = item,
                                        large = viewMode == 6,
                                        multiSelect = multiSelect,
                                        selected = selectedIds.contains(item.id),
                                        onClick = {
                                            if (multiSelect) {
                                                selectedIds = if (selectedIds.contains(item.id)) selectedIds - item.id else selectedIds + item.id
                                            } else previewItem = item
                                        }
                                    )
                                }
                            }
                        } else if (viewMode == 3) {
                            // Mode Mengalir (Wrapped)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    filtered.forEach { item ->
                                        val isSel = selectedIds.contains(item.id)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isSel) Color(0xFF2A9D8F) else Color.White.copy(alpha = 0.90f))
                                                .clickable {
                                                    if (multiSelect) selectedIds = if (isSel) selectedIds - item.id else selectedIds + item.id
                                                    else previewItem = item
                                                }
                                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                        ) {
                                            Text("${item.nama} · ${KirimSessionStore.formatSize(item.ukuran)}", color = Color(0xFF0F4C5C), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        } else {
                            // Mode Ringkas (0), Berkolom (1), Rinci (2), Minimal (4)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                                items(filtered, key = { it.id + it.waktuMs }) { item ->
                                    HistoryRowCard(
                                        item = item,
                                        mode = viewMode,
                                        multiSelect = multiSelect,
                                        selected = selectedIds.contains(item.id),
                                        onClick = {
                                            if (multiSelect) {
                                                selectedIds = if (selectedIds.contains(item.id)) selectedIds - item.id else selectedIds + item.id
                                            } else previewItem = item
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Bilah Status & Aksi Massal Bawah
                    val totalBytes = filtered.sumOf { it.ukuran }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${filtered.size} file · total ${KirimSessionStore.formatSize(totalBytes)}", color = Color(0xFFE0F7F4), fontSize = 12.sp)
                        if (multiSelect && selectedIds.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    KirimSessionStore.removeHistoryIds(ctx, selectedIds)
                                    selectedIds = emptySet()
                                    multiSelect = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("Hapus (${selectedIds.size})", color = Color(0xFFFF8A8A), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (showScanner) {
                QrScannerDialog(
                    onScannedUrl = { url ->
                        showScanner = false
                        val i = Intent(ctx, TransferService::class.java).apply {
                            action = TransferService.ACTION_CONNECT
                            putExtra(TransferService.EXTRA_URL, url)
                        }
                        ContextCompat.startForegroundService(ctx, i)
                    },
                    onDismiss = { showScanner = false }
                )
            }

            previewItem?.let { item ->
                HistoryPreviewModal(
                    item = item,
                    onClose = { previewItem = null },
                    onResend = {
                        if (item.uriStr.isNotEmpty()) {
                            enqueueUris(listOf(Uri.parse(item.uriStr)))
                            selectedTab = 0
                            previewItem = null
                        }
                    },
                    onDeleteHistory = {
                        KirimSessionStore.removeHistoryIds(ctx, setOf(item.id))
                        previewItem = null
                    }
                )
            }
        }
    }

    @Composable
    fun HistoryRowCard(item: HistoryItem, mode: Int, multiSelect: Boolean, selected: Boolean, onClick: () -> Unit) {
        val dateStr = remember(item.waktuMs) { SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(item.waktuMs)) }
        val arrow = if (item.arah == "kirim") "↑" else "↓"
        val isFail = item.status == "gagal" || item.status == "dibatalkan" || item.status == "ditolak"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) Color(0xFFCCFBF1) else Color.White.copy(alpha = 0.90f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = if (mode == 0 || mode == 4) 7.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.size(20.dp))
            }
            when (mode) {
                4 -> { // Minimal
                    Text(item.nama, color = Color(0xFF0F4C5C), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                0 -> { // Ringkas
                    Text("$arrow ${item.nama}", color = Color(0xFF0F4C5C), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(KirimSessionStore.formatSize(item.ukuran), color = Color(0xFF136A70), fontSize = 12.sp)
                }
                1 -> { // Berkolom
                    Text(item.nama, color = Color(0xFF0F4C5C), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.perangkat, color = Color(0xFF136A70), fontSize = 11.5.sp, modifier = Modifier.weight(0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(KirimSessionStore.formatSize(item.ukuran), color = Color(0xFF136A70), fontSize = 11.5.sp)
                }
                else -> { // Rinci (2)
                    Box(
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE0F7F4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(KirimSessionStore.getCategory(item.nama).take(3).uppercase(Locale.US), color = Color(0xFF0F4C5C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.nama, color = Color(0xFF0F4C5C), fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val st = if (item.status == "gagal") "Gagal ${item.persenGagal}%" else item.status
                        Text("$arrow ${item.perangkat} · $dateStr · $st", color = if (isFail) Color(0xFFB4142F) else Color(0xFF136A70), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(KirimSessionStore.formatSize(item.ukuran), color = Color(0xFF136A70), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    fun HistoryTileCard(item: HistoryItem, large: Boolean, multiSelect: Boolean, selected: Boolean, onClick: () -> Unit) {
        val ctx = LocalContext.current
        val bmp = remember(item.uriStr) { loadThumb(ctx, item) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Color(0xFFCCFBF1) else Color.White.copy(alpha = 0.92f))
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (large) 160.dp else 92.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFA8DADC), Color(0xFF2A9D8F)))),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = item.nama, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(KirimSessionStore.getCategory(item.nama), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.nama, color = Color(0xFF0F4C5C), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${KirimSessionStore.formatSize(item.ukuran)} · ${item.status}", color = Color(0xFF136A70), fontSize = 11.sp)
        }
    }

    @Composable
    fun HistoryPreviewModal(
        item: HistoryItem,
        onClose: () -> Unit,
        onResend: () -> Unit,
        onDeleteHistory: () -> Unit
    ) {
        val ctx = LocalContext.current
        val cat = KirimSessionStore.getCategory(item.nama)
        val bmp = remember(item.uriStr) { if (cat == "Gambar") loadThumb(ctx, item, maxDim = 1024) else null }
        val textPreview = remember(item.uriStr) { if (cat == "Dokumen") loadTextSnippet(ctx, item) else null }
        var zoom100 by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xBF061B24))
                .clickable(onClick = onClose)
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFFF7FFFD))
                    .clickable(enabled = false) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Pratinjau File", color = Color(0xFF0F4C5C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(onClick = onClose) { Text("Tutup", color = Color(0xFF136A70)) }
                }

                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFE0F7F4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = item.nama,
                            contentScale = if (zoom100) ContentScale.None else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { zoom100 = false }, shape = RoundedCornerShape(10.dp)) { Text("Muat ke layar", color = Color(0xFF0F4C5C), fontSize = 12.sp) }
                        OutlinedButton(onClick = { zoom100 = true }, shape = RoundedCornerShape(10.dp)) { Text("100%", color = Color(0xFF0F4C5C), fontSize = 12.sp) }
                    }
                } else if (textPreview != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE0F7F4))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(textPreview, color = Color(0xFF0F4C5C), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }

                if (item.status == "gagal") {
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x1AE11D48)).padding(10.dp)
                    ) {
                        Text("Gagal ${item.persenGagal}%: ${item.penyebab}", color = Color(0xFFB4142F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(item.nama, color = Color(0xFF0F4C5C), fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                val dt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(item.waktuMs))
                Text("${KirimSessionStore.formatSize(item.ukuran)} · ${item.perangkat} · $dt", color = Color(0xFF136A70), fontSize = 12.sp)

                if (item.uriStr.isNotEmpty()) {
                    Button(
                        onClick = {
                            try {
                                val u = Uri.parse(item.uriStr)
                                val ext = item.nama.substringAfterLast('.', "").lowercase(Locale.US)
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(u, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(viewIntent)
                            } catch (_: Throwable) {}
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F4C5C), contentColor = Color.White)
                    ) { Text("Buka", fontWeight = FontWeight.SemiBold) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.uriStr.isNotEmpty()) {
                        OutlinedButton(onClick = onResend, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                            Text("Kirim lagi", color = Color(0xFF0F4C5C), fontSize = 12.5.sp)
                        }
                    }
                    OutlinedButton(onClick = onDeleteHistory, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                        Text("Hapus riwayat", color = Color(0xFFB4142F), fontSize = 12.5.sp)
                    }
                }
            }
        }
    }

    private fun loadThumb(ctx: android.content.Context, item: HistoryItem, maxDim: Int = 320): ImageBitmap? {
        if (item.uriStr.isEmpty() || KirimSessionStore.getCategory(item.nama) != "Gambar") return null
        return try {
            val u = Uri.parse(item.uriStr)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }?.asImageBitmap()
        } catch (_: Throwable) { null }
    }

    private fun loadTextSnippet(ctx: android.content.Context, item: HistoryItem): String? {
        if (item.uriStr.isEmpty()) return null
        val ext = item.nama.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext !in setOf("txt", "md", "json", "csv", "log")) return null
        return try {
            val u = Uri.parse(item.uriStr)
            ctx.contentResolver.openInputStream(u)?.use { input ->
                val buf = ByteArray(32768)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }
        } catch (_: Throwable) { null }
    }

    @Composable
    fun FrostedCard(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.90f))
                .border(1.dp, Color.White.copy(alpha = 0.70f), RoundedCornerShape(20.dp))
                .padding(14.dp),
            content = content
        )
    }
}
