package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.SafeKeepLocksManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;

public class SafeKeepApiHandler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepApi");

    public static boolean handle(OutputStream out, String method, String path, String body) {
        try {
            String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

            if (cleanPath.equals("/api/safekeep/status")) {
                handleStatus(out);
                return true;
            } else if (cleanPath.equals("/api/safekeep/clips")) {
                handleClips(out, body);
                return true;
            } else if (cleanPath.equals("/api/safekeep/lock") && method.equals("POST")) {
                handleLock(out, body);
                return true;
            } else if (cleanPath.equals("/api/safekeep/upload") && method.equals("POST")) {
                handleUpload(out, body);
                return true;
            } else if (cleanPath.equals("/api/safekeep/config")) {
                handleConfig(out, method, body);
                return true;
            } else if (cleanPath.equals("/api/safekeep/enable") && method.equals("POST")) {
                handleEnable(out, body);
                return true;
            } else if (cleanPath.equals("/api/safekeep/queue")) {
                handleQueue(out);
                return true;
            } else if (cleanPath.equals("/api/safekeep/dryrun")) {
                handleDryRun(out);
                return true;
            } else if (cleanPath.equals("/api/safekeep/pause") ||
                       cleanPath.equals("/api/safekeep/resume") ||
                       cleanPath.equals("/api/safekeep/run") ||
                       cleanPath.equals("/api/safekeep/sync")) {
                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("state", SafeKeepLocksManager.getInstance().isEnabled() ? "idle" : "off");
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
        } catch (Exception e) {
            logger.warn("SafeKeepApiHandler error: " + e.getMessage());
            try {
                HttpResponse.sendError(out, 500, e.getMessage());
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    private static void handleStatus(OutputStream out) throws Exception {
        boolean enabled = SafeKeepLocksManager.getInstance().isEnabled();
        JSONObject status = SafeKeepLocksManager.getInstance().getStatusJson();
        status.put("enabled", enabled);
        status.put("state", enabled ? "idle" : "off");
        HttpResponse.sendJson(out, status.toString());
    }

    private static void handleClips(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        JSONArray clips = SafeKeepLocksManager.getInstance().getLocksJson();
        resp.put("clips", clips);

        JSONObject states = new JSONObject();
        for (int i = 0; i < clips.length(); i++) {
            JSONObject c = clips.getJSONObject(i);
            String fn = c.optString("filename");
            if (!fn.isEmpty()) {
                JSONObject s = new JSONObject();
                s.put("locked", true);
                s.put("state", "none");
                s.put("reason", c.optString("reason", "user"));
                states.put(fn, s);
            }
        }
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                JSONArray files = req.optJSONArray("files");
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        String fn = files.getString(i);
                        if (!states.has(fn)) {
                            JSONObject s = new JSONObject();
                            s.put("locked", SafeKeepLocksManager.isLocked(fn));
                            s.put("state", "none");
                            states.put(fn, s);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        resp.put("states", states);
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleEnable(OutputStream out, String body) throws Exception {
        JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
        boolean enabled = req.optBoolean("enabled", false);
        SafeKeepLocksManager.getInstance().setEnabled(enabled);

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", enabled);
        resp.put("state", enabled ? "idle" : "off");
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleLock(OutputStream out, String body) throws Exception {
        JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
        String path = req.optString("path", req.optString("file"));
        // Support both "lock" and "locked" parameter names (frontend sends "locked: false" on unlock)
        boolean lock = req.has("locked") ? req.optBoolean("locked", true) : req.optBoolean("lock", true);
        String reason = req.optString("reason", "user");

        boolean ok;
        if (lock) {
            ok = SafeKeepLocksManager.getInstance().lock(path, reason);
        } else {
            ok = SafeKeepLocksManager.getInstance().unlock(path);
        }

        JSONObject resp = new JSONObject();
        resp.put("success", ok);
        resp.put("locked", lock);
        resp.put("path", path);
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleUpload(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("message", "Upload requested");
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleConfig(OutputStream out, String method, String body) throws Exception {
        if (method.equals("POST") && body != null && !body.isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                if (req.has("enabled")) {
                    SafeKeepLocksManager.getInstance().setEnabled(req.optBoolean("enabled", false));
                }
            } catch (Exception ignored) {}
        }
        boolean enabled = SafeKeepLocksManager.getInstance().isEnabled();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", enabled);
        resp.put("autoUpload", false);
        resp.put("keepLocal", true);
        JSONObject configObj = new JSONObject();
        configObj.put("enabled", enabled);
        resp.put("config", configObj);
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleQueue(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("items", new JSONArray());
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleDryRun(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        JSONObject net = new JSONObject();
        net.put("transport", "wifi");
        net.put("parked", true);
        resp.put("network", net);
        JSONObject result = new JSONObject();
        result.put("mediaAllowed", SafeKeepLocksManager.getInstance().isEnabled());
        resp.put("result", result);
        HttpResponse.sendJson(out, resp.toString());
    }
}
