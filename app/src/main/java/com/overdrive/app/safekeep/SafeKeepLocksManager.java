package com.overdrive.app.safekeep;

import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SafeKeepLocksManager {
    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepLocks");
    private static final SafeKeepLocksManager INSTANCE = new SafeKeepLocksManager();

    private final Object fileLock = new Object();
    private final ConcurrentHashMap<String, JSONObject> lockedFiles = new ConcurrentHashMap<>();
    private final File storageFile;
    private volatile boolean enabled = false;

    public static SafeKeepLocksManager getInstance() {
        return INSTANCE;
    }

    private SafeKeepLocksManager() {
        File baseDir = new File("/data/local/tmp/.safekeep");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        storageFile = new File(baseDir, "locks.json");
        load();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
        logger.info("SafeKeep master state set to: " + enabled);
    }

    public static boolean isLocked(File file) {
        if (file == null) return false;
        if (!getInstance().isEnabled()) return false;
        return getInstance().isLockedInternal(file.getName()) || getInstance().isLockedInternal(file.getAbsolutePath());
    }

    public static boolean isLocked(String pathOrName) {
        if (pathOrName == null) return false;
        if (!getInstance().isEnabled()) return false;
        return getInstance().isLockedInternal(pathOrName);
    }

    public boolean isLockedInternal(String pathOrName) {
        if (lockedFiles.containsKey(pathOrName)) return true;
        String name = new File(pathOrName).getName();
        return lockedFiles.containsKey(name);
    }

    public boolean lock(String path, String reason) {
        if (path == null || path.isEmpty()) return false;
        String name = new File(path).getName();
        JSONObject obj = new JSONObject();
        try {
            obj.put("path", path);
            obj.put("filename", name);
            obj.put("reason", reason != null ? reason : "user");
            obj.put("lockedAt", System.currentTimeMillis());
            lockedFiles.put(name, obj);
            lockedFiles.put(path, obj);
            save();
            logger.info("Locked recording: " + name + " (reason=" + reason + ")");
            return true;
        } catch (Exception e) {
            logger.warn("Failed to lock recording " + path + ": " + e.getMessage());
            return false;
        }
    }

    public boolean unlock(String path) {
        if (path == null || path.isEmpty()) return false;
        String name = new File(path).getName();
        boolean removed = (lockedFiles.remove(name) != null);
        removed |= (lockedFiles.remove(path) != null);
        if (removed) {
            save();
            logger.info("Unlocked recording: " + name);
        }
        return removed;
    }

    public Set<String> getLockedFilenames() {
        Set<String> set = new HashSet<>();
        for (JSONObject obj : lockedFiles.values()) {
            String fn = obj.optString("filename");
            if (!fn.isEmpty()) {
                set.add(fn);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public JSONArray getLocksJson() {
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject obj : lockedFiles.values()) {
            String fn = obj.optString("filename");
            if (!fn.isEmpty() && seen.add(fn)) {
                arr.put(obj);
            }
        }
        return arr;
    }

    public JSONObject getStatusJson() {
        JSONObject status = new JSONObject();
        try {
            status.put("success", true);
            status.put("enabled", enabled);
            status.put("state", enabled ? "idle" : "off");
            status.put("count", getLockedFilenames().size());
            status.put("locks", getLocksJson());
        } catch (Exception ignored) {}
        return status;
    }

    private void load() {
        synchronized (fileLock) {
            if (!storageFile.exists()) return;
            try (FileInputStream fis = new FileInputStream(storageFile)) {
                byte[] data = new byte[(int) storageFile.length()];
                int read = fis.read(data);
                if (read > 0) {
                    String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8);
                    JSONObject root = new JSONObject(jsonStr);
                    this.enabled = root.optBoolean("enabled", false);
                    JSONArray arr = root.optJSONArray("locks");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            String path = item.optString("path");
                            String name = item.optString("filename", new File(path).getName());
                            if (!name.isEmpty()) {
                                lockedFiles.put(name, item);
                                if (!path.isEmpty()) {
                                    lockedFiles.put(path, item);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load locks.json: " + e.getMessage());
            }
        }
    }

    private void save() {
        synchronized (fileLock) {
            try {
                JSONObject root = new JSONObject();
                root.put("enabled", enabled);
                root.put("locks", getLocksJson());
                byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                    fos.write(bytes);
                    fos.flush();
                }
            } catch (Exception e) {
                logger.warn("Failed to save locks.json: " + e.getMessage());
            }
        }
    }
}
