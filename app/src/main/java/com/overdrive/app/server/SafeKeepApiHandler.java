package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.SafeKeepLocksManager;
import org.json.JSONArray;
import org.json.JSONObject;

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
                handleClips(out);
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
            } else if (cleanPath.equals("/api/safekeep/enable") ||
                       cleanPath.equals("/api/safekeep/pause") ||
                       cleanPath.equals("/api/safekeep/resume") ||
                       cleanPath.equals("/api/safekeep/run") ||
                       cleanPath.equals("/api/safekeep/dryrun") ||
                       cleanPath.equals("/api/safekeep/sync")) {
                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("state", "idle");
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
        JSONObject status = SafeKeepLocksManager.getInstance().getStatusJson();
        status.put("enabled", true);
        status.put("state", "idle");
        HttpResponse.sendJson(out, status.toString());
    }

    private static void handleClips(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("clips", SafeKeepLocksManager.getInstance().getLocksJson());
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleLock(OutputStream out, String body) throws Exception {
        JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
        String path = req.optString("path", req.optString("file"));
        boolean lock = req.optBoolean("lock", true);
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
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", true);
        resp.put("autoUpload", false);
        resp.put("keepLocal", true);
        HttpResponse.sendJson(out, resp.toString());
    }
}
