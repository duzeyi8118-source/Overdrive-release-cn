package com.overdrive.app.monitor;

import android.os.SystemClock;
import org.json.JSONObject;

public final class BluetoothRadioState {

    public static final class Observation {
        public final int power;
        public final boolean radio;
        public final long observedAtElapsedMs;
        public final long timestampMs;

        public Observation(int power, boolean radio, long observedAtElapsedMs, long timestampMs) {
            this.power = power;
            this.radio = radio;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.timestampMs = timestampMs;
        }
    }

    public static final class Store {
        private volatile Observation lastObservation;

        public synchronized boolean update(int power, boolean radio) {
            long nowElapsed = SystemClock.elapsedRealtime();
            long nowWall = System.currentTimeMillis();
            lastObservation = new Observation(power, radio, nowElapsed, nowWall);
            return true;
        }

        public synchronized boolean update(Observation obs) {
            if (obs != null) {
                lastObservation = obs;
                return true;
            }
            return false;
        }

        public JSONObject toJson(long nowElapsedMs) {
            Observation obs = lastObservation;
            JSONObject json = new JSONObject();
            try {
                if (obs == null) {
                    json.put("power", 0);
                    json.put("radio", false);
                    json.put("observedAtElapsedMs", -1);
                    json.put("ageMs", JSONObject.NULL);
                } else {
                    json.put("power", obs.power);
                    json.put("radio", obs.radio);
                    json.put("observedAtElapsedMs", obs.observedAtElapsedMs);
                    long age = nowElapsedMs - obs.observedAtElapsedMs;
                    json.put("ageMs", age >= 0 ? age : JSONObject.NULL);
                }
            } catch (Exception ignored) {}
            return json;
        }

        public Observation getLastObservation() {
            return lastObservation;
        }
    }

    private BluetoothRadioState() {}
}
