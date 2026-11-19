package dev.omshutter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.time.Instant;

/** One foreground-only, lifecycle-cancellable platform fix request per explicit action. */
final class ForegroundLocationAcquirer {
    static final long TIMEOUT_MILLIS = 10_000L;

    enum Failure {
        LOCATION_UNAVAILABLE,
        TIME_UNAVAILABLE
    }

    interface Callback {
        void onLocation(Fix fix);
        void onUnavailable(Failure failure);
    }

    /** Ephemeral immutable result. This class performs no persistence or logging. */
    static final class Fix {
        private final double latitudeDeg;
        private final double longitudeDeg;
        private final float accuracyMeters;
        private final long fixElapsedRealtimeNanos;
        private final long receiptElapsedRealtimeNanos;
        private final Instant utc;

        private Fix(
                double latitudeDeg,
                double longitudeDeg,
                float accuracyMeters,
                long fixElapsedRealtimeNanos,
                long receiptElapsedRealtimeNanos,
                Instant utc) {
            this.latitudeDeg = latitudeDeg;
            this.longitudeDeg = longitudeDeg;
            this.accuracyMeters = accuracyMeters;
            this.fixElapsedRealtimeNanos = fixElapsedRealtimeNanos;
            this.receiptElapsedRealtimeNanos = receiptElapsedRealtimeNanos;
            this.utc = utc;
        }

        double getLatitudeDeg() { return latitudeDeg; }
        double getLongitudeDeg() { return longitudeDeg; }
        float getAccuracyMeters() { return accuracyMeters; }
        long getFixElapsedRealtimeNanos() { return fixElapsedRealtimeNanos; }
        long getReceiptElapsedRealtimeNanos() { return receiptElapsedRealtimeNanos; }
        Instant getUtc() { return utc; }
    }

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler;
    private final Runnable timeout = this::onTimeout;

    private Callback callback;
    private ProviderRequest providerRequest;
    private int generation;
    private boolean sawQualifiedLocation;

