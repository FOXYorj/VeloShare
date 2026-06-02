package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SystemAppInfo(
    val name: String,
    val packageName: String,
    val size: Long,
    val sizeFormatted: String,
    val sourcePath: String,
    val uri: Uri
)

data class MediaItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String,
    val sizeFormatted: String
)

data class HistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val sizeFormatted: String,
    val peerName: String,
    val isIncoming: Boolean,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ShareViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ShareViewModel"
    private val context = application.applicationContext

    private val nsdHelper = NsdHelper(context)
    private val transferEngine = TransferEngine(context, viewModelScope)
    private val notificationHelper = NotificationHelper(context)

    // User information with SharedPreferences persistence
    private val prefs = context.getSharedPreferences("veloshare_preferences", Context.MODE_PRIVATE)
    val deviceNameState = MutableStateFlow(prefs.getString("device_name", null) ?: Build.MODEL)
    val localIpState = MutableStateFlow("127.0.0.1")
    val profileImageUriState = MutableStateFlow(prefs.getString("profile_image_uri", "") ?: "")

    // Advanced settings states
    val autoAcceptIncoming = MutableStateFlow(true)
    val onlyWifiState = MutableStateFlow(false)
    val keepScreenOnState = MutableStateFlow(true)
    val showDetailedStats = MutableStateFlow(true)
    val highContrastTheme = MutableStateFlow(true)
    val defaultSaveDirectory = MutableStateFlow("Dahili Depolama / VeloShare")

    // Peer and transfer states
    val discoveredPeers: StateFlow<Map<String, Peer>> = nsdHelper.discoveredPeers
    val transferState: StateFlow<TransferState> = transferEngine.transferState

    // Cached category lists
    private val _installedApps = MutableStateFlow<List<SystemAppInfo>>(emptyList())
    val installedApps = _installedApps.asStateFlow()

    private val _galleryItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val galleryItems = _galleryItems.asStateFlow()

    private val _audioItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val audioItems = _audioItems.asStateFlow()

    private val _isLoadingCategory = MutableStateFlow(false)
    val isLoadingCategory = _isLoadingCategory.asStateFlow()

    // Real File Explorer states
    val currentExplorerDir = MutableStateFlow<File?>(null)
    val explorerFiles = MutableStateFlow<List<File>>(emptyList())
    val selectedExplorerFiles = MutableStateFlow<Set<File>>(emptySet())

    // Storage analytics states
    val totalStorageBytes = MutableStateFlow(1L)
    val freeStorageBytes = MutableStateFlow(0L)

    // Transfer history states
    val transferHistory = MutableStateFlow<List<HistoryItem>>(emptyList())
    // Recent connections
    val recentPeers = MutableStateFlow<List<Peer>>(emptyList())

    init {
        resolveLocalInfo()
        startDiscoveryAndServer()
        updateStorageMetrics()
        initExplorer()
        observeTransferEngine()
    }

    fun resolveLocalInfo() {
        val ip = NsdHelper.getLocalIpAddress()
        localIpState.value = ip
        Log.d(TAG, "Local IP: $ip, Device Name: ${deviceNameState.value}")
    }

    private fun startDiscoveryAndServer() {
        viewModelScope.launch {
            transferEngine.startServer(deviceNameState.value)
            nsdHelper.registerService(deviceNameState.value, TransferEngine.SERVER_PORT)
            nsdHelper.discoverServices()
        }
    }

    fun updateDeviceName(newName: String) {
        if (newName.isNotBlank() && newName != deviceNameState.value) {
            deviceNameState.value = newName
            prefs.edit().putString("device_name", newName).apply()
            // Re-register mDNS service with the new name
            viewModelScope.launch {
                nsdHelper.stopRegistration()
                nsdHelper.registerService(newName, TransferEngine.SERVER_PORT)
            }
        }
    }

    fun updateProfileImageUri(uri: String) {
        profileImageUriState.value = uri
        prefs.edit().putString("profile_image_uri", uri).apply()
    }

    fun setProfileImageFromUri(uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val profileDir = File(context.filesDir, "profile")
                if (!profileDir.exists()) {
                    profileDir.mkdirs()
                }
                val destFile = File(profileDir, "avatar.png")
                val outputStream = FileOutputStream(destFile)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                updateProfileImageUri(Uri.fromFile(destFile).toString())
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy profile picture", e)
            false
        }
    }

    // CATEGORY LODING ACTIONS
    fun loadInstalledApps() {
        _isLoadingCategory.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val appsList = mutableListOf<SystemAppInfo>()
                val processedPackages = mutableSetOf<String>()
                
                // 1. Query helper to get all apps with Launcher intents (this catches Google Apps, System browser, YouTube, etc.)
                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                    for (ri in resolveInfos) {
                        val appInfo = ri.activityInfo.applicationInfo
                        val pkgName = appInfo.packageName
                        if (processedPackages.contains(pkgName)) continue
                        processedPackages.add(pkgName)
                        
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val apkFile = File(appInfo.publicSourceDir)
                        if (apkFile.exists()) {
                            val size = apkFile.length()
                            val uri = Uri.fromFile(apkFile)
                            appsList.add(
                                SystemAppInfo(
                                    name = name,
                                    packageName = pkgName,
                                    size = size,
                                    sizeFormatted = formatFileSize(size),
                                    sourcePath = appInfo.publicSourceDir,
                                    uri = uri
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error matching launcher applications", e)
                }

                // 2. Query any remaining user-installed applications (non-system flags)
                try {
                    val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (appInfo in packages) {
                        if (processedPackages.contains(appInfo.packageName)) continue
                        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                            processedPackages.add(appInfo.packageName)
                            val name = pm.getApplicationLabel(appInfo).toString()
                            val apkFile = File(appInfo.publicSourceDir)
                            if (apkFile.exists()) {
                                val size = apkFile.length()
                                val uri = Uri.fromFile(apkFile)
                                appsList.add(
                                    SystemAppInfo(
                                        name = name,
                                        packageName = appInfo.packageName,
                                        size = size,
                                        sizeFormatted = formatFileSize(size),
                                        sourcePath = appInfo.publicSourceDir,
                                        uri = uri
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error matching non-system applications", e)
                }
                
                // Sort alphabetically by application label
                _installedApps.value = appsList.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading apps", e)
            } finally {
                _isLoadingCategory.value = false
            }
        }
    }

    fun loadGalleryItems() {
        _isLoadingCategory.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaList = mutableListOf<MediaItem>()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE
                )
                
                // Get Images
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        mediaList.add(MediaItem(id, name, uri, size, mime, formatFileSize(size)))
                    }
                }

                // Get Videos
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null, null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)
                        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                        mediaList.add(MediaItem(id, name, uri, size, mime, formatFileSize(size)))
                    }
                }

                _galleryItems.value = mediaList
            } catch (e: Exception) {
                Log.e(TAG, "Error loading gallery", e)
            } finally {
                _isLoadingCategory.value = false
            }
        }
    }

    fun loadAudioItems() {
        _isLoadingCategory.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioList = mutableListOf<MediaItem>()
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE
                )
                
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        audioList.add(MediaItem(id, name, uri, size, mime, formatFileSize(size)))
                    }
                }
                _audioItems.value = audioList
            } catch (e: Exception) {
                Log.e(TAG, "Error loading audio", e)
            } finally {
                _isLoadingCategory.value = false
            }
        }
    }

    // DISPATCH TRANSFERS
    fun sendClipboardText(peer: Peer, text: String) {
        transferEngine.sendClipboard(peer.ip, peer.port, text, deviceNameState.value)
    }

    fun sendFileUri(peer: Peer, uri: Uri, customName: String? = null, customSize: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                var fileName = customName ?: ""
                var fileSize = customSize ?: 0L
                var mimeType = "application/octet-stream"

                // Query resolver to get real filename and size if not provided
                if (customName == null || customSize == null) {
                    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
                    resolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                            
                            if (nameCol != -1) fileName = cursor.getString(nameCol) ?: "file.bin"
                            if (sizeCol != -1) fileSize = cursor.getLong(sizeCol)
                            if (mimeCol != -1) mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                        }
                    }
                }

                // If URI is private/package path, we may need a helper method
                if (fileSize == 0L) {
                    // Try direct stream length if available
                    try {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                            fileSize = fd.length
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get asset FD length", e)
                    }
                }

                // Force .apk extension for Android packages on the network layer
                if (mimeType == "application/vnd.android.package-archive" || 
                    uri.path?.contains(".apk") == true || 
                    uri.path?.contains("base.apk") == true ||
                    fileName.lowercase().contains("base.apk")
                ) {
                    if (!fileName.lowercase().endsWith(".apk")) {
                        fileName = if (fileName.isNotBlank()) "$fileName.apk" else "app.apk"
                    }
                }

                transferEngine.sendFile(
                    ip = peer.ip,
                    port = peer.port,
                    uri = uri,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    senderDeviceName = deviceNameState.value
                )
            } catch (e: Exception) {
                Log.e(TAG, "Send initialization error", e)
            }
        }
    }

    // Direct match via QR code connection details
    fun connectDirectlyAndSend(ip: String, port: Int, uri: Uri?, textToSend: String? = null) {
        val proxyPeer = Peer(id = "QR_Direct", name = "Doğrudan Cihaz (QR)", ip = ip, port = port)
        addRecentPeer(proxyPeer)
        if (textToSend != null) {
            sendClipboardText(proxyPeer, textToSend)
        } else if (uri != null) {
            sendFileUri(proxyPeer, uri)
        }
    }

    private fun observeTransferEngine() {
        viewModelScope.launch {
            transferEngine.transferState.collect { state ->
                when (state) {
                    is TransferState.Progress -> {
                        notificationHelper.showProgressNotification(
                            fileName = state.name,
                            isSending = state.isSending,
                            progress = state.percentage.toInt(),
                            speed = state.speedMbS
                        )
                    }
                    is TransferState.Success -> {
                        val cleanName = state.message
                            .replace(" başarıyla gönderildi!", "")
                            .replace(" başarıyla indirildi!", "")
                            .replace(" başarıyla kaydedildi!", "")
                        notificationHelper.showSuccessNotification(
                            fileName = cleanName,
                            isSending = state.isSending
                        )
                        addHistoryItem(
                            name = state.message,
                            sizeFormatted = if (state.type == "clipboard") "Metin" else "Başarılı",
                            peerName = state.peerName,
                            isIncoming = !state.isSending,
                            isSuccess = true
                        )
                    }
                    is TransferState.Error -> {
                        notificationHelper.showErrorNotification(state.message)
                        addHistoryItem(
                            name = "Aktarım hatası: ${state.message}",
                            sizeFormatted = "Hata",
                            peerName = "Bilinmeyen Cihaz",
                            isIncoming = false,
                            isSuccess = false
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    fun addHistoryItem(name: String, sizeFormatted: String, peerName: String, isIncoming: Boolean, isSuccess: Boolean) {
        val newItem = HistoryItem(
            name = name,
            sizeFormatted = sizeFormatted,
            peerName = peerName,
            isIncoming = isIncoming,
            isSuccess = isSuccess
        )
        transferHistory.value = (listOf(newItem) + transferHistory.value).take(50)
    }

    fun addRecentPeer(peer: Peer) {
        val current = recentPeers.value.toMutableList()
        current.removeAll { it.ip == peer.ip }
        current.add(0, peer)
        recentPeers.value = current.take(6)
    }

    fun updateStorageMetrics() {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            totalStorageBytes.value = totalBlocks * blockSize
            freeStorageBytes.value = availableBlocks * blockSize
        } catch (e: Exception) {
            Log.e(TAG, "Error matching storage metrics", e)
            totalStorageBytes.value = 100L * 1024 * 1024 * 1024
            freeStorageBytes.value = 40L * 1024 * 1024 * 1024
        }
    }

    fun initExplorer() {
        val root = Environment.getExternalStorageDirectory()
        if (root.exists() && root.canRead()) {
            currentExplorerDir.value = root
            loadFilesFromDir(root)
        } else {
            val fallback = context.getExternalFilesDir(null) ?: context.filesDir
            currentExplorerDir.value = fallback
            loadFilesFromDir(fallback)
        }
    }

    fun loadFilesFromDir(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = dir.listFiles()?.toList() ?: emptyList()
                val sorted = files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                explorerFiles.value = sorted
            } catch (e: Exception) {
                Log.e(TAG, "Error listing files in $dir", e)
                explorerFiles.value = emptyList()
            }
        }
    }

    fun navigateToDir(dir: File) {
        if (dir.isDirectory && dir.canRead()) {
            currentExplorerDir.value = dir
            loadFilesFromDir(dir)
        }
    }

    fun navigateUp() {
        val current = currentExplorerDir.value
        val parent = current?.parentFile
        val root = Environment.getExternalStorageDirectory()
        if (current != null && current.absolutePath != root.absolutePath && parent != null && parent.canRead()) {
            currentExplorerDir.value = parent
            loadFilesFromDir(parent)
        }
    }

    fun toggleSelectFile(file: File) {
        val current = selectedExplorerFiles.value.toMutableSet()
        if (current.contains(file)) {
            current.remove(file)
        } else {
            current.add(file)
        }
        selectedExplorerFiles.value = current
    }

    fun clearSelectedFiles() {
        selectedExplorerFiles.value = emptySet()
    }

    fun sendSelectedExplorerFiles(peer: Peer) {
        viewModelScope.launch {
            val selected = selectedExplorerFiles.value
            selected.forEach { file ->
                val uri = Uri.fromFile(file)
                sendFileUri(peer, uri, file.name, file.length())
            }
            clearSelectedFiles()
        }
    }

    fun resetState() {
        transferEngine.resetState()
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val formatted = String.format("%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))
        return "$formatted ${units[digitGroups]}"
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.cleanup()
        transferEngine.stopServer()
    }
}
