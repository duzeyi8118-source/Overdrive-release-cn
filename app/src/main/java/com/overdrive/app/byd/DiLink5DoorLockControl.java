package com.overdrive.app.byd;

import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/**
 * Direct typed door-lock actuation rail for DiLink 5 IVI platforms.
 *
 * Interacts with {@code android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice}
 * using reflective invocation of {@code setAllDoorsLock} and confirmation via
 * {@code getDoorLockStatus}.
 */
public final class DiLink5DoorLockControl {

    private static final String TAG = "DiLink5DoorLockControl";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final class Result {
        public final boolean success;
        public final String reason;

        public Result(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }

    public static final class ReflectivePort {
        public final Object doorLockDevice;
        public final Object doorlockManagerImpl;
        public final Object tsManagerImpl;
        public final Method setAllDoorsLockMethod;
        public final Method getDoorLockStatusMethod;
        public final Method isConnectedMethod;
        public final Method isCarServiceConnectMethod;
        public final Method getCarCabinAdapterManagerMethod;
        public final Field forTestField;

        public ReflectivePort(Object device) {
            if (device == null || !"android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice".equals(device.getClass().getName())) {
                throw new IllegalArgumentException("unexpected door-lock device");
            }
            this.doorLockDevice = device;
            Class<?> cls = device.getClass();
            try {
                int lockVal = cls.getField("DOOR_LOCK_STATE_LOCK").getInt(null);
                int unlockVal = cls.getField("DOOR_LOCK_STATE_UNLOCK").getInt(null);
                int lfArea = cls.getField("DOOR_LOCK_AREA_LEFT_FRONT").getInt(null);
                if (lockVal != 1 || unlockVal != 2 || lfArea != 1) {
                    throw new IllegalArgumentException("unverified door-lock state domain");
                }
                this.setAllDoorsLockMethod = cls.getMethod("setAllDoorsLock", int.class);
                this.getDoorLockStatusMethod = cls.getMethod("getDoorLockStatus", int.class);

                Field dlmField = cls.getDeclaredField("mDoorlockManagerImpl");
                dlmField.setAccessible(true);
                this.doorlockManagerImpl = dlmField.get(device);
                if (this.doorlockManagerImpl == null) {
                    throw new IllegalArgumentException("doorlock manager null");
                }

                Field testF = this.doorlockManagerImpl.getClass().getDeclaredField("mForTest");
                testF.setAccessible(true);
                this.forTestField = testF;

                Field tsmField = this.doorlockManagerImpl.getClass().getDeclaredField("mTsManagerImpl");
                tsmField.setAccessible(true);
                this.tsManagerImpl = tsmField.get(this.doorlockManagerImpl);
                if (this.tsManagerImpl == null) {
                    throw new IllegalArgumentException("ts manager null");
                }

                Class<?> tsmCls = this.tsManagerImpl.getClass();
                this.isConnectedMethod = tsmCls.getMethod("isConnected");
                this.isCarServiceConnectMethod = tsmCls.getMethod("isCarServiceConnect");
                this.getCarCabinAdapterManagerMethod = tsmCls.getMethod("getCarCabinAdapterManager");
            } catch (Exception e) {
                throw new IllegalArgumentException("failed resolving DiLink5 doorlock port: " + e.getMessage(), e);
            }
        }

        public boolean isAlive() {
            try {
                if (forTestField != null && forTestField.getInt(doorlockManagerImpl) != 0) {
                    return true;
                }
                Boolean conn = (Boolean) isConnectedMethod.invoke(tsManagerImpl);
                Boolean carConn = (Boolean) isCarServiceConnectMethod.invoke(tsManagerImpl);
                if (!Boolean.TRUE.equals(conn) || !Boolean.TRUE.equals(carConn)) {
                    return false;
                }
                Object cabin = getCarCabinAdapterManagerMethod.invoke(tsManagerImpl);
                if (cabin == null) return false;
                Field srvField = cabin.getClass().getDeclaredField("mService");
                srvField.setAccessible(true);
                Object srv = srvField.get(cabin);
                if (srv instanceof IInterface) {
                    IBinder b = ((IInterface) srv).asBinder();
                    return b != null && b.isBinderAlive();
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private DiLink5DoorLockControl() {}

    public static Result execute(ReflectivePort port, boolean lock,
                                 BooleanSupplier activeCheck, BooleanSupplier proximityCheck) {
        if (port == null) return fail("null reflective port");
        int targetState = lock ? 1 : 2; // 1 = LOCK, 2 = UNLOCK

        try {
            if (!activeCheck.getAsBoolean()) return fail("request expired before start");
            if (!port.isAlive()) return fail("cabin adapter unavailable");

            int current = ((Integer) port.getDoorLockStatusMethod.invoke(port.doorLockDevice, 1)).intValue();
            if (current == targetState) {
                return new Result(true, "driver door already in target state");
            }

            if (!proximityCheck.getAsBoolean()) return fail("proximity command revoked before write");

            int rc = ((Integer) port.setAllDoorsLockMethod.invoke(port.doorLockDevice, targetState)).intValue();
            if (rc != 0) {
                return fail("setAllDoorsLock refused rc=" + rc);
            }

            // Confirm state change
            long start = SystemClock.elapsedRealtime();
            int consecutiveMatches = 0;
            while (SystemClock.elapsedRealtime() - start < 2000L) {
                if (!activeCheck.getAsBoolean()) return fail("request expired during confirmation");
                int read = ((Integer) port.getDoorLockStatusMethod.invoke(port.doorLockDevice, 1)).intValue();
                if (read == targetState) {
                    consecutiveMatches++;
                    if (consecutiveMatches >= 2) {
                        return new Result(true, "driver-door readback confirmed");
                    }
                } else {
                    consecutiveMatches = 0;
                }
                Thread.sleep(100L);
            }
            return fail("driver-door confirmation timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("door-lock request interrupted");
        } catch (Throwable t) {
            return fail("typed door-lock request failed (" + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
    }

    private static Result fail(String reason) {
        logger.debug(reason);
        return new Result(false, reason);
    }
}
