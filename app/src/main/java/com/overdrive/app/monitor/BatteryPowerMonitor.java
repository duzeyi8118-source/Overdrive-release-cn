package com.overdrive.app.monitor;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for 12V battery power voltage.
 * 
 * Monitors the actual battery voltage in volts via BYDAutoOtaDevice.
 * Uses reflection and PermissionBypassContext to access real BYD hardware API.
 * Uses polling since OTA device may not support listeners reliably.
 */
public class BatteryPowerMonitor extends BaseDeviceMonitor<BatteryPowerData> {
    
    private Object device;  // Real BYDAutoOtaDevice from system
    private final AtomicReference<BatteryPowerData> cachedData = new AtomicReference<>();
    private Thread pollThread;
    // Poll every 60s. Was 5 min — too coarse now that BatteryVoltageMonitorV2
    // consumes voltage from the same poll loop for MCU sleep/wake hysteresis,
    // and V2's hysteresis windows are minute-scale.
    private static final long POLL_INTERVAL_MS = 60_000;
    // Debounce window for low battery notifications (30 minutes)
    private static final long ALERT_DEBOUNCE_MS = 30 * 60 * 1000L;
    private long lastAlertTimeMs = 0;
    private boolean lastAlertWasCritical = false;
    private boolean alertActive = false;
    
    public BatteryPowerMonitor() {
        super("BatteryPowerMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        
        try {
            log("Initializing BYDAutoOtaDevice via reflection...");
            
            // Context is already PermissionBypassContext from AccSentryDaemon
            // Get real device via reflection
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            device = getInstance.invoke(null, context);
            
            if (device == null) {
                logError("BYDAutoOtaDevice.getInstance returned null", null);
                markUnavailable();
                return;
            }
            
            log("Got real BYDAutoOtaDevice: " + device.getClass().getName());
            
            // Get initial value
            try {
                Method getBatteryPowerVoltage = deviceClass.getMethod("getBatteryPowerVoltage");
                double initialVoltage = (Double) getBatteryPowerVoltage.invoke(device);
                cachedData.set(new BatteryPowerData(initialVoltage));
                log("Initial battery power voltage: " + initialVoltage + "V");
            } catch (Exception e) {
                logError("Failed to get initial battery power voltage", e);
            }
            
            markAvailable();
            log("Initialized successfully");
            
        } catch (Exception e) {
            logError("Initialization failed", e);
            markUnavailable();
        }
    }
    
    @Override
    public void start() {
        if (device == null) {
            logError("Cannot start - device not initialized", null);
            return;
        }
        
        if (isRunning.getAndSet(true)) {
            log("Already running");
            return;
        }
        
        // Start polling thread
        pollThread = new Thread(() -> {
            log("Polling thread started");
            
            while (isRunning.get()) {
                try {
                    // Poll voltage via reflection
                    Class<?> deviceClass = device.getClass();
                    Method getBatteryPowerVoltage = deviceClass.getMethod("getBatteryPowerVoltage");
                    double voltage = (Double) getBatteryPowerVoltage.invoke(device);
                    
                    BatteryPowerData oldData = cachedData.get();
                    BatteryPowerData newData = new BatteryPowerData(voltage);
                    
                    // Only log if value changed significantly (> 0.1V)
                    if (oldData == null || Math.abs(voltage - oldData.voltageVolts) > 0.1) {
                        log("Battery power voltage: " + voltage + "V");
                        
                        if (newData.isCritical) {
                            log("CRITICAL: Battery voltage is critically low (" + voltage + "V < 10.5V)!");
                        } else if (newData.isWarning) {
                            log("WARNING: Battery voltage is low (" + voltage + "V < 11.5V)");
                        }
                        
                        if (!newData.isValidRange()) {
                            log("WARNING: Battery voltage out of valid range (9.0-16.0V): " + voltage + "V");
                        }
                    }
                    
                    cachedData.set(newData);
                    checkAndNotifyLowBattery(newData);

                    // Fan out to BatteryVoltageMonitorV2 (acc_sentry-process MCU
                    // sleep/wake hysteresis). V2 lives in this same process,
                    // so the cross-process gap that affects the cam_daemon
                    // fan-out doesn't apply here. No-op when V2 is not running.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}

                    Thread.sleep(POLL_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logError("Polling error", e);
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            
            log("Polling thread stopped");
        }, "BatteryPowerPoll");
        
        pollThread.start();
        log("Started successfully (polling mode)");
    }
    
    @Override
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            log("Already stopped");
            return;
        }
        
        cancelRetries();
        
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            pollThread = null;
        }
        
        log("Stopped successfully");
    }
    
    @Override
    public BatteryPowerData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        BatteryPowerData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }

    /**
     * Check if voltage is in low/critical state and notify owner via WeChat Work.
     * Includes a 30-minute debounce window and allows immediate escalation from Warning to Critical.
     * Resets automatically once battery recovers above 12.0V (0.5V hysteresis).
     */
    private void checkAndNotifyLowBattery(BatteryPowerData data) {
        if (data == null || !data.isValidRange()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean shouldAlert = false;

        if (data.isCritical) {
            // Critical (< 10.5V): Alert if interval passed, or escalated from Warning
            if (!alertActive || !lastAlertWasCritical || (now - lastAlertTimeMs >= ALERT_DEBOUNCE_MS)) {
                shouldAlert = true;
                lastAlertWasCritical = true;
                alertActive = true;
            }
        } else if (data.isWarning) {
            // Warning (< 11.5V): Alert if interval passed and didn't recently fire Critical
            if (!alertActive || (!lastAlertWasCritical && now - lastAlertTimeMs >= ALERT_DEBOUNCE_MS)) {
                shouldAlert = true;
                lastAlertWasCritical = false;
                alertActive = true;
            }
        } else {
            // Normal range (>= 11.5V): hysteresis reset at 12.0V
            if (alertActive && data.voltageVolts >= 12.0) {
                alertActive = false;
                lastAlertWasCritical = false;
                log("12V battery voltage recovered to " + String.format(java.util.Locale.CHINA, "%.2fV", data.voltageVolts));
            }
            return;
        }

        if (shouldAlert) {
            lastAlertTimeMs = now;
            String level = data.isCritical ? "严重亏电（< 10.5V）" : "低电量预警（< 11.5V）";
            String details = "当前电压：" + String.format(java.util.Locale.CHINA, "%.2fV", data.voltageVolts)
                    + "\n• 告警等级：" + level
                    + "\n• 建议：请尽快启动车辆或检查车载电器，避免小电瓶亏电无法上电";
            try {
                com.overdrive.app.wecom.WeComNotifier.notifyCritical("LOW_BATTERY", details);
                log("Dispatched WeCom low battery critical notification: " + level);
            } catch (Throwable t) {
                logError("Failed to dispatch WeCom notification for low battery", t);
            }
        }
    }
}
