package com.example

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

sealed class TransferState {
    object Idle : TransferState()
    data class WaitingForAccept(
        val peerName: String,
        val type: String, // "file" or "clipboard"
        val name: String,
        val size: Long,
        val ip: String,
        val onAccept: (Boolean) -> Unit
    ) : TransferState()
    data class Progress(
        val peerName: String,
        val isSending: Boolean,
        val name: String,
        val processedBytes: Long,
        val totalBytes: Long,
        val percentage: Float,
        val speedMbS: Float
    ) : TransferState()
    data class Success(
        val peerName: String,
        val isSending: Boolean,
        val message: String,
        val type: String = "file"
    ) : TransferState()
    data class Error(val message: String) : TransferState()
}

class TransferEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "TransferEngine"
    private var serverSocket: ServerSocket? = null
    private var serverActive = false

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState = _transferState.asStateFlow()

    private var activeSocket: Socket? = null

    companion object {
        const val SERVER_PORT = 53210
    }

    fun startServer(deviceName: String) {
        if (serverActive) return
        serverActive = true
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SERVER_PORT).apply {
                    reuseAddress = true
                }
                Log.d(TAG, "Soket sunucusu başlatıldı. Port: $SERVER_PORT")

                while (serverActive) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Yeni alıcı bağlantısı sağlandı: ${socket.remoteSocketAddress}")
                    handleIncomingConnection(socket, deviceName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Soket sunucu hatası", e)
                if (serverActive) {
                    _transferState.value = TransferState.Error("Server hatası: ${e.message}")
                }
            }
        }
    }

    fun stopServer() {
        serverActive = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Server kapatma hatası", e)
        }
        serverSocket = null
    }

    private suspend fun handleIncomingConnection(socket: Socket, receiverDeviceName: String) = withContext(Dispatchers.IO) {
        activeSocket = socket
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream()

            // 1. Handshake okuma
            val handshakeLine = reader.readLine() ?: throw Exception("Handshake alınamadı")
            Log.d(TAG, "Handshake alındı: $handshakeLine")
            val handshakeJson = JSONObject(handshakeLine)
            
            val type = handshakeJson.optString("type", "file")
            val peerName = handshakeJson.optString("senderName", "Bilinmeyen Cihaz")
            
            if (type == "clipboard") {
                val text = handshakeJson.optString("text", "")
                _transferState.value = TransferState.WaitingForAccept(
                    peerName = peerName,
                    type = "clipboard",
                    name = if (text.length > 50) text.take(50) + "..." else text,
                    size = text.length.toLong(),
                    ip = socket.inetAddress.hostAddress ?: "",
                ) { accepted ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (accepted) {
                                writer.write("READY\n".toByteArray())
                                writer.flush()
                                
                                // Panoya Yaz
                                withContext(Dispatchers.Main) {
                                    copyToClipboard(text)
                                }
                                _transferState.value = TransferState.Success(peerName, false, "Metin panoya başarıyla kaydedildi!", "clipboard")
                            } else {
                                writer.write("REJECT\n".toByteArray())
                                writer.flush()
                                _transferState.value = TransferState.Idle
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Clipboard onay hatası", e)
                            _transferState.value = TransferState.Error("İşlem yarıda kesildi: ${e.localizedMessage}")
                        } finally {
                            closeActiveSocket()
                        }
                    }
                }
            } else {
                // File Transfer
                val fileName = handshakeJson.optString("fileName", "BilinmeyenDosya")
                val fileSize = handshakeJson.optLong("fileSize", 0L)
                val mimeType = handshakeJson.optString("fileType", "application/octet-stream")

                _transferState.value = TransferState.WaitingForAccept(
                    peerName = peerName,
                    type = "file",
                    name = fileName,
                    size = fileSize,
                    ip = socket.inetAddress.hostAddress ?: ""
                ) { accepted ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (accepted) {
                                writer.write("READY\n".toByteArray())
                                writer.flush()
                                
                                // Dosyayı almaya başla
                                receiveFileFromSocket(socket, fileName, fileSize, mimeType, peerName)
                            } else {
                                writer.write("REJECT\n".toByteArray())
                                writer.flush()
                                _transferState.value = TransferState.Idle
                                closeActiveSocket()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Dosya onay hatası", e)
                            _transferState.value = TransferState.Error("Dosya alımı başarısız: ${e.localizedMessage}")
                            closeActiveSocket()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bağlantı yönetimi hatası", e)
            _transferState.value = TransferState.Error("Bağlantı hatası: ${e.localizedMessage}")
            closeActiveSocket()
        }
    }

    private suspend fun receiveFileFromSocket(
        socket: Socket,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        peerName: String
    ) = withContext(Dispatchers.IO) {
        var outputStream: OutputStream? = null
        var fileUri: Uri? = null
        try {
            val inputStream = socket.getInputStream()
            
            // Modern Scoped Storage entegrasyonu (Download dizinine kaydetme)
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VeloShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            fileUri = resolver.insert(collection, contentValues) ?: throw Exception("Galeri/Download veritabanına kayıt açılamadı")
            outputStream = resolver.openOutputStream(fileUri) ?: throw Exception("Dosya çıkış akışı açılamadı")

            val buffer = ByteArray(65536) // 64KB Chunk
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastUpdate = System.currentTimeMillis()
            var speedBytes = 0L
            var speedMbS = 0.0f

            _transferState.value = TransferState.Progress(
                peerName = peerName,
                isSending = false,
                name = fileName,
                processedBytes = 0L,
                totalBytes = fileSize,
                percentage = 0.0f,
                speedMbS = 0.0f
            )

            while (totalBytesRead < fileSize) {
                // Determine block size remaining
                val remaining = fileSize - totalBytesRead
                val readLimit = if (remaining < buffer.size) remaining.toInt() else buffer.size
                
                bytesRead = inputStream.read(buffer, 0, readLimit)
                if (bytesRead == -1) break
                
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                speedBytes += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 300) {
                    val durationSec = (now - lastUpdate) / 1000.0f
                    speedMbS = (speedBytes / (1024.0f * 1024.0f)) / durationSec
                    speedBytes = 0L
                    lastUpdate = now

                    val progressPercent = (totalBytesRead.toFloat() / fileSize.toFloat()) * 100
                    _transferState.value = TransferState.Progress(
                        peerName = peerName,
                        isSending = false,
                        name = fileName,
                        processedBytes = totalBytesRead,
                        totalBytes = fileSize,
                        percentage = progressPercent,
                        speedMbS = speedMbS
                    )
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)
            }

            _transferState.value = TransferState.Success(
                peerName = peerName,
                isSending = false,
                message = "$fileName başarıyla indirildi!"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dosya indirilirken hata oluştu", e)
            _transferState.value = TransferState.Error("Dosya alım hatası: ${e.localizedMessage}")
            // Temizle
            fileUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (de: Exception) {
                    Log.e(TAG, "Yarım kalan dosya silinemedi", de)
                }
            }
        } finally {
            try { outputStream?.close() } catch (e: Exception) {}
            closeActiveSocket()
        }
    }

    // GÖNDERİCİ TARAF (TCP CLIENT)
    fun sendClipboard(ip: String, port: Int, text: String, senderDeviceName: String) {
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                _transferState.value = TransferState.Progress(
                    peerName = "Bağlanıyor...",
                    isSending = true,
                    name = "Pano Paylaşımı",
                    processedBytes = 0L,
                    totalBytes = 100L,
                    percentage = 10.0f,
                    speedMbS = 0.0f
                )

                socket = Socket(ip, port)
                val writer = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // 1. Handshake Gönder
                val handshake = JSONObject().apply {
                    put("type", "clipboard")
                    put("senderName", senderDeviceName)
                    put("text", text)
                }
                writer.write((handshake.toString() + "\n").toByteArray())
                writer.flush()

                // 2. Cevap Bekle
                val response = reader.readLine()
                if (response == "READY") {
                    _transferState.value = TransferState.Success(
                        peerName = ip,
                        isSending = true,
                        message = "Pano başarıyla gönderildi!",
                        type = "clipboard"
                    )
                } else {
                    _transferState.value = TransferState.Error("Karşı taraf pano transferini reddetti.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pano gönderim hatası", e)
                _transferState.value = TransferState.Error("Pano gönderilemedi: ${e.localizedMessage}")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }
    }

    fun sendFile(
        ip: String,
        port: Int,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        senderDeviceName: String
    ) {
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var inputStream: InputStream? = null
            try {
                _transferState.value = TransferState.Progress(
                    peerName = "Bağlanıyor...",
                    isSending = true,
                    name = fileName,
                    processedBytes = 0L,
                    totalBytes = fileSize,
                    percentage = 5f,
                    speedMbS = 0f
                )

                socket = Socket(ip, port)
                val writer = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // 1. Handshake Gönder
                val handshake = JSONObject().apply {
                    put("type", "file")
                    put("senderName", senderDeviceName)
                    put("fileName", fileName)
                    put("fileSize", fileSize)
                    put("fileType", mimeType)
                }
                writer.write((handshake.toString() + "\n").toByteArray())
                writer.flush()

                // 2. Onay Bekle
                val response = reader.readLine()
                if (response == "READY") {
                    // Start upload stream
                    inputStream = context.contentResolver.openInputStream(uri) 
                        ?: throw Exception("Seçilen dosya okunamadı")

                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var totalBytesSent = 0L
                    var lastUpdate = System.currentTimeMillis()
                    var speedBytes = 0L
                    var speedMbS = 0.0f

                    while (totalBytesSent < fileSize) {
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        
                        writer.write(buffer, 0, bytesRead)
                        totalBytesSent += bytesRead
                        speedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 300) {
                            val durationSec = (now - lastUpdate) / 1000.0f
                            speedMbS = (speedBytes / (1024.0f * 1024.0f)) / durationSec
                            speedBytes = 0L
                            lastUpdate = now

                            val progressPercent = (totalBytesSent.toFloat() / fileSize.toFloat()) * 100
                            _transferState.value = TransferState.Progress(
                                peerName = ip,
                                isSending = true,
                                name = fileName,
                                processedBytes = totalBytesSent,
                                totalBytes = fileSize,
                                percentage = progressPercent,
                                speedMbS = speedMbS
                            )
                        }
                    }
                    writer.flush()
                    _transferState.value = TransferState.Success(
                        peerName = ip,
                        isSending = true,
                        message = "$fileName başarıyla gönderildi!"
                    )
                } else {
                    _transferState.value = TransferState.Error("Karşı taraf transferi reddetti.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dosya gönderme hatası", e)
                _transferState.value = TransferState.Error("Dosya gönderilemedi: ${e.localizedMessage}")
            } finally {
                try { inputStream?.close() } catch (e: Exception) {}
                try { socket?.close() } catch (e: Exception) {}
            }
        }
    }

    private fun closeActiveSocket() {
        try {
            activeSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Soket kapatılamadı", e)
        }
        activeSocket = null
    }

    fun resetState() {
        _transferState.value = TransferState.Idle
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("VeloShare Pano", text)
        clipboard.setPrimaryClip(clip)
    }
}
