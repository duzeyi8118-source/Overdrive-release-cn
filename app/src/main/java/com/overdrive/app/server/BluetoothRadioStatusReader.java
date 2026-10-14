package com.overdrive.app.server;

import android.bluetooth.BluetoothAdapter;
import android.os.SystemClock;
import com.overdrive.app.monitor.BluetoothRadioState;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BluetoothRadioStatusReader {

    public static final class Cache {
        public final BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        public final AtomicBoolean refreshing = new AtomicBoolean(false);
        private volatile long lastRefreshElapsedMs = 0;

        public void refresh() {
            long now = SystemClock.elapsedRealtime();
            if (now - lastRefreshElapsedMs < 2000L) {
                return;
            }
            if (refreshing.compareAndSet(false, true)) {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        boolean enabled = adapter.isEnabled();
                        int state = adapter.getState();
                        store.update(state, enabled);
                    }
                    lastRefreshElapsedMs = SystemClock.elapsedRealtime();
                } catch (Throwable ignored) {
                } finally {
                    refreshing.set(false);
                }
            }
        }
    }

    public static final Cache instance = new Cache();
}
