package com.overdrive.app.server;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public abstract class GlassWidgetsApiHandler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("GlassWidgetsApi");
    private static final String[] HOME_PACKAGES = new String[]{"com.byd.launcher", "com.overdrive.launcher"};
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GlassWidgetsExec");
            t.setDaemon(true);
            return t;
        }
    });

    private static String cachedHomePkg = null;
    private static long cachedHomePkgTime = 0;
    private static final Field displayIdField;

    static {
        Field f = null;
        try {
            f = ActivityManager.RunningTaskInfo.class.getField("displayId");
        } catch (Throwable ignored) {}
        displayIdField = f;
    }

    public static class MainTop {
        public final ComponentName topActivity;
        public final Boolean isHome;

        public MainTop(ComponentName topActivity, Boolean isHome) {
            this.topActivity = topActivity;
            this.isHome = isHome;
        }
    }

    public static boolean handle(OutputStream out, String method, String path) {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/widgets/sync") && "POST".equalsIgnoreCase(method)) {
            handleSync(out);
            return true;
        }
        if (cleanPath.equals("/api/widgets/foreground") && "GET".equalsIgnoreCase(method)) {
            handleForeground(out);
            return true;
        }
        if (cleanPath.equals("/api/widgets/live") && "GET".equalsIgnoreCase(method)) {
            handleLive(out, path);
            return true;
        }
        if (cleanPath.equals("/api/widgets/automations") && "GET".equalsIgnoreCase(method)) {
            handleAutomations(out);
            return true;
        }
        if (cleanPath.equals("/api/widgets/arrange") && "POST".equalsIgnoreCase(method)) {
            handleArrange(out);
            return true;
        }
        return false;
    }

    private static void handleSync(OutputStream out) {
        boolean enabled = false;
        try {
            JSONObject config = UnifiedConfigManager.loadConfig();
            JSONObject widgets = config.optJSONObject("glassWidgets");
            if (widgets != null) {
                enabled = widgets.optBoolean("enabled", false);
            }
        } catch (Throwable t) {
            logger.warn("handleSync config error: " + t.getMessage());
        }

        final boolean finalEnabled = enabled;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String cmd = finalEnabled
                            ? "am start-foreground-service -n com.overdrive.app/com.overdrive.app.overlay.GlassWidgetsOverlayService"
                            : "am stopservice -n com.overdrive.app/com.overdrive.app.overlay.GlassWidgetsOverlayService";
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                } catch (Throwable t) {
                    logger.warn("Sync exec error: " + t.getMessage());
                }
            }
        });

        try {
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("enabled", enabled);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Throwable t) {
            try { HttpResponse.sendError(out, 500, t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    private static void handleForeground(OutputStream out) {
        try {
            Context ctx = CameraDaemon.getAppContext();
            String pkg = null;
            String cls = null;
            String base = null;
            Boolean isHome = null;

            if (ctx != null) {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(8);
                    MainTop mainTop = getMainTop();
                    if (tasks != null) {
                        for (ActivityManager.RunningTaskInfo task : tasks) {
                            if (task == null || task.topActivity == null) continue;
                            if (mainTop != null && !mainTop.topActivity.equals(task.topActivity)) {
                                continue;
                            }
                            if (displayIdField != null) {
                                try {
                                    if (displayIdField.getInt(task) != 0) continue;
                                } catch (Throwable ignored) {}
                            }
                            pkg = task.topActivity.getPackageName();
                            cls = task.topActivity.getClassName();
                            if (task.baseActivity != null) {
                                base = task.baseActivity.getClassName();
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 29 && task.baseIntent != null) {
                                isHome = task.baseIntent.hasCategory(Intent.CATEGORY_HOME);
                            }
                            break;
                        }
                    }
                    if (mainTop != null) {
                        if (pkg == null) {
                            pkg = mainTop.topActivity.getPackageName();
                            cls = mainTop.topActivity.getClassName();
                        }
                        if (isHome == null) {
                            isHome = mainTop.isHome;
                        }
                    }
                }
            }

            boolean isHomeResolved = false;
            if (pkg != null) {
                for (String homePkg : HOME_PACKAGES) {
                    if (homePkg.equalsIgnoreCase(pkg)) {
                        isHomeResolved = true;
                        break;
                    }
                }
                if (!isHomeResolved) {
                    long now = System.currentTimeMillis();
                    if (cachedHomePkg != null && (now - cachedHomePkgTime < 60000)) {
                        isHomeResolved = pkg.equalsIgnoreCase(cachedHomePkg);
                    } else if (ctx != null) {
                        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                        homeIntent.addCategory(Intent.CATEGORY_HOME);
                        PackageManager pm = ctx.getPackageManager();
                        ResolveInfo resolve = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
                        if (resolve != null && resolve.activityInfo != null && !"android".equals(resolve.activityInfo.packageName)) {
                            cachedHomePkg = resolve.activityInfo.packageName;
                            cachedHomePkgTime = now;
                            isHomeResolved = pkg.equalsIgnoreCase(cachedHomePkg);
                        }
                    }
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("pkg", pkg != null ? pkg : JSONObject.NULL);
            resp.put("cls", cls != null ? cls : JSONObject.NULL);
            resp.put("base", base != null ? base : JSONObject.NULL);
            resp.put("homeTask", isHome != null ? isHome : JSONObject.NULL);
            resp.put("home", isHomeResolved);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Throwable t) {
            try { HttpResponse.sendError(out, 500, t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    private static void handleLive(OutputStream out, String path) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("ts", System.currentTimeMillis());

            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData data = collector != null ? collector.getData() : null;

            Double speedKmh = null;
            if (data != null && !Double.isNaN(data.speedKmh) && data.speedKmh >= 0) {
                speedKmh = Math.round(data.speedKmh * 10.0) / 10.0;
            }
            resp.put("speedKmh", speedKmh != null ? speedKmh : JSONObject.NULL);

            Double packKw = null;
            if (data != null && !Double.isNaN(data.remainKwh)) {
                // approximate power if available or NaN
            }
            resp.put("packKw", packKw != null ? packKw : JSONObject.NULL);

            Integer accel = null;
            if (data != null && data.accelPercent >= 0 && data.accelPercent <= 100) {
                accel = data.accelPercent;
            }
            resp.put("accelPct", accel != null ? accel : JSONObject.NULL);

            Integer brake = null;
            if (data != null && data.brakePercent >= 0 && data.brakePercent <= 100) {
                brake = data.brakePercent;
            }
            resp.put("brakePct", brake != null ? brake : JSONObject.NULL);

            Integer frontRpm = null;
            if (data != null && data.frontMotorSpeed != BydVehicleData.UNAVAILABLE) {
                frontRpm = data.frontMotorSpeed;
            }
            resp.put("frontRpm", frontRpm != null ? frontRpm : JSONObject.NULL);

            Integer rearRpm = null;
            if (data != null && data.rearMotorSpeed != BydVehicleData.UNAVAILABLE) {
                rearRpm = data.rearMotorSpeed;
            }
            resp.put("rearRpm", rearRpm != null ? rearRpm : JSONObject.NULL);

            JSONObject blindSpot = new JSONObject();
            blindSpot.put("left", 0);
            blindSpot.put("right", 0);
            blindSpot.put("any", 0);
            resp.put("blindSpot", blindSpot);

            HttpResponse.sendJson(out, resp.toString());
        } catch (Throwable t) {
            try { HttpResponse.sendError(out, 500, t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    private static void handleAutomations(OutputStream out) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("success", true);

            JSONArray automationsArr = new JSONArray();
            try {
                JSONArray list = Automations.listForPicker(null);
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject auto = list.getJSONObject(i);
                        JSONObject item = new JSONObject();
                        item.put("id", auto.optString("id"));
                        item.put("name", auto.optString("name"));
                        item.put("mode", auto.optString("mode", "active"));
                        item.put("glyph", auto.optString("glyph", "bolt"));
                        automationsArr.put(item);
                    }
                }
            } catch (Throwable t) {
                logger.warn("Automations listing error: " + t.getMessage());
            }

            resp.put("automations", automationsArr);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Throwable t) {
            try { HttpResponse.sendError(out, 500, t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    private static void handleArrange(OutputStream out) {
        boolean enabled = false;
        try {
            JSONObject config = UnifiedConfigManager.loadConfig();
            JSONObject widgets = config.optJSONObject("glassWidgets");
            if (widgets != null) {
                enabled = widgets.optBoolean("enabled", false);
            }
        } catch (Throwable t) {
            logger.warn("handleArrange config error: " + t.getMessage());
        }

        if (enabled) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String cmd = "am start-foreground-service -n com.overdrive.app/com.overdrive.app.overlay.GlassWidgetsOverlayService --es action arrange";
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    } catch (Throwable t) {
                        logger.warn("Arrange exec error: " + t.getMessage());
                    }
                }
            });
        }

        try {
            JSONObject resp = new JSONObject();
            resp.put("success", enabled);
            resp.put("enabled", enabled);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Throwable t) {
            try { HttpResponse.sendError(out, 500, t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    public static MainTop getMainTop() {
        try {
            Method getService = ActivityManager.class.getMethod("getService");
            Object ams = getService.invoke(null);
            if (ams == null) return null;
            Method getAllStackInfos = ams.getClass().getMethod("getAllStackInfos");
            List<?> stacks = (List<?>) getAllStackInfos.invoke(ams);
            if (stacks == null) return null;

            for (Object stack : stacks) {
                Field displayIdF = stack.getClass().getField("displayId");
                if (displayIdF.getInt(stack) != 0) continue;
                Field visibleF = stack.getClass().getField("visible");
                if (!visibleF.getBoolean(stack)) continue;
                Field topActivityF = stack.getClass().getField("topActivity");
                ComponentName top = (ComponentName) topActivityF.get(stack);
                if (top == null) continue;

                Field configF = stack.getClass().getField("configuration");
                Object cfg = configF.get(stack);
                Field windowConfigF = cfg.getClass().getField("windowConfiguration");
                Object winCfg = windowConfigF.get(cfg);

                Method getWindowingMode = winCfg.getClass().getMethod("getWindowingMode");
                int windowingMode = (Integer) getWindowingMode.invoke(winCfg);
                if (windowingMode == 2) continue; // PINNED

                Method getActivityType = winCfg.getClass().getMethod("getActivityType");
                int activityType = (Integer) getActivityType.invoke(winCfg);
                boolean isHome = (activityType == 2); // ACTIVITY_TYPE_HOME = 2

                return new MainTop(top, isHome);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
