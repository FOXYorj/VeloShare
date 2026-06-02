package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    companion object {
        const val CHANNEL_ID = "veloshare_transfer_channel"
        const val CHANNEL_NAME = "Aktarım Durumu"
        const val NOTIFICATION_ID = 2468
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VeloShare dosya gönderme ve alma bildirimleri"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgressNotification(fileName: String, isSending: Boolean, progress: Int, speed: Float) {
        val title = if (isSending) "Dosya Gönderiliyor" else "Dosya Alınıyor"
        val speedStr = String.format("%.1f MB/s", speed)
        val progressText = "$progress% • $speedStr"
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$fileName ($progressText)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            // Permission catch
        }
    }

    fun showSuccessNotification(fileName: String, isSending: Boolean) {
        val title = if (isSending) "Gönderim Tamamlandı" else "Alım Tamamlandı"
        val text = if (isSending) "$fileName başarıyla gönderildi." else "$fileName başarıyla indirildi."
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
        } catch (e: Exception) {
            // Permission catch
        }
    }

    fun showErrorNotification(message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Aktarım Başarısız")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_ID + 2, builder.build())
        } catch (e: Exception) {
            // Permission catch
        }
    }
}