    ForegroundLocationAcquirer(Context context) {
        this.context = context;
        locationManager = context.getSystemService(LocationManager.class);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Caller must hold coarse or fine foreground-location permission. */
    @SuppressLint("MissingPermission")
    void request(Callback nextCallback) {
        if (nextCallback == null) throw new IllegalArgumentException("callback is null");
        cancel();
        callback = nextCallback;
        sawQualifiedLocation = false;
        int token = generation;
        if (locationManager == null) {
            finishUnavailable(Failure.LOCATION_UNAVAILABLE);
            return;
        }

        String provider = chooseProvider(
                isEnabledProvider(LocationManager.NETWORK_PROVIDER),
                isEnabledProvider(LocationManager.GPS_PROVIDER));
        if (provider == null) {
            finishUnavailable(Failure.LOCATION_UNAVAILABLE);
            return;
        }

        providerRequest = new ProviderRequest(provider, token);
        mainHandler.postDelayed(timeout, TIMEOUT_MILLIS);
        providerRequest.start();
    }

    void cancel() {
        generation++;
        cleanupPlatformRequest();
        callback = null;
        sawQualifiedLocation = false;
    }

    private void onTimeout() {
        finishUnavailable(sawQualifiedLocation
                ? Failure.TIME_UNAVAILABLE
                : Failure.LOCATION_UNAVAILABLE);
    }

    static String chooseProvider(boolean networkEnabled, boolean gpsEnabled) {
        if (networkEnabled) return LocationManager.NETWORK_PROVIDER;
        if (gpsEnabled) return LocationManager.GPS_PROVIDER;
        return null;
    }

    private boolean isEnabledProvider(String provider) {
        try {
            return locationManager.getAllProviders().contains(provider)
                    && locationManager.isProviderEnabled(provider);
        } catch (RuntimeException ignored) {
            // Treat a provider that cannot be queried as unavailable.
            return false;
        }
    }

    private void handleLocation(ProviderRequest request, Location location) {
        if (request.finished || request.token != generation || callback == null) return;
        long receiptElapsedNanos = SystemClock.elapsedRealtimeNanos();
        if (location == null || !ForegroundLocationPolicy.isQualified(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy(),
                location.getAccuracy(),
                location.getElapsedRealtimeNanos(),
                receiptElapsedNanos,
                isMockLocation(location))) {
            providerFinished(request);
            return;
        }

        sawQualifiedLocation = true;
        Instant utc = trustedUtc(location, receiptElapsedNanos);
        if (utc == null) {
            providerFinished(request);
            return;
        }
        finishLocation(new Fix(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                location.getElapsedRealtimeNanos(),
                receiptElapsedNanos,
                utc));
    }

    private void providerFinished(ProviderRequest request) {
        if (!request.markFinished() || request.token != generation || callback == null) return;
        finishUnavailable(sawQualifiedLocation
                ? Failure.TIME_UNAVAILABLE
                : Failure.LOCATION_UNAVAILABLE);
    }

    private void finishLocation(Fix fix) {
        Callback completed = callback;
        if (completed == null) return;
        cleanupPlatformRequest();
        callback = null;
        completed.onLocation(fix);
    }

    private void finishUnavailable(Failure failure) {
        Callback completed = callback;
        if (completed == null) return;
        cleanupPlatformRequest();
        callback = null;
        completed.onUnavailable(failure);
    }

    private void cleanupPlatformRequest() {
        mainHandler.removeCallbacks(timeout);
        if (providerRequest == null) return;
        providerRequest.cancelPlatform();
        providerRequest = null;
    }

    private Instant trustedUtc(Location location, long receiptElapsedNanos) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return SystemClock.currentGnssTimeClock().instant();
            } catch (RuntimeException ignored) {
                // Continue to the next qualified source.
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return SystemClock.currentNetworkTimeClock().instant();
            } catch (RuntimeException ignored) {
                // Continue to location UTC or automatic system time.
            }
        }
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            Instant fromLocation = ForegroundLocationPolicy.advanceLocationUtc(
                    location.getTime(),
                    location.getElapsedRealtimeNanos(),
                    receiptElapsedNanos);
            if (fromLocation != null) return fromLocation;
        }
        try {
            if (Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.AUTO_TIME, 0) == 1) {
                return Instant.now();
            }
        } catch (RuntimeException ignored) {
            // Automatic-time state or the clock itself is unavailable.
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean isMockLocation(Location location) {
        return Build.VERSION.SDK_INT >= 31
                ? location.isMock()
                : location.isFromMockProvider();
    }

    private final class ProviderRequest implements LocationListener {
        private final String provider;
        private final int token;
        private CancellationSignal cancellationSignal;
        private boolean finished;

        private ProviderRequest(String provider, int token) {
            this.provider = provider;
            this.token = token;
        }

        @SuppressLint("MissingPermission")
        private void start() {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    cancellationSignal = new CancellationSignal();
                    locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            context.getMainExecutor(),
                            location -> handleLocation(this, location));
                } else {
                    locationManager.requestSingleUpdate(provider, this, Looper.getMainLooper());
                }
            } catch (RuntimeException error) {
                providerFinished(this);
            }
        }

        private boolean markFinished() {
            if (finished) return false;
            finished = true;
            if (Build.VERSION.SDK_INT < 30) {
                try {
                    locationManager.removeUpdates(this);
                } catch (RuntimeException ignored) {
                    // Request is already terminal.
                }
            }
            return true;
        }

        private void cancelPlatform() {
            if (cancellationSignal != null) cancellationSignal.cancel();
            try {
                locationManager.removeUpdates(this);
            } catch (RuntimeException ignored) {
                // No active legacy listener.
            }
            finished = true;
        }

        @Override
        public void onLocationChanged(Location location) {
            handleLocation(this, location);
        }

        @Override public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            providerFinished(this);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
                providerFinished(this);
            }
        }
    }
}
