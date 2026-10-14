package com.overdrive.app.surveillance.impact;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import com.overdrive.app.R;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.DaemonHttpClient;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public final class ParkedImpactSensorService extends Service implements SensorEventListener {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ParkedImpactSensor");
    private static final String CHANNEL_ID = "parked_impact";
    private static final int NOTIFICATION_ID = 9988;

    private HandlerThread handlerThread;
    private Handler handler;
    private SensorManager sensorManager;
    private boolean isRegistered;
    private ImpactDetector detector;
    private String currentSensitivity = "medium";
    private long lastHeartbeatTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Parked impact watch",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("Sentry")
                .setContentText("Watching for impacts while parked")
                .setSmallIcon(R.drawable.ic_sentry)
                .setOngoing(true)
                .setGroup("com.overdrive.app.STATUS");
        Notification notification = builder.build();

        startForeground(NOTIFICATION_ID, notification);

        handlerThread = new HandlerThread("parked-impact", Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String sensitivity = "medium";
        if (intent != null && intent.hasExtra("sensitivity")) {
            String s = intent.getStringExtra("sensitivity");
            if (s != null && !s.isEmpty()) {
                sensitivity = s;
            }
        }
        this.currentSensitivity = sensitivity;
        this.detector = ImpactDetector.forSensitivity(sensitivity);

        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        }

        if (sensorManager != null && !isRegistered) {
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                isRegistered = sensorManager.registerListener(
                        this,
                        accel,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        handler
                );
                logger.info("Accelerometer registered for parked impact detection (sensitivity=" + sensitivity + ")");
            } else {
                logger.warn("No accelerometer found on this vehicle hardware");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null && isRegistered) {
            sensorManager.unregisterListener(this);
            isRegistered = false;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
            handler = null;
        }
        logger.info("ParkedImpactSensorService stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        if (event.values == null || event.values.length < 3) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastHeartbeatTime >= 300000L) { // Log liveness every 5 mins
            lastHeartbeatTime = now;
            logger.info("Parked impact sensor active (accel OK)");
        }

        if (detector == null) return;
        float peak = detector.processSample(event.values[0], event.values[1], event.values[2], now);
        if (!Float.isNaN(peak)) {
            logger.warn(String.format("Impact detected! Peak magnitude: %.2f m/s^2", peak));
            reportImpactToDaemon(peak);
        }
    }

    private void reportImpactToDaemon(float peak) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = DaemonHttpClient.open(
                        "/api/surveillance/impact",
                        "POST",
                        1500,
                        2000
                );
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                JSONObject json = new JSONObject();
                json.put("peak", (double) peak);
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                logger.warn("Failed to report impact event to daemon: " + e.getMessage());
            }
        }, "impact-reporter").start();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static final Companion i = Companion.INSTANCE;

    public static class Companion {
        public static final Companion INSTANCE = new Companion();

        public static void runShell(String cmd) {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            } catch (Exception e) {
                logger.warn("runShell failed: " + e.getMessage());
            }
        }

        public static void a(String cmd) {
            runShell(cmd);
        }

        public static void start(String sensitivity) {
            runShell("am start-foreground-service -n com.overdrive.app/com.overdrive.app.surveillance.impact.ParkedImpactSensorService --es sensitivity " + sensitivity);
        }

        public static void stop() {
            runShell("am stopservice -n com.overdrive.app/com.overdrive.app.surveillance.impact.ParkedImpactSensorService");
        }
    }
}
