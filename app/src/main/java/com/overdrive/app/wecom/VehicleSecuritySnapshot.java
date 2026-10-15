package com.overdrive.app.wecom;

import android.content.Context;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.daemon.DaemonBootstrap;
import com.overdrive.app.power.BatteryVoltageMonitorV2;
import com.overdrive.app.power.SocCutoffMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 车辆安防闭锁与电池状态快照提取器。
 * 纯文本输出，无 Markdown，用于企业微信 Webhook 哨兵预警与汇总消息。
 */
public final class VehicleSecuritySnapshot {

    private VehicleSecuritySnapshot() {}

    /**
     * 获取车辆安防门窗与电量状态单行摘要。
     * 示例：
     * "车门已关 | 车窗全闭 | 小电瓶 12.8V | 动力电池 72%"
     * 或
     * "⚠️ 主驾门未关 | 车窗全闭 | 小电瓶 12.8V | 动力电池 72%"
     *
     * @return 格式化单行纯文本，若全无数据则返回 null。
     */
    public static String getSecurityAndBatterySummary() {
        Context ctx = DaemonBootstrap.getContext();
        return getSecurityAndBatterySummary(ctx);
    }

    public static String getSecurityAndBatterySummary(Context ctx) {
        StringBuilder sb = new StringBuilder();

        // 1. 门窗状态
        String doorWinStr = getDoorAndWindowStatus(ctx);
        if (doorWinStr != null && !doorWinStr.isEmpty()) {
            sb.append(doorWinStr);
        }

        // 2. 电池电量状态
        String batStr = getBatteryStatus(ctx);
        if (batStr != null && !batStr.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(batStr);
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String getDoorAndWindowStatus(Context ctx) {
        try {
            Object bodywork = BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", ctx);
            if (bodywork == null) return null;

            // 门状态：area 1..6 (1:主驾, 2:副驾, 3:左后, 4:右后, 5:前机盖, 6:后备箱)
            // 0=closed, 1=open
            List<String> openDoors = new ArrayList<>();
            checkDoor(bodywork, 1, "主驾门", openDoors);
            checkDoor(bodywork, 2, "副驾门", openDoors);
            checkDoor(bodywork, 3, "左后门", openDoors);
            checkDoor(bodywork, 4, "右后门", openDoors);
            checkDoor(bodywork, 5, "前机盖", openDoors);
            checkDoor(bodywork, 6, "后备箱", openDoors);

            // 窗状态：area 1..4 (1:左前, 2:右前, 3:左后, 4:右后)
            // 0=closed, 1=open
            List<String> openWindows = new ArrayList<>();
            checkWindow(bodywork, 1, "左前窗", openWindows);
            checkWindow(bodywork, 2, "右前窗", openWindows);
            checkWindow(bodywork, 3, "左后窗", openWindows);
            checkWindow(bodywork, 4, "右后窗", openWindows);

            StringBuilder sb = new StringBuilder();
            if (openDoors.isEmpty()) {
                sb.append("车门已关");
            } else {
                sb.append("⚠️ ").append(String.join("/", openDoors)).append("未关");
            }

            sb.append(" | ");

            if (openWindows.isEmpty()) {
                sb.append("车窗全闭");
            } else {
                sb.append("⚠️ ").append(String.join("/", openWindows)).append("未关");
            }

            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void checkDoor(Object bodywork, int area, String name, List<String> openDoors) {
        try {
            Object val = BydDeviceHelper.callMethod(bodywork, "getDoorState", area);
            if (val instanceof Number && ((Number) val).intValue() == 1) {
                openDoors.add(name);
            }
        } catch (Throwable ignored) {}
    }

    private static void checkWindow(Object bodywork, int area, String name, List<String> openWindows) {
        try {
            Object val = BydDeviceHelper.callGetter(bodywork, "getWindowState", area);
            if (val instanceof Number && ((Number) val).intValue() == 1) {
                openWindows.add(name);
            }
        } catch (Throwable ignored) {}
    }

    private static String getBatteryStatus(Context ctx) {
        Double voltage12V = null;
        Double soc = null;

        // 1. 优先从内存监控器缓存读取
        try {
            double v = BatteryVoltageMonitorV2.getLastPowerVoltage();
            if (v > 9.0 && v < 16.0) voltage12V = v;
        } catch (Throwable ignored) {}

        try {
            Double s = SocCutoffMonitor.getLastSoc();
            if (s != null && s > 0.0 && s <= 100.0) soc = s;
        } catch (Throwable ignored) {}

        // 2. 内存缺失时通过 HAL 设备直接读取
        if (voltage12V == null) {
            try {
                Object ota = BydDeviceHelper.getDevice(
                        "android.hardware.bydauto.ota.BYDAutoOtaDevice", ctx);
                if (ota != null) {
                    Object v = BydDeviceHelper.callGetter(ota, "getBatteryPowerVoltage");
                    if (v instanceof Number) {
                        double val = ((Number) v).doubleValue();
                        if (val > 9.0 && val < 16.0) voltage12V = val;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (soc == null) {
            try {
                Object stat = BydDeviceHelper.getDevice(
                        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice", ctx);
                if (stat != null) {
                    Object v = BydDeviceHelper.callGetter(stat, "getElecPercentageValue");
                    if (v instanceof Number) {
                        double val = ((Number) v).doubleValue();
                        if (val > 0.0 && val <= 100.0) soc = val;
                    }
                }
            } catch (Throwable ignored) {}
        }

        List<String> parts = new ArrayList<>();
        if (voltage12V != null) {
            parts.add(String.format(Locale.US, "小电瓶 %.1fV", voltage12V));
        }
        if (soc != null) {
            parts.add(String.format(Locale.US, "动力电池 %.0f%%", soc));
        }

        return parts.isEmpty() ? null : String.join(" | ", parts);
    }
}
