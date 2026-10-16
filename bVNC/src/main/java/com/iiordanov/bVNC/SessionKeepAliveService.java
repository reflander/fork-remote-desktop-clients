package com.iiordanov.bVNC;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.undatech.remoteClientUi.R;

/**
 * FanRDP: keeps the app process in the foreground while a remote session is active, so the
 * OS does not suspend or kill it when the app is sent to the background or the screen turns off.
 * The RDP connection itself lives in RemoteCanvasActivity; this service only keeps the process
 * alive (and shows a persistent "session active" notification).
 *
 * It is started only when the "Keep Background Connection" setting is enabled.
 */
public class SessionKeepAliveService extends Service {

    private static final String CHANNEL_ID = "fanrdp_session";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The notification is required for a foreground service. It is the only thing this
        // service does: hold the process in the foreground state.
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.keep_alive_notification_title))
                .setContentText(getString(R.string.keep_alive_notification_text))
                .setSmallIcon(R.drawable.icon_ardp)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
