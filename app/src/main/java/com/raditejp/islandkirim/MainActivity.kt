package com.raditejp.islandkirim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KirimSessionStore.loadSaveFolder(this)
        handleShareIntent(intent)
        setContent { IslandKirimScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        KirimSessionStore.loadSaveFolder(this)
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

    @Composable
    fun IslandKirimScreen() {
        val ctx = LocalContext.current
        val connected by KirimSessionStore.connected.collectAsState()
        val pcName by KirimSessionStore.pcName.collectAsState()
        val remSec by KirimSessionStore.remainingSec.collectAsState()
        val pcFiles by KirimSessionStore.pcFiles.collectAsState()
        val uploads by KirimSessionStore.uploads.collectAsState()
        val saveFolderLabel by KirimSessionStore.saveFolderLabel.collectAsState()

        var selectedTab by remember { mutableStateOf(0) } // 0=Semua, 1=Dari PC, 2=Kirim ke PC
        var showScanner by remember { mutableStateOf(false) }

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

        // Latar gradien kaca teal-ocean (inspirasi desain referensi)
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
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

                // Bilah Pil Segmented (gaya referensi kaca)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Semua", "Dari PC (${pcFiles.size})", "Kirim (${uploads.size})").forEachIndexed { idx, label ->
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
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

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
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A9D8F), contentColor = Color(0xFF042228))
                ) {
                    Text("Pilih File untuk Dikirim ke PC", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                // Daftar Kartu Frosted Putih (seperti kartu properti di gambar referensi)
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
        }
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
