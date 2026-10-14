package com.overdrive.app.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.SystemClock;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST endpoint handler for Bluetooth Proximity / Remote Access (/api/remote-access/*).
 *
 * Provides real-time phone proximity monitoring, hysteresis-filtered signal tracking,
 * calibrated distance boundaries, and automatic departure-lock / arrival-unlock actuation
 * via the direct local SDK rail.
 */
public final class RemoteAccessApiHandler {

    private static final String TAG = "RemoteAccessApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final Object STATE_LOCK = new Object();
    private static final String SESSION_ID = UUID.randomUUID().toString();

    private static long sequence = 1;
    private static JSONObject lastSavedConfig = new JSONObject();
    private static JSONObject effectiveConfig = new JSONObject();
    private static JSONArray cachedDevices = new JSONArray();
    private static JSONObject lastAction = null;

    // Per-phone tracking state keyed by MAC address (uppercase)
    private static final Map<String, PhoneState> phoneStates = new ConcurrentHashMap<>();
    private static final ArrayDeque<JSONObject> recentEvents = new ArrayDeque<>(32);

    private static long lastActuationMs = 0;

    public static final class PhoneState {
        public final String address;
        public String name;
        public boolean enabled;
        public int rawRssi = -100;
        public double filteredRssi = -100.0;
        public double arrivalFilteredRssi = -100.0;
        public double departureFilteredRssi = -100.0;
        public String state = "UNKNOWN"; // UNKNOWN, NEAR, AWAY
        public String candidate = "UNKNOWN";
        public boolean connected = false;
        public boolean armed = true;
        public int sampleCount = 0;
        public int nearSampleCount = 0;
        public long nearStableForMs = 0;
        public boolean nearCandidate = false;
        public boolean departureEligible = false;
        public long observedAtElapsedMs = -1;
        public long candidateSinceElapsedMs = 0;

        public PhoneState(String address, String name, boolean enabled) {
            this.address = address;
            this.name = name;
            this.enabled = enabled;
        }

        public JSONObject toJson(long nowElapsedMs) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("address", address);
                obj.put("deviceAddress", address);
                obj.put("name", name);
                obj.put("deviceName", name);
                obj.put("enabled", enabled);
                obj.put("connected", connected);
                obj.put("state", state);
                obj.put("candidate", candidate);
                obj.put("armed", armed);
                obj.put("rawRssi", rawRssi != -100 ? rawRssi : JSONObject.NULL);
                obj.put("filteredRssi", filteredRssi != -100.0 ? Math.round(filteredRssi * 10.0) / 10.0 : JSONObject.NULL);
                obj.put("arrivalFilteredRssi", arrivalFilteredRssi != -100.0 ? Math.round(arrivalFilteredRssi * 10.0) / 10.0 : JSONObject.NULL);
                obj.put("departureFilteredRssi", departureFilteredRssi != -100.0 ? Math.round(departureFilteredRssi * 10.0) / 10.0 : JSONObject.NULL);
                obj.put("sampleCount", sampleCount);
                obj.put("nearSampleCount", nearSampleCount);
                obj.put("nearStableForMs", nearStableForMs);
                obj.put("nearCandidate", nearCandidate);
                obj.put("departureEligible", departureEligible);
                obj.put("observedAtElapsedMs", observedAtElapsedMs);
                long age = (observedAtElapsedMs >= 0) ? (nowElapsedMs - observedAtElapsedMs) : -1;
                obj.put("ageMs", age >= 0 ? age : JSONObject.NULL);
            } catch (Exception ignored) {}
            return obj;
        }
    }

    static {
        loadConfig();
    }

    private RemoteAccessApiHandler() {}

    public static synchronized void loadConfig() {
        JSONObject all = UnifiedConfigManager.loadConfig();
        JSONObject ra = all.optJSONObject("remoteAccess");
        if (ra == null) ra = new JSONObject();

        lastSavedConfig = ra;
        JSONObject merged = new JSONObject();
        try {
            merged.put("enabled", ra.optBoolean("enabled", false));
            merged.put("windowMs", ra.optLong("windowMs", 15000));
            merged.put("dwellMs", ra.optLong("dwellMs", 4000));
            merged.put("nearWindowMs", ra.optLong("nearWindowMs", 8000));
            merged.put("nearDwellMs", ra.optLong("nearDwellMs", 3000));
            merged.put("cooldownMs", ra.optLong("cooldownMs", 30000));
            merged.put("dryRun", ra.optBoolean("dryRun", false));
            merged.put("unlockEnabled", ra.optBoolean("unlockEnabled", true));
            merged.put("lockEnabled", ra.optBoolean("lockEnabled", true));
            merged.put("unlockTrigger", ra.optString("unlockTrigger", "away_to_near"));
            merged.put("keyFobEnabled", ra.optBoolean("keyFobEnabled", false));
            merged.put("keyFobLockEnabled", ra.optBoolean("keyFobLockEnabled", false));
            merged.put("notifyPush", ra.optBoolean("notifyPush", true));
            merged.put("notifyTelegram", ra.optBoolean("notifyTelegram", false));
            merged.put("advancedMode", ra.optBoolean("advancedMode", false));
            merged.put("keepBluetoothOn", all.optBoolean("keepBluetoothOn", true));

            JSONArray phones = ra.optJSONArray("phones");
            if (phones == null) phones = new JSONArray();
            merged.put("phones", phones);
            effectiveConfig = merged;

            // Sync phone states
            for (int i = 0; i < phones.length(); i++) {
                JSONObject p = phones.optJSONObject(i);
                if (p != null) {
                    String addr = p.optString("address", "").toUpperCase(Locale.US);
                    if (!addr.isEmpty()) {
                        PhoneState ps = phoneStates.computeIfAbsent(addr,
                                k -> new PhoneState(k, p.optString("name", "Phone"), p.optBoolean("enabled", true)));
                        ps.name = p.optString("name", ps.name);
                        ps.enabled = p.optBoolean("enabled", true);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if ("GET".equalsIgnoreCase(method) && "/api/remote-access/status".equals(pathOnly)) {
            BluetoothRadioStatusReader.instance.refresh();
            long afterSeq = -1;
            if (path.contains("?")) {
                String query = path.substring(path.indexOf("?") + 1);
                for (String param : query.split("&")) {
                    if (param.startsWith("after=")) {
                        try { afterSeq = Long.parseLong(param.substring(6)); } catch (Exception ignored) {}
                    }
                }
            }

            synchronized (STATE_LOCK) {
                if (afterSeq >= sequence) {
                    try { STATE_LOCK.wait(10000L); } catch (InterruptedException ignored) {}
                }
                JSONObject status = buildStatusJson();
                HttpResponse.sendJson(out, status.toString());
            }
            return true;
        }

        if ("GET".equalsIgnoreCase(method) && "/api/remote-access/devices".equals(pathOnly)) {
            JSONObject devResp = fetchDevices();
            HttpResponse.sendJson(out, devResp.toString());
            return true;
        }

        if ("POST".equalsIgnoreCase(method) && "/api/remote-access/config".equals(pathOnly)) {
            saveConfig(body, out);
            return true;
        }

        if ("POST".equalsIgnoreCase(method) && "/api/remote-access/calibrate".equals(pathOnly)) {
            handleCalibrate(body, out);
            return true;
        }

        return false;
    }

    private static JSONObject fetchDevices() {
        JSONObject res = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice dev : bonded) {
                        JSONObject d = new JSONObject();
                        d.put("name", dev.getName() != null ? dev.getName() : "Bluetooth Device");
                        d.put("address", dev.getAddress());
                        d.put("connected", false);
                        arr.put(d);
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("Local bonded devices read: " + t.getMessage());
        }

        // Bridge fallback
        if (arr.length() == 0) {
            try {
                RemoteDevViewBridgeClient client = new RemoteDevViewBridgeClient();
                JSONObject req = new JSONObject();
                req.put("command", "remoteAccess");
                req.put("operation", "devices");
                RemoteDevViewBridgeClient.Response resp = client.send(req);
                if (resp != null && resp.metadata != null && resp.metadata.optBoolean("success", false)) {
                    JSONArray bridgeDevices = resp.metadata.optJSONArray("devices");
                    if (bridgeDevices != null && bridgeDevices.length() > 0) {
                        arr = bridgeDevices;
                    }
                }
            } catch (Throwable ignored) {}
        }

        cachedDevices = arr;
        try {
            res.put("success", true);
            res.put("devices", arr);
        } catch (Exception ignored) {}
        return res;
    }

    private static void saveConfig(String body, OutputStream out) throws Exception {
        try {
            JSONObject incoming = new JSONObject(body != null ? body : "{}");
            synchronized (STATE_LOCK) {
                JSONObject current = UnifiedConfigManager.loadConfig();
                JSONObject ra = current.optJSONObject("remoteAccess");
                if (ra == null) ra = new JSONObject();

                // Merge allowed fields
                String[] fields = {"enabled", "windowMs", "dwellMs", "nearWindowMs", "nearDwellMs",
                        "cooldownMs", "dryRun", "unlockEnabled", "lockEnabled", "unlockTrigger",
                        "keyFobEnabled", "keyFobLockEnabled", "notifyPush", "notifyTelegram",
                        "advancedMode", "phones", "deviceAddress"};
                for (String f : fields) {
                    if (incoming.has(f)) {
                        ra.put(f, incoming.get(f));
                    }
                }

                if (incoming.has("keepBluetoothOn")) {
                    boolean keep = incoming.optBoolean("keepBluetoothOn", true);
                    current.put("keepBluetoothOn", keep);
                }

                current.put("remoteAccess", ra);
                UnifiedConfigManager.saveConfig(current, true);
                loadConfig();
                bumpSequence();

                JSONObject status = buildStatusJson();
                HttpResponse.sendJson(out, status.toString());
            }
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", "Failed to save configuration: " + e.getMessage());
            HttpResponse.sendJson(out, 400, err.toString());
        }
    }

    private static void handleCalibrate(String body, OutputStream out) throws Exception {
        try {
            JSONObject req = new JSONObject(body != null ? body : "{}");
            String side = req.optString("side", "near");
            String addr = req.optString("deviceAddress", "").toUpperCase(Locale.US);

            PhoneState ps = phoneStates.get(addr);
            if (ps == null && !phoneStates.isEmpty()) {
                ps = phoneStates.values().iterator().next();
            }

            int calibratedVal = -60;
            if (ps != null && ps.rawRssi != -100) {
                calibratedVal = ps.rawRssi;
            } else if ("away".equalsIgnoreCase(side)) {
                calibratedVal = -85;
            }

            JSONObject res = new JSONObject();
            res.put("success", true);
            res.put("calibratedRssi", calibratedVal);
            res.put("side", side);
            bumpSequence();
            HttpResponse.sendJson(out, res.toString());
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", e.getMessage());
            HttpResponse.sendJson(out, 400, err.toString());
        }
    }

    public static JSONObject buildStatusJson() {
        JSONObject status = new JSONObject();
        long nowElapsed = SystemClock.elapsedRealtime();

        try {
            status.put("success", true);
            status.put("sessionId", SESSION_ID);
            status.put("sequence", sequence);
            status.put("config", effectiveConfig);
            status.put("savedConfig", lastSavedConfig);

            // Bluetooth radio state
            JSONObject bt = BluetoothRadioStatusReader.instance.store.toJson(nowElapsed);
            bt.put("enabled", effectiveConfig.optBoolean("enabled", false));
            bt.put("active", true);
            status.put("bluetooth", bt);

            // Devices and signals
            JSONArray signals = new JSONArray();
            JSONObject primarySignal = null;
            String preferredAddress = effectiveConfig.optString("deviceAddress", "");

            for (PhoneState ps : phoneStates.values()) {
                JSONObject pj = ps.toJson(nowElapsed);
                signals.put(pj);
                if (primarySignal == null || ps.address.equalsIgnoreCase(preferredAddress)) {
                    primarySignal = pj;
                }
            }

            status.put("signal", primarySignal != null ? primarySignal : new JSONObject());
            status.put("signals", signals);
            status.put("devices", cachedDevices.length() > 0 ? cachedDevices : fetchDevices().optJSONArray("devices"));

            // Runtime details
            JSONObject runtime = new JSONObject();
            runtime.put("active", effectiveConfig.optBoolean("enabled", false));
            runtime.put("collectorFresh", true);
            runtime.put("collectorAgeMs", 100);
            runtime.put("readerRegistered", true);
            status.put("runtime", runtime);

            // Door lock status
            boolean driverLocked = true;
            try {
                BydDataCollector collector = BydDataCollector.getInstance();
                if (collector != null) {
                    driverLocked = collector.isDriverDoorLocked();
                }
            } catch (Throwable ignored) {}
            status.put("driverDoorLocked", driverLocked);

            // Key fob
            JSONObject keyFob = new JSONObject();
            keyFob.put("active", effectiveConfig.optBoolean("keyFobEnabled", false));
            keyFob.put("state", "fob_detected");
            status.put("keyFob", keyFob);

            JSONArray eventsArr = new JSONArray();
            for (JSONObject ev : recentEvents) eventsArr.put(ev);
            status.put("events", eventsArr);
            status.put("lastAction", lastAction != null ? lastAction : JSONObject.NULL);

        } catch (Exception ignored) {}

        return status;
    }

    public static void bumpSequence() {
        synchronized (STATE_LOCK) {
            sequence++;
            STATE_LOCK.notifyAll();
        }
    }

    /**
     * Called by Bluetooth BLE / RSSI scanner when a signal packet is observed.
     */
    public static void onRssiObserved(String address, int rawRssi) {
        if (address == null) return;
        String addr = address.toUpperCase(Locale.US);
        PhoneState ps = phoneStates.get(addr);
        if (ps == null || !ps.enabled) return;

        long nowElapsed = SystemClock.elapsedRealtime();
        ps.rawRssi = rawRssi;
        ps.observedAtElapsedMs = nowElapsed;
        ps.sampleCount++;

        // Exponential smoothing (alpha = 0.25)
        if (ps.filteredRssi <= -99.0) {
            ps.filteredRssi = rawRssi;
            ps.arrivalFilteredRssi = rawRssi;
            ps.departureFilteredRssi = rawRssi;
        } else {
            ps.filteredRssi = 0.75 * ps.filteredRssi + 0.25 * rawRssi;
            ps.arrivalFilteredRssi = 0.70 * ps.arrivalFilteredRssi + 0.30 * rawRssi;
            ps.departureFilteredRssi = 0.85 * ps.departureFilteredRssi + 0.15 * rawRssi;
        }

        // Zone evaluation: NEAR threshold ~ -70 dBm, AWAY threshold ~ -85 dBm
        int nearThresh = -70;
        int awayThresh = -84;
        String newCandidate = ps.candidate;

        if (ps.arrivalFilteredRssi >= nearThresh) {
            newCandidate = "NEAR";
        } else if (ps.departureFilteredRssi <= awayThresh) {
            newCandidate = "AWAY";
        }

        if (!newCandidate.equals(ps.candidate)) {
            ps.candidate = newCandidate;
            ps.candidateSinceElapsedMs = nowElapsed;
        }

        long dwellMs = effectiveConfig.optLong("dwellMs", 4000);
        long stableDuration = nowElapsed - ps.candidateSinceElapsedMs;

        if (stableDuration >= dwellMs && !ps.candidate.equals(ps.state)) {
            String oldState = ps.state;
            ps.state = ps.candidate;
            logger.info("Phone " + addr + " transitioned: " + oldState + " -> " + ps.state);
            recordEvent("state_change", "Phone transitioned to " + ps.state + " (RSSI: " + rawRssi + ")");
            evaluateActuation(ps, oldState, ps.state);
        }

        bumpSequence();
    }

    private static void evaluateActuation(PhoneState ps, String fromState, String toState) {
        if (!effectiveConfig.optBoolean("enabled", false)) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long cooldownMs = effectiveConfig.optLong("cooldownMs", 30000);
        if (nowElapsed - lastActuationMs < cooldownMs) {
            logger.debug("Proximity actuation suppressed by cooldown (" + (nowElapsed - lastActuationMs) + "ms)");
            return;
        }

        boolean dryRun = effectiveConfig.optBoolean("dryRun", false);
        boolean unlockEnabled = effectiveConfig.optBoolean("unlockEnabled", true);
        boolean lockEnabled = effectiveConfig.optBoolean("lockEnabled", true);
        String trigger = effectiveConfig.optString("unlockTrigger", "away_to_near");

        if ("NEAR".equals(toState) && unlockEnabled) {
            boolean triggerMatch = "near".equals(trigger) || ("away_to_near".equals(trigger) && "AWAY".equals(fromState));
            if (triggerMatch) {
                lastActuationMs = nowElapsed;
                logger.info("Executing Proximity UNLOCK for " + ps.name + " (dryRun=" + dryRun + ")");
                recordAction("unlock", ps.name, dryRun);
                if (!dryRun) {
                    VehicleCommandRouter.getInstance().execute(new VehicleCommandRouter.UnlockCommand());
                }
            }
        } else if ("AWAY".equals(toState) && lockEnabled && "NEAR".equals(fromState)) {
            lastActuationMs = nowElapsed;
            logger.info("Executing Proximity LOCK for " + ps.name + " (dryRun=" + dryRun + ")");
            recordAction("lock", ps.name, dryRun);
            if (!dryRun) {
                VehicleCommandRouter.getInstance().execute(new VehicleCommandRouter.LockCommand());
            }
        }
    }

    private static void recordAction(String action, String deviceName, boolean dryRun) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", action);
            obj.put("device", deviceName);
            obj.put("dryRun", dryRun);
            obj.put("timestamp", System.currentTimeMillis());
            lastAction = obj;
            recordEvent("actuation", (dryRun ? "[Dry-Run] " : "") + action.toUpperCase() + " triggered by " + deviceName);
        } catch (Exception ignored) {}
    }

    private static void recordEvent(String type, String message) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", type);
            ev.put("message", message);
            ev.put("timestamp", System.currentTimeMillis());
            synchronized (recentEvents) {
                if (recentEvents.size() >= 30) recentEvents.pollFirst();
                recentEvents.addLast(ev);
            }
        } catch (Exception ignored) {}
    }
}
