package com.overdrive.app.byd;

import android.content.Context;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Direct local vehicle actuation rail.
 *
 * Sourced from BYD's OEM "auto" framework service (device 1034 / 0xAA000004 setBuffer).
 * Bypasses cloud connectivity and DiLink client permission checks entirely,
 * allowing instant local unlock, lock, find-car and hazard flashing even with zero network.
 */
public final class RemoteKeyControl {

    private static final String TAG = "RemoteKeyControl";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final byte[] TEMPLATE = new byte[]{
            (byte) 0xfe, (byte) 0x14, (byte) 0x2e, (byte) 0x82, (byte) 0x5c, (byte) 0xf1,
            (byte) 0x78, (byte) 0x50, (byte) 0x44, (byte) 0xa2, (byte) 0xb9, (byte) 0xca,
            (byte) 0x15, (byte) 0xa3, (byte) 0xee, (byte) 0xbc, (byte) 0xd4, (byte) 0x7b,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00
    };

    public static final int CODE_UNLOCK = 5;
    public static final int CODE_LOCK = 7;
    public static final int CODE_FIND_CAR = 10;
    public static final int CODE_FLASH_LIGHTS = 22;

    private static final Object lock = new Object();
    private static final ThreadLocal<String> lastReason = new ThreadLocal<>();

    private RemoteKeyControl() {}

    public static boolean isEnabled() {
        JSONObject config = UnifiedConfigManager.loadConfig();
        return !config.optBoolean("localRemoteKeyDisabled", false);
    }

    public static boolean sendKey(Context ctx, int code) {
        synchronized (lock) {
            return sendKeyDirect(ctx, code);
        }
    }

    public static boolean sendKeyDirect(Context ctx, int code) {
        lastReason.remove();
        if (ctx == null) {
            setLastReason("code=" + code + " refused: no context");
            return false;
        }
        if (!isEnabled()) {
            setLastReason("code=" + code + " skipped: local rail disabled by config");
            return false;
        }

        // Primary OEM rail: autoService.setBuffer(1034, -1442840572, byte[])
        try {
            Object auto = ctx.getSystemService("auto");
            if (auto != null) {
                Method setBuffer = auto.getClass().getMethod("setBuffer", int.class, int.class, byte[].class);
                byte[] buf = TEMPLATE.clone();
                buf[22] = (byte) code;
                Object res = setBuffer.invoke(auto, 1034, -1442840572, buf);
                boolean ok = (res instanceof Integer) && ((Integer) res) == 0;
                if (ok) {
                    logger.info("Local key command 0x" + Integer.toHexString(code) + " succeeded via auto service");
                    return true;
                }
                setLastReason("code=" + code + " rc=" + res);
            }
        } catch (NoSuchMethodException ignored) {
            setLastReason("code=" + code + " setBuffer absent on auto service");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            setLastReason("code=" + code + " auto service exception: " + cause.getMessage());
        }

        // Secondary fallback: HazardLightProbe PermissiveContext
        try {
            if (code == CODE_LOCK || code == CODE_UNLOCK) {
                int targetVal = (code == CODE_UNLOCK) ? 1 : 0;
                JSONObject probeRes = HazardLightProbe.runCandidate(ctx,
                        HazardLightProbe.candidateById("L"), 100);
                if (probeRes != null && probeRes.optBoolean("acceptedByHal", false)) {
                    logger.info("Local lock/unlock command (" + targetVal + ") succeeded via HazardLightProbe fallback");
                    return true;
                }
            } else if (code == CODE_FLASH_LIGHTS || code == CODE_FIND_CAR) {
                JSONObject probeRes = HazardLightProbe.runCandidate(ctx,
                        HazardLightProbe.candidateById("A"), 800);
                if (probeRes != null && probeRes.optBoolean("acceptedByHal", false)) {
                    logger.info("Local flash/hazard command succeeded via HazardLightProbe fallback");
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.warn("HazardLightProbe fallback exception: " + t.getMessage());
        }

        return false;
    }

    private static void setLastReason(String reason) {
        logger.debug(reason);
        lastReason.set(reason);
    }

    public static String getLastReason() {
        return lastReason.get();
    }
}
