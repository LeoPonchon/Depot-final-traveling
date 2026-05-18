package com.shimtraveling.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TravelingFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (user.isAnonymous) return
        val uid = user.uid
        CoroutineScope(Dispatchers.IO).launch {
            val repo = (application as? TravelingApp)?.firestoreRepository ?: return@launch
            repo.updateFcmToken(uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "traveling_fcm"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((message.messageId ?: message.data["id"] ?: "fcm").hashCode(), notif)
    }
}
