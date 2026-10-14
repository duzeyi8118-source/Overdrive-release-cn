package com.overdrive.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.overdrive.app.R;
import com.overdrive.app.logging.DaemonLogger;

public class GlassWidgetsOverlayService extends Service {
    private static final String TAG = "GlassWidgetsOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "glass_widgets";
    private static final int NOTIFICATION_ID = 8877;

    private WindowManager windowManager;
    private View overlayRootView;
    private boolean isOverlayAttached = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArranging = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification("Glass widgets active");
        startForeground(NOTIFICATION_ID, notification);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        logger.info("GlassWidgetsOverlayService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("arrange".equalsIgnoreCase(action)) {
                isArranging = true;
                logger.info("Arrange mode triggered");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlayView();
        logger.info("GlassWidgetsOverlayService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void removeOverlayView() {
        if (isOverlayAttached && overlayRootView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRootView);
            } catch (Exception ignored) {}
            isOverlayAttached = false;
            overlayRootView = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Glass Widgets",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("OverDrive Glass")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(true);
        return builder.build();
    }
}
