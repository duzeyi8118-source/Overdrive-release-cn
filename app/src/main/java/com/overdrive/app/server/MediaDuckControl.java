package com.overdrive.app.server;

import android.os.IBinder;
import android.os.SystemClock;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.DaemonStorage;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class MediaDuckControl {
    private static final DaemonLogger logger = DaemonLogger.getInstance("MediaDuck");
    private static final String STATE_FILE = DaemonStorage.rebase("/data/local/tmp/overdrive_media_duck.state");
    private static final Object LOCK = new Object();
    private static boolean initialized = false;
    private static Object audioService = null;
    private static Method setTrackVolumeDuckMethod = null;
    private static Method getCurrentAudioFocusStreamMethod = null;
    private static boolean supported = false;
    private static boolean isDucked = false;
    private static long leaseExpiresAt = 0;
    private static ScheduledExecutorService executor = null;
    private static ScheduledFuture<?> releaseFuture = null;

    /**
     * Initializes reflective handles into Android's IAudioService.
     */
    public static void init() {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getMethod("getService", String.class);
                IBinder binder = (IBinder) getService.invoke(null, "audio");
                Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                audioService = asInterface.invoke(null, binder);

                Class<?> iAudioClass = Class.forName("android.media.IAudioService");
                setTrackVolumeDuckMethod = iAudioClass.getMethod("setTrackVolumeDuck", boolean.class, String.class);
                getCurrentAudioFocusStreamMethod = iAudioClass.getMethod("getCurrentAudioFocusStream", String.class);

                // Self-healing: if an unreleased duck state file remained after daemon crash/restart, restore audio
                File state = new File(STATE_FILE);
                if (state.exists()) {
                    if (isStreamDucked()) {
                        invokeDuck(false);
                    }
                    state.delete();
                }
                supported = (setTrackVolumeDuckMethod != null);
            } catch (Throwable t) {
                logger.warn("MediaDuckControl init failed: " + t.getMessage());
                setTrackVolumeDuckMethod = null;
                getCurrentAudioFocusStreamMethod = null;
                supported = false;
            }
        }
    }

    public static boolean isStreamDucked() {
        if (getCurrentAudioFocusStreamMethod == null || audioService == null) return false;
        try {
            Object res = getCurrentAudioFocusStreamMethod.invoke(audioService, "com.android.systemui");
            if (res instanceof Integer) {
                return ((Integer) res) == 16;
            }
        } catch (Throwable t) {
            logger.warn("isStreamDucked failed: " + t.getMessage());
        }
        return false;
    }

    private static boolean invokeDuck(boolean duck) {
        if (setTrackVolumeDuckMethod == null || audioService == null) return false;
        try {
            setTrackVolumeDuckMethod.invoke(audioService, duck, "com.byd.vrassistant.xf");
            return true;
        } catch (Throwable t) {
            logger.warn("invokeDuck failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Smoothly ducks background media for the duration of leaseMs.
     */
    public static void duck(long leaseMs) {
        synchronized (LOCK) {
            init();
            if (!supported) return;
            if (!invokeDuck(true)) return;
            isDucked = true;
            try {
                File f = new File(STATE_FILE);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                logger.warn("Failed to write media duck state: " + t.getMessage());
            }

            leaseExpiresAt = SystemClock.elapsedRealtime() + leaseMs;
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "media-duck");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (releaseFuture != null) {
                releaseFuture.cancel(false);
            }
            releaseFuture = executor.schedule(() -> {
                synchronized (LOCK) {
                    if (isDucked && SystemClock.elapsedRealtime() >= leaseExpiresAt) {
                        release();
                    }
                }
            }, leaseMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Immediately restores background media volume.
     */
    public static void release() {
        synchronized (LOCK) {
            if (releaseFuture != null) {
                releaseFuture.cancel(false);
                releaseFuture = null;
            }
            if (!isDucked) return;
            isDucked = false;
            invokeDuck(false);
            File f = new File(STATE_FILE);
            if (f.exists()) {
                f.delete();
            }
        }
    }

    /**
     * Dispatches REST API requests for media ducking.
     */
    public static JSONObject handleCommand(JSONObject req) {
        String action = req.optString("action", "");
        synchronized (LOCK) {
            init();
            if ("duck".equals(action)) {
                long lease = req.optLong("leaseMs", 15000);
                lease = Math.max(2000, Math.min(60000, lease));
                duck(lease);
                return statusJson("duck");
            } else if ("release".equals(action)) {
                release();
                return statusJson("release");
            } else if ("state".equals(action)) {
                return statusJson("state");
            } else {
                JSONObject r = new JSONObject();
                try {
                    r.put("success", false);
                    r.put("target", "media_duck");
                    r.put("error", "action must be state, duck or release");
                } catch (Exception ignored) {}
                return r;
            }
        }
    }

    public static JSONObject statusJson(String action) {
        JSONObject r = new JSONObject();
        try {
            r.put("success", true);
            r.put("target", "media_duck");
            r.put("action", action);
            r.put("supported", supported);
            r.put("ducked", isDucked);
        } catch (Exception ignored) {}
        return r;
    }

    // Upstream compat aliases
    public static void a(long leaseMs) { duck(leaseMs); }
    public static boolean b() { return isStreamDucked(); }
    public static JSONObject c(JSONObject req) { return handleCommand(req); }
    public static boolean d(boolean duck) { return invokeDuck(duck); }
    public static void e() { release(); }
    public static JSONObject f(String action) { return statusJson(action); }
    public static void g() { init(); }
}
