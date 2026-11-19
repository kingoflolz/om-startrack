package dev.omshutter.ble;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import dev.omshutter.ApertureCatalogView;
import dev.omshutter.MainActivity;
import dev.omshutter.R;
import dev.omshutter.protocol.StarTelemetry;
import dev.omshutter.protocol.AstroRateGattProtocol;
import dev.omshutter.protocol.AstroRateSetpoint;
import dev.omshutter.protocol.AstroRateTransport;
import dev.omshutter.protocol.CameraExposureState;
import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.IbisPrepositionProtocol;
import dev.omshutter.protocol.GyroAttitudeProtocol;
import dev.omshutter.protocol.OlympusProtocol;
import dev.omshutter.protocol.StarCaptureSession;
import dev.omshutter.protocol.StarCaptureProtocol;
import dev.omshutter.protocol.StarCatalogFence;
import dev.omshutter.protocol.StarProtocol;
import dev.omshutter.protocol.StarStateMutationSession;
import dev.omshutter.solver.CameraGravityWindow;
import dev.omshutter.solver.CameraAttitudeEstimator;
import dev.omshutter.solver.ObserverCoordinateContext;
import dev.omshutter.solver.CorrectionAttitude;
import dev.omshutter.solver.CorrectionScale;
import dev.omshutter.solver.CorrectionSourceSelector;
import dev.omshutter.solver.IbisPrepositionPlanner;
import dev.omshutter.solver.NativeTetra3Engine;
import dev.omshutter.solver.ObserverLocationFix;
import dev.omshutter.solver.PlateSolverCoordinator;
import dev.omshutter.solver.PlateSolverSettings;
import dev.omshutter.solver.SiderealRateCorrection;
import dev.omshutter.solver.SolvedTrackingModel;
import dev.omshutter.solver.SolveResult;
import dev.omshutter.solver.SolveSnapshot;
import dev.omshutter.solver.StarSolveRequestAdapter;
import dev.omshutter.solver.TrackingExposureEstimator;
import dev.omshutter.storage.AstroSettingsStore;
import dev.omshutter.storage.CameraCredentials;
import dev.omshutter.storage.CaptureRecoveryStore;
import dev.omshutter.storage.PlateSolverSettingsStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Owns the scanner, BluetoothGatt, Olympus transaction queue, logical shutter state,
 * reconnect policy, and foreground notification. It deliberately outlives the activity.
 */
public final class CameraConnectionService extends Service {
    private static final String TAG = "OmShutterService";

    public static final String ACTION_CONNECT = "dev.omshutter.action.CONNECT";
    public static final String ACTION_DISCONNECT = "dev.omshutter.action.DISCONNECT";
    public static final String ACTION_TAKE_PHOTO = "dev.omshutter.action.TAKE_PHOTO";
    public static final String ACTION_RELEASE_ALL = "dev.omshutter.action.RELEASE_ALL";

    private static final String NOTIFICATION_CHANNEL = "camera_connection";
    private static final int NOTIFICATION_ID = 4101;
    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    private static final long DISCOVERY_TIMEOUT_MS = 10_000L;
    private static final long DESCRIPTOR_TIMEOUT_MS = 5_000L;
    private static final long SHUTTER_TIMEOUT_MS = 3_000L;
    private static final long SETUP_TIMEOUT_MS = 10_000L;
    private static final long RESULT_REPLY_TIMEOUT_MS = 2_000L;
    private static final long STAR_JOB_TIMEOUT_MS = 180_000L;
    private static final long STAR_SETTINGS_NOTIFICATION_TIMEOUT_MS = 2_000L;
    // Covers strict catalog resolution, three 4-second setting settle windows,
    // authoritative verification, and the camera's bounded rollback path.
    private static final long STAR_SETTINGS_CONFIRM_DEADLINE_MS = 50_000L;
    private static final long STAR_SETTINGS_READ_BACKOFF_INITIAL_MS = 250L;
    private static final long STAR_SETTINGS_READ_BACKOFF_MAX_MS = 2_000L;
    // The companion exposes STAR before its camera worker has necessarily published the cold
    // catalog/STATE snapshot. ATT 0x0e is therefore transient for that one read-only
    // initialization batch. Keep the retry window fixed and bounded; no mutation is replayed.
    private static final long STAR_INITIAL_READ_RETRY_WINDOW_MS = 12_000L;
    private static final long STAR_INITIAL_READ_RETRY_INITIAL_MS = 100L;
    private static final long STAR_INITIAL_READ_RETRY_MAX_MS = 1_000L;
    private static final String STAR_STATE_INVALID_DETAIL =
            "Camera sent invalid settings state";
    private static final String STAR_STATE_INCOMPLETE_DETAIL =
            "STAR mutable state is temporarily unavailable";
    private static final double SOLVE_FOCAL_RELATIVE_TOLERANCE = 0.05;
    private static final int KEEP_AWAKE_LEASE_SECONDS = 45;
    private static final long KEEP_AWAKE_RENEWAL_MS = 15_000L;
    private static final long ACTIVITY_HIDDEN_GRACE_MS = 1_000L;
    // A possibly held shutter justifies urgent wakeful recovery, but not an all-night lock when
    // the camera is unreachable. The durable release marker survives after this window expires.
    private static final long SHUTTER_RECOVERY_URGENT_WAKE_MS = 120_000L;
    // Run scan/GATT teardown while the bounded safety lock is still guaranteed to be held.
    private static final long SHUTTER_RECOVERY_CLEANUP_LEAD_MS = 1_500L;
    private static final int STAR_TERMINAL_RATE_CANCEL_ATTEMPTS = 3;
    // Exact STAR ATT errors returned before begin_capture_current() is called.
    private static final int STAR_ATT_INVALID_HANDLE = 0x01;
    private static final int STAR_ATT_REQUEST_NOT_SUPPORTED = 0x06;
    private static final int STAR_ATT_INVALID_OFFSET = 0x07;
    private static final int STAR_ATT_INVALID_ATTRIBUTE_LENGTH = 0x0d;
    private static final int STAR_ATT_UNLIKELY_ERROR = 0x0e;
    private static final int STAR_ATT_VALUE_NOT_ALLOWED = 0x13;
    private static final int STAR_ATT_BUSY = 0x80;
    private static final int STAR_ATT_CCCD_IMPROPERLY_CONFIGURED = 0xfd;
    // Covers one bounded interactive-foreground or urgent safety-recovery scan/connect/setup
    // attempt. Ordinary hidden reconnect backoff never owns this lock.
    private static final long CONNECTION_WAKE_LOCK_MS = 90_000L;
    private static final long GRACEFUL_DISCONNECT_TIMEOUT_MS = 10_000L;
    private static final long[] RECONNECT_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    public enum Phase {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING,
        AUTHENTICATING,
        ENTERING_REMOTE,
        READY,
        RECONNECTING,
        WAITING_FOR_BLUETOOTH,
        ERROR
    }

    public interface Listener {
        void onStateChanged(Snapshot snapshot);
        void onCameraFound(CameraCandidate camera);
    }

    public interface StarListener {
        void onStarJobChanged(StarCaptureSession.Snapshot snapshot);
    }

    /** Structured state rendered in both Shutter and Star Tracker tabs. */
    public static final class AstroSnapshot {
        public final boolean correctionEnabled;
        public final int factorPercent;
        public final boolean manualOverride;
        public final String manualRa;
        public final String manualDec;
        public final boolean solvedAttitudeAvailable;
        public final SolveResult lastSolvedResult;
        public final long lastSolvedIntentId;
        public final long lastSolvedDurationNanos;
        /** Monotonic acceptance time of the retained successful solve, or -1 if unavailable. */
        public final long lastSolvedElapsedRealtimeNanos;
        /** Observer fix belonging to the retained successful solve, or null if unavailable. */
        public final ObserverCoordinateContext cameraObserverContext;
        public final CorrectionSourceSelector.Source correctionSource;
        public final SiderealRateCorrection desiredRate;
        public final SiderealRateCorrection acknowledgedRate;
        public final AstroRateSetpoint desiredRateSetpoint;
        public final AstroRateSetpoint acknowledgedRateSetpoint;
        public final boolean acknowledgedRateHealthy;
        public final long correctionGeneration;
        public final long activeSolveGeneration;
        public final long desiredSolveGeneration;
        public final long acknowledgedSolveGeneration;
        public final long acknowledgedCorrectionGeneration;
        public final long lastSolveIntentId;
        public final boolean lastSolveIntentAccepted;
        public final boolean solveBusy;
        public final String correctionStatus;
        public final String trackingModelStatus;
        public final long lastStarCaptureWallTimeMs;
        public final boolean starStateAvailable;
        public final StarTelemetry.DetectorConfig detectorConfig;
        public final boolean detectorConfigKnown;
        public final boolean detectorWritePending;
        public final String detectorStatus;
        public final PlateSolverSettings plateSolverSettings;
        public final String plateSolverSettingsStatus;
        public final boolean ibisTelemetryAvailable;
        public final StarTelemetry.IbisSample ibisSample;
        public final long ibisReceivedElapsedMs;
        public final float authoritativeFocalLengthMm;
        public final String authoritativeLensName;
        public final boolean cameraAccelerationAvailable;
        public final StarProtocol.Acceleration cameraAccelerationSample;
        public final long cameraAccelerationReceivedElapsedRealtimeNanos;
        public final CameraGravityWindow.Estimate cameraGravityEstimate;
        public final CameraAttitudeEstimator.Estimate cameraAttitudeEstimate;
        public final TrackingExposureEstimator.Estimate trackingExposureEstimate;
        public final boolean prepositionAvailable;
        public final boolean prepositionEnabled;
        public final String prepositionStatus;

        private AstroSnapshot(
                boolean correctionEnabled,
                int factorPercent,
                boolean manualOverride,
                String manualRa,
                String manualDec,
                boolean solvedAttitudeAvailable,
                SolveResult lastSolvedResult,
                long lastSolvedIntentId,
                long lastSolvedDurationNanos,
                long lastSolvedElapsedRealtimeNanos,
                ObserverCoordinateContext cameraObserverContext,
                CorrectionSourceSelector.Source correctionSource,
                SiderealRateCorrection desiredRate,
                SiderealRateCorrection acknowledgedRate,
                AstroRateSetpoint desiredRateSetpoint,
                AstroRateSetpoint acknowledgedRateSetpoint,
                boolean acknowledgedRateHealthy,
                long correctionGeneration,
                long activeSolveGeneration,
                long desiredSolveGeneration,
                long acknowledgedSolveGeneration,
                long acknowledgedCorrectionGeneration,
                long lastSolveIntentId,
                boolean lastSolveIntentAccepted,
                boolean solveBusy,
                String correctionStatus,
                String trackingModelStatus,
                long lastStarCaptureWallTimeMs,
                boolean starStateAvailable,
                StarTelemetry.DetectorConfig detectorConfig,
                boolean detectorConfigKnown,
                boolean detectorWritePending,
                String detectorStatus,
                PlateSolverSettings plateSolverSettings,
                String plateSolverSettingsStatus,
                boolean ibisTelemetryAvailable,
                StarTelemetry.IbisSample ibisSample,
                long ibisReceivedElapsedMs,
                float authoritativeFocalLengthMm,
                String authoritativeLensName,
                boolean cameraAccelerationAvailable,
                StarProtocol.Acceleration cameraAccelerationSample,
                long cameraAccelerationReceivedElapsedRealtimeNanos,
                CameraGravityWindow.Estimate cameraGravityEstimate,
                CameraAttitudeEstimator.Estimate cameraAttitudeEstimate,
                TrackingExposureEstimator.Estimate trackingExposureEstimate,
                boolean prepositionAvailable,
                boolean prepositionEnabled,
                String prepositionStatus) {
            this.correctionEnabled = correctionEnabled;
            this.factorPercent = factorPercent;
            this.manualOverride = manualOverride;
            this.manualRa = manualRa == null ? "" : manualRa;
            this.manualDec = manualDec == null ? "" : manualDec;
            this.solvedAttitudeAvailable = solvedAttitudeAvailable;
            this.lastSolvedResult = lastSolvedResult;
            this.lastSolvedIntentId = lastSolvedIntentId;
            this.lastSolvedDurationNanos = lastSolvedDurationNanos;
            this.lastSolvedElapsedRealtimeNanos = lastSolvedElapsedRealtimeNanos;
            this.cameraObserverContext = cameraObserverContext;
            this.correctionSource = correctionSource;
            this.desiredRate = desiredRate;
            this.acknowledgedRate = acknowledgedRate;
            this.desiredRateSetpoint = desiredRateSetpoint;
            this.acknowledgedRateSetpoint = acknowledgedRateSetpoint;
            this.acknowledgedRateHealthy = acknowledgedRateHealthy;
            this.correctionGeneration = correctionGeneration;
            this.activeSolveGeneration = activeSolveGeneration;
            this.desiredSolveGeneration = desiredSolveGeneration;
            this.acknowledgedSolveGeneration = acknowledgedSolveGeneration;
            this.acknowledgedCorrectionGeneration = acknowledgedCorrectionGeneration;
            this.lastSolveIntentId = lastSolveIntentId;
            this.lastSolveIntentAccepted = lastSolveIntentAccepted;
            this.solveBusy = solveBusy;
            this.correctionStatus = correctionStatus == null ? "" : correctionStatus;
            this.trackingModelStatus = trackingModelStatus;
            this.lastStarCaptureWallTimeMs = lastStarCaptureWallTimeMs;
            this.starStateAvailable = starStateAvailable;
            this.detectorConfig = detectorConfig;
            this.detectorConfigKnown = detectorConfigKnown;
            this.detectorWritePending = detectorWritePending;
            this.detectorStatus = detectorStatus == null ? "" : detectorStatus;
            this.plateSolverSettings = plateSolverSettings == null
                    ? PlateSolverSettings.defaults() : plateSolverSettings;
            this.plateSolverSettingsStatus = plateSolverSettingsStatus == null
                    ? "" : plateSolverSettingsStatus;
            this.ibisTelemetryAvailable = ibisTelemetryAvailable;
            this.ibisSample = ibisSample;
            this.ibisReceivedElapsedMs = ibisReceivedElapsedMs;
            this.authoritativeFocalLengthMm = authoritativeFocalLengthMm;
            this.authoritativeLensName = authoritativeLensName == null
                    ? "" : authoritativeLensName;
            this.cameraAccelerationAvailable = cameraAccelerationAvailable;
            this.cameraAccelerationSample = cameraAccelerationSample;
            this.cameraAccelerationReceivedElapsedRealtimeNanos =
                    cameraAccelerationReceivedElapsedRealtimeNanos;
            this.cameraGravityEstimate = cameraGravityEstimate;
            this.cameraAttitudeEstimate = cameraAttitudeEstimate;
            this.trackingExposureEstimate = trackingExposureEstimate == null
                    ? TrackingExposureEstimator.Estimate.unavailable()
                    : trackingExposureEstimate;
            this.prepositionAvailable = prepositionAvailable;
            this.prepositionEnabled = prepositionEnabled;
            this.prepositionStatus = prepositionStatus == null ? "" : prepositionStatus;
        }

        private static AstroSnapshot initial() {
            return new AstroSnapshot(
                    true, CorrectionScale.DEFAULT_PERCENT, false, "", "", false, null,
                    -1L, SolveSnapshot.DURATION_UNAVAILABLE_NANOS, -1L, null,
                    null, null, null, null, null, false, -1L, -1L, -1L, -1L, -1L,
                    -1L, false, false, "", "", 0L,
                    false, StarTelemetry.DEFAULT_CONFIG, false, false, "",
                    PlateSolverSettings.defaults(), "",
                    false, null, 0L,
                    Float.NaN, "",
                    false, null, -1L,
                    null, null,
                    TrackingExposureEstimator.Estimate.unavailable(), false, false, "");
        }
    }

    public static final class Snapshot {
        public final Phase phase;
        public final String status;
        public final String detail;
        public final String cameraName;
        public final boolean desiredConnected;
        public final boolean scanning;
        public final boolean ready;
        public final boolean busy;
        public final boolean stockMutationAllowed;
        public final boolean halfHeld;
        public final boolean shutterHeld;
        public final boolean shutterPressOverHalf;
        public final boolean starAvailable;
        public final boolean astroRateAvailable;
        public final boolean astroRateActive;
        public final String astroRateDetail;
        public final AstroSnapshot astro;
        public final boolean settingsAvailable;
        public final boolean settingsWritePending;
        public final boolean settingsReadyForShutter;
        public final boolean settingsReadyForCapture;
        public final ExposureCatalogs exposureCatalogs;
        public final ExposureCatalogs.Selection appliedSelection;
        public final ExposureCatalogs.Selection desiredSelection;
        public final ExposureCatalogs.Selection starRecipeSelection;
        public final ExposureCatalogs.Selection desiredStarRecipeSelection;
        public final boolean starRecipeWritePending;
        public final long lastCameraSettingsIntentId;
        public final boolean lastCameraSettingsIntentAccepted;
        public final long lastStarRecipeIntentId;
        public final boolean lastStarRecipeIntentAccepted;
        public final CameraExposureState cameraState;
        public final int connectionGeneration;
        public final int movieState;
        public final long shutterHeldSinceElapsedMs;

        private Snapshot(
                Phase phase,
                String status,
                String detail,
                String cameraName,
                boolean desiredConnected,
                boolean scanning,
                boolean ready,
                boolean busy,
                boolean stockMutationAllowed,
                boolean halfHeld,
                boolean shutterHeld,
                boolean shutterPressOverHalf,
                boolean starAvailable,
                boolean astroRateAvailable,
                boolean astroRateActive,
                String astroRateDetail,
                AstroSnapshot astro,
                boolean settingsAvailable,
                boolean settingsWritePending,
                boolean settingsReadyForShutter,
                boolean settingsReadyForCapture,
                ExposureCatalogs exposureCatalogs,
                ExposureCatalogs.Selection appliedSelection,
                ExposureCatalogs.Selection desiredSelection,
                ExposureCatalogs.Selection starRecipeSelection,
                ExposureCatalogs.Selection desiredStarRecipeSelection,
                boolean starRecipeWritePending,
                long lastCameraSettingsIntentId,
                boolean lastCameraSettingsIntentAccepted,
                long lastStarRecipeIntentId,
                boolean lastStarRecipeIntentAccepted,
                CameraExposureState cameraState,
                int connectionGeneration,
                int movieState,
                long shutterHeldSinceElapsedMs) {
            this.phase = phase;
            this.status = status;
            this.detail = detail;
            this.cameraName = cameraName;
            this.desiredConnected = desiredConnected;
            this.scanning = scanning;
            this.ready = ready;
            this.busy = busy;
            this.stockMutationAllowed = stockMutationAllowed;
            this.halfHeld = halfHeld;
            this.shutterHeld = shutterHeld;
            this.shutterPressOverHalf = shutterPressOverHalf;
            this.starAvailable = starAvailable;
            this.astroRateAvailable = astroRateAvailable;
            this.astroRateActive = astroRateActive;
            this.astroRateDetail = astroRateDetail == null ? "" : astroRateDetail;
            this.astro = astro == null ? AstroSnapshot.initial() : astro;
            this.settingsAvailable = settingsAvailable;
            this.settingsWritePending = settingsWritePending;
            this.settingsReadyForShutter = settingsReadyForShutter;
            this.settingsReadyForCapture = settingsReadyForCapture;
            this.exposureCatalogs = exposureCatalogs;
            this.appliedSelection = appliedSelection;
            this.desiredSelection = desiredSelection;
            this.starRecipeSelection = starRecipeSelection;
            this.desiredStarRecipeSelection = desiredStarRecipeSelection;
            this.starRecipeWritePending = starRecipeWritePending;
            this.lastCameraSettingsIntentId = lastCameraSettingsIntentId;
            this.lastCameraSettingsIntentAccepted = lastCameraSettingsIntentAccepted;
            this.lastStarRecipeIntentId = lastStarRecipeIntentId;
            this.lastStarRecipeIntentAccepted = lastStarRecipeIntentAccepted;
            this.cameraState = cameraState;
            this.connectionGeneration = connectionGeneration;
            this.movieState = movieState;
            this.shutterHeldSinceElapsedMs = shutterHeldSinceElapsedMs;
        }
    }

    public static final class CameraCandidate {
        public final String name;
        public final String address;
        public final int rssi;

        private CameraCandidate(String name, String address, int rssi) {
            this.name = name == null || name.isEmpty() ? "OM camera" : name;
            this.address = address;
            this.rssi = rssi;
        }

        @Override
        public String toString() {
            return name + "  (" + rssi + " dBm)";
        }
    }

    public final class LocalBinder extends Binder {
        public CameraConnectionService getService() {
            return CameraConnectionService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final AtomicLong nextExposureIntentId = new AtomicLong();
    private final AtomicLong nextSolveIntentId = new AtomicLong();
    private final AtomicLong lastIssuedCameraSettingsIntentId = new AtomicLong();
    private final AtomicLong lastIssuedStarRecipeIntentId = new AtomicLong();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Set<StarListener> starListeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Deque<Command> commandQueue = new ArrayDeque<>();
    private final Set<String> scanResultsReported = new HashSet<>();

    private HandlerThread bluetoothThread;
    private Handler bluetoothHandler;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic resultCharacteristic;
    private BluetoothGattCharacteristic eventCharacteristic;
    private BluetoothGattCharacteristic starControlCharacteristic;
    private BluetoothGattCharacteristic starStateCharacteristic;
    private boolean gyroAttitudeSupported;
    private final CameraGyroTracker cameraGyroTracker = new CameraGyroTracker();
    private final CameraAttitudeEstimator cameraAttitudeEstimator = new CameraAttitudeEstimator();
    private CameraGyroTracker.Capture solvedGyroCapture;
    private CameraGyroTracker.Capture verifiedGyroCapture;
    private CameraAttitudeEstimator.Capture solvedGyroToken;
    private ObserverCoordinateContext cameraObserverContext;
    private long cameraObserverIntentId = -1L;
    private long cameraAttitudeAnchoredIntentId = -1L;
    private long cameraAttitudeBaselineGeneration = -1L;
    private boolean gyroReadInFlight;
    private long gyroReadStartedNanos;
    private long gyroReadCaptureGeneration = -1L;
    private BluetoothGattCharacteristic starShutterCatalogCharacteristic;
    private BluetoothGattCharacteristic starApertureCatalogCharacteristic;
    private BluetoothGattCharacteristic starIsoCatalogCharacteristic;
    private BluetoothGattCharacteristic astroRateCharacteristic;
    private BluetoothGattCharacteristic ibisPrepositionCharacteristic;
    private AstroRateTransport astroRateTransport;
    private BluetoothGattCharacteristic keepAwakeLeaseCharacteristic;
    private BluetoothGattDescriptor expectedDescriptor;
    private List<BluetoothGattCharacteristic> subscriptionCharacteristics = Collections.emptyList();
    private ScanCallback scanCallback;
    private CameraCredentials credentials;
    private AstroSettingsStore astroSettings;
    private PlateSolverSettingsStore plateSolverSettingsStore;
    private CaptureRecoveryStore captureRecoveryStore;
    private final CaptureRestoreBarrier captureRestoreBarrier =
            new CaptureRestoreBarrier();
    private CameraCredentials.SavedCamera configuredCamera;
    private PowerManager powerManager;
    private PowerManager.WakeLock transactionWakeLock;
    private long wakeLockDeadlineElapsedMs;

    private volatile Snapshot snapshot = new Snapshot(
        Phase.DISCONNECTED, "Disconnected", "", "", false,
            false, false, false, false, false, false, false, false,
            false, false, "", AstroSnapshot.initial(), false, false, false, false,
            null, null, null, null, null,
            false, 0L, false, 0L, false, null, 0, -1, 0);
    private Phase phase = Phase.DISCONNECTED;
    private String status = "Disconnected";
    private String detail = "";
    private boolean desiredConnected;
    private boolean foreground;
    private boolean activityVisiblyForeground;
    private boolean screenInteractive;
    private ActivityVisibilityHandoff activityVisibilityHandoff;
    private boolean reconnectDeferredUntilForeground;
    private volatile boolean backgroundStateRefreshRequired;
    private volatile boolean dropIdleStateNotifications;
    private boolean backgroundCorrectionResumeAwaitingState;
    // Main-thread-owned key for the last NotificationManager/startForeground publication.
    private ForegroundNotificationState foregroundNotificationState;
    private boolean scanForConnection;
    private boolean scanRunning;
    private int scanGeneration;
    private int connectionEpoch;
    private final AtomicInteger latestStartId = new AtomicInteger();
    private int activeConnectionStartId;
    private int disconnectStopStartId;
    private int subscriptionIndex;
    private int reconnectAttempt;
    private boolean connectionAttemptUrgentRecovery;
    private int nextRequestSequence = 1;
    private int lastCompletedResultToken = -1;
    private int recoveryReleaseAction;
    private long recoveryUrgentUntilEpochMs;
    private long recoveryUrgentUntilElapsedMs;
    private int recoveryUrgentBootCount = -1;
    private final ShutterRecoveryPowerWindow shutterRecoveryPowerWindow =
            new ShutterRecoveryPowerWindow(SHUTTER_RECOVERY_URGENT_WAKE_MS);
    private boolean starTransportReady;
    private boolean awaitingStarMtu;
    private int starNegotiatedMtu = 23;
    private boolean halfHeld;
    private boolean halfPressRequested;
    private boolean halfReleaseQueued;
    private boolean fullPressRequested;
    private boolean fullHeld;
    private boolean fullPressOverHalf;
    private boolean releaseQueued;
    private boolean releaseHalfAfterFullRelease;
    private boolean disconnectRequested;
    private long fullHeldSinceElapsed;
    private int movieState = -1; // -1 unknown, 0 locally inferred stopped, 1 locally inferred recording.

    private Pending pending;
    private WritePurpose writePurpose = WritePurpose.NONE;
    private Runnable phaseTimeout;
    private Runnable transactionTimeout;
    private Runnable reconnectRunnable;
    private Runnable shutterRecoveryDeadlineRunnable;
    private Runnable disconnectTimeout;
    private Runnable starJobTimeout;
    private Runnable starSettingsTimeout;
    private Runnable starInitializationReadRetryRunnable;
    private boolean starInitializationDeferredUntilForeground;
    private AstroRateCadenceTimers astroRateCadenceTimers;
    private Runnable astroRateCaptureCancelRunnable;
    private boolean starControlWriteInFlight;
    private boolean starStateWriteInFlight;
    private byte[] starStateWritePending;
    private final AstroRateLease astroRateLease = new AstroRateLease();
    private boolean astroRateWriteInFlight;
    private long astroRateActiveWriteStartedElapsedMs = -1L;
    private final BackgroundCorrectionGate backgroundCorrectionGate =
            new BackgroundCorrectionGate();
    private Runnable pendingCaptureRateMutation;
    private boolean postTerminalRateMutationAwaitingState;
    private final AstroRateCaptureArming starCaptureRateArming =
            new AstroRateCaptureArming();
    private boolean starTerminalWaitingForRateRetirement;
    private int starTerminalRateCancelAttemptsRemaining;
    private int starTerminalRateRetirementEpoch;
    private long starTerminalRateRetirementSession = -1L;
    private String astroRateDetail = "";
    private boolean solveProcessing;
    private final DeferredStarSolveAdmission deferredStarSolveAdmission =
            new DeferredStarSolveAdmission();
    private final SolveCaptureTransaction solveCaptureTransaction =
            new SolveCaptureTransaction();
    private long nextCorrectionGeneration;
    private long correctionPublicationPendingGeneration = -1L;
    private long correctionGeneration = -1L;
    private long desiredSolveGeneration = -1L;
    private long acknowledgedSolveGeneration = -1L;
    private long acknowledgedCorrectionGeneration = -1L;
    private long lastSolveIntentId = -1L;
    private boolean lastSolveIntentAccepted;
    private boolean correctionEnabled = true;
    private int correctionFactorPercent = CorrectionScale.DEFAULT_PERCENT;
    // Optional experimental feature. Deliberately off on every service start.
    private boolean prepositionEnabled;
    private String prepositionStatus = "Extra actuator travel during tracking";
    private final IbisPrepositionCapture prepositionCapture = new IbisPrepositionCapture();
    private enum PrepositionWrite { NONE, STAR_STAGE, STOCK_APPLY, CANCEL }
    private PrepositionWrite prepositionPendingWrite = PrepositionWrite.NONE;
    private PrepositionWrite prepositionWriteInFlight = PrepositionWrite.NONE;
    private byte[] prepositionPendingValue;
    private IbisPrepositionPlanner.Plan prepositionPlan;
    private boolean starPrepositionRequested;
    private boolean starPrepositionPinned;
    private boolean prepositionedSolveNeedsCenteredReference;
    private long stockPrepositionExposureUntilMs;
    private double stockPrepositionExposureSeconds;
    private Runnable prepositionSettleRunnable;
    private Runnable prepositionExposureRunnable;
    private boolean manualOverride;
    private String manualRa = "";
    private String manualDec = "";
    private boolean manualDraftDirty;
    private SolveResult lastSolvedResult;
    private long lastSolvedIntentId = -1L;
    private long lastSolvedDurationNanos = SolveSnapshot.DURATION_UNAVAILABLE_NANOS;
    private long lastSolvedElapsedRealtimeNanos = -1L;
    private CorrectionSourceSelector.Source correctionSource;
    private SiderealRateCorrection desiredCorrection;
    private AstroRateSetpoint desiredRateSetpoint;
    private CorrectionAttitude desiredCorrectionAttitude;
    private SiderealRateCorrection acknowledgedCorrection;
    private AstroRateSetpoint acknowledgedRateSetpoint;
    private boolean acknowledgedCorrectionHealthy;
    private String correctionStatus = "";
    private long lastStarCaptureWallTimeMs;
    private StarTelemetry.DetectorConfig detectorConfig =
            StarTelemetry.DEFAULT_CONFIG;
    private boolean detectorConfigKnown;
    private StarTelemetry.DetectorConfig detectorConfigPending;
    private StarTelemetry.DetectorConfig detectorConfigInFlight;
    private boolean detectorConfigWriteInFlight;
    private String detectorStatus = "";
    private ForegroundLeaseScheduler foregroundLeaseScheduler;
    private Integer keepAwakeLeasePendingSeconds;
    private Runnable keepAwakeLeasePendingCompletion;
    private boolean keepAwakeLeaseWriteInFlight;
    private int keepAwakeLeaseInFlightSeconds;
    private Runnable keepAwakeLeaseInFlightCompletion;
    private PlateSolverSettings plateSolverSettings = PlateSolverSettings.defaults();
    private String plateSolverSettingsStatus = "";
    private StarTelemetry.IbisSample ibisSample;
    private long ibisReceivedElapsedMs;
    private final CameraAccelerationTracker cameraAccelerationTracker =
            new CameraAccelerationTracker();
    private final Deque<StarReadPurpose> starReadQueue = new ArrayDeque<>();
    private StarReadPurpose starReadInFlight;
    private boolean starReadInFlightSettingsVerification;
    private boolean starReadInFlightInitialization;
    private boolean starInitializationReadBatch;
    private boolean starFenceFreshConnectionBaseline;
    private boolean settingsVerificationReadRequired;
    private long starSettingsDeadlineElapsedMs;
    private long starSettingsReadBackoffMs;
    private StarCaptureSession starSession;
    private volatile StarCaptureSession.Snapshot starSnapshot;
    private PlateSolverCoordinator plateSolverCoordinator;
    private long starSolveGeneration = -1L;
    private long nextStarSolveGeneration;
    private StarTelemetry.IbisSample starCaptureStartQ10;
    private long starCaptureStartQ10Generation = -1L;
    private StarTelemetry.IbisSample starSolveReferenceQ10;
    private long starSolveReferenceQ10Generation = -1L;
    private RateSolveContext astroRateSolveContext;
    private ManualGravityContext pendingManualGravitySolveContext;
    private long lastAstroRateSolveGeneration = -1L;
    private long activeAcceptedSolveIntentId = -1L;
    private CorrectionAttitude solvedCorrectionReferenceAttitude;
    private StarTelemetry.IbisSample solvedCorrectionReferenceQ10;
    private double solvedCorrectionReferenceFocalMm = Double.NaN;
    private long solvedCorrectionReferenceGeneration = -1L;
    private SolvedTrackingModel solvedTrackingModel;
    private CorrectionAttitude captureRateReferenceAttitude;
    private StarTelemetry.IbisSample captureRateReferenceQ10;
    private double captureRateReferenceFocalMm = Double.NaN;
    private long captureRateReferenceGeneration = -1L;
    private SolvedTrackingModel captureTrackingModel;
    private String starSessionCameraIdentity = "";
    private StarStateMutationSession settingsSession;
    private StateMutationKind stateMutationKind = StateMutationKind.NONE;
    private final LatestExposureWrite persistentSettingsIntent = new LatestExposureWrite();
    private final LatestExposureWrite starRecipeIntent = new LatestExposureWrite();
    private final StarRecipeStartupReset starRecipeStartupReset =
            new StarRecipeStartupReset();
    // An automatic write with an ambiguous outcome may be verified after reconnect, but never
    // emitted a second time. This is intentionally process-scoped like the GATT mutation owner.
    private String suppressedStartupStarRecipeCameraIdentity = "";
    private final StarInitializationReadRetry starInitializationReadRetry =
            new StarInitializationReadRetry(
                    STAR_INITIAL_READ_RETRY_WINDOW_MS,
                    STAR_INITIAL_READ_RETRY_INITIAL_MS,
                    STAR_INITIAL_READ_RETRY_MAX_MS);
    private final StarInitializationRecoveryGate starInitializationRecoveryGate =
            new StarInitializationRecoveryGate();
    private ExposureCatalogs fullExposureCatalogs;
    private ExposureCatalogs exposureCatalogs;
    private ExposureCatalogs filteredCatalogSource;
    private String filteredApertureMin = "";
    private String filteredApertureMax = "";
    private List<ExposureCatalogs.Option> shutterCatalog;
    private List<ExposureCatalogs.Option> apertureCatalog;
    private List<ExposureCatalogs.Option> isoCatalog;
    private String lastMissingCatalogStateKey = "";
    private CameraExposureState cameraState;
    private StarProtocol.State starState;
    private final StarCatalogFence starCatalogFence = new StarCatalogFence();
    private long exposureCatalogGeneration;
    private boolean exposureCatalogRefreshRequired;
    private boolean apertureBoundsRefreshRequired;
    private long lastCameraSettingsIntentId;
    private boolean lastCameraSettingsIntentAccepted;
    private long lastStarRecipeIntentId;
    private boolean lastStarRecipeIntentAccepted;

    private final PlateSolverCoordinator.Listener astroRateSolveListener = solved -> {
        Handler handler = bluetoothHandler;
        if (handler != null) handler.post(() -> handleSolvedAstroRate(solved));
    };

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            bluetoothHandler.post(() -> handleAdapterState(state));
        }
    };

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (!Intent.ACTION_SCREEN_OFF.equals(action)
                    && !Intent.ACTION_SCREEN_ON.equals(action)) return;
            boolean announcedInteractive = Intent.ACTION_SCREEN_ON.equals(action);
            bluetoothHandler.post(
                    () -> handleScreenInteractiveChanged(announcedInteractive));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        plateSolverCoordinator = new PlateSolverCoordinator(
                () -> new NativeTetra3Engine(getApplicationContext()),
                mainHandler::post);
        credentials = new CameraCredentials(this);
        astroSettings = new AstroSettingsStore(this);
        plateSolverSettingsStore = new PlateSolverSettingsStore(this);
        captureRecoveryStore = new CaptureRecoveryStore(this);
        captureRestoreBarrier.restore(captureRecoveryStore.load());
        correctionEnabled = astroSettings.isCorrectionEnabled();
        correctionFactorPercent = astroSettings.getFactorPercent();
        manualOverride = astroSettings.isManualOverrideEnabled();
        manualRa = astroSettings.getManualRa();
        manualDec = astroSettings.getManualDec();
        manualDraftDirty = astroSettings.isManualDirty();
        plateSolverSettings = plateSolverSettingsStore.load();
        plateSolverSettingsStatus = "Local solver settings loaded";
        correctionStatus = correctionEnabled
                ? "Correction enabled; solve or apply to start"
                : "Correction disabled";
        desiredConnected = credentials.isDesiredConnected();
        recoveryReleaseAction = credentials.getPendingReleaseAction();
        restoreUrgentShutterRecoveryWindow();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        bluetoothThread = new HandlerThread("om-ble-engine");
        bluetoothThread.start();
        bluetoothHandler = new Handler(bluetoothThread.getLooper());
        scheduleUrgentShutterRecoveryDeadline();
        activityVisibilityHandoff = new ActivityVisibilityHandoff(
                new ActivityVisibilityHandoff.EventLoop() {
                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        bluetoothHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        bluetoothHandler.removeCallbacks(runnable);
                    }
                },
                this::setAppVisiblyForegroundInternal,
                ACTIVITY_HIDDEN_GRACE_MS);
        astroRateCadenceTimers = new AstroRateCadenceTimers(
                new AstroRateCadenceTimers.EventLoop() {
                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        bluetoothHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        bluetoothHandler.removeCallbacks(runnable);
                    }
                },
                AstroRateGattProtocol.APP_RENEWAL_MS);
        powerManager = getSystemService(PowerManager.class);
        screenInteractive = queryScreenInteractive();
        foregroundLeaseScheduler = new ForegroundLeaseScheduler(
                new ForegroundLeaseScheduler.EventLoop() {
                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        bluetoothHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        bluetoothHandler.removeCallbacks(runnable);
                    }
                },
                this::queueForegroundKeepAwakeRenewal,
                this::verifyForegroundKeepAwakeEligibility,
                this::queueBestEffortKeepAwakeCancel,
                KEEP_AWAKE_RENEWAL_MS);
        plateSolverCoordinator.addListener(astroRateSolveListener);

        if (powerManager != null) {
            transactionWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":ble-transaction");
            transactionWakeLock.setReferenceCounted(false);
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            // Bluetooth is a highly privileged framework sender, not this app's UID.
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, filter);
        }
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            // Screen state is a protected framework broadcast, not an app-local sender.
            registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, screenFilter);
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId.set(startId);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            bluetoothHandler.post(() -> disconnectByUser(startId));
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(action)) {
            if (!promoteToForeground()) return START_NOT_STICKY;
            // Desired-state changes share the BLE queue with Disconnect so the most
            // recently delivered intent wins even when intents arrive back-to-back.
            bluetoothHandler.post(() -> {
                activeConnectionStartId = startId;
                disconnectRequested = false;
                cancelDisconnectTimeout();
                desiredConnected = true;
                credentials.setDesiredConnected(true);
                beginConfiguredConnection(false);
            });
        } else if (ACTION_TAKE_PHOTO.equals(action)) {
            if (!promoteToForeground()) return START_NOT_STICKY;
            bluetoothHandler.post(() -> {
                if (desiredConnected) activeConnectionStartId = startId;
                if (phase == Phase.READY) {
                    if (backgroundCorrectionGate.isSuspended()
                            && correctionRequested()) {
                        correctionStatus =
                                "Correction paused for lock-screen shutter; open the app to rearm it";
                        astroRateDetail = correctionStatus;
                        publishCurrent();
                    }
                    takePhotoInternal();
                }
                else if (desiredConnected) {
                    activeConnectionStartId = startId;
                    beginConfiguredConnection(true);
                }
                else stopIdleStartedService(startId);
            });
        } else if (ACTION_RELEASE_ALL.equals(action)) {
            if (!promoteToForeground()) return START_NOT_STICKY;
            bluetoothHandler.post(() -> {
                if (desiredConnected) activeConnectionStartId = startId;
                if (phase == Phase.READY) {
                    releaseAllInternal();
                } else if (desiredConnected) {
                    activeConnectionStartId = startId;
                    // The operator explicitly retried a persisted safety release. Give that
                    // recovery a fresh, bounded wakeful window even if an earlier one expired.
                    restartUrgentShutterRecoveryWindow();
                    beginConfiguredConnection(true);
                }
                else stopIdleStartedService(startId);
            });
        } else if (intent == null && desiredConnected) {
            if (!promoteToForeground()) return START_NOT_STICKY;
            bluetoothHandler.post(() -> {
                activeConnectionStartId = startId;
                boolean urgentRecovery = ensureUrgentShutterRecoveryWindow();
                if (BackgroundPowerPolicy.deferReconnect(
                        isAppInteractiveVisible(), urgentRecovery)) {
                    deferReconnectUntilForeground(
                            "Background restart paused until the app is visible");
                } else {
                    beginConfiguredConnection(true);
                }
            });
        } else {
            // Decide on the serialized BLE queue: an ACTION_CONNECT immediately before this
            // unknown start may not have applied desiredConnected yet.
            boolean mayNeedStartedLifetime = desiredConnected
                    || credentials.isDesiredConnected()
                    || foreground;
            if (mayNeedStartedLifetime && !promoteToForeground()) return START_NOT_STICKY;
            bluetoothHandler.post(() -> handleUnknownStart(startId));
        }
        return (desiredConnected || foreground || ACTION_CONNECT.equals(action))
                ? START_STICKY : START_NOT_STICKY;
    }

    private void handleUnknownStart(int startId) {
        if (!desiredConnected) {
            stopIdleStartedService(startId);
            return;
        }
        activeConnectionStartId = startId;
        boolean coldTransport = phase == Phase.DISCONNECTED
                && gatt == null
                && !scanRunning
                && reconnectRunnable == null;
        if (!coldTransport) return;
        boolean urgentRecovery = ensureUrgentShutterRecoveryWindow();
        if (BackgroundPowerPolicy.deferReconnect(
                isAppInteractiveVisible(), urgentRecovery)) {
            deferReconnectUntilForeground(
                    shutterRecoveryRequired()
                            ? "Safe release is still pending—open the app to retry"
                            : "Background restart paused until the app is visible");
        } else {
            beginConfiguredConnection(true);
        }
    }

    private void stopIdleStartedService(int startId) {
        mainHandler.post(() -> {
            if (!stopSelfResult(startId)) return;
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
                foregroundNotificationState = null;
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        PlateSolverCoordinator solver = plateSolverCoordinator;
        plateSolverCoordinator = null;
        if (solver != null) {
            solver.removeListener(astroRateSolveListener);
            solver.close();
        }
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already gone.
        }
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already gone.
        }
        if (bluetoothHandler != null) {
            bluetoothHandler.post(() -> {
                if (activityVisibilityHandoff != null) activityVisibilityHandoff.cancel();
                cancelUrgentShutterRecoveryDeadline();
                stopScanInternal();
                closeGatt();
                cancelDisconnectTimeout();
                releaseWakeLock();
            });
        }
        if (bluetoothThread != null) bluetoothThread.quitSafely();
        super.onDestroy();
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        mainHandler.post(() -> listener.onStateChanged(snapshot));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public StarCaptureSession.Snapshot getStarSnapshot() {
        return starSnapshot;
    }

    public void addStarListener(StarListener listener) {
        if (listener == null) return;
        starListeners.add(listener);
        StarCaptureSession.Snapshot current = starSnapshot;
        if (current != null) mainHandler.post(() -> listener.onStarJobChanged(current));
    }

    public void removeStarListener(StarListener listener) {
        starListeners.remove(listener);
    }

    public void addPlateSolveListener(PlateSolverCoordinator.Listener listener) {
        if (listener == null) return;
        PlateSolverCoordinator solver = plateSolverCoordinator;
        if (solver != null) solver.addListener(listener);
    }

    public void removePlateSolveListener(PlateSolverCoordinator.Listener listener) {
        PlateSolverCoordinator solver = plateSolverCoordinator;
        if (solver != null) solver.removeListener(listener);
    }

    /** Starts a solve without a manual-gravity correction context. */
    public long requestSolveWithoutManualCorrection() {
        return requestSolveWithoutManualCorrection(false);
    }

    /** Starts a solve and optionally admits one stock-camera photo after it completes. */
    public long requestSolveWithoutManualCorrection(boolean captureAfterSolve) {
        long intentId = nextSolveIntentId.incrementAndGet();
        bluetoothHandler.post(() -> startStarDetectionInternal(
                intentId, null, captureAfterSolve));
        return intentId;
    }

    /**
     * Starts a solve with immutable manual-pointing inputs for an optional gravity correction.
     * The plate solve itself remains valid if those inputs later fail qualification.
     */
    public long requestSolve(
            String ra,
            String dec,
            long assertedElapsedRealtimeNanos,
            ObserverLocationFix location,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos) {
        return requestSolve(
                ra,
                dec,
                assertedElapsedRealtimeNanos,
                location,
                utcEpochSecond,
                utcNano,
                utcAnchorElapsedRealtimeNanos,
                false);
    }

    /** Starts a solve and optionally admits one stock-camera photo after it completes. */
    public long requestSolve(
            String ra,
            String dec,
            long assertedElapsedRealtimeNanos,
            ObserverLocationFix location,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos,
            boolean captureAfterSolve) {
        ManualGravityContext context = ManualGravityContext.capture(
                ra,
                dec,
                assertedElapsedRealtimeNanos,
                location,
                utcEpochSecond,
                utcNano,
                utcAnchorElapsedRealtimeNanos);
        long intentId = nextSolveIntentId.incrementAndGet();
        bluetoothHandler.post(() -> startStarDetectionInternal(
                intentId, context, captureAfterSolve));
        return intentId;
    }

    public void setCorrectionEnabled(boolean enabled) {
        bluetoothHandler.post(() -> setCorrectionEnabledInternal(enabled));
    }

    public void setCorrectionStrengthPercent(int percent) {
        CorrectionScale.requirePercent(percent);
        bluetoothHandler.post(() -> setCorrectionStrengthInternal(percent));
    }

    public void setManualOverrideEnabled(boolean enabled) {
        bluetoothHandler.post(() -> {
            if (prepositionTrajectoryPinned()) return;
            if (manualOverride == enabled) return;
            manualOverride = enabled;
            astroSettings.setManualOverrideEnabled(enabled);
            invalidatePendingCorrectionWorkflow("Correction source changed; solve or apply again");
            stopCorrectionForChangedIntent();
            publishCurrent();
        });
    }

    /** Saves the roll-free manual pointing draft used by gravity-derived correction. */
    public void saveManualDraft(String ra, String dec) {
        bluetoothHandler.post(() -> {
            manualRa = ra == null ? "" : ra;
            manualDec = dec == null ? "" : dec;
            manualDraftDirty = true;
            astroSettings.saveManualDraft(manualRa, manualDec, true);
            publishCurrent();
        });
    }

    /** Applies the last solved attitude without reading or mutating the manual draft. */
    public void applyCorrection() {
        bluetoothHandler.post(this::applySolvedCorrectionInternal);
    }

    /** Derives and applies a manual correction from current camera gravity. */
    public void applyManualGravityCorrection(
            String ra,
            String dec,
            long assertedElapsedRealtimeNanos,
            ObserverLocationFix location,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos) {
        ManualGravityContext context = ManualGravityContext.capture(
                ra,
                dec,
                assertedElapsedRealtimeNanos,
                location,
                utcEpochSecond,
                utcNano,
                utcAnchorElapsedRealtimeNanos);
        bluetoothHandler.post(() -> applyManualGravityCorrectionInternal(context));
    }

    public void stopCorrection() {
        bluetoothHandler.post(() -> stopCorrectionInternal("Stopping correction"));
    }

    public void setPrepositionEnabled(boolean enabled) {
        bluetoothHandler.post(() -> {
            if (prepositionTrajectoryPinned()) return;
            prepositionEnabled = enabled && ibisPrepositionCharacteristic != null;
            prepositionStatus = prepositionEnabled
                    ? "Extra actuator travel during tracking; fixed settling delay"
                    : "Pre-position IBIS is off";
            publishCurrent();
        });
    }

    public void commitDetectorConfig(StarTelemetry.DetectorConfig config) {
        if (config == null) throw new IllegalArgumentException("detector config is null");
        bluetoothHandler.post(() -> commitDetectorConfigInternal(config));
    }

    public void commitPlateSolverSettings(PlateSolverSettings settings) {
        if (settings == null) throw new IllegalArgumentException("plate-solver settings are null");
        bluetoothHandler.post(() -> commitPlateSolverSettingsInternal(settings));
    }

    public void restoreDefaultPlateSolverSettings() {
        bluetoothHandler.post(this::restoreDefaultPlateSolverSettingsInternal);
    }

    /** Persistently applies three exact camera-authored labels for stock Shutter mode. */
    public long setCameraSettings(ExposureCatalogs.Selection selection) {
        long intentId = nextExposureIntentId.incrementAndGet();
        lastIssuedCameraSettingsIntentId.set(intentId);
        bluetoothHandler.post(() -> setCameraSettingsInternal(selection, intentId));
        return intentId;
    }

    /** Commits the camera-owned temporary exposure recipe used by the next STAR capture. */
    public long setStarRecipe(ExposureCatalogs.Selection selection) {
        long intentId = nextExposureIntentId.incrementAndGet();
        lastIssuedStarRecipeIntentId.set(intentId);
        bluetoothHandler.post(() -> setStarRecipeInternal(selection, intentId));
        return intentId;
    }

    /** Latest Shutter intent allocated before asynchronous BLE-handler admission. */
    public long getLastIssuedCameraSettingsIntentId() {
        return lastIssuedCameraSettingsIntentId.get();
    }

    /** Latest STAR recipe intent allocated before asynchronous BLE-handler admission. */
    public long getLastIssuedStarRecipeIntentId() {
        return lastIssuedStarRecipeIntentId.get();
    }

    /** Refreshes the authoritative camera settings, focal length, and lens name. */
    public void refreshCameraState() {
        bluetoothHandler.post(this::requestStarInitializationReads);
    }

    /** Reconnects an idle stock session so Android rediscovers a newly registered STAR service. */
    public void refreshStarService() {
        bluetoothHandler.post(this::refreshStarServiceInternal);
    }

    public void startDiscovery() {
        bluetoothHandler.post(() -> startScanInternal(false));
    }

    public void stopDiscovery() {
        bluetoothHandler.post(() -> {
            if (!scanForConnection) {
                stopScanInternal();
                publish(Phase.DISCONNECTED, "Disconnected", "Discovery stopped");
            }
        });
    }

    public void halfPressDown() {
        bluetoothHandler.post(this::halfPressDownInternal);
    }

    public void halfPressUp() {
        bluetoothHandler.post(() -> {
            if (prepositionCapture.isPreparing()) {
                abortPreposition("Pre-positioned capture cancelled", true);
                pumpCommands();
            } else {
                halfPressUpInternal();
            }
        });
    }

    public void shutterDown() {
        bluetoothHandler.post(this::shutterDownInternal);
    }

    public void shutterUp() {
        bluetoothHandler.post(this::shutterUpInternal);
    }

    public void releaseAll() {
        bluetoothHandler.post(this::releaseAllInternal);
    }

    public void takePhoto() {
        bluetoothHandler.post(this::takePhotoInternal);
    }

    public void toggleMovie() {
        bluetoothHandler.post(this::toggleMovieInternal);
    }

    public void powerOn() {
        bluetoothHandler.post(this::powerOnInternal);
    }

    /** Debounces Activity handoff before applying interactive-foreground power policy. */
    public void setAppVisiblyForeground(Object activityOwner, boolean visiblyForeground) {
        bluetoothHandler.post(() -> {
            ActivityVisibilityHandoff handoff = activityVisibilityHandoff;
            if (handoff != null) handoff.setVisiblyForeground(activityOwner, visiblyForeground);
        });
    }

    public void disconnect() {
        int startId = latestStartId.get();
        bluetoothHandler.post(() -> disconnectByUser(startId));
    }

    private void setCorrectionEnabledInternal(boolean enabled) {
        if (correctionEnabled == enabled) return;
        correctionEnabled = enabled;
        astroSettings.setCorrectionEnabled(enabled);
        invalidatePendingCorrectionWorkflow(
                "Correction toggle changed; pending correction intent was cancelled");
        if (!enabled) {
            stopCorrectionInternal("Correction disabled; publishing exact stop");
        } else {
            correctionStatus = "Correction enabled; solve or apply to start";
            astroRateDetail = correctionStatus;
            publishCurrent();
        }
    }

    private void setCorrectionStrengthInternal(int percent) {
        if (prepositionTrajectoryPinned() && percent != 0) return;
        if (correctionFactorPercent == percent) return;
        CorrectionSourceSelector.Source sourceToReapply = correctionSource;
        CorrectionAttitude attitudeToReapply = desiredCorrectionAttitude;
        boolean hadActiveCorrectionIntent = desiredCorrection != null
                && sourceToReapply != null
                && attitudeToReapply != null
                && (backgroundCorrectionGate.isSuspended()
                    || hasActiveRateIntent(desiredRateSetpoint)
                    || astroRateLease.wantsSetpoint()
                    || astroRateLease.isPossiblyActive()
                    || astroRateLease.hasPendingWrite()
                    || astroRateLease.hasWriteInFlight());
        correctionFactorPercent = CorrectionScale.requirePercent(percent);
        astroSettings.setFactorPercent(percent);
        invalidatePendingCorrectionWorkflow(
                "Correction strength changed; pending correction intent was cancelled");
        if (percent == 0) {
            stopCorrectionInternal("Correction strength is 0×; publishing exact stop");
        } else if (correctionEnabled && hadActiveCorrectionIntent) {
            if (sourceToReapply == CorrectionSourceSelector.Source.MANUAL_GRAVITY) {
                stopCorrectionInternal(
                        "Correction strength changed; Apply again for current camera tilt");
            } else {
                publishCalculatedCorrection(sourceToReapply, attitudeToReapply, false);
            }
        } else {
            correctionStatus = "Strength set to " + percent / 100.0 + "×; solve or apply";
            publishCurrent();
        }
    }

    private void applyManualGravityCorrectionInternal(
            ManualGravityContext manualGravityContext) {
        if (prepositionTrajectoryPinned()) return;
        manualRa = manualGravityContext.getRightAscension();
        manualDec = manualGravityContext.getDeclination();
        manualDraftDirty = true;
        astroSettings.saveManualDraft(manualRa, manualDec, true);
        invalidatePendingCorrectionWorkflow("Manual Apply replaced the active solve workflow");
        if (!correctionEnabled) {
            correctionStatus = "Enable correction before applying it";
            publishCurrent();
            return;
        }
        if (correctionFactorPercent == 0) {
            stopCorrectionInternal("Correction strength is 0×; publishing exact stop");
            return;
        }

        long nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        ManualGravityContext.Derivation derived = manualGravityContext.derive(
                cameraAccelerationTracker.estimate(nowElapsedRealtimeNanos),
                nowElapsedRealtimeNanos);
        if (!derived.isValid()) {
            correctionStatus = "Correction not applied: " + derived.getFailure();
            publishCurrent();
            return;
        }
        CorrectionAttitude attitude = derived.getAttitude();
        manualRa = formatDraft(attitude.getRightAscensionDeg());
        manualDec = formatDraft(attitude.getDeclinationDeg());
        // The derived roll and observer location are intentionally ephemeral.
        astroSettings.saveManualDraft(manualRa, manualDec, true);
        publishCalculatedCorrection(
                CorrectionSourceSelector.Source.MANUAL_GRAVITY,
                attitude,
                false);
    }

    private void applySolvedCorrectionInternal() {
        if (prepositionTrajectoryPinned()) return;
        invalidatePendingCorrectionWorkflow("Apply replaced the active solve workflow");
        if (manualOverride) {
            correctionStatus = "Correction not applied: Manual context required";
            publishCurrent();
            return;
        }
        if (!correctionEnabled) {
            correctionStatus = "Enable correction before applying it";
            publishCurrent();
            return;
        }
        if (correctionFactorPercent == 0) {
            stopCorrectionInternal("Correction strength is 0×; publishing exact stop");
            return;
        }
        if (lastSolvedResult == null) {
            correctionStatus = "Correction not applied: Plate solution unavailable";
            publishCurrent();
            return;
        }
        CorrectionAttitude solveAttitude = solvedCorrectionReferenceAttitude;
        if (solveAttitude == null) {
            correctionStatus = "Correction not applied: Solve reference unavailable";
            publishCurrent();
            return;
        }
        publishCalculatedCorrection(
                CorrectionSourceSelector.Source.SOLVED,
                solveAttitude,
                false);
    }

    private void publishCalculatedCorrection(
            CorrectionSourceSelector.Source source,
            CorrectionAttitude attitude,
            boolean automaticSolve) {
        SolvedTrackingModel model = null;
        if (source == CorrectionSourceSelector.Source.SOLVED) {
            if (attitude == solvedCorrectionReferenceAttitude) model = solvedTrackingModel;
            else if (astroRateLease.isCaptureHeld() && attitude == captureRateReferenceAttitude) {
                model = captureTrackingModel;
            }
        }
        publishCalculatedCorrection(source, attitude, automaticSolve, model);
    }

    private void publishCalculatedCorrection(
            CorrectionSourceSelector.Source source,
            CorrectionAttitude attitude,
            boolean automaticSolve,
            SolvedTrackingModel model) {
        if (prepositionTrajectoryPinned()) {
            correctionStatus = "Correction held steady for the pre-positioned capture";
            publishCurrent();
            return;
        }
        if (astroRateLease.isCaptureRetiring()
                && !starTerminalWaitingForRateRetirement) {
            pendingCaptureRateMutation = null;
            correctionStatus = "Correction remains stopped until the active STAR capture ends";
            astroRateDetail = correctionStatus;
            publishCurrent();
            return;
        }
        if (starTerminalWaitingForRateRetirement || postTerminalRateMutationAwaitingState) {
            pendingCaptureRateMutation = () ->
                    publishCalculatedCorrection(source, attitude, automaticSolve, model);
            correctionStatus = "Correction update queued behind STAR correction retirement";
            astroRateDetail = correctionStatus;
            publishCurrent();
            return;
        }
        if (astroRateLease.isCaptureHeld()
                && (astroRateWriteInFlight
                    || astroRateLease.hasPendingWrite()
                    || !functionalGattIdleForAstroRateMutation())) {
            pendingCaptureRateMutation = () ->
                    publishCalculatedCorrection(source, attitude, automaticSolve, model);
            correctionStatus = "Correction update queued behind the current capture GATT write";
            astroRateDetail = correctionStatus;
            publishCurrent();
            return;
        }
        if (phase != Phase.READY
                || gatt == null
                || astroRateCharacteristic == null
                || astroRateTransport == null) {
            correctionStatus = "Astro Rate service is unavailable";
            if (automaticSolve) {
                solveProcessing = false;
                solveCaptureTransaction.cancel();
            }
            publishCurrent();
            return;
        }
        SiderealRateCorrection correction;
        AstroRateSetpoint setpoint;
        byte[] value;
        try {
            correction = model != null
                    ? model.commandRate(CorrectionScale.factor(correctionFactorPercent))
                    : SiderealRateCorrection.fromEquatorialAttitude(
                    attitude.getRightAscensionDeg(),
                    attitude.getDeclinationDeg(),
                    attitude.getRollDeg(),
                    CorrectionScale.factor(correctionFactorPercent));
            setpoint = AstroRateSetpoint.bodyOnly(correction);
            value = astroRateTransport.setpoint(
                    setpoint, AstroRateGattProtocol.APP_TTL_TICKS);
        } catch (IllegalArgumentException error) {
            correctionStatus = "Correction not applied: " + safeMessage(error);
            if (automaticSolve) {
                solveProcessing = false;
                solveCaptureTransaction.cancel();
            }
            publishCurrent();
            return;
        }

        long generation = nextCorrectionGeneration++;
        cancelAstroRateTimers();
        correctionGeneration = generation;
        desiredSolveGeneration = automaticSolve ? starSolveGeneration : -1L;
        correctionSource = source;
        desiredCorrectionAttitude = attitude;
        desiredCorrection = correction;
        desiredRateSetpoint = setpoint;
        acknowledgedCorrectionHealthy = false;
        if (shouldSuspendCorrectionForBackground()) {
            correctionPublicationPendingGeneration = -1L;
            suspendIdleCorrectionForBackground();
            return;
        }
        backgroundCorrectionGate.clear();
        correctionPublicationPendingGeneration = generation;
        if (astroRateLease.isCaptureHeld()) {
            astroRateLease.offerCaptureSetpoint(
                    generation, connectionEpoch, starSolveGeneration, setpoint, value);
        } else {
            astroRateLease.offerSetpoint(generation, connectionEpoch, setpoint, value);
        }
        correctionStatus = "Publishing calculated correction to Linux";
        astroRateDetail = correctionStatus;
        publishCurrent();
        pumpCommands();
    }

    private void stopCorrectionForChangedIntent() {
        if (backgroundCorrectionGate.isSuspended()
                || hasActiveRateIntent(desiredRateSetpoint)
                || astroRateLease.isPossiblyActive()
                || astroRateLease.hasPendingWrite()
                || astroRateLease.hasWriteInFlight()) {
            stopCorrectionInternal("Correction source changed; publishing exact stop");
        }
    }

    private void stopCorrectionInternal(String message) {
        if (starPrepositionRequested) failPrepositionCapture("Pre-positioning cancelled");
        else abortPreposition("Pre-positioning cancelled", true);
        invalidatePendingCorrectionWorkflow(message);
        cancelAstroRateTimers();
        backgroundCorrectionGate.clear();
        pendingCaptureRateMutation = null;
        correctionPublicationPendingGeneration = -1L;
        correctionGeneration = nextCorrectionGeneration++;
        correctionSource = null;
        desiredCorrectionAttitude = null;
        desiredCorrection = null;
        desiredRateSetpoint = null;
        acknowledgedCorrectionHealthy = false;
        desiredSolveGeneration = -1L;
        correctionStatus = message;
        astroRateDetail = message;
        if (astroRateLease.isCaptureRetiring()) {
            publishCurrent();
            return;
        }
        if (phase == Phase.READY
                && gatt != null
                && astroRateCharacteristic != null
                && astroRateTransport != null) {
            astroRateLease.forceCancel(connectionEpoch, astroRateTransport.cancel());
            publishCurrent();
            pumpCommands();
        } else {
            astroRateLease.reset();
            acknowledgedCorrection = null;
            acknowledgedRateSetpoint = null;
            acknowledgedCorrectionGeneration = -1L;
            acknowledgedSolveGeneration = -1L;
            correctionStatus = correctionEnabled
                    ? "Correction stopped locally; Astro Rate service is unavailable"
                    : "Correction disabled";
            publishCurrent();
        }
    }

    private void invalidatePendingCorrectionWorkflow(String reason) {
        rejectDeferredStarSolveAdmission();
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        pendingManualGravitySolveContext = null;
        if (astroRateSolveContext != null) {
            astroRateSolveContext = astroRateSolveContext.withAutomaticCorrection(false);
        }
        correctionPublicationPendingGeneration = -1L;
    }

    private boolean rejectDeferredStarSolveAdmission() {
        DeferredStarSolveAdmission.Request rejected = deferredStarSolveAdmission.reject();
        if (rejected == null) return false;
        lastSolveIntentId = rejected.intentId;
        lastSolveIntentAccepted = false;
        return true;
    }

    /** Consumes before the RATE completion path is allowed to re-arm the cadence timer. */
    private boolean admitDeferredStarSolveAtAcknowledgedRateBoundary() {
        DeferredStarSolveAdmission.Request request = deferredStarSolveAdmission.consume();
        if (request == null) return false;
        startStarDetectionInternal(
                request.intentId,
                request.manualGravityContext,
                request.captureAfterSolve);
        return lastSolveIntentId == request.intentId && lastSolveIntentAccepted;
    }

    private void commitDetectorConfigInternal(
            StarTelemetry.DetectorConfig config) {
        if (phase != Phase.READY || gatt == null || !starTransportReady
                || starState == null || fullExposureCatalogs == null) {
            detectorStatus = "STAR detector state is unavailable on this connection";
            publishCurrent();
            return;
        }
        if (!canCommitDetectorConfig()) {
            detectorStatus = "Wait until the camera and correction workflow are idle";
            publishCurrent();
            return;
        }
        detectorConfigPending = config;
        ExposureCatalogs.Selection live = currentCameraSelection();
        ExposureCatalogs.Selection recipe = starRecipeIntent.getAuthoritative();
        if (startUnifiedStateMutation(
                live, recipe, config, StateMutationKind.DETECTOR)) {
            detectorConfigInFlight = config;
            detectorConfigPending = null;
            detectorConfigWriteInFlight = true;
            detectorStatus = "Saving detector settings in complete STAR state";
            pumpCommands();
        } else {
            detectorConfigPending = null;
            detectorStatus = detail;
        }
        publishCurrent();
    }

    private void commitPlateSolverSettingsInternal(PlateSolverSettings settings) {
        if (!canCommitPlateSolverSettings()) {
            plateSolverSettingsStatus =
                    "Wait until the camera and correction workflow are idle";
            publishCurrent();
            return;
        }
        plateSolverSettings = settings;
        plateSolverSettingsStore.save(settings);
        plateSolverSettingsStatus = "Local solver settings saved";
        publishCurrent();
    }

    private void restoreDefaultPlateSolverSettingsInternal() {
        if (!canCommitPlateSolverSettings()) {
            plateSolverSettingsStatus =
                    "Wait until the camera and correction workflow are idle";
            publishCurrent();
            return;
        }
        plateSolverSettingsStore.reset();
        plateSolverSettings = PlateSolverSettings.defaults();
        plateSolverSettingsStatus = "Local solver defaults restored";
        publishCurrent();
    }

    private boolean canCommitPlateSolverSettings() {
        return !solveProcessing
                && !deferredStarSolveAdmission.isPending()
                && !solveCaptureTransaction.isPending()
                && !isStarSessionActive();
    }

    private boolean canCommitDetectorConfig() {
        return canAcceptControl()
                && !halfPressRequested && !halfHeld
                && !fullPressRequested && !fullHeld
                && !solveProcessing
                && !deferredStarSolveAdmission.isPending()
                && correctionPublicationPendingGeneration < 0L;
    }

    private void startStarDetectionInternal(
            long intentId,
            ManualGravityContext manualGravityContext,
            boolean captureAfterSolve) {
        if (prepositionTrajectoryPinned()
                || prepositionPendingWrite != PrepositionWrite.NONE
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || isStarSessionActive() || starTerminalWaitingForRateRetirement
                || solveProcessing
                || deferredStarSolveAdmission.isPending()
                || solveCaptureTransaction.isPending()
                || correctionPublicationPendingGeneration >= 0L
                || exposureWritePending()
                || detectorConfigWriteInFlight || detectorConfigPending != null) {
            lastSolveIntentId = intentId;
            lastSolveIntentAccepted = false;
            correctionStatus = "Solve request was not admitted because the camera is busy";
            publishCurrent();
            return;
        }
        if (!astroRateLease.isCaptureStartReady(connectionEpoch)) {
            if (astroRateLease.isCaptureStartWaitingForHeartbeat(connectionEpoch)
                    && deferredStarSolveAdmission.defer(
                            intentId, manualGravityContext, captureAfterSolve)) {
                // Stop future cadence. The already queued or in-flight exact/adaptive heartbeat
                // owns the next callback, which retries admission before cadence is re-armed.
                cancelAstroRateTimers();
                correctionStatus = "Waiting for the current correction renewal before solve";
                astroRateDetail = correctionStatus;
                publishCurrent();
                pumpCommands();
                return;
            }
            lastSolveIntentId = intentId;
            lastSolveIntentAccepted = false;
            correctionStatus = "Solve request was not admitted because the camera is busy";
            publishCurrent();
            return;
        }
        solveCaptureTransaction.begin(captureAfterSolve);
        lastSolveIntentId = intentId;
        lastSolveIntentAccepted = true;
        activeAcceptedSolveIntentId = intentId;
        solveProcessing = true;
        starSolveGeneration = -1L;
        pendingManualGravitySolveContext = manualOverride
                        && correctionEnabled
                        && correctionFactorPercent > 0
                ? manualGravityContext
                : null;
        // Preserve the active solved/Q10 reference for the slower adaptive updates that may be
        // intentionally serialized during this capture. The UI solve itself is still invalidated.
        if (correctionSource == CorrectionSourceSelector.Source.SOLVED
                && astroRateLease.isPossiblyActive()
                && solvedCorrectionReferenceAttitude != null
                && solvedCorrectionReferenceGeneration >= 0L) {
            captureRateReferenceAttitude = solvedCorrectionReferenceAttitude;
            captureRateReferenceQ10 = solvedCorrectionReferenceQ10;
            captureRateReferenceFocalMm = solvedCorrectionReferenceFocalMm;
            captureRateReferenceGeneration = correctionGeneration;
            captureTrackingModel = solvedTrackingModel;
        } else {
            clearCaptureRateReference();
        }
        // Clear the prior generation before replacing or publishing the STAR session, including
        // preflight failures, so the UI cannot pair a new failed capture with stale coordinates.
        invalidatePlateSolve("Plate solution cleared because a new STAR detection started");
        astroRateSolveContext = null;
        correctionPublicationPendingGeneration = -1L;
        desiredSolveGeneration = -1L;
        beginStarDetectionCapture();
    }

    private void beginStarDetectionCapture() {
        StarCaptureSession nextSession = new StarCaptureSession();
        if (!settingsReadyForCapture()) {
            solveCaptureTransaction.cancel();
            nextSession.transportFailed(
                    "Wait for camera settings and the STAR recipe to become ready before capture");
            starSession = nextSession;
            solveProcessing = false;
            pendingManualGravitySolveContext = null;
            correctionStatus = nextSession.snapshot().getDetail();
            publishStarSnapshot();
            publishCurrent();
            return;
        }
        if (!starTransportReady || !canAcceptControlBase()
                || pending != null || !commandQueue.isEmpty()
                || halfPressRequested || halfHeld || fullPressRequested || fullHeld) {
            solveCaptureTransaction.cancel();
            nextSession.transportFailed(starTransportReady
                    ? "Camera is not idle and ready for a STAR job"
                    : "STAR is unavailable on this connection");
            starSession = nextSession;
            solveProcessing = false;
            pendingManualGravitySolveContext = null;
            correctionStatus = nextSession.snapshot().getDetail();
            publishStarSnapshot();
            publishCurrent();
            return;
        }
        starSession = nextSession;
        starSolveGeneration = nextStarSolveGeneration++;
        prepositionedSolveNeedsCenteredReference = false;
        boolean holdingRate;
        try {
            holdingRate = astroRateLease.beginCaptureHold(
                    connectionEpoch, starSolveGeneration);
        } catch (IllegalStateException error) {
            solveCaptureTransaction.cancel();
            nextSession.transportFailed(
                    "Astro Rate changed before the STAR capture boundary");
            solveProcessing = false;
            pendingManualGravitySolveContext = null;
            correctionStatus = nextSession.snapshot().getDetail();
            publishStarSnapshot();
            publishCurrent();
            return;
        }
        starCaptureStartQ10Generation = starSolveGeneration;
        starCaptureStartQ10 = qualifiedQ10OrNull(ibisSample);
        solveCaptureTransaction.bindGeneration(starSolveGeneration);
        starSessionCameraIdentity = cameraIdentity(configuredCamera);
        publishStarSnapshot();
        acquireWakeLock(STAR_JOB_TIMEOUT_MS + 5_000L);
        scheduleStarJobTimeout();
        AstroRateCaptureArming.Decision armingDecision =
                starCaptureRateArming.begin(holdingRate);
        if (armingDecision == AstroRateCaptureArming.Decision.WAIT_FOR_ACTIVE_ACK) {
            // Refresh while the server is still IDLE and wait for its ATT ACK. CAPTURE_RECIPE then
            // starts with the complete TTL4095 budget instead of consuming a near-expiry lease.
            cancelAstroRateHeartbeat();
            if (!astroRateLease.queueRenewal(connectionEpoch)) {
                starCaptureRateArming.reset();
                starSession.transportFailed(
                        "Could not refresh Astro Rate before STAR capture");
                publishStarSnapshot();
                finishStarSessionLifecycle();
                return;
            }
            correctionStatus = "Refreshing the correction lease before STAR capture";
            astroRateDetail = correctionStatus;
            publishCurrent();
            pumpCommands();
            return;
        }
        correctionStatus = "Capturing stars for solve";
        astroRateDetail = correctionStatus;
        publishCurrent();
        writeStarCaptureRecipe();
    }

    private void finishStarCaptureRateArming(boolean rateActive) {
        correctionStatus = rateActive
                ? "Capturing stars while renewing against the retained focal"
                : "Capturing stars after correction was stopped";
        astroRateDetail = correctionStatus;
        publishCurrent();
        writeStarCaptureRecipe();
    }

    private void refreshStarServiceInternal() {
        if (!desiredConnected || phase != Phase.READY || gatt == null
                || starTransportReady
                || pending != null || !commandQueue.isEmpty()
                || writePurpose != WritePurpose.NONE || starControlWriteInFlight
                || starStateWriteInFlight || starStateWritePending != null
                || gyroReadInFlight || starReadInFlight != null || !starReadQueue.isEmpty()
                || exposureWritePending() || isStarSessionActive()
                || halfPressRequested || halfHeld || fullPressRequested || fullHeld) {
            return;
        }
        scheduleReconnect("Refreshing STAR service discovery");
    }

    private void beginConfiguredConnection(boolean reconnect) {
        if (!desiredConnected) return;
        reconnectDeferredUntilForeground = false;
        CameraCredentials.SavedCamera nextCamera = credentials.load();
        String nextIdentity = cameraIdentity(nextCamera);
        if (starSession != null
                && !starSessionCameraIdentity.isEmpty()
                && !starSessionCameraIdentity.equals(nextIdentity)) {
            clearStarSnapshotForCameraChange();
        }
        configuredCamera = nextCamera;
        if (!configuredCamera.isComplete()) {
            terminalError("Camera name or passcode is missing");
            return;
        }
        if (!hasConnectPermission()) {
            terminalError("Bluetooth connect permission is missing");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            connectionAttemptUrgentRecovery = false;
            releaseWakeLock();
            publish(Phase.WAITING_FOR_BLUETOOTH, "Waiting for Bluetooth", "Turn Bluetooth on to reconnect");
            return;
        }

        cancelReconnect();
        boolean interactiveVisible = isAppInteractiveVisible();
        boolean urgentRecovery = ensureUrgentShutterRecoveryWindow();
        connectionAttemptUrgentRecovery = !interactiveVisible && urgentRecovery;
        if (interactiveVisible) {
            acquireWakeLock(CONNECTION_WAKE_LOCK_MS);
        } else if (urgentRecovery) {
            acquireWakeLock(Math.min(
                    CONNECTION_WAKE_LOCK_MS, urgentShutterRecoveryRemainingMs()));
        } else {
            releaseWakeLock();
        }
        boolean periodicNameScan = reconnect && reconnectAttempt > 0 && reconnectAttempt % 3 == 0;
        if (!configuredCamera.address.isEmpty() && !periodicNameScan) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(configuredCamera.address);
                connectDevice(device, reconnect);
                return;
            } catch (IllegalArgumentException | SecurityException error) {
                Log.w(TAG, "Saved address is no longer usable; scanning by service/name", error);
            }
        }
        startScanInternal(true);
    }

    private void startScanInternal(boolean forConnection) {
        if (!forConnection && (desiredConnected || gatt != null)) return;
        if (!hasScanPermission() || (forConnection && !hasBackgroundScanPermission())) {
            if (forConnection) {
                terminalError(Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 30
                        ? "Background location permission is required to find the camera while locked"
                        : "Bluetooth scan permission is missing");
            }
            else publish(Phase.ERROR, "Permission required", "Grant Bluetooth scan permission");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            publish(Phase.WAITING_FOR_BLUETOOTH, "Bluetooth is off", "Turn Bluetooth on to scan");
            return;
        }
        stopScanInternal();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            publish(Phase.ERROR, "Scanner unavailable", "Android did not provide a BLE scanner");
            return;
        }

        scanForConnection = forConnection;
        scanRunning = true;
        scanResultsReported.clear();
        int generation = ++scanGeneration;
        publish(Phase.SCANNING, forConnection ? "Finding camera" : "Scanning", "Looking for the OM/Olympus BLE service");

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                bluetoothHandler.post(() -> handleScanResult(generation, result));
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    bluetoothHandler.post(() -> handleScanResult(generation, result));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                bluetoothHandler.post(() -> handleScanFailure(generation, errorCode));
            }
        };

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(OlympusProtocol.SERVICE_UUID))
                .build();
        BackgroundPowerPolicy.ScanMode powerMode = BackgroundPowerPolicy.scanMode(
                isAppInteractiveVisible(), urgentShutterRecoveryRequired());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(powerMode == BackgroundPowerPolicy.ScanMode.LOW_LATENCY
                        ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            bluetoothHandler.postDelayed(() -> handleScanTimeout(generation), SCAN_TIMEOUT_MS);
        } catch (SecurityException | IllegalStateException error) {
            scanRunning = false;
            publish(Phase.ERROR, "Could not scan", safeMessage(error));
            if (forConnection) scheduleReconnect("BLE scan could not start");
        }
    }

    private void handleScanResult(int generation, ScanResult result) {
        if (!scanRunning || generation != scanGeneration || result == null) return;
        BluetoothDevice device = result.getDevice();
        if (device == null) return;
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        try {
            if (name == null || name.isEmpty()) name = device.getName();
        } catch (SecurityException ignored) {
            // Name from the scan response is enough when CONNECT is not yet granted.
        }
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException error) {
            return;
        }
        CameraCandidate candidate = new CameraCandidate(name, address, result.getRssi());
        if (scanResultsReported.add(address)) {
            for (Listener listener : listeners) {
                CameraCandidate delivered = candidate;
                mainHandler.post(() -> listener.onCameraFound(delivered));
            }
        }

        if (!scanForConnection) return;
        configuredCamera = credentials.load();
        boolean addressMatches = !configuredCamera.address.isEmpty()
                && configuredCamera.address.equalsIgnoreCase(address);
        boolean nameMatches = !configuredCamera.name.isEmpty()
                && configuredCamera.name.equals(name);
        if (addressMatches || nameMatches) {
            stopScanInternal();
            credentials.updateAddress(address);
            configuredCamera = credentials.load();
            connectDevice(device, false);
        }
    }

    private void handleScanTimeout(int generation) {
        if (!scanRunning || generation != scanGeneration) return;
        boolean wasForConnection = scanForConnection;
        stopScanInternal();
        if (wasForConnection) scheduleReconnect("Camera was not advertising");
        else publish(Phase.DISCONNECTED, "Scan complete", "Select a camera or enter its Bluetooth name");
    }

    private void handleScanFailure(int generation, int errorCode) {
        if (generation != scanGeneration) return;
        boolean wasForConnection = scanForConnection;
        stopScanInternal();
        if (wasForConnection) scheduleReconnect("BLE scan failed (" + errorCode + ")");
        else publish(Phase.ERROR, "Scan failed", "Android BLE error " + errorCode);
    }

    private void stopScanInternal() {
        ++scanGeneration;
        if (scanRunning && scanner != null && scanCallback != null) {
            try {
                if (hasScanPermission()) scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException ignored) {
                // Scanner is already unavailable.
            }
        }
        scanRunning = false;
        scanCallback = null;
        scanner = null;
    }

    private void connectDevice(BluetoothDevice device, boolean autoConnect) {
        boolean urgentRecoveryAttempt = connectionAttemptUrgentRecovery;
        stopScanInternal();
        closeGatt();
        clearTransportState();
        // closeGatt() fences the previous transport, but this connect is the continuation of the
        // same bounded hidden safety attempt and must retain its wake-lock deadline.
        connectionAttemptUrgentRecovery = urgentRecoveryAttempt;
        int epoch = ++connectionEpoch;
        starInitializationRecoveryGate.resetForConnection(epoch);
        publish(Phase.CONNECTING,
                autoConnect ? "Reconnecting" : "Connecting",
                autoConnect ? "Waiting for the saved camera" : "Opening BLE connection");
        try {
            BluetoothGattCallback callback = new GattCallback(epoch);
            gatt = device.connectGatt(this, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                scheduleReconnect("Android could not create a GATT client");
                return;
            }
            schedulePhaseTimeout(epoch, CONNECT_TIMEOUT_MS, "Connection timed out");
        } catch (SecurityException | IllegalArgumentException error) {
            scheduleReconnect("Could not connect: " + safeMessage(error));
        }
    }

    private void handleConnectionState(int epoch, BluetoothGatt callbackGatt, int statusCode, int newState) {
        if (!isCurrent(epoch, callbackGatt)) return;
        cancelPhaseTimeout();
        if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
            publish(Phase.DISCOVERING, "Connected", "Discovering camera services");
            try {
                if (!callbackGatt.discoverServices()) {
                    scheduleReconnect("Service discovery could not start");
                    return;
                }
                schedulePhaseTimeout(epoch, DISCOVERY_TIMEOUT_MS, "Service discovery timed out");
            } catch (SecurityException error) {
                scheduleReconnect("Bluetooth permission was revoked");
            }
            return;
        }

        if (desiredConnected) {
            rememberReleaseForRecovery();
            scheduleReconnect("Camera disconnected (GATT " + statusCode + ")");
        } else {
            closeGatt();
            resetLogicalState();
            publish(Phase.DISCONNECTED, "Disconnected", "");
        }
    }

    private void handleServicesDiscovered(int epoch, BluetoothGatt callbackGatt, int statusCode) {
        if (!isCurrent(epoch, callbackGatt)) return;
        cancelPhaseTimeout();
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            scheduleReconnect("Service discovery failed (GATT " + statusCode + ")");
            return;
        }
        BluetoothGattService service = callbackGatt.getService(OlympusProtocol.SERVICE_UUID);
        if (service == null) {
            terminalError("The camera does not expose the Olympus BLE service");
            return;
        }
        commandCharacteristic = service.getCharacteristic(OlympusProtocol.COMMAND_UUID);
        resultCharacteristic = service.getCharacteristic(OlympusProtocol.RESULT_UUID);
        eventCharacteristic = service.getCharacteristic(OlympusProtocol.EVENT_UUID);
        if (commandCharacteristic == null || resultCharacteristic == null || eventCharacteristic == null) {
            terminalError("The camera BLE service is missing a required characteristic");
            return;
        }

        BluetoothGattService rateService =
                callbackGatt.getService(AstroRateGattProtocol.SERVICE_V2_UUID);
        astroRateCharacteristic = rateService == null ? null
                : rateService.getCharacteristic(AstroRateGattProtocol.RATE_V2_UUID);
        if (!hasProperties(
                astroRateCharacteristic, BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            terminalError("Camera is missing mandatory writable Astro Rate v2");
            return;
        }
        astroRateTransport = AstroRateTransport.V2;
        ibisPrepositionCharacteristic = rateService.getCharacteristic(
                IbisPrepositionProtocol.CHARACTERISTIC_UUID);
        if (!hasProperties(ibisPrepositionCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            ibisPrepositionCharacteristic = null;
            prepositionEnabled = false;
        }
        prepositionStatus = ibisPrepositionCharacteristic == null
                ? "Pre-position IBIS requires updated camera firmware"
                : "Extra actuator travel during tracking";

        BluetoothGattService starService = callbackGatt.getService(StarProtocol.SERVICE_UUID);
        if (starService == null) {
            terminalError("Camera is missing mandatory STAR");
            return;
        }
        starControlCharacteristic = starService.getCharacteristic(StarProtocol.CONTROL_UUID);
        starStateCharacteristic = starService.getCharacteristic(StarProtocol.STATE_UUID);
        starShutterCatalogCharacteristic =
                starService.getCharacteristic(StarProtocol.SHUTTER_CATALOG_UUID);
        starApertureCatalogCharacteristic =
                starService.getCharacteristic(StarProtocol.APERTURE_CATALOG_UUID);
        starIsoCatalogCharacteristic =
                starService.getCharacteristic(StarProtocol.ISO_CATALOG_UUID);
        keepAwakeLeaseCharacteristic = starService.getCharacteristic(StarProtocol.LEASE_UUID);
        if (!hasProperties(
                    starControlCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)
                || !hasProperties(
                    starStateCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_READ
                            | BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                || !hasProperties(
                    starShutterCatalogCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_READ)
                || !hasProperties(
                    starApertureCatalogCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_READ)
                || !hasProperties(
                    starIsoCatalogCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_READ)
                || !hasProperties(
                    keepAwakeLeaseCharacteristic,
                    BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            terminalError("Camera STAR profile is incomplete or has incompatible properties");
            return;
        }

        beginStarMtuNegotiation(epoch, callbackGatt);
    }

    private void beginStarMtuNegotiation(int epoch, BluetoothGatt callbackGatt) {
        publish(Phase.SUBSCRIBING, "Preparing STAR",
                "Negotiating the mandatory " + StarProtocol.MINIMUM_ATT_MTU
                        + "-byte Bluetooth transfer size");
        try {
            awaitingStarMtu = true;
            if (!callbackGatt.requestMtu(StarProtocol.PREFERRED_ATT_MTU)) {
                awaitingStarMtu = false;
                terminalError("Could not request the mandatory STAR MTU");
                return;
            }
            cancelPhaseTimeout();
            phaseTimeout = () -> {
                if (epoch != connectionEpoch || !awaitingStarMtu) return;
                awaitingStarMtu = false;
                terminalError("STAR MTU negotiation timed out");
            };
            bluetoothHandler.postDelayed(phaseTimeout, DESCRIPTOR_TIMEOUT_MS);
        } catch (SecurityException error) {
            awaitingStarMtu = false;
            terminalError("Bluetooth permission was revoked");
        }
    }

    private static boolean hasProperties(
            BluetoothGattCharacteristic characteristic, int required) {
        return characteristic != null
                && (characteristic.getProperties() & required) == required;
    }

    private void handleMtuChanged(
            int epoch, BluetoothGatt callbackGatt, int mtu, int statusCode) {
        if (!isCurrent(epoch, callbackGatt) || !awaitingStarMtu) return;
        awaitingStarMtu = false;
        cancelPhaseTimeout();
        if (statusCode == BluetoothGatt.GATT_SUCCESS
                && mtu >= StarProtocol.MINIMUM_ATT_MTU) {
            starNegotiatedMtu = mtu;
            starTransportReady = true;
        } else {
            terminalError("Camera did not negotiate the mandatory STAR MTU "
                    + StarProtocol.MINIMUM_ATT_MTU);
            return;
        }
        beginSubscriptions(epoch);
    }

    private void beginSubscriptions(int epoch) {
        subscriptionCharacteristics = new ArrayList<>();
        subscriptionCharacteristics.add(commandCharacteristic);
        subscriptionCharacteristics.add(resultCharacteristic);
        subscriptionCharacteristics.add(eventCharacteristic);
        if (starTransportReady) {
            // STATE comes first so no authoritative update can be missed while the
            // three read-only catalogs and initial STATE are fetched.
            subscriptionCharacteristics.add(starStateCharacteristic);
            subscriptionCharacteristics.add(starControlCharacteristic);
        }
        subscriptionIndex = 0;
        publish(Phase.SUBSCRIBING, "Preparing notifications",
                "Enabling camera channel 1 of " + subscriptionCharacteristics.size());
        subscribeNext(epoch);
    }

    private void subscribeNext(int epoch) {
        if (epoch != connectionEpoch || gatt == null) return;
        if (subscriptionIndex >= subscriptionCharacteristics.size()) {
            expectedDescriptor = null;
            onGattReady();
            return;
        }
        BluetoothGattCharacteristic characteristic = subscriptionCharacteristics.get(subscriptionIndex);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                StarProtocol.CLIENT_CONFIGURATION_UUID);
        if (descriptor == null) {
            if (subscriptionIndex >= 3) {
                terminalError("Mandatory STAR notification channel has no CCCD");
                return;
            }
            terminalError("A camera notification channel has no CCCD");
            return;
        }
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                if (subscriptionIndex >= 3) {
                    scheduleReconnect("Could not enable mandatory STAR notifications");
                    return;
                }
                scheduleReconnect("Could not enable camera notifications");
                return;
            }
            expectedDescriptor = descriptor;
            byte[] subscriptionValue = StarProtocol.CONTROL_UUID.equals(
                    characteristic.getUuid())
                    ? StarProtocol.controlSubscriptionValue()
                    : StarProtocol.stateSubscriptionValue();
            if (!GattCompat.writeDescriptor(gatt, descriptor, subscriptionValue)) {
                if (subscriptionIndex >= 3) {
                    scheduleReconnect("Could not write a mandatory STAR descriptor");
                    return;
                }
                scheduleReconnect("Could not write camera notification descriptor");
                return;
            }
            publish(Phase.SUBSCRIBING, "Preparing notifications",
                    "Enabling camera channel " + (subscriptionIndex + 1) + " of "
                            + subscriptionCharacteristics.size());
            schedulePhaseTimeout(
                    epoch, DESCRIPTOR_TIMEOUT_MS, "Notification setup timed out");
        } catch (SecurityException error) {
            terminalError("Bluetooth permission was revoked");
        }
    }

    private void handleDescriptorWrite(
            int epoch, BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int statusCode) {
        if (!isCurrent(epoch, callbackGatt) || descriptor != expectedDescriptor) return;
        cancelPhaseTimeout();
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            if (subscriptionIndex >= 3) {
                scheduleReconnect(
                        "Mandatory STAR notification setup failed (GATT "
                                + statusCode + ")");
                return;
            }
            scheduleReconnect("Notification setup failed (GATT " + statusCode + ")");
            return;
        }
        subscriptionIndex++;
        subscribeNext(epoch);
    }

    private void onGattReady() {
        configuredCamera = credentials.load();
        String connectedCameraIdentity = cameraIdentity(configuredCamera);
        captureRestoreBarrier.beginFreshConnection();
        boolean automaticWriteSuppressed =
                !suppressedStartupStarRecipeCameraIdentity.isEmpty()
                        && ("unknown".equals(suppressedStartupStarRecipeCameraIdentity)
                            || connectedCameraIdentity.equals(
                                    suppressedStartupStarRecipeCameraIdentity));
        starRecipeStartupReset.beginConnection(
                !automaticWriteSuppressed);
        nextRequestSequence = 1;
        lastCompletedResultToken = -1;
        starControlWriteInFlight = false;
        starStateWriteInFlight = false;
        starStateWritePending = null;
        starReadQueue.clear();
        starReadInFlight = null;
        starReadInFlightSettingsVerification = false;
        starReadInFlightInitialization = false;
        starInitializationReadBatch = false;
        resetStarInitializationReadRetry();
        starInitializationRecoveryGate.resetForConnection(connectionEpoch);
        settingsVerificationReadRequired = false;
        cancelStarSettingsTimeout();
        settingsSession = null;
        stateMutationKind = StateMutationKind.NONE;
        exposureCatalogs = null;
        fullExposureCatalogs = null;
        cameraState = null;
        starState = null;
        clearFilteredCatalogCache();
        shutterCatalog = null;
        apertureCatalog = null;
        isoCatalog = null;
        lastMissingCatalogStateKey = "";
        cameraState = null;
        starState = null;
        starCatalogFence.clear();
        commandQueue.clear();
        pending = null;
        writePurpose = WritePurpose.NONE;
        movieState = -1;
        if (recoveryReleaseAction == 0) {
            recoveryReleaseAction = credentials.getPendingReleaseAction();
        }
        publish(Phase.AUTHENTICATING, "Authenticating", "Sending the camera passcode");
        enqueue(new Command(
                "Authenticate",
                0x0c,
                0x02,
                configuredCamera.passcode.getBytes(StandardCharsets.UTF_8),
                SETUP_TIMEOUT_MS,
                (success, resultStatus, error) -> {
                    if (!success) {
                        // Transport/protocol failures are followed by the transaction
                        // engine's reconnect path. Only a valid camera rejection is
                        // terminal (normally an incorrect passcode).
                        if (resultStatus != 0) {
                            terminalError("Camera rejected the passcode (status " + resultStatus + ")");
                        }
                        return;
                    }
                    publish(Phase.ENTERING_REMOTE, "Preparing remote", "Selecting Bluetooth-only shutter mode");
                    enqueue(new Command(
                            "Enter remote mode", 0x1d, 0x01, new byte[]{0x01},
                            SETUP_TIMEOUT_MS,
                            (remoteSuccess, remoteStatus, remoteError) -> {
                                if (!remoteSuccess) {
                                    if (remoteStatus != 0) {
                                        terminalError("Camera rejected remote mode (status " + remoteStatus + ")");
                                    }
                                    return;
                                }
                                finishSessionSetup();
                            }));
                }));
    }

    private void finishSessionSetup() {
        if (recoveryReleaseAction != 0) {
            int action = recoveryReleaseAction;
            publish(Phase.ENTERING_REMOTE, "Restoring safe state", "Releasing a shutter state after reconnect");
            enqueue(shutterCommand(action, (success, resultStatus, error) -> {
                if (!success) {
                    if (resultStatus != 0) {
                        terminalError("Camera rejected the safe shutter release (status "
                                + resultStatus + ")");
                    }
                    return;
                }
                if (action == OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF) {
                    // Action 04 deliberately retains first-stage/AF. Release that too so a
                    // reconnect can never leave the camera logically half-pressed.
                    setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE);
                    enqueue(shutterCommand(OlympusProtocol.ACTION_HALF_RELEASE,
                            (halfSuccess, halfStatus, halfError) -> {
                                if (!halfSuccess) {
                                    if (halfStatus != 0) {
                                        terminalError("Camera rejected the AF release (status "
                                                + halfStatus + ")");
                                    }
                                    return;
                                }
                                setRecoveryReleaseAction(0);
                                resetLogicalState();
                                becomeReady("Recovered and ready");
                            }));
                } else {
                    setRecoveryReleaseAction(0);
                    resetLogicalState();
                    becomeReady("Recovered and ready");
                }
            }));
        } else {
            resetLogicalState();
            becomeReady("Camera remote is ready");
        }
    }

    private void becomeReady(String readyDetail) {
        reconnectAttempt = 0;
        releaseWakeLock();
        publish(Phase.READY, "Ready", readyDetail);
        if (starTransportReady) requestStarInitializationReads();
    }

    private boolean requestStarInitializationReads() {
        if (!starTransportReady || gatt == null) return false;
        if (!isAppInteractiveVisible() && !correctionCaptureCriticalWork()) {
            deferOrdinaryStarInitializationUntilForeground();
            detail = "Exposure choices will refresh when the app is visible";
            publishCurrent();
            releaseWakeLockIfIdle();
            return false;
        }
        if (exposureWritePending()
                || isStarSessionActive()
                || disconnectRequested
                || pending != null
                || !commandQueue.isEmpty()
                || writePurpose != WritePurpose.NONE
                || starControlWriteInFlight
                || gyroReadInFlight || starReadInFlight != null
                || !starReadQueue.isEmpty()
                || halfPressRequested
                || halfHeld
                || fullPressRequested
                || fullHeld) {
            exposureCatalogRefreshRequired = true;
            detail = "Exposure choices will refresh when the camera is idle";
            publishCurrent();
            return false;
        }
        starInitializationDeferredUntilForeground = false;
        cancelStarInitializationReadRetryCallback();
        starInitializationRecoveryGate.resetForConnection(connectionEpoch);
        if (!starInitializationRecoveryGate.beginFence(connectionEpoch)) return false;
        starInitializationReadBatch = true;
        exposureCatalogRefreshRequired = false;
        apertureBoundsRefreshRequired = false;
        exposureCatalogs = null;
        fullExposureCatalogs = null;
        cameraState = null;
        starState = null;
        clearFilteredCatalogCache();
        shutterCatalog = null;
        apertureCatalog = null;
        isoCatalog = null;
        settingsSession = null;
        stateMutationKind = StateMutationKind.NONE;
        starCatalogFence.clear();
        persistentSettingsIntent.clear();
        starRecipeIntent.clear();
        removeQueuedCatalogAndStateReads();
        starReadQueue.addLast(StarReadPurpose.SHUTTER_CATALOG);
        starReadQueue.addLast(StarReadPurpose.APERTURE_CATALOG);
        starReadQueue.addLast(StarReadPurpose.ISO_CATALOG);
        starFenceFreshConnectionBaseline =
                captureRestoreBarrier.canAcceptFreshConnectionBaseline();
        starReadQueue.addFirst(StarReadPurpose.CATALOG_FENCE_STATE_A);
        starReadQueue.addLast(StarReadPurpose.CATALOG_FENCE_STATE_B);
        detail = "Refreshing camera exposure choices";
        acquireWakeLock(5L * DESCRIPTOR_TIMEOUT_MS + 5_000L);
        publishCurrent();
        pumpCommands();
        return true;
    }

    private void deferOrdinaryStarInitializationUntilForeground() {
        starInitializationDeferredUntilForeground = true;
        exposureCatalogRefreshRequired = true;
        starInitializationReadBatch = false;
        resetStarInitializationReadRetry();
        removeQueuedCatalogAndStateReads();
        starCatalogFence.clear();
    }

    private void requestStarStateRead() {
        if (!starTransportReady || gatt == null) return;
        // Keep the post-terminal ordering obligation observable even if service discovery did not
        // publish STATE. The queued read enters the same bounded coherent-fence recovery as every
        // other nonterminal ordered read failure instead of leaving the correction latched forever.
        if (starStateCharacteristic == null && !postTerminalRateMutationAwaitingState) return;
        if (starInitializationReadRetryRunnable != null) return;
        // A post-write semantic barrier owns its verification read even when a prior capture
        // recovery obligation also exists. Completion of the full v6 mutation certifies the live
        // target; classifying this as a generic recovery read would consume the one-shot timer
        // without scheduling the next verification read.
        StarReadPurpose purpose = settingsVerificationReadRequired
                ? StarReadPurpose.STATE
                : postTerminalRateMutationAwaitingState
                    ? StarReadPurpose.POST_TERMINAL_STATE
                    : captureRestoreBarrier.isRecoveryRequired()
                        ? StarReadPurpose.CAPTURE_RESTORE_STATE
                        : StarReadPurpose.STATE;
        if (starReadInFlight != purpose && !starReadQueue.contains(purpose)) {
            // Specialized reads are deliberately distinct from an ordinary STATE owner. If a
            // pre-terminal STATE read is already in flight, the post-terminal owner queues behind.
            starReadQueue.addLast(purpose);
        }
        if (settingsVerificationReadRequired
                && postTerminalRateMutationAwaitingState
                && starReadInFlight != StarReadPurpose.POST_TERMINAL_STATE
                && !starReadQueue.contains(StarReadPurpose.POST_TERMINAL_STATE)) {
            // The settings read keeps its own confirmation deadline; the next explicitly owned
            // read closes the independent post-terminal ordering barrier.
            starReadQueue.addLast(StarReadPurpose.POST_TERMINAL_STATE);
        }
        acquireWakeLock(DESCRIPTOR_TIMEOUT_MS + 5_000L);
        publishCurrent();
        pumpCommands();
    }

    private void setCameraSettingsInternal(
            ExposureCatalogs.Selection selection, long intentId) {
        lastCameraSettingsIntentId = intentId;
        lastCameraSettingsIntentAccepted = false;
        if (selection == null) {
            detail = "Choose shutter, aperture, and ISO for Shutter mode";
            publishCurrent();
            return;
        }
        if (!starTransportReady || exposureCatalogs == null
                || !exposureCatalogs.contains(selection)) {
            detail = "Exposure choices changed; refreshing them from the camera";
            publishCurrent();
            requestStarInitializationReads();
            return;
        }
        if (persistentSettingsIntent.hasActive()) {
            if (!canCoalescePersistentSettingsIntent()) {
                detail = "Camera is busy; the Shutter setting change was not queued";
                publishCurrent();
                return;
            }
            persistentSettingsIntent.offer(
                    selection, connectionEpoch, exposureCatalogGeneration);
            lastCameraSettingsIntentAccepted = true;
            detail = selection.equals(persistentSettingsIntent.getActive())
                    ? "Keeping the active Shutter settings request"
                    : "Queued the latest Shutter settings choice";
            publishCurrent();
            return;
        }
        if (stateMutationInProgress()) {
            LatestExposureWrite.Offer queued = persistentSettingsIntent.offer(
                    selection, connectionEpoch, exposureCatalogGeneration);
            if (queued == LatestExposureWrite.Offer.STALE_GENERATION) {
                detail = "Exposure choices changed; refreshing them from the camera";
                publishCurrent();
                requestStarInitializationReads();
                return;
            }
            lastCameraSettingsIntentAccepted = true;
            detail = queued == LatestExposureWrite.Offer.SATISFIED
                    ? "Shutter settings already match the camera"
                    : "Queued the latest Shutter settings choice";
            publishCurrent();
            return;
        }
        if (!canStartExposureMutation()) {
            detail = "Camera is busy; settings were not sent";
            publishCurrent();
            return;
        }

        LatestExposureWrite.Offer offered = persistentSettingsIntent.offer(
                selection, connectionEpoch, exposureCatalogGeneration);
        if (offered == LatestExposureWrite.Offer.STALE_GENERATION) {
            detail = "Exposure choices changed; refreshing them from the camera";
            publishCurrent();
            requestStarInitializationReads();
            return;
        }
        if (offered == LatestExposureWrite.Offer.SATISFIED) {
            lastCameraSettingsIntentAccepted = true;
            detail = clearCaptureRestoreBarrierIfExact(selection)
                    ? "Shutter settings already match the camera"
                    : "Shutter settings match, but a different STAR recovery obligation remains";
            publishCurrent();
            return;
        }
        lastCameraSettingsIntentAccepted = true;
        startNextPersistentSettingsWrite();
    }

    private void startNextPersistentSettingsWrite() {
        ExposureCatalogs.Selection selection = persistentSettingsIntent.startNext(
                connectionEpoch, exposureCatalogGeneration);
        if (selection == null) return;
        if (exposureCatalogs == null || !exposureCatalogs.contains(selection)) {
            lastCameraSettingsIntentAccepted = false;
            persistentSettingsIntent.failToAuthoritative(
                    currentCameraSelection(), connectionEpoch, exposureCatalogGeneration);
            detail = "The selected Shutter settings are no longer available";
            requestStarInitializationReads();
            return;
        }

        ExposureCatalogs.Selection recipe = starRecipeIntent.getAuthoritative();
        if (!startUnifiedStateMutation(
                selection, recipe, detectorConfig, StateMutationKind.LIVE)) {
            lastCameraSettingsIntentAccepted = false;
            persistentSettingsIntent.failToAuthoritative(
                    currentCameraSelection(), connectionEpoch, exposureCatalogGeneration);
        } else {
            pumpCommands();
        }
    }

    private boolean startUnifiedStateMutation(
            ExposureCatalogs.Selection live,
            ExposureCatalogs.Selection recipe,
            StarTelemetry.DetectorConfig detector,
            StateMutationKind kind) {
        if (solveOrRateOwnsExposureMutationBoundary()
                || settingsSession != null
                || stateMutationKind != StateMutationKind.NONE
                || starStateWritePending != null
                || starStateWriteInFlight) {
            detail = "Another STAR state change is already in progress";
            publishCurrent();
            return false;
        }
        try {
            StarProtocol.MutationTarget target = StarProtocol.target(
                    starState, fullExposureCatalogs, live, recipe, detector);
            StarStateMutationSession next = new StarStateMutationSession();
            starStateWritePending = next.begin(target);
            settingsSession = next;
            stateMutationKind = kind;
            settingsVerificationReadRequired = false;
            cancelStarSettingsTimeout();
            detail = "Applying the complete STAR camera state";
            acquireWakeLock(
                    STAR_SETTINGS_CONFIRM_DEADLINE_MS + DESCRIPTOR_TIMEOUT_MS + 5_000L);
            publishCurrent();
            return true;
        } catch (IllegalArgumentException | IllegalStateException error) {
            detail = "STAR state target is invalid: " + safeMessage(error);
            publishCurrent();
            return false;
        }
    }

    private void setStarRecipeInternal(
            ExposureCatalogs.Selection selection, long intentId) {
        lastStarRecipeIntentId = intentId;
        lastStarRecipeIntentAccepted = false;
        if (selection == null) {
            detail = "Choose shutter, aperture, and ISO for STAR mode";
            publishCurrent();
            return;
        }
        if (!starTransportReady || exposureCatalogs == null
                || !exposureCatalogs.contains(selection)) {
            detail = "STAR recipe choices changed; refreshing them from the camera";
            publishCurrent();
            requestStarInitializationReads();
            return;
        }
        if (starRecipeIntent.hasActive()) {
            if (!canCoalesceStarRecipeIntent()) {
                detail = "Camera is busy; the STAR recipe change was not queued";
                publishCurrent();
                return;
            }
            starRecipeIntent.offer(selection, connectionEpoch, exposureCatalogGeneration);
            lastStarRecipeIntentAccepted = true;
            detail = selection.equals(starRecipeIntent.getActive())
                    ? "Keeping the active STAR recipe request"
                    : "Queued the latest STAR recipe choice";
            publishCurrent();
            return;
        }
        if (stateMutationInProgress()) {
            LatestExposureWrite.Offer queued = starRecipeIntent.offer(
                    selection, connectionEpoch, exposureCatalogGeneration);
            if (queued == LatestExposureWrite.Offer.STALE_GENERATION) {
                detail = "STAR recipe choices changed; refreshing them from the camera";
                publishCurrent();
                requestStarInitializationReads();
                return;
            }
            lastStarRecipeIntentAccepted = true;
            detail = queued == LatestExposureWrite.Offer.SATISFIED
                    ? "STAR recipe already matches the camera"
                    : "Queued the latest STAR recipe choice";
            publishCurrent();
            return;
        }
        if (!canStartExposureMutation()) {
            detail = "Camera is busy; the STAR recipe was not sent";
            publishCurrent();
            return;
        }
        LatestExposureWrite.Offer offered = starRecipeIntent.offer(
                selection, connectionEpoch, exposureCatalogGeneration);
        if (offered == LatestExposureWrite.Offer.STALE_GENERATION) {
            detail = "STAR recipe choices changed; refreshing them from the camera";
            publishCurrent();
            requestStarInitializationReads();
            return;
        }
        if (offered == LatestExposureWrite.Offer.SATISFIED) {
            lastStarRecipeIntentAccepted = true;
            detail = "STAR recipe already matches the camera";
            publishCurrent();
            return;
        }
        lastStarRecipeIntentAccepted = true;
        startNextStarRecipeWrite();
    }

    private void startNextStarRecipeWrite() {
        ExposureCatalogs.Selection selection = starRecipeIntent.startNext(
                connectionEpoch, exposureCatalogGeneration);
        if (selection == null) return;
        if (exposureCatalogs == null || !exposureCatalogs.contains(selection)) {
            lastStarRecipeIntentAccepted = false;
            starRecipeIntent.failToAuthoritative(
                    starRecipeIntent.getAuthoritative(),
                    connectionEpoch,
                    exposureCatalogGeneration);
            detail = "The selected STAR recipe is no longer available";
            failStartupStarRecipeResetIfWriting(detail);
            requestStarInitializationReads();
            return;
        }
        StateMutationKind kind = starRecipeStartupReset.isWriting()
                ? StateMutationKind.STARTUP_RECIPE : StateMutationKind.RECIPE;
        if (!startUnifiedStateMutation(
                currentCameraSelection(), selection, detectorConfig, kind)) {
            lastStarRecipeIntentAccepted = false;
            starRecipeIntent.failToAuthoritative(
                    starRecipeIntent.getAuthoritative(),
                    connectionEpoch,
                    exposureCatalogGeneration);
            failStartupStarRecipeResetIfWriting(detail);
        } else {
            pumpCommands();
        }
    }

    private boolean settingsWritePending() {
        return starStateWritePending != null
                || starStateWriteInFlight
                || settingsSession != null
                || persistentSettingsIntent.isPending()
                || detectorConfigPending != null;
    }

    private boolean starRecipeWritePending() {
        return stateMutationKind == StateMutationKind.RECIPE
                || stateMutationKind == StateMutationKind.STARTUP_RECIPE
                || starRecipeIntent.isPending();
    }

    private boolean exposureWritePending() {
        return settingsWritePending() || starRecipeWritePending();
    }

    private ExposureCatalogs.Selection appliedSelection() {
        return persistentSettingsIntent.getAuthoritative();
    }

    private boolean settingsReadyForShutter() {
        ExposureCatalogs.Selection desired = persistentSettingsIntent.getDesired();
        if (exposureCatalogs == null || cameraState == null || desired == null) {
            return false;
        }
        return !captureRestoreBarrier.isBlocking()
                && persistentSettingsIntent.isReady()
                && exposureCatalogs.contains(desired)
                && desired.matches(cameraState)
                && !starRecipeWritePending();
    }

    private boolean settingsReadyForCapture() {
        ExposureCatalogs.Selection recipe = starRecipeIntent.getAuthoritative();
        return settingsReadyForShutter()
                && starRecipeStartupReset.isReady()
                && starRecipeIntent.isReady()
                && recipe != null
                && exposureCatalogs.contains(recipe)
                && !exposureWritePending();
    }

    private ExposureCatalogs.Selection currentCameraSelection() {
        return cameraState == null ? null : ExposureCatalogs.Selection.from(cameraState);
    }

    private boolean armCaptureRestoreBarrier() {
        ExposureCatalogs.Selection selection = persistentSettingsIntent.getAuthoritative();
        String identity = cameraIdentity(configuredCamera);
        if (selection == null || identity.isEmpty() || captureRestoreBarrier.isBlocking()) {
            return false;
        }
        CaptureRecoveryStore.Obligation obligation =
                new CaptureRecoveryStore.Obligation(identity, selection);
        if (captureRecoveryStore == null || !captureRecoveryStore.arm(obligation)) {
            return false;
        }
        captureRestoreBarrier.arm(obligation);
        return true;
    }

    private void requireCaptureRestoreReadback() {
        captureRestoreBarrier.requirePostTerminalState();
    }

    private boolean clearCaptureRestoreBarrier() {
        if (!captureRestoreBarrier.isBlocking()) return true;
        if (captureRecoveryStore == null || !captureRecoveryStore.clear()) return false;
        captureRestoreBarrier.clear();
        return true;
    }

    private boolean clearCaptureRestoreBarrierIfExact(
            ExposureCatalogs.Selection confirmedSelection) {
        if (!captureRestoreBarrier.isBlocking()) return true;
        CaptureRestoreBarrier.Observation observation = captureRestoreBarrier.observe(
                cameraIdentity(configuredCamera), confirmedSelection);
        return observation == CaptureRestoreBarrier.Observation.MATCHED
                && clearCaptureRestoreBarrier();
    }

    private void observeCaptureRestoreState(
            CameraExposureState authoritative,
            boolean orderedPostTerminalRead,
            boolean freshConnectionBaseline,
            boolean coherentAuthoritativeState) {
        CaptureRestoreBarrier.Observation observation = freshConnectionBaseline
                ? captureRestoreBarrier.observeFreshConnectionState(
                        cameraIdentity(configuredCamera),
                        ExposureCatalogs.Selection.from(authoritative),
                        coherentAuthoritativeState)
                : captureRestoreBarrier.observePostTerminalState(
                        cameraIdentity(configuredCamera),
                        ExposureCatalogs.Selection.from(authoritative),
                        orderedPostTerminalRead && coherentAuthoritativeState);
        if (observation == CaptureRestoreBarrier.Observation.MATCHED
                || observation == CaptureRestoreBarrier.Observation.FRESH_BASELINE) {
            if (!clearCaptureRestoreBarrier()) solveCaptureTransaction.cancel();
        } else if (observation == CaptureRestoreBarrier.Observation.MISMATCH
                || observation == CaptureRestoreBarrier.Observation.BLOCKED_UNKNOWN) {
            // Never let a later generic refresh revive an old Solve + Capture intent.
            solveCaptureTransaction.cancel();
        }
    }

    private boolean canStartExposureMutation() {
        return desiredConnected
                && !prepositionTrajectoryPinned()
                && prepositionPendingWrite == PrepositionWrite.NONE
                && prepositionWriteInFlight == PrepositionWrite.NONE
                && phase == Phase.READY
                && starTransportReady
                && gatt != null
                && !disconnectRequested
                && !exposureCatalogRefreshRequired
                && !apertureBoundsRefreshRequired
                && !solveOrRateOwnsExposureMutationBoundary()
                && pending == null
                && commandQueue.isEmpty()
                && writePurpose == WritePurpose.NONE
                && !starControlWriteInFlight
                && !starStateWriteInFlight
                && starStateWritePending == null
                && settingsSession == null
                && stateMutationKind == StateMutationKind.NONE
                && !gyroReadInFlight && starReadInFlight == null
                && starReadQueue.isEmpty()
                && !exposureWritePending()
                && !isStarSessionActive()
                && !halfPressRequested
                && !halfHeld
                && !fullPressRequested
                && !fullHeld;
    }

    /** Prevents STATE mutation from stealing the quiet RATE boundary reserved for a solve. */
    private boolean solveOrRateOwnsExposureMutationBoundary() {
        return prepositionTrajectoryPinned()
                || prepositionPendingWrite != PrepositionWrite.NONE
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || exposureMutationBlockedBySolveOrRate(
                solveProcessing,
                deferredStarSolveAdmission.isPending(),
                solveCaptureTransaction.isPending(),
                astroRateWriteInFlight,
                astroRateLease.hasPendingWrite());
    }

    static boolean exposureMutationBlockedBySolveOrRate(
            boolean solveProcessing,
            boolean deferredSolve,
            boolean solveCapturePending,
            boolean rateWriteInFlight,
            boolean rateWritePending) {
        return solveProcessing
                || deferredSolve
                || solveCapturePending
                || rateWriteInFlight
                || rateWritePending;
    }

    private boolean canCoalescePersistentSettingsIntent() {
        return stateMutationInProgress()
                && persistentSettingsIntent.isCurrent(
                        connectionEpoch, exposureCatalogGeneration);
    }

    private boolean canCoalesceStarRecipeIntent() {
        return stateMutationInProgress()
                && starRecipeIntent.isCurrent(
                        connectionEpoch, exposureCatalogGeneration);
    }

    private boolean stateMutationInProgress() {
        if (settingsSession == null || stateMutationKind == StateMutationKind.NONE) {
            return false;
        }
        StarStateMutationSession.Snapshot current = settingsSession.snapshot();
        return phase == Phase.READY
                && !disconnectRequested
                && !current.isReady()
                && !current.isTerminalFailed();
    }

    private void writeStarCaptureRecipe() {
        if (prepositionEnabled && acknowledgedCorrectionHealthy
                && acknowledgedCorrection != null) {
            IbisPrepositionPlanner.Plan plan = planPreposition(
                    starRecipeIntent.getAuthoritative());
            if (plan == null || !plan.isAvailable()) {
                failPrepositionCapture("Pre-positioning needs a fixed exposure and current tracking rate");
                return;
            }
            prepositionPlan = plan;
            starPrepositionRequested = true;
            starPrepositionPinned = true;
            // The existing start sample precedes the worker-owned move. Neither it nor a
            // terminal sample measures the optical pose of the pre-positioned exposure.
            // Keep the new solved rate, but withhold adaptive Q10 adjustments for this solve.
            starSolveReferenceQ10 = null;
            starSolveReferenceQ10Generation = starSolveGeneration;
            prepositionedSolveNeedsCenteredReference = true;
            queuePrepositionWrite(PrepositionWrite.STAR_STAGE,
                    IbisPrepositionProtocol.ACTION_STAGE_STAR, plan);
            return;
        }
        writeStarCaptureRecipeAfterPreposition();
    }

    /** Sets a local test reference without altering the solve or camera correction. */
    public void zeroCameraAltAzForTesting(int connectionGeneration) {
        bluetoothHandler.post(() -> {
            if (connectionGeneration != connectionEpoch || phase != Phase.READY) return;
            if (cameraAttitudeEstimator.zeroForTesting(SystemClock.elapsedRealtimeNanos())) {
                // An observer fix arriving for the old solve must not undo the user's zero.
                // Accepting a new solve clears this fence and supplies its real horizon anchor.
                cameraAttitudeAnchoredIntentId = lastSolvedIntentId;
                if (Log.isLoggable("OmGyro", Log.DEBUG)) {
                    CameraGyroTracker.Sample current = cameraGyroTracker.getLatestSample();
                    logGyroDiagnostic("zero", current == null ? null : current.getSnapshot(),
                            SystemClock.elapsedRealtimeNanos());
                }
            }
            publishCurrent();
        });
    }

    /** Receives the same trusted observer fix used for the corresponding solved coordinates. */
    public void setCameraObserverContext(
            ObserverCoordinateContext context, long solveIntentId, int connectionGeneration) {
        if (context == null) return;
        bluetoothHandler.post(() -> {
            if (connectionGeneration != connectionEpoch || lastSolvedResult == null
                    || solveIntentId < 0L || solveIntentId != lastSolvedIntentId
                    || (cameraObserverContext == context
                        && cameraObserverIntentId == solveIntentId)) return;
            if (cameraObserverContext != null && cameraObserverIntentId == solveIntentId
                    && context.getLocationFixElapsedRealtimeNanos()
                        < cameraObserverContext.getLocationFixElapsedRealtimeNanos()) return;
            cameraObserverContext = context;
            cameraObserverIntentId = solveIntentId;
            updateSolvedTrackingObserver();
            if (cameraAttitudeEstimator.estimate(SystemClock.elapsedRealtimeNanos())
                    .getCaptureSequence() >= 0L) {
                // Refresh the real solve's horizon frame with its new observer fix. A test
                // zero carries capture sequence -1 and must retain its explicit anchor fence.
                cameraAttitudeAnchoredIntentId = -1L;
            }
            maybeAnchorCameraAttitude();
            publishCurrent();
        });
    }

    private boolean startCaptureAttitudeBaselineRead() {
        if (starStateCharacteristic == null || gatt == null || gyroReadInFlight) return false;
        gyroReadInFlight = true;
        gyroReadCaptureGeneration = starSolveGeneration;
        gyroReadStartedNanos = SystemClock.elapsedRealtimeNanos();
        try {
            if (!gatt.readCharacteristic(starStateCharacteristic)) {
                gyroReadInFlight = false;
                return false;
            }
        } catch (SecurityException error) {
            gyroReadInFlight = false;
            return false;
        }
        int epoch = connectionEpoch;
        cancelTransactionTimeout();
        transactionTimeout = () -> {
            if (epoch != connectionEpoch || !gyroReadInFlight) return;
            transactionTimeout = null;
            gyroReadInFlight = false;
            // Retiring an unanswered ATT operation requires reconnect: issuing the capture
            // here would overlap an operation whose ownership is still unknown.
            scheduleReconnect("Capture attitude STATE read timed out");
        };
        bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
        return true;
    }

    private void handleGyroAttitudeRead(
            byte[] value, int statusCode, long receivedElapsedRealtimeNanos) {
        if (!gyroReadInFlight) return;
        cancelTransactionTimeout();
        long captureGeneration = gyroReadCaptureGeneration;
        gyroReadInFlight = false;
        if (statusCode == BluetoothGatt.GATT_SUCCESS && value != null) {
            try {
                StarProtocol.State state = StarProtocol.parseState(value);
                GyroAttitudeProtocol.Snapshot sample = state.getGyroAttitude();
                if (sample != null && captureGeneration == starSolveGeneration) {
                    cameraGyroTracker.beginCapture(captureGeneration, sample,
                            gyroReadStartedNanos, receivedElapsedRealtimeNanos);
                }
                acceptStarTelemetry(state, receivedElapsedRealtimeNanos);
            } catch (StarProtocol.ProtocolException | IllegalArgumentException error) {
                Log.w(TAG, "Ignoring invalid capture attitude STATE read", error);
            }
        }
        if (captureGeneration == starSolveGeneration
                && isStarSessionActive()) {
            writeStarCaptureRecipeAfterPreposition();
        } else {
            pumpCommands();
        }
        publishCurrent();
    }

    private void acceptGyroAttitude(
            GyroAttitudeProtocol.Snapshot sample, long receivedElapsedRealtimeNanos) {
        if (sample == null) {
            cameraAttitudeEstimator.pause();
            logGyroDiagnostic("state", null, receivedElapsedRealtimeNanos);
            return;
        }
        cameraGyroTracker.accept(sample, receivedElapsedRealtimeNanos);
        if (verifiedGyroCapture == null && starSession != null
                && starSession.snapshot().isTerminal() && starSession.snapshot().isSuccessful()) {
            verifiedGyroCapture = cameraGyroTracker.confirmCapture(
                    starSolveGeneration, sample, false);
        }
        if (!sample.isCurrentValid()) {
            cameraAttitudeEstimator.pause();
            logGyroDiagnostic("state", sample, receivedElapsedRealtimeNanos);
            return;
        }
        cameraAttitudeEstimator.accept(sample.getTick(),
                sample.getCurrentQuaternion(), receivedElapsedRealtimeNanos);
        updateSolvedGyroCapture();
        logGyroDiagnostic("state", sample, receivedElapsedRealtimeNanos);
    }

    // Enable with: adb shell setprop log.tag.OmGyro DEBUG
    private void logGyroDiagnostic(String event, GyroAttitudeProtocol.Snapshot sample,
            long receivedNanos) {
        if (!Log.isLoggable("OmGyro", Log.DEBUG)) return;
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        CameraAttitudeEstimator.Estimate estimate = cameraAttitudeEstimator.estimate(nowNanos);
        double[] q = sample == null ? null : sample.getCurrentQuaternion();
        int[] q30 = sample == null ? null : sample.getCurrentQ30();
        StringBuilder line = new StringBuilder(400).append("event=").append(event)
                .append(" rx_ns=").append(receivedNanos).append(" now_ns=").append(nowNanos)
                .append(" tick=").append(sample == null ? -1L : sample.getTick())
                .append(" flags=").append(sample == null ? -1 : sample.getFlags());
        String[] axes = {"w", "x", "y", "z"};
        for (int axis = 0; axis < axes.length; axis++) {
            line.append(" q").append(axes[axis]).append('=').append(q == null ? Double.NaN : q[axis]);
            line.append(" q30_").append(axes[axis]).append('=')
                    .append(q30 == null ? "null" : Integer.toString(q30[axis]));
        }
        line.append(" est_tick=").append(estimate.getTick())
                .append(" est_state=").append(estimate.getState())
                .append(" alt_deg=").append(estimate.getAltitudeDeg())
                .append(" az_deg=").append(estimate.getAzimuthDeg())
                .append(" roll_deg=").append(estimate.getRollDeg());
        StarProtocol.Acceleration acceleration = cameraAccelerationTracker.getLatestSample();
        if (acceleration != null) {
            line.append(" accel_flags=").append(acceleration.getFlags())
                    .append(" accel_rx_ns=")
                    .append(cameraAccelerationTracker.getLatestReceiptElapsedRealtimeNanos())
                    .append(" ax_mg=").append(acceleration.getXMillig())
                    .append(" ay_mg=").append(acceleration.getYMillig())
                    .append(" az_mg=").append(acceleration.getZMillig());
        }
        Log.d("OmGyro", line.toString());
    }

    private void updateSolvedGyroCapture() {
        if (lastSolvedResult == null) return;
        if (solvedGyroCapture == null) {
            solvedGyroCapture = verifiedGyroCapture != null
                    && verifiedGyroCapture.getSolveGeneration() == lastAstroRateSolveGeneration
                    ? verifiedGyroCapture : null;
            if (solvedGyroCapture != null) {
                solvedGyroToken = cameraAttitudeEstimator.capture(
                        solvedGyroCapture.getSequence(),
                        solvedGyroCapture.getTick(), solvedGyroCapture.getQuaternion());
            }
        }
        maybeAnchorCameraAttitude();
        updateSolvedTrackingObserver();
    }

    private void updateSolvedTrackingObserver() {
        if (solvedTrackingModel == null || solvedGyroCapture == null
                || cameraObserverContext == null || cameraObserverIntentId != lastSolvedIntentId) {
            return;
        }
        try {
            solvedTrackingModel = solvedTrackingModel.withObserver(cameraObserverContext,
                    solvedGyroCapture.getElapsedRealtimeNanos());
        } catch (IllegalArgumentException | ArithmeticException error) {
            Log.w(TAG, "Refraction reference unavailable for this capture", error);
        }
    }

    private String trackingModelStatus() {
        SolvedTrackingModel model = astroRateLease.isCaptureHeld()
                ? captureTrackingModel : solvedTrackingModel;
        if (model == null || correctionSource != CorrectionSourceSelector.Source.SOLVED) return "";
        return model.refractionStatus() + (prepositionedSolveNeedsCenteredReference
                ? ". Adaptive geometry needs a solve with pre-positioning off." : "");
    }

    private void maybeAnchorCameraAttitude() {
        if (lastSolvedResult == null || solvedGyroCapture == null || solvedGyroToken == null
                || cameraObserverContext == null || cameraObserverIntentId != lastSolvedIntentId
                || cameraAttitudeAnchoredIntentId == lastSolvedIntentId) return;
        try {
            ObserverCoordinateContext.UtcInstant utc = cameraObserverContext.utcAtCaptureElapsed(
                    solvedGyroCapture.getElapsedRealtimeNanos());
            if (cameraAttitudeEstimator.anchor(lastSolvedResult, solvedGyroToken,
                    cameraObserverContext.getLatitudeDeg(),
                    cameraObserverContext.getLongitudeEastDeg(),
                    utc.getEpochSecond(), utc.getNano())) {
                cameraAttitudeAnchoredIntentId = lastSolvedIntentId;
            }
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            // No trustworthy capture UTC means no horizon anchor.
        }
    }

    private void clearCameraAttitude() {
        verifiedGyroCapture = null;
        cameraGyroTracker.clear();
        cameraAttitudeEstimator.clear();
        solvedGyroCapture = null;
        solvedGyroToken = null;
        cameraObserverContext = null;
        cameraObserverIntentId = -1L;
        cameraAttitudeAnchoredIntentId = -1L;
        cameraAttitudeBaselineGeneration = -1L;
        gyroReadInFlight = false;
    }

    private void writeStarCaptureRecipeAfterPreposition() {
        if (gyroAttitudeSupported
                && cameraAttitudeBaselineGeneration != starSolveGeneration
                && starSession != null && isStarSessionActive()) {
            cameraAttitudeBaselineGeneration = starSolveGeneration;
            if (startCaptureAttitudeBaselineRead()) return;
        }
        if (starSession == null || !isStarSessionActive()
                || starControlCharacteristic == null || gatt == null) return;
        byte[] request;
        try {
            request = starSession.beginCapture(starRecipeIntent.getAuthoritative());
        } catch (IllegalArgumentException | IllegalStateException error) {
            starSession.transportFailed(error.getMessage());
            publishStarSnapshot();
            finishStarSessionLifecycle();
            return;
        }
        if (!armCaptureRestoreBarrier()) {
            starSession.transportFailed(
                    "Could not persist the pre-capture camera settings; capture was not sent");
            publishStarSnapshot();
            finishStarSessionLifecycle();
            return;
        }
        starControlWriteInFlight = true;
        try {
            if (!GattCompat.writeCharacteristic(gatt, starControlCharacteristic, request)) {
                starControlWriteInFlight = false;
                if (!clearCaptureRestoreBarrier()) requireCaptureRestoreReadback();
                starSession.transportFailed("GATT rejected the unsent CAPTURE_RECIPE write");
                publishStarSnapshot();
                finishStarSessionLifecycle();
                return;
            }
            int epoch = connectionEpoch;
            cancelTransactionTimeout();
            transactionTimeout = () -> {
                if (epoch != connectionEpoch || !starControlWriteInFlight) return;
                transactionTimeout = null;
                starControlWriteInFlight = false;
                requireCaptureRestoreReadback();
                if (starSession != null && !starSession.snapshot().isTerminal()) {
                    starSession.transportFailed(
                            "CAPTURE_RECIPE write callback timed out; request will not be retried");
                    publishStarSnapshot();
                }
                failAmbiguousStarCapture("CAPTURE_RECIPE write callback timed out");
            };
            bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
            publishCurrent();
        } catch (SecurityException error) {
            starControlWriteInFlight = false;
            requireCaptureRestoreReadback();
            starSession.transportFailed("Bluetooth permission was revoked after CAPTURE_RECIPE was attempted");
            publishStarSnapshot();
            failAmbiguousStarCapture("Bluetooth permission was revoked during STAR capture");
        }
    }

    private boolean isStarSessionActive() {
        return starSession != null && !starSession.snapshot().isTerminal();
    }

    private void disconnectStarSession() {
        invalidatePlateSolve("Plate solution cleared because the camera connection changed");
        cancelStarJobTimeout();
        requireCaptureRestoreReadback();
        if (starSession != null) {
            starSession.transportFailed(
                    "Camera disconnected; CAPTURE_RECIPE will not be retried");
            publishStarSnapshot();
        }
        solveCaptureTransaction.cancel();
        publishCurrent();
    }

    private void publishStarSnapshot() {
        if (starSession == null) return;
        StarCaptureSession.Snapshot next = starSession.snapshot();
        starSnapshot = next;
        maybeSubmitPlateSolve(next);
        for (StarListener listener : starListeners) {
            mainHandler.post(() -> listener.onStarJobChanged(next));
        }
    }

    private void maybeSubmitPlateSolve(StarCaptureSession.Snapshot next) {
        PlateSolverCoordinator solver = plateSolverCoordinator;
        if (solver == null || starSolveGeneration < 0 || !next.isSolveReady()) return;
        try {
            StarSolveRequestAdapter.Adapted adapted =
                    StarSolveRequestAdapter.adapt(
                            starSolveGeneration, next, plateSolverSettings);
            double focalLengthMm = adapted.getRequest() == null
                    ? Double.NaN : adapted.getRequest().getFocalLengthMm();
            astroRateSolveContext = new RateSolveContext(
                    starSolveGeneration,
                    connectionEpoch,
                    activeAcceptedSolveIntentId,
                    solveProcessing,
                    pendingManualGravitySolveContext,
                    focalLengthMm,
                    captureAlignedSolveReferenceQ10(starSolveGeneration));
            pendingManualGravitySolveContext = null;
            boolean accepted;
            if (adapted.getRequest() == null) {
                accepted = solver.submitMissingFocal(
                        starSolveGeneration, adapted.isLensMetadataMissing());
            } else {
                accepted = solver.submit(adapted.getRequest());
            }
            if (!accepted) {
                astroRateSolveContext = null;
                solveProcessing = false;
                solveCaptureTransaction.cancel();
                correctionStatus = "Plate solver did not accept this capture";
                publishCurrent();
            }
        } catch (IllegalArgumentException error) {
            StarCaptureProtocol.CaptureMetadata metadata = next.getCapturedMetadata();
            boolean lensMissing = metadata == null || !metadata.hasLensName();
            astroRateSolveContext = new RateSolveContext(
                    starSolveGeneration,
                    connectionEpoch,
                    activeAcceptedSolveIntentId,
                    solveProcessing,
                    pendingManualGravitySolveContext,
                    Double.NaN,
                    captureAlignedSolveReferenceQ10(starSolveGeneration));
            pendingManualGravitySolveContext = null;
            if (!solver.submitError(
                    starSolveGeneration,
                    "Plate-solver input rejected: " + error.getMessage(),
                    lensMissing)) {
                astroRateSolveContext = null;
                solveProcessing = false;
                solveCaptureTransaction.cancel();
                correctionStatus = "Plate-solver input was rejected";
                publishCurrent();
            }
        }
    }

    private StarTelemetry.IbisSample captureAlignedSolveReferenceQ10(
            long generation) {
        if (generation == starSolveReferenceQ10Generation) return starSolveReferenceQ10;
        starSolveReferenceQ10 = selectSolveReferenceQ10(
                generation,
                starCaptureStartQ10Generation,
                starCaptureStartQ10,
                ibisSample);
        starSolveReferenceQ10Generation = generation;
        return starSolveReferenceQ10;
    }

    /** Prefer capture-start Q10; terminal Q10 is only fallback. */
    static StarTelemetry.IbisSample selectSolveReferenceQ10(
            long solveGeneration,
            long captureStartGeneration,
            StarTelemetry.IbisSample captureStart,
            StarTelemetry.IbisSample terminal) {
        StarTelemetry.IbisSample sameGenerationCapture =
                solveGeneration == captureStartGeneration
                        ? qualifiedQ10OrNull(captureStart) : null;
        return sameGenerationCapture != null
                ? sameGenerationCapture : qualifiedQ10OrNull(terminal);
    }

    private static StarTelemetry.IbisSample qualifiedQ10OrNull(
            StarTelemetry.IbisSample sample) {
        return TrackingExposureEstimator.isQualifiedQ10(sample) ? sample : null;
    }

    /** Receives solver callbacks on the BLE owner after fencing main-thread replay races. */
    private void handleSolvedAstroRate(SolveSnapshot solved) {
        if (solved == null) return;
        PlateSolverCoordinator solver = plateSolverCoordinator;
        RateSolveContext context = astroRateSolveContext;
        if (solver == null || solver.getSnapshot() != solved
                || context == null
                || solved.getGeneration() != context.generation
                || solved.getGeneration() != starSolveGeneration
                || context.connectionEpoch != connectionEpoch) {
            return;
        }

        if (solved.getState() != SolveSnapshot.State.SOLVED || solved.getResult() == null) {
            if (solved.getState() == SolveSnapshot.State.QUEUED
                    || solved.getState() == SolveSnapshot.State.SOLVING) {
                if (context.automaticCorrection) {
                    correctionStatus = solved.getDetail();
                    publishCurrent();
                }
            } else {
                String failure = solved.getDetail();
                if (failure == null || failure.isEmpty()) failure = "Plate solving failed";
                solveCaptureTransaction.solveFailed(solved.getGeneration());
                if (context.automaticCorrection) {
                    solveProcessing = false;
                    correctionStatus = failure;
                    correctionPublicationPendingGeneration = -1L;
                }
                astroRateSolveContext = null;
                publishCurrent();
                releaseWakeLockIfIdle();
            }
            return;
        }
        if (solved.getGeneration() <= lastAstroRateSolveGeneration) return;
        lastAstroRateSolveGeneration = solved.getGeneration();
        lastSolvedResult = solved.getResult();
        lastSolvedIntentId = context.solveIntentId;
        lastSolvedDurationNanos = solved.getSolveDurationNanos();
        lastSolvedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        solvedGyroCapture = null;
        solvedGyroToken = null;
        cameraAttitudeAnchoredIntentId = -1L;
        cameraAttitudeEstimator.clearAnchor();
        solvedCorrectionReferenceAttitude = CorrectionAttitude.fromSolve(lastSolvedResult);
        solvedTrackingModel = SolvedTrackingModel.withoutObserver(
                solvedCorrectionReferenceAttitude, Instant.now());
        if (lastSolvedResult.hasFocalCalibration()) {
            solvedTrackingModel = solvedTrackingModel.withFocalCalibration(
                    lastSolvedResult.getSuppliedFocalLengthMm(),
                    lastSolvedResult.getSolvedFocalLengthMm());
        }
        updateSolvedGyroCapture();
        solvedCorrectionReferenceQ10 = context.solveReferenceQ10;
        solvedCorrectionReferenceFocalMm = context.focalLengthMm;
        solvedCorrectionReferenceGeneration = solved.getGeneration();
        solveCaptureTransaction.solveSucceeded(solved.getGeneration());
        if (!manualDraftDirty && !manualOverride) {
            manualRa = formatDraft(lastSolvedResult.getRightAscensionDeg());
            manualDec = formatDraft(lastSolvedResult.getDeclinationDeg());
            astroSettings.saveManualDraft(manualRa, manualDec, false);
        }

        if (!context.automaticCorrection) {
            astroRateSolveContext = null;
            publishCurrent();
            pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (!correctionEnabled || correctionFactorPercent == 0) {
            solveProcessing = false;
            desiredSolveGeneration = solved.getGeneration();
            correctionStatus = correctionEnabled
                    ? "Plate solved; strength is 0×"
                    : "Plate solved; correction is disabled";
            astroRateDetail = correctionStatus;
            astroRateSolveContext = null;
            publishCurrent();
            pumpCommands();
            return;
        }

        CorrectionSourceSelector.Source source;
        CorrectionAttitude attitude;
        if (!manualOverride) {
            // A solved correction always uses the position angle from this exact solve.
            source = CorrectionSourceSelector.Source.SOLVED;
            attitude = solvedCorrectionReferenceAttitude;
        } else {
            ManualGravityContext manualContext = context.manualGravityContext;
            if (manualContext == null) {
                withholdSolvedManualCorrection("Camera tilt unavailable");
                return;
            }
            long nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            ManualGravityContext.Derivation derived = manualContext.derive(
                    cameraAccelerationTracker.estimate(nowElapsedRealtimeNanos),
                    nowElapsedRealtimeNanos);
            if (!derived.isValid()) {
                withholdSolvedManualCorrection(derived.getFailure());
                return;
            }
            source = CorrectionSourceSelector.Source.MANUAL_GRAVITY;
            attitude = derived.getAttitude();
            manualRa = formatDraft(attitude.getRightAscensionDeg());
            manualDec = formatDraft(attitude.getDeclinationDeg());
            // Never persist the gravity-derived roll or the observer location.
            astroSettings.saveManualDraft(manualRa, manualDec, manualDraftDirty);
        }
        publishCalculatedCorrection(source, attitude, true);
        astroRateSolveContext = null;
    }

    private void withholdSolvedManualCorrection(String reason) {
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        correctionPublicationPendingGeneration = -1L;
        correctionStatus = "Solved; " + (reason == null || reason.isEmpty()
                ? "manual correction unavailable" : reason);
        astroRateDetail = correctionStatus;
        astroRateSolveContext = null;
        publishCurrent();
        releaseWakeLockIfIdle();
    }

    private static String formatDraft(double value) {
        return String.format(java.util.Locale.US, "%.6f", value);
    }

    private void suspendIdleCorrectionForBackground() {
        if (!shouldSuspendCorrectionForBackground()) return;
        cancelAstroRateTimers();
        BackgroundCorrectionGate.Action suspensionAction =
                backgroundCorrectionGate.suspend();

        pendingCaptureRateMutation = null;
        correctionPublicationPendingGeneration = -1L;
        acknowledgedCorrectionHealthy = false;
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        desiredSolveGeneration = -1L;
        correctionStatus = "Correction paused while the app is in the background";
        astroRateDetail = correctionStatus;

        // Every hidden-idle publication must retire its Activity-owned solve workflow, even when
        // an earlier correction already crossed this gate. Only the first transition owns the
        // exact-zero write; later retained intents will be republished once foreground resumes.
        if (suspensionAction != BackgroundCorrectionGate.Action.SUSPEND) {
            publishCurrent();
            releaseWakeLockIfIdle();
            return;
        }

        boolean cancelQueued = false;
        if (phase == Phase.READY
                && gatt != null
                && astroRateCharacteristic != null
                && astroRateTransport != null) {
            cancelQueued = astroRateLease.requestCancel(
                    connectionEpoch, astroRateTransport.cancel());
        } else {
            astroRateLease.reset();
        }
        if (!cancelQueued) {
            acknowledgedCorrection = null;
            acknowledgedRateSetpoint = null;
            acknowledgedCorrectionGeneration = -1L;
            acknowledgedSolveGeneration = -1L;
        }
        publishCurrent();
        if (cancelQueued) pumpCommands();
        releaseWakeLockIfIdle();
    }

    private void resumeBackgroundSuspendedCorrection() {
        if (!backgroundCorrectionGate.isSuspended()
                || !isAppInteractiveVisible()
                || !desiredConnected
                || disconnectRequested
                || phase != Phase.READY
                || !correctionRequested()) {
            return;
        }
        CorrectionSourceSelector.Source source = correctionSource;
        CorrectionAttitude attitude = desiredCorrectionAttitude;
        backgroundCorrectionGate.resume();
        if (source != null && attitude != null) {
            publishCalculatedCorrection(source, attitude, false);
        }
    }

    private void scheduleAstroRateHeartbeat(long generation) {
        if (shouldSuspendCorrectionForBackground()) {
            suspendIdleCorrectionForBackground();
            return;
        }
        acquireWakeLock(AstroRateGattProtocol.APP_RENEWAL_MS + DESCRIPTOR_TIMEOUT_MS + 5_000L);
        int epoch = connectionEpoch;
        long delayMs = AstroRateCadenceTimers.remainingPeriodDelayMs(
                AstroRateGattProtocol.APP_RENEWAL_MS,
                astroRateActiveWriteStartedElapsedMs,
                SystemClock.elapsedRealtime());
        astroRateCadenceTimers.rearmPublicationAfter(
                () -> runAstroRateHeartbeat(generation, epoch), delayMs);
    }

    /**
     * Uses the one-second phone heartbeat opportunity for the Q10-derived update when possible;
     * the exact latest-ACKed value is the fallback, never a second cadence write. Each write gives
     * CPU0 a fresh raw-hook TTL. A capture-time update uses the server's frozen focal and becomes
     * the next renewal baseline only after its ATT write succeeds.
     */
    private void runAstroRateHeartbeat(long generation, int epoch) {
        if (shouldSuspendCorrectionForBackground()) {
            suspendIdleCorrectionForBackground();
            return;
        }
        if (epoch != connectionEpoch
                || generation != astroRateLease.getDesiredGeneration()
                || starTerminalWaitingForRateRetirement) {
            releaseWakeLockIfIdle();
            return;
        }
        if (astroRateWriteInFlight || astroRateLease.hasPendingWrite()) {
            retryAstroRateHeartbeat(generation, epoch);
            return;
        }
        boolean queued = correctionSource == CorrectionSourceSelector.Source.SOLVED
                ? AstroRateHeartbeatPolicy.queueAdaptiveOrExact(
                        () -> queueAstroRateAdaptiveRefresh(generation, epoch),
                        () -> astroRateLease.queueRenewal(epoch))
                : astroRateLease.queueRenewal(epoch);
        if (queued) {
            pumpCommands();
        } else {
            // A transient lease-state mismatch must not permanently stop an otherwise-current
            // correction. Retry at the normal publication cadence without adding a RATE write.
            astroRateCadenceTimers.rearmPublication(
                    () -> runAstroRateHeartbeat(generation, epoch));
        }
    }

    private void retryAstroRateHeartbeat(long generation, int epoch) {
        astroRateCadenceTimers.retryPublication(
                () -> runAstroRateHeartbeat(generation, epoch),
                AstroRateGattProtocol.APP_FAST_RETRY_MS);
    }

    private boolean queueAstroRateAdaptiveRefresh(long generation, int epoch) {
        if (!AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                    prepositionCapture.isPreparing(), starPrepositionRequested)
                || astroRateTransport == null
                || !functionalGattIdleForAstroRateMutation()
                || astroRateWriteInFlight || astroRateLease.hasPendingWrite()) return false;
        CorrectionAttitude referenceAttitude = astroRateLease.isCaptureHeld()
                ? captureRateReferenceAttitude : solvedCorrectionReferenceAttitude;
        StarTelemetry.IbisSample referenceQ10 = astroRateLease.isCaptureHeld()
                ? captureRateReferenceQ10 : solvedCorrectionReferenceQ10;
        long referenceGeneration = astroRateLease.isCaptureHeld()
                ? captureRateReferenceGeneration : solvedCorrectionReferenceGeneration;
        SolvedTrackingModel model = astroRateLease.isCaptureHeld()
                ? captureTrackingModel : solvedTrackingModel;
        if (correctionSource != CorrectionSourceSelector.Source.SOLVED
                || generation != correctionGeneration
                || referenceGeneration < 0L
                || referenceAttitude == null || model == null) {
            return false;
        }
        double referenceFocalMm = astroRateLease.isCaptureHeld()
                ? captureRateReferenceFocalMm : solvedCorrectionReferenceFocalMm;
        double currentFocalLengthMm = compatibleCurrentSolveFocalLengthMm(referenceFocalMm);
        if (!Double.isFinite(currentFocalLengthMm)) {
            return false;
        }

        try {
            // A PRE solve has no offset-aligned Q10 reference. It can still use the dated pole
            // and local refraction rate, but must not invent a planar reference. If an existing
            // reference temporarily becomes unusable, retain the last ACKed adjusted rate.
            SiderealRateCorrection refreshed = model.rateAtQ10(
                    CorrectionScale.factor(correctionFactorPercent),
                    referenceQ10, ibisSample, currentFocalLengthMm);
            if (refreshed == null) return false;
            AstroRateSetpoint refreshedSetpoint = AstroRateSetpoint.bodyOnly(refreshed);
            byte[] value = astroRateTransport.setpoint(
                    refreshedSetpoint, AstroRateGattProtocol.APP_TTL_TICKS);
            if (astroRateLease.isCaptureHeld()) {
                astroRateLease.offerCaptureSetpoint(
                        generation, epoch, starSolveGeneration, refreshedSetpoint, value);
            } else if (!astroRateLease.offerHeartbeatSetpoint(
                    generation, epoch, refreshedSetpoint, value)) {
                return false;
            }
            desiredCorrection = refreshed;
            desiredRateSetpoint = refreshedSetpoint;
            cancelAstroRateHeartbeat();
            publishCurrent();
            return true;
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "Holding the prior correction after a tracking refresh was rejected", error);
            return false;
        }
    }

    private double compatibleCurrentSolveFocalLengthMm(double referenceFocalMm) {
        if (!Double.isFinite(referenceFocalMm)
                || referenceFocalMm <= 0.0
                || cameraState == null
                || !cameraState.hasFocalLength()) {
            return Double.NaN;
        }
        double currentFocalLengthMm = cameraState.getFocalLengthMm();
        if (!Double.isFinite(currentFocalLengthMm) || currentFocalLengthMm <= 0.0) {
            return Double.NaN;
        }
        double relativeChange = Math.abs(
                currentFocalLengthMm - referenceFocalMm)
                / referenceFocalMm;
        return relativeChange <= SOLVE_FOCAL_RELATIVE_TOLERANCE
                ? currentFocalLengthMm : Double.NaN;
    }

    private boolean functionalGattIdleForAstroRateMutation() {
        return pending == null
                && prepositionPendingWrite == PrepositionWrite.NONE
                && prepositionWriteInFlight == PrepositionWrite.NONE
                && writePurpose == WritePurpose.NONE
                && !starControlWriteInFlight
                && !starStateWriteInFlight
                && !keepAwakeLeaseWriteInFlight
                && !gyroReadInFlight && starReadInFlight == null;
    }

    private void cancelAstroRateHeartbeat() {
        if (astroRateCadenceTimers != null) astroRateCadenceTimers.cancelPublication();
    }

    private void cancelAstroRateTimers() {
        cancelAstroRateHeartbeat();
    }

    private void failAstroRateWrite(
            String reason,
            boolean reconnectForLateCallback,
            AstroRateLease.Write failedWrite) {
        if (starPrepositionRequested) {
            failPrepositionCapture("Tracking interrupted; pre-positioned capture cancelled");
        } else {
            abortPreposition("Tracking interrupted; pre-positioned capture cancelled", true);
        }
        cancelAstroRateTimers();
        rejectDeferredStarSolveAdmission();
        pendingCaptureRateMutation = null;
        astroRateDetail = reason;
        correctionStatus = reason;
        acknowledgedCorrectionHealthy = false;
        correctionPublicationPendingGeneration = -1L;
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        clearDesiredCorrectionIntent();
        boolean safetyStopQueued = false;
        if (!reconnectForLateCallback
                && failedWrite != null
                && !failedWrite.isCancel()
                && astroRateLease.isPossiblyActive()
                && !astroRateLease.isCaptureHeld()
                && !astroRateLease.isCaptureRetiring()
                && phase == Phase.READY
                && gatt != null
                && astroRateTransport != null) {
            // The failed heartbeat/replacement was proven unsent, but its acknowledged baseline
            // may still be live. Retire that baseline with one exact zero instead of abandoning
            // the lease in a permanently possibly-active, nonrenewable state.
            safetyStopQueued = astroRateLease.requestCancel(
                    connectionEpoch, astroRateTransport.cancel());
            if (safetyStopQueued) {
                correctionStatus = reason + "; stopping the prior correction";
                astroRateDetail = correctionStatus;
            }
        }
        if (!safetyStopQueued) {
            boolean unresolvedActiveLease = astroRateLease.isPossiblyActive();
            astroRateLease.abandonWrites();
            // Ambiguous failures already reconnect. A definitive zero rejection also requires a
            // new connection boundary; otherwise possiblyActive would be forgotten locally while
            // capture could start against a value the peer may still be applying.
            reconnectForLateCallback |= unresolvedActiveLease && desiredConnected;
        }
        publishCurrent();
        if (reconnectForLateCallback && desiredConnected) {
            scheduleReconnect(reason);
        } else {
            pumpCommands();
            releaseWakeLockIfIdle();
        }
    }

    /** A definitively unapplied capture update is never replayed; exact zero retires the baseline. */
    private void beginCaptureRateFailureCleanup(String reason) {
        cancelAstroRateTimers();
        pendingCaptureRateMutation = null;
        acknowledgedCorrectionHealthy = false;
        correctionPublicationPendingGeneration = -1L;
        correctionStatus = reason;
        astroRateDetail = reason;
        if (astroRateTransport == null) {
            failAstroRateWrite(reason, false, null);
            return;
        }
        astroRateLease.forceCancel(connectionEpoch, astroRateTransport.cancel());
        publishCurrent();
        pumpCommands();
    }

    /**
     * Keeps the capture/session fence after an exact zero was proven not to reach the peer.
     * The zero is not replayed while capture is active; DONE starts a fresh terminal attempt.
     */
    private void retainCaptureRetirementAfterUnappliedCancel(String reason) {
        cancelAstroRateTimers();
        pendingCaptureRateMutation = null;
        acknowledgedCorrectionHealthy = false;
        correctionPublicationPendingGeneration = -1L;
        correctionStatus = reason;
        astroRateDetail = reason;
        if (starCaptureRateArming.zeroProvenUnapplied()
                == AstroRateCaptureArming.Decision.FAIL_CAPTURE
                && isStarSessionActive()) {
            starSession.transportFailed(reason);
            publishStarSnapshot();
            finishStarSessionLifecycle();
            return;
        }
        publishCurrent();
        pumpCommands();
        releaseWakeLockIfIdle();
    }

    private void clearStarSnapshotForCameraChange() {
        invalidatePlateSolve("Plate solution cleared for the newly selected camera");
        cancelStarJobTimeout();
        starSession = null;
        starSnapshot = null;
        starSolveGeneration = -1L;
        solveCaptureTransaction.cancel();
        starSessionCameraIdentity = "";
        for (StarListener listener : starListeners) {
            mainHandler.post(() -> listener.onStarJobChanged(null));
        }
    }

    private void invalidatePlateSolve(String reason) {
        verifiedGyroCapture = null;
        cameraGyroTracker.cancelPendingCapture();
        cameraAttitudeEstimator.clearAnchor();
        solvedGyroCapture = null;
        solvedGyroToken = null;
        cameraAttitudeAnchoredIntentId = -1L;
        lastSolvedResult = null;
        lastSolvedIntentId = -1L;
        lastSolvedDurationNanos = SolveSnapshot.DURATION_UNAVAILABLE_NANOS;
        lastSolvedElapsedRealtimeNanos = -1L;
        clearSolveQ10References();
        PlateSolverCoordinator solver = plateSolverCoordinator;
        if (solver == null) return;
        SolveSnapshot current = solver.getSnapshot();
        if (current.getGeneration() >= 0
                && current.getState() != SolveSnapshot.State.IDLE) {
            solver.invalidate(reason);
        }
    }

    private void clearSolveQ10References() {
        astroRateSolveContext = null;
        starCaptureStartQ10 = null;
        starCaptureStartQ10Generation = -1L;
        starSolveReferenceQ10 = null;
        starSolveReferenceQ10Generation = -1L;
        solvedCorrectionReferenceAttitude = null;
        solvedCorrectionReferenceQ10 = null;
        solvedCorrectionReferenceFocalMm = Double.NaN;
        solvedCorrectionReferenceGeneration = -1L;
        solvedTrackingModel = null;
    }

    private void clearCaptureRateReference() {
        captureRateReferenceAttitude = null;
        captureRateReferenceQ10 = null;
        captureRateReferenceFocalMm = Double.NaN;
        captureRateReferenceGeneration = -1L;
        captureTrackingModel = null;
    }

    private static String cameraIdentity(CameraCredentials.SavedCamera camera) {
        if (camera == null) return "";
        return camera.address.isEmpty()
                ? "name:" + camera.name
                : "address:" + camera.address.toUpperCase(java.util.Locale.US);
    }

    private void scheduleStarJobTimeout() {
        cancelStarJobTimeout();
        starJobTimeout = () -> {
            starJobTimeout = null;
            if (!isStarSessionActive()) return;
            starSession.transportFailed(
                    "STAR capture timed out; CAPTURE_RECIPE will not be retried");
            publishStarSnapshot();
            failAmbiguousStarCapture("STAR capture timed out");
        };
        bluetoothHandler.postDelayed(starJobTimeout, STAR_JOB_TIMEOUT_MS);
    }

    private void cancelStarJobTimeout() {
        if (starJobTimeout != null) bluetoothHandler.removeCallbacks(starJobTimeout);
        starJobTimeout = null;
    }

    private void scheduleStarSettingsConfirmationTimeout() {
        cancelStarSettingsTimeout();
        starSettingsDeadlineElapsedMs =
                SystemClock.elapsedRealtime() + STAR_SETTINGS_CONFIRM_DEADLINE_MS;
        starSettingsReadBackoffMs = STAR_SETTINGS_READ_BACKOFF_INITIAL_MS;
        acquireWakeLock(STAR_SETTINGS_CONFIRM_DEADLINE_MS + DESCRIPTOR_TIMEOUT_MS + 5_000L);
        scheduleStarSettingsVerificationRead(STAR_SETTINGS_NOTIFICATION_TIMEOUT_MS);
    }

    private void scheduleStarSettingsVerificationRead(long delayMs) {
        if (starSettingsTimeout != null) {
            bluetoothHandler.removeCallbacks(starSettingsTimeout);
        }
        long remainingMs = starSettingsDeadlineElapsedMs - SystemClock.elapsedRealtime();
        if (remainingMs <= 0L) {
            failSettingsApply(
                    "Camera did not confirm the requested exposure settings before the deadline",
                    true);
            return;
        }
        starSettingsTimeout = () -> {
            starSettingsTimeout = null;
            if (settingsSession == null) return;
            StarStateMutationSession.Snapshot current = settingsSession.snapshot();
            if (current.isReady() || current.isTerminalFailed() || !current.isWriteComplete()) {
                return;
            }
            if (SystemClock.elapsedRealtime() >= starSettingsDeadlineElapsedMs) {
                failSettingsApply(
                        "Camera did not confirm the requested exposure settings before the deadline",
                        true);
                return;
            }
            settingsVerificationReadRequired = true;
            detail = "Waiting for authoritative camera settings confirmation";
            requestStarStateRead();
        };
        bluetoothHandler.postDelayed(
                starSettingsTimeout, Math.min(Math.max(0L, delayMs), remainingMs));
    }

    private void scheduleNextStarSettingsVerificationRead(String reason) {
        if (settingsSession == null) return;
        StarStateMutationSession.Snapshot current = settingsSession.snapshot();
        if (current.isReady() || current.isTerminalFailed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= starSettingsDeadlineElapsedMs) {
            failSettingsApply(
                    "Camera did not confirm the requested exposure settings before the deadline",
                    true);
            return;
        }
        long delayMs = Math.max(
                STAR_SETTINGS_READ_BACKOFF_INITIAL_MS, starSettingsReadBackoffMs);
        starSettingsReadBackoffMs = Math.min(
                STAR_SETTINGS_READ_BACKOFF_MAX_MS, delayMs * 2L);
        detail = reason;
        publishCurrent();
        scheduleStarSettingsVerificationRead(delayMs);
    }

    private void cancelStarSettingsTimeout() {
        if (starSettingsTimeout != null) {
            bluetoothHandler.removeCallbacks(starSettingsTimeout);
        }
        starSettingsTimeout = null;
        if (settingsVerificationReadRequired) {
            starReadQueue.removeFirstOccurrence(StarReadPurpose.STATE);
        }
        settingsVerificationReadRequired = false;
        starSettingsDeadlineElapsedMs = 0L;
        starSettingsReadBackoffMs = 0L;
    }

    private void finishSettingsBarrier() {
        cancelStarSettingsTimeout();
        settingsVerificationReadRequired = false;
        StateMutationKind completedKind = stateMutationKind;
        ExposureCatalogs.Selection actual = currentCameraSelection();
        ExposureCatalogs.Selection recipe = StarProtocol.resolveRecipe(
                starState, fullExposureCatalogs);
        if (actual == null || recipe == null || starState == null
                || starState.getDetector() == null) {
            failSettingsApply("Matching STAR state could not be resolved", true);
            return;
        }
        boolean liveIntentUpdated = completedKind == StateMutationKind.LIVE
                ? persistentSettingsIntent.confirmActive(
                        connectionEpoch, exposureCatalogGeneration)
                : persistentSettingsIntent.observeAuthoritative(
                        actual, connectionEpoch, exposureCatalogGeneration);
        boolean recipeIntentUpdated = completedKind == StateMutationKind.RECIPE
                        || completedKind == StateMutationKind.STARTUP_RECIPE
                ? starRecipeIntent.confirmActive(
                        connectionEpoch, exposureCatalogGeneration)
                : starRecipeIntent.observeAuthoritative(
                        recipe, connectionEpoch, exposureCatalogGeneration);
        if (!liveIntentUpdated || !recipeIntentUpdated) {
            failSettingsApply(
                    "Matching STAR state belonged to a stale UI intent generation", true);
            return;
        }
        detectorConfig = starState.getDetector();
        detectorConfigKnown = true;
        detectorConfigPending = null;
        detectorConfigInFlight = null;
        detectorConfigWriteInFlight = false;
        settingsSession = null;
        stateMutationKind = StateMutationKind.NONE;
        // Every STATE mutation freshly applies and certifies the complete live target, even
        // when the user's changed field was recipe or detector state.
        clearCaptureRestoreBarrierIfExact(actual);
        if (completedKind == StateMutationKind.STARTUP_RECIPE) {
            starRecipeStartupReset.writeSucceeded();
            suppressedStartupStarRecipeCameraIdentity = "";
            detail = "Camera confirmed the startup STAR recipe";
        } else if (completedKind == StateMutationKind.RECIPE) {
            starRecipeStartupReset.userWriteSucceeded(recipe);
            detail = "Camera confirmed the requested STAR recipe";
        } else if (completedKind == StateMutationKind.DETECTOR) {
            detectorStatus = "Detector settings confirmed in STAR state";
            detail = detectorStatus;
        } else {
            detail = "Camera confirmed the requested Shutter settings";
        }
        publishCurrent();
        if (disconnectRequested) {
            continueRequestedDisconnect();
        } else {
            startQueuedStateMutation();
        }
        releaseWakeLockIfIdle();
    }

    private void startQueuedStateMutation() {
        if (settingsSession != null
                || stateMutationKind != StateMutationKind.NONE
                || gyroReadInFlight || starReadInFlight != null
                || starStateWriteInFlight
                || starStateWritePending != null) {
            return;
        }
        if (persistentSettingsIntent.isPending()
                && !persistentSettingsIntent.hasActive()) {
            startNextPersistentSettingsWrite();
        } else if (starRecipeIntent.isPending()
                && !starRecipeIntent.hasActive()) {
            startNextStarRecipeWrite();
        }
    }

    private void failSettingsApply(String reason, boolean refreshCatalogs) {
        cancelStarSettingsTimeout();
        settingsVerificationReadRequired = false;
        starStateWritePending = null;
        if (stateMutationKind == StateMutationKind.STARTUP_RECIPE
                && settingsSession != null) {
            StarStateMutationSession.Snapshot failed = settingsSession.snapshot();
            if (failed.isWriteComplete() && !failed.isReady()) {
                // ATT success without the later matching STATE is still ambiguous across a
                // reconnect. Never re-emit the automatic startup target in this process.
                fenceAmbiguousStartupStarRecipeWrite();
            }
        }
        if (settingsSession != null) settingsSession.transportFailed(reason);
        if (stateMutationKind == StateMutationKind.LIVE) {
            lastCameraSettingsIntentAccepted = false;
        }
        if (stateMutationKind == StateMutationKind.RECIPE
                || stateMutationKind == StateMutationKind.STARTUP_RECIPE) {
            lastStarRecipeIntentAccepted = false;
        }
        if (stateMutationKind == StateMutationKind.STARTUP_RECIPE
                && starRecipeStartupReset.isWriting()) {
            starRecipeStartupReset.writeFailed(reason);
            starRecipeIntent.clear();
        }
        if (stateMutationKind == StateMutationKind.DETECTOR) {
            detectorStatus = reason;
        }
        persistentSettingsIntent.failToAuthoritative(
                currentCameraSelection(), connectionEpoch, exposureCatalogGeneration);
        if (starRecipeIntent.getAuthoritative() != null) {
            starRecipeIntent.failToAuthoritative(
                    starRecipeIntent.getAuthoritative(),
                    connectionEpoch,
                    exposureCatalogGeneration);
        }
        detectorConfigPending = null;
        detectorConfigInFlight = null;
        detectorConfigWriteInFlight = false;
        settingsSession = null;
        stateMutationKind = StateMutationKind.NONE;
        detail = reason;
        publishCurrent();
        if (refreshCatalogs && !disconnectRequested && starTransportReady && gatt != null) {
            requestStarInitializationReads();
            detail = reason + "; refreshing exposure choices";
            publishCurrent();
        }
        if (disconnectRequested) continueRequestedDisconnect();
        releaseWakeLockIfIdle();
    }

    private void failStartupStarRecipeResetIfWriting(String reason) {
        if (!starRecipeStartupReset.isWriting()) return;
        starRecipeStartupReset.writeFailed(reason);
        starRecipeIntent.clear();
        detail = "STAR defaults unavailable: " + starRecipeStartupReset.failure();
    }

    private void fenceAmbiguousStartupStarRecipeWrite() {
        if (!starRecipeStartupReset.isWriting()) return;
        String identity = cameraIdentity(configuredCamera);
        suppressedStartupStarRecipeCameraIdentity = identity.isEmpty() ? "unknown" : identity;
    }

    private void finishStarSessionLifecycle() {
        if (starPrepositionPinned || starPrepositionRequested) {
            starPrepositionPinned = false;
            starPrepositionRequested = false;
            prepositionPlan = null;
            // Cancelling an already consumed request is harmless. This also discards a staged
            // request if CAPTURE was rejected before the worker took ownership.
            queuePrepositionCancel();
        }
        cancelStarJobTimeout();
        starCaptureRateArming.reset();
        StarCaptureSession.Snapshot completed = starSession == null
                ? null : starSession.snapshot();
        if (captureRestoreBarrier.isCaptureInFlight()) {
            boolean exposureStateCertified = completed != null
                    && completed.isTerminal()
                    && completed.isExposureStateCertified();
            boolean durableMarkerCleared = exposureStateCertified
                    && captureRecoveryStore != null
                    && captureRecoveryStore.clear();
            captureRestoreBarrier.finishTerminal(
                    exposureStateCertified, durableMarkerCleared);
        }
        if (completed != null && completed.isTerminal() && !completed.isSuccessful()
                && solveProcessing) {
            String failure = completed.getDetail();
            if (failure == null || failure.isEmpty()) failure = "STAR capture failed";
            solveProcessing = false;
            solveCaptureTransaction.cancel();
            correctionStatus = failure;
            astroRateSolveContext = null;
            pendingManualGravitySolveContext = null;
        }
        boolean waitingForRateRetirement = beginStarTerminalRateRetirement(completed);
        publishCurrent();
        if (waitingForRateRetirement) {
            pumpCommands();
            return;
        }
        clearCaptureRateReference();
        if (disconnectRequested) {
            continueRequestedDisconnect();
        } else {
            // Certified terminals have already retired the durable marker. Unsafe or ambiguous
            // terminals still require the exact pre-capture tuple within this connection; a
            // later fresh connection may instead establish a coherent same-camera baseline.
            requestStarStateRead();
        }
        releaseWakeLockIfIdle();
    }

    /**
     * Supersedes any queued capture renewal with exact zero before the post-terminal STATE read.
     * A successful restored capture mirrors the validated host cleanup with three acknowledged
     * zero publications, 50 ms apart. Failures use one best-effort zero and never replay an
     * ambiguous attempt.
     */
    private boolean beginStarTerminalRateRetirement(
            StarCaptureSession.Snapshot completed) {
        if (completed == null || !completed.isTerminal()
                || astroRateTransport == null
                || !astroRateLease.isCaptureHeld(connectionEpoch, starSolveGeneration)) {
            return false;
        }
        cancelAstroRateTimers();
        cancelAstroRateCaptureCancel();
        starTerminalWaitingForRateRetirement = true;
        starTerminalRateRetirementEpoch = connectionEpoch;
        starTerminalRateRetirementSession = starSolveGeneration;
        starTerminalRateCancelAttemptsRemaining = completed.isSuccessful()
                ? STAR_TERMINAL_RATE_CANCEL_ATTEMPTS : 1;
        if (!astroRateLease.isCaptureRetiring(connectionEpoch, starSolveGeneration)) {
            astroRateLease.beginCaptureRetirement(
                    connectionEpoch, starSolveGeneration, astroRateTransport.cancel());
        } else if (!astroRateLease.hasPendingWrite()
                && !astroRateLease.hasWriteInFlight()) {
            // An explicit mid-capture stop may already have ACKed before DONE arrived. Retain its
            // session fence, but start the terminal zero sequence from a fresh serialized write.
            astroRateLease.queueCaptureRetirementRetry(
                    connectionEpoch, starSolveGeneration, astroRateTransport.cancel());
        }
        if (!hasCompletedSolveOutcomeWithoutDeferredCorrection(starSolveGeneration)) {
            correctionStatus = "STAR complete; publishing exact correction stop";
            astroRateDetail = correctionStatus;
        }
        return true;
    }

    private void scheduleNextStarTerminalRateCancel() {
        cancelAstroRateCaptureCancel();
        final int epoch = starTerminalRateRetirementEpoch;
        final long session = starTerminalRateRetirementSession;
        astroRateCaptureCancelRunnable = () -> {
            astroRateCaptureCancelRunnable = null;
            if (!starTerminalWaitingForRateRetirement
                    || epoch != connectionEpoch
                    || session != starTerminalRateRetirementSession
                    || astroRateTransport == null
                    || !astroRateLease.isCaptureRetiring(epoch, session)) {
                return;
            }
            astroRateLease.queueCaptureRetirementRetry(
                    epoch, session, astroRateTransport.cancel());
            pumpCommands();
        };
        bluetoothHandler.postDelayed(
                astroRateCaptureCancelRunnable, AstroRateGattProtocol.APP_FAST_RETRY_MS);
    }

    private void finishStarTerminalRateRetirement() {
        int epoch = starTerminalRateRetirementEpoch;
        long session = starTerminalRateRetirementSession;
        cancelAstroRateCaptureCancel();
        astroRateLease.completeCaptureRetirement(epoch, session);
        clearCaptureRateReference();
        starTerminalWaitingForRateRetirement = false;
        starTerminalRateCancelAttemptsRemaining = 0;
        starTerminalRateRetirementSession = -1L;
        acknowledgedCorrection = null;
        acknowledgedRateSetpoint = null;
        acknowledgedCorrectionHealthy = false;
        acknowledgedCorrectionGeneration = -1L;
        acknowledgedSolveGeneration = -1L;
        correctionPublicationPendingGeneration = -1L;
        boolean deferredMutation = pendingCaptureRateMutation != null;
        if (!deferredMutation) {
            desiredCorrection = null;
            desiredRateSetpoint = null;
            desiredCorrectionAttitude = null;
            correctionSource = null;
            desiredSolveGeneration = -1L;
        }
        correctionGeneration = nextCorrectionGeneration++;
        correctionStatus = PostTerminalCorrectionPolicy.retirementStatus(
                correctionStatus,
                solveProcessing,
                lastSolvedResult != null
                        && solvedCorrectionReferenceGeneration == session,
                deferredMutation);
        astroRateDetail = correctionStatus;
        // Do not replay a capture-time mutation until the ordered post-terminal STATE proves the
        // server has left its capture operation and restored an authoritative focal surface.
        postTerminalRateMutationAwaitingState = true;
        if (disconnectRequested) {
            continueRequestedDisconnect();
        } else {
            requestStarStateRead();
        }
        publishCurrent();
        pumpCommands();
        releaseWakeLockIfIdle();
    }

    private void failStarTerminalRateRetirement(
            String reason, boolean reconnectForAmbiguousWrite) {
        cancelAstroRateTimers();
        cancelAstroRateCaptureCancel();
        pendingCaptureRateMutation = null;
        postTerminalRateMutationAwaitingState = false;
        astroRateLease.abandonWrites();
        clearCaptureRateReference();
        starTerminalWaitingForRateRetirement = false;
        starTerminalRateCancelAttemptsRemaining = 0;
        starTerminalRateRetirementSession = -1L;
        acknowledgedCorrectionHealthy = false;
        correctionPublicationPendingGeneration = -1L;
        correctionStatus = reason;
        astroRateDetail = reason;
        publishCurrent();
        if (reconnectForAmbiguousWrite && desiredConnected) {
            scheduleReconnect(reason);
            return;
        }
        if (disconnectRequested) {
            continueRequestedDisconnect();
        } else {
            requestStarStateRead();
            pumpCommands();
        }
        releaseWakeLockIfIdle();
    }

    private boolean hasCompletedSolveOutcomeWithoutDeferredCorrection(long solveGeneration) {
        return lastSolvedResult != null
                && solvedCorrectionReferenceGeneration == solveGeneration
                && !solveProcessing
                && pendingCaptureRateMutation == null;
    }

    private void cancelAstroRateCaptureCancel() {
        if (astroRateCaptureCancelRunnable != null && bluetoothHandler != null) {
            bluetoothHandler.removeCallbacks(astroRateCaptureCancelRunnable);
        }
        astroRateCaptureCancelRunnable = null;
    }

    private void failAmbiguousStarCapture(String reason) {
        cancelStarJobTimeout();
        requireCaptureRestoreReadback();
        if (starSession != null && !starSession.snapshot().isTerminal()) {
            starSession.transportFailed(reason);
            publishStarSnapshot();
        }
        // Reconnecting never replays CAPTURE_RECIPE. It only restores transport readiness; a
        // replacement session is created exclusively by a later explicit Detect request.
        if (desiredConnected) {
            scheduleReconnect(reason);
        } else {
            publishCurrent();
            releaseWakeLockIfIdle();
        }
    }

    private void enqueue(Command command) {
        commandQueue.addLast(command);
        publishCurrent();
        pumpCommands();
    }

    private void pumpCommands() {
        if (pending != null || writePurpose != WritePurpose.NONE
                || starControlWriteInFlight || starStateWriteInFlight
                || keepAwakeLeaseWriteInFlight
                || astroRateWriteInFlight
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || gyroReadInFlight || starReadInFlight != null) return;
        if (gatt == null || commandCharacteristic == null || resultCharacteristic == null) return;
        // Terminal RATE retirement exclusively owns functional GATT writes until its exact-zero
        // sequence completes. In particular, do not let the post-terminal STATE read pass zero.
        if (starTerminalWaitingForRateRetirement) {
            maybeStartAstroRateWrite();
            return;
        }
        if (astroRateLease.isCaptureHeld()
                && !astroRateLease.isCaptureRetiring()
                && pendingCaptureRateMutation != null
                && !astroRateLease.hasPendingWrite()) {
            Runnable mutation = pendingCaptureRateMutation;
            pendingCaptureRateMutation = null;
            mutation.run();
            return;
        }
        // A due RATE publication takes the next free functional GATT turn. Already in-flight
        // operations cannot be preempted, but queued reads/commands must not starve the lease.
        if (astroRateLease.hasPendingWrite()) {
            maybeStartAstroRateWrite();
            if (astroRateWriteInFlight || astroRateLease.hasPendingWrite()) return;
        }
        if (prepositionPendingWrite != PrepositionWrite.NONE) {
            startPrepositionWrite();
            return;
        }
        if (maybeStartDeferredExposureCatalogRefresh()) return;

        if (starStateWritePending != null && !isStarSessionActive()
                && starStateCharacteristic != null) {
            byte[] value = starStateWritePending;
            starStateWriteInFlight = true;
            try {
                if (!GattCompat.writeCharacteristic(gatt, starStateCharacteristic, value)) {
                    starStateWriteInFlight = false;
                    starStateWritePending = null;
                    lastCameraSettingsIntentAccepted = false;
                    failSettingsApply(
                            "GATT rejected the unsent exposure settings write", true);
                    return;
                }
            } catch (SecurityException error) {
                starStateWriteInFlight = false;
                starStateWritePending = null;
                fenceAmbiguousStartupStarRecipeWrite();
                failSettingsApply(
                        "Bluetooth permission was revoked after exposure settings were attempted",
                        false);
                terminalError("Bluetooth permission was revoked");
                return;
            }
            starStateWritePending = null;
            int epoch = connectionEpoch;
            cancelTransactionTimeout();
            transactionTimeout = () -> {
                if (epoch != connectionEpoch || !starStateWriteInFlight) return;
                transactionTimeout = null;
                starStateWriteInFlight = false;
                fenceAmbiguousStartupStarRecipeWrite();
                failSettingsApply(
                        "Exposure settings write timed out and will not be retried", false);
                scheduleReconnect("Exposure settings write callback timed out; mutation will not be retried");
            };
            bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
            publishCurrent();
            return;
        }

        if (!starReadQueue.isEmpty() && !isStarSessionActive()) {
            StarReadPurpose purpose = starReadQueue.removeFirst();
            boolean settingsVerificationRead = purpose == StarReadPurpose.STATE
                    && settingsVerificationReadRequired;
            boolean initializationRead = starInitializationReadBatch;
            if (settingsVerificationRead) settingsVerificationReadRequired = false;
            BluetoothGattCharacteristic characteristic = characteristicFor(purpose);
            if (characteristic == null) {
                if (settingsVerificationRead) {
                    scheduleNextStarSettingsVerificationRead(
                            "Camera settings are temporarily unavailable");
                } else if (recoverPostTerminalAuthorityReadFailure(
                        purpose,
                        "Camera settings characteristic is unavailable",
                        initializationRead,
                        false,
                        false)) {
                    // A nonterminal ordered failure owns one bounded coherent read fence.
                } else {
                    if (initializationRead) failStarInitializationReadBatch();
                    handleStarReadFailure(
                            purpose, "Camera settings characteristic is unavailable");
                }
                publishCurrent();
                pumpCommands();
                releaseWakeLockIfIdle();
                return;
            }
            long settingsReadRemainingMs = settingsVerificationRead
                    ? starSettingsDeadlineElapsedMs - SystemClock.elapsedRealtime()
                    : 0L;
            if (settingsVerificationRead && settingsReadRemainingMs <= 0L) {
                scheduleNextStarSettingsVerificationRead(
                        "Camera settings confirmation deadline expired");
                pumpCommands();
                return;
            }
            boolean started;
            try {
                started = gatt.readCharacteristic(characteristic);
            } catch (SecurityException error) {
                terminalError("Bluetooth permission was revoked");
                return;
            }
            if (!started) {
                if (settingsVerificationRead) {
                    scheduleNextStarSettingsVerificationRead(
                            "Waiting to retry the authoritative camera settings read");
                } else if (recoverPostTerminalAuthorityReadFailure(
                        purpose,
                        "Camera settings read could not start",
                        initializationRead,
                        false,
                        false)) {
                    // Recovery was scheduled or its bounded window was exhausted.
                } else if (initializationRead) {
                    failStarInitializationReadBatch();
                    handleStarReadFailure(purpose, "Camera settings read could not start");
                } else {
                    handleStarReadFailure(purpose, "Camera settings read could not start");
                }
                publishCurrent();
                pumpCommands();
                releaseWakeLockIfIdle();
                return;
            }
            starReadInFlight = purpose;
            starReadInFlightSettingsVerification = settingsVerificationRead;
            starReadInFlightInitialization = initializationRead;
            int epoch = connectionEpoch;
            cancelTransactionTimeout();
            transactionTimeout = () -> {
                if (epoch != connectionEpoch || starReadInFlight != purpose) return;
                transactionTimeout = null;
                starReadInFlight = null;
                starReadInFlightSettingsVerification = false;
                starReadInFlightInitialization = false;
                if (initializationRead && !starInitializationReadBatch) {
                    publishCurrent();
                    pumpCommands();
                    releaseWakeLockIfIdle();
                    return;
                }
                if (settingsVerificationRead) {
                    if (initializationRead) failStarInitializationReadBatch();
                    StarStateMutationSession.Snapshot current = settingsSession == null
                            ? null : settingsSession.snapshot();
                    if (current != null && !current.isReady() && !current.isTerminalFailed()) {
                        failSettingsApply(
                                "Camera did not confirm the requested exposure settings before the deadline",
                                true);
                    } else {
                        startQueuedStateMutation();
                    }
                } else {
                    if (!recoverPostTerminalAuthorityReadFailure(
                            purpose,
                            "Camera settings read timed out",
                            initializationRead,
                            false,
                            false)) {
                        if (initializationRead) failStarInitializationReadBatch();
                        handleStarReadFailure(purpose, "Camera settings read timed out");
                    }
                }
                publishCurrent();
                pumpCommands();
                releaseWakeLockIfIdle();
            };
            bluetoothHandler.postDelayed(
                    transactionTimeout,
                    settingsVerificationRead
                            ? Math.max(1L, settingsReadRemainingMs)
                            : DESCRIPTOR_TIMEOUT_MS);
            publishCurrent();
            return;
        }
        if (commandQueue.isEmpty()) {
            if (maybeAdmitSolveCapture()) return;
            maybeStartAstroRateWrite();
            if (!astroRateWriteInFlight) maybeStartKeepAwakeLeaseWrite();
            return;
        }
        Command command = commandQueue.removeFirst();
        int sequence = nextRequestSequence;
        nextRequestSequence = (nextRequestSequence + 1) & 0xff;
        byte[] value = OlympusProtocol.request(sequence, command.group, command.opcode, command.parameters);
        Pending newPending = new Pending(command, sequence, connectionEpoch);
        pending = newPending;
        writePurpose = WritePurpose.REQUEST;
        acquireWakeLock(Math.max(command.timeoutMs + 5_000L, 10_000L));
        Log.d(TAG, "TX " + command.name + " seq=" + sequence + " " + redactedHex(command, value));
        try {
            if (!GattCompat.writeCharacteristic(gatt, commandCharacteristic, value)) {
                pending = null;
                writePurpose = WritePurpose.NONE;
                rememberReleaseForCommand(command);
                command.complete(false, 0, "GATT rejected the write");
                scheduleReconnect("Camera write could not start");
                return;
            }
            scheduleTransactionTimeout(newPending);
            publishCurrent();
        } catch (SecurityException error) {
            pending = null;
            writePurpose = WritePurpose.NONE;
            command.complete(false, 0, "Bluetooth permission was revoked");
            terminalError("Bluetooth permission was revoked");
        }
    }

    /** Starts only after every foreground, STAR, settings, and stock command is idle. */
    private void maybeStartAstroRateWrite() {
        if (astroRateWriteInFlight || astroRateCharacteristic == null
                || gatt == null || phase != Phase.READY) return;
        AstroRateLease.Write write = astroRateLease.startNext(connectionEpoch);
        if (write == null) return;
        astroRateWriteInFlight = true;
        acquireWakeLock(DESCRIPTOR_TIMEOUT_MS + 5_000L);
        try {
            if (!GattCompat.writeCharacteristic(
                    gatt, astroRateCharacteristic, write.getValue())) {
                astroRateWriteInFlight = false;
                astroRateLease.completeUnapplied(connectionEpoch);
                if (write.isCancel() && starTerminalWaitingForRateRetirement) {
                    failStarTerminalRateRetirement(
                            "Could not send the terminal correction stop", true);
                } else if (write.isCancel() && astroRateLease.isCaptureRetiring()) {
                    retainCaptureRetirementAfterUnappliedCancel(
                            "Capture correction stop was not sent; terminal cleanup will retry");
                } else if (!write.isCancel() && astroRateLease.isCaptureHeld()) {
                    beginCaptureRateFailureCleanup(
                            "Capture-time correction update was not sent; stopping the prior value");
                } else {
                    failAstroRateWrite(
                            write.isCancel()
                                    ? "Could not stop sidereal correction"
                                    : "Camera rejected the unsent sidereal correction",
                            false,
                            write);
                }
                return;
            }
            if (!write.isCancel()) {
                astroRateActiveWriteStartedElapsedMs = SystemClock.elapsedRealtime();
            }
        } catch (SecurityException error) {
            astroRateWriteInFlight = false;
            AstroRateLease.Completion failed = astroRateLease.complete(connectionEpoch, false);
            if (failed != null && failed.getWrite().isCancel()
                    && starTerminalWaitingForRateRetirement) {
                failStarTerminalRateRetirement(
                        "Bluetooth permission was revoked during terminal correction stop", false);
            }
            terminalError("Bluetooth permission was revoked");
            return;
        }

        int epoch = connectionEpoch;
        cancelTransactionTimeout();
        transactionTimeout = () -> {
            if (epoch != connectionEpoch || !astroRateWriteInFlight) return;
            transactionTimeout = null;
            astroRateWriteInFlight = false;
            AstroRateLease.Completion timedOut = astroRateLease.complete(epoch, false);
            boolean cancel = timedOut != null && timedOut.getWrite().isCancel();
            if (cancel && starTerminalWaitingForRateRetirement) {
                failStarTerminalRateRetirement(
                        "Terminal correction stop timed out and will not be replayed", true);
            } else {
                failAstroRateWrite(
                        cancel
                                ? "Correction cancellation timed out"
                                : "Sidereal correction write timed out and will not be retried",
                        true,
                        timedOut == null ? null : timedOut.getWrite());
            }
        };
        bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
    }

    private void handleCharacteristicWrite(
            int epoch,
            BluetoothGatt callbackGatt,
            BluetoothGattCharacteristic characteristic,
            int statusCode) {
        if (!isCurrent(epoch, callbackGatt)) return;
        UUID uuid = characteristic.getUuid();

        if (IbisPrepositionProtocol.CHARACTERISTIC_UUID.equals(uuid)) {
            if (prepositionWriteInFlight == PrepositionWrite.NONE) return;
            cancelTransactionTimeout();
            PrepositionWrite completed = prepositionWriteInFlight;
            prepositionWriteInFlight = PrepositionWrite.NONE;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                if (completed != PrepositionWrite.CANCEL) {
                    failPrepositionCapture("Camera rejected IBIS pre-positioning; capture was not sent");
                } else {
                    prepositionStatus = "Camera could not confirm pre-position cancellation";
                }
            } else if (completed == PrepositionWrite.STAR_STAGE && starPrepositionRequested) {
                starPrepositionRequested = false;
                prepositionStatus = "Pre-positioning staged for the camera-owned capture";
                writeStarCaptureRecipeAfterPreposition();
            } else if (completed == PrepositionWrite.STOCK_APPLY
                    && prepositionCapture.applyCompleted(true)) {
                schedulePrepositionSettle();
            }
            publishCurrent();
            if (continueRequestedDisconnect()) {
                releaseWakeLockIfIdle();
                return;
            }
            pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (StarProtocol.LEASE_UUID.equals(uuid)) {
            if (!keepAwakeLeaseWriteInFlight) return;
            cancelTransactionTimeout();
            Runnable completion = keepAwakeLeaseInFlightCompletion;
            int completedSeconds = keepAwakeLeaseInFlightSeconds;
            keepAwakeLeaseWriteInFlight = false;
            keepAwakeLeaseInFlightCompletion = null;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Camera rejected keep-awake lease " + completedSeconds
                        + "s (GATT " + statusCode + "); relying on local expiry");
            }
            if (completion != null) completion.run();
            if (continueRequestedDisconnect()) {
                releaseWakeLockIfIdle();
                return;
            }
            pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (astroRateTransport != null && astroRateTransport.getRateUuid().equals(uuid)) {
            if (!astroRateWriteInFlight) return;
            cancelTransactionTimeout();
            astroRateWriteInFlight = false;
            boolean writeSucceeded = statusCode == BluetoothGatt.GATT_SUCCESS;
            boolean definitiveRejection = !writeSucceeded
                    && isDefinitiveStarWriteRejection(statusCode);
            AstroRateLease.Completion completion = definitiveRejection
                    ? astroRateLease.completeUnapplied(connectionEpoch)
                    : astroRateLease.complete(connectionEpoch, writeSucceeded);
            if (completion == null) return;
            AstroRateLease.Write completed = completion.getWrite();
            if (!completion.isSuccess()) {
                if (definitiveRejection && !completion.isLatestDesired()) {
                    correctionStatus = completed.isCancel()
                            ? "Older correction stop was rejected; publishing the latest intent"
                            : "Older correction write was rejected; publishing the latest intent";
                    astroRateDetail = correctionStatus;
                    publishCurrent();
                    pumpCommands();
                    releaseWakeLockIfIdle();
                    return;
                }
                if (!completed.isCancel()) {
                    if (completed.getGeneration() == correctionPublicationPendingGeneration) {
                        correctionPublicationPendingGeneration = -1L;
                    }
                }
                if (!completed.isCancel() && definitiveRejection
                        && astroRateLease.isCaptureHeld()) {
                    beginCaptureRateFailureCleanup(
                            "Camera rejected the capture-time correction update; stopping the prior value");
                    return;
                }
                if (completed.isCancel() && starTerminalWaitingForRateRetirement) {
                    failStarTerminalRateRetirement(
                            "Camera rejected the terminal correction stop (GATT "
                                    + statusCode + "); it will not be replayed",
                            true);
                    return;
                }
                if (completed.isCancel() && definitiveRejection
                        && astroRateLease.isCaptureRetiring()) {
                    retainCaptureRetirementAfterUnappliedCancel(
                            "Capture correction stop was rejected; terminal cleanup will retry");
                    return;
                }
                failAstroRateWrite(
                        completed.isCancel()
                                ? "Camera rejected correction cancellation"
                                : "Camera rejected the sidereal correction (GATT "
                                    + statusCode + ")",
                        !definitiveRejection,
                        completed);
                return;
            }

            if (completed.isCancel()) {
                cancelAstroRateTimers();
                if (completion.isLatestDesired()) {
                    boolean terminalRetirementOwner = starTerminalWaitingForRateRetirement
                            && astroRateLease.isCaptureRetiring(
                                    starTerminalRateRetirementEpoch,
                                    starTerminalRateRetirementSession);
                    if (terminalRetirementOwner) {
                        --starTerminalRateCancelAttemptsRemaining;
                        if (starTerminalRateCancelAttemptsRemaining > 0) {
                            if (!hasCompletedSolveOutcomeWithoutDeferredCorrection(
                                    starTerminalRateRetirementSession)) {
                                correctionStatus = "Confirming terminal correction stop ("
                                        + (STAR_TERMINAL_RATE_CANCEL_ATTEMPTS
                                            - starTerminalRateCancelAttemptsRemaining)
                                        + "/" + STAR_TERMINAL_RATE_CANCEL_ATTEMPTS + ")";
                                astroRateDetail = correctionStatus;
                            }
                            scheduleNextStarTerminalRateCancel();
                        } else {
                            finishStarTerminalRateRetirement();
                            return;
                        }
                    } else if (!astroRateLease.isCaptureRetiring()) {
                        astroRateLease.reset();
                    }
                    acknowledgedCorrection = null;
                    acknowledgedRateSetpoint = null;
                    acknowledgedCorrectionHealthy = false;
                    acknowledgedCorrectionGeneration = -1L;
                    acknowledgedSolveGeneration = -1L;
                    correctionPublicationPendingGeneration = -1L;
                    if (!terminalRetirementOwner) {
                        correctionStatus = backgroundCorrectionGate.isSuspended()
                                ? "Correction paused while the app is in the background"
                                : correctionEnabled
                                    ? "Correction stopped"
                                    : "Correction disabled";
                        astroRateDetail = correctionStatus;
                    }
                    if (starCaptureRateArming.isWaiting()
                            && astroRateLease.isCaptureRetiring(
                                    connectionEpoch, starSolveGeneration)
                            && isStarSessionActive()) {
                        if (starCaptureRateArming.zeroAcknowledged()
                                == AstroRateCaptureArming.Decision.START_WITHOUT_RATE) {
                            finishStarCaptureRateArming(false);
                            return;
                        }
                    }
                }
            } else if (completion.isLatestDesired()) {
                if (completed.getGeneration() == correctionGeneration) {
                    AstroRateSetpoint published = completed.getSetpoint();
                    acknowledgedRateSetpoint = published == null
                            ? null : astroRateTransport.acknowledgedSetpoint(published);
                    acknowledgedCorrection = acknowledgedRateSetpoint == null
                            ? null : acknowledgedRateSetpoint.getBodyRate();
                    acknowledgedCorrectionHealthy = acknowledgedRateSetpoint != null;
                    acknowledgedCorrectionGeneration = completed.getGeneration();
                    acknowledgedSolveGeneration = desiredSolveGeneration;
                }
                if (completed.getGeneration() == correctionPublicationPendingGeneration) {
                    correctionPublicationPendingGeneration = -1L;
                }
                correctionStatus =
                        "Correction published to Linux; physical application is unconfirmed";
                astroRateDetail = correctionStatus;
                if (completed.getGeneration() == correctionGeneration
                        && desiredSolveGeneration >= 0L) {
                    solveProcessing = false;
                }
                boolean admittedDeferredSolve =
                        admitDeferredStarSolveAtAcknowledgedRateBoundary();
                if (admittedDeferredSolve && astroRateLease.isCaptureHeld()) {
                    // beginStarDetectionCapture() now owns the lease and has queued its fresh
                    // arming renewal. Do not let this older completion re-arm ordinary cadence.
                    return;
                }
                Runnable deferredMutation = pendingCaptureRateMutation;
                pendingCaptureRateMutation = null;
                if (deferredMutation != null && astroRateLease.isCaptureHeld()) {
                    deferredMutation.run();
                } else {
                    scheduleAstroRateHeartbeat(completed.getGeneration());
                    if (starCaptureRateArming.isWaiting()
                            && astroRateLease.isCaptureHeld(
                                    connectionEpoch, starSolveGeneration)
                            && isStarSessionActive()) {
                        if (starCaptureRateArming.activeWriteAcknowledged()
                                == AstroRateCaptureArming.Decision.START_WITH_RATE) {
                            finishStarCaptureRateArming(true);
                            return;
                        }
                    }
                }
            }
            publishCurrent();
            pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (StarProtocol.STATE_UUID.equals(uuid)) {
            if (!starStateWriteInFlight) return;
            cancelTransactionTimeout();
            starStateWriteInFlight = false;
            if (settingsSession == null) {
                publishCurrent();
                pumpCommands();
                return;
            }
            settingsSession.onWriteComplete(statusCode == BluetoothGatt.GATT_SUCCESS);
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                if (isDefinitiveStarWriteRejection(statusCode)) {
                    failSettingsApply(
                            "Camera rejected exposure settings (GATT " + statusCode + ")", true);
                } else {
                    fenceAmbiguousStartupStarRecipeWrite();
                    failSettingsApply(
                            "Exposure settings delivery became ambiguous (GATT " + statusCode
                                    + ") and will not be retried",
                            false);
                    scheduleReconnect(
                            "Exposure settings delivery became ambiguous; mutation will not be retried");
                }
                return;
            }
            if (settingsSession.snapshot().isReady()) {
                finishSettingsBarrier();
            } else {
                scheduleStarSettingsConfirmationTimeout();
                detail = settingsSession.snapshot().getDetail();
                publishCurrent();
            }
            pumpCommands();
            return;
        }

        if (StarProtocol.CONTROL_UUID.equals(uuid)) {
            if (!starControlWriteInFlight) return;
            cancelTransactionTimeout();
            starControlWriteInFlight = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                StarCaptureSession.Snapshot current =
                        starSession == null ? null : starSession.snapshot();
                if (current == null || !current.isTerminal()) {
                    if (isDefinitiveStarWriteRejection(statusCode)) {
                        cancelStarJobTimeout();
                        if (!clearCaptureRestoreBarrier()) requireCaptureRestoreReadback();
                        if (starSession != null) {
                            starSession.transportFailed(
                                    "CAPTURE_RECIPE was rejected before acceptance (ATT "
                                            + statusCode + ")");
                            publishStarSnapshot();
                        }
                        finishStarSessionLifecycle();
                        return;
                    }
                    if (starSession != null) {
                        starSession.transportFailed(
                                "CAPTURE_RECIPE write failed ambiguously with GATT "
                                        + statusCode);
                        publishStarSnapshot();
                    }
                    failAmbiguousStarCapture("CAPTURE_RECIPE write failed");
                    return;
                }
            }
            publishCurrent();
            pumpCommands();
            return;
        }

        if (writePurpose == WritePurpose.REQUEST && pending != null
                && OlympusProtocol.COMMAND_UUID.equals(uuid)) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Command command = pending.command;
                rememberReleaseForCommand(command);
                clearPending();
                command.complete(false, 0, "Request write failed with GATT " + statusCode);
                scheduleReconnect("Request write failed");
                return;
            }
            pending.requestWriteComplete = true;
            writePurpose = WritePurpose.NONE;
            maybeWriteResultReply();
            return;
        }

        if ((writePurpose == WritePurpose.ACK || writePurpose == WritePurpose.NACK)
                && pending != null && OlympusProtocol.RESULT_UUID.equals(uuid)) {
            Pending completed = pending;
            boolean wasNack = writePurpose == WritePurpose.NACK;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                clearPending();
                rememberReleaseForCommand(completed.command);
                completed.command.complete(
                        false,
                        completed.result == null ? 0 : completed.result.getStatus(),
                        "Result acknowledgment failed with GATT " + statusCode,
                        null);
                scheduleReconnect("Result acknowledgment failed");
                return;
            }

            clearPending();
            if (wasNack || completed.protocolError != null) {
                rememberReleaseForCommand(completed.command);
                completed.command.complete(
                        false,
                        0,
                        completed.protocolError == null
                                ? "Camera result was rejected"
                                : completed.protocolError,
                        completed.result);
                scheduleReconnect("Protocol result was malformed or mismatched");
                return;
            }
            if (completed.result == null) {
                rememberReleaseForCommand(completed.command);
                completed.command.complete(false, 0, "Camera result was missing");
                scheduleReconnect("Camera result was missing");
                return;
            }
            if (completed.result.getStatus() != 0) {
                completed.command.complete(
                        false,
                        completed.result.getStatus(),
                        "Camera rejected the command",
                        completed.result);
            } else {
                completed.command.complete(true, 0, "", completed.result);
            }
            lastCompletedResultToken = completed.replyToken;
            if (disconnectRequested && completed.result.getStatus() != 0
                    && isShutterReleaseCommand(completed.command)) {
                finishDisconnectByUser();
                return;
            }
            if (continueRequestedDisconnect()) return;
            publishCurrent();
            releaseWakeLockIfIdle();
            pumpCommands();
        }
    }

    private static boolean isDefinitiveStarWriteRejection(int statusCode) {
        return statusCode == STAR_ATT_INVALID_HANDLE
                || statusCode == STAR_ATT_REQUEST_NOT_SUPPORTED
                || statusCode == STAR_ATT_INVALID_OFFSET
                || statusCode == STAR_ATT_INVALID_ATTRIBUTE_LENGTH
                || statusCode == STAR_ATT_UNLIKELY_ERROR
                || statusCode == STAR_ATT_VALUE_NOT_ALLOWED
                || statusCode == STAR_ATT_BUSY
                || statusCode == STAR_ATT_CCCD_IMPROPERLY_CONFIGURED;
    }

    private void handleNotification(
            int epoch,
            BluetoothGatt callbackGatt,
            UUID uuid,
            byte[] value,
            long receivedElapsedRealtimeNanos) {
        if (!isCurrent(epoch, callbackGatt) || value == null) return;

        if (StarProtocol.STATE_UUID.equals(uuid)) {
            if (!shouldProcessStateTelemetry()) {
                // The peer may publish IBIS/acceleration-backed STATE at 10 Hz. Parsing and
                // rebuilding snapshots while no visible or operational consumer exists needlessly
                // wakes both app loopers. A foreground transition performs an authoritative read.
                backgroundStateRefreshRequired = true;
                return;
            }
            try {
                acceptStarState(
                        StarProtocol.parseState(value), false, false, false, true,
                        receivedElapsedRealtimeNanos);
                PostTerminalCorrectionPolicy.AuthorityAction action =
                        PostTerminalCorrectionPolicy.afterState(
                                postTerminalRateMutationAwaitingState,
                                true,
                                starState != null
                                        && !starState.isRecoveryTarget()
                                        && cameraState != null,
                                exposureCatalogRefreshRequired
                                        || starInitializationReadBatch
                                        || starInitializationReadRetryRunnable != null);
                if (action
                        == PostTerminalCorrectionPolicy.AuthorityAction.REQUEST_ORDERED_STATE) {
                    requestStarStateRead();
                } else if (backgroundCorrectionResumeAwaitingState
                        && backgroundCorrectionGate.isSuspended()
                        && correctionRequested()
                        && backgroundCorrectionResumeAuthorityReady(
                            true,
                            exposureCatalogRefreshRequired,
                            starState != null && cameraState != null,
                            starState != null && starState.isRecoveryTarget(),
                            settingsSession != null,
                            captureRestoreBarrier.isBlocking())
                        && !gyroReadInFlight && starReadInFlight == null
                        && starReadQueue.isEmpty()
                        && !starInitializationReadBatch
                        && starInitializationReadRetryRunnable == null) {
                    // Notifications never release the fence directly. If every ordered owner has
                    // retired, use this fresh signal to request another authoritative read.
                    requestStarStateRead();
                }
                publishCurrent();
                // A generation change may have enqueued a coherent catalog refresh. Notifications
                // do not have a later GATT completion frame to drive the serialized owner.
                pumpCommands();
                releaseWakeLockIfIdle();
            } catch (StarProtocol.ProtocolException | IllegalArgumentException error) {
                Log.w(TAG, "Ignoring malformed STAR STATE notification", error);
                acceptGyroAttitude(null, receivedElapsedRealtimeNanos);
                cameraState = null;
                detail = STAR_STATE_INVALID_DETAIL;
                publishCurrent();
            }
            return;
        }

        if (StarProtocol.CONTROL_UUID.equals(uuid)) {
            if (starSession == null || starSession.snapshot().isTerminal()) {
                Log.w(TAG, "Ignoring STAR indication without an active capture");
                return;
            }
            try {
                starSession.acceptIndication(value);
            } catch (StarCaptureProtocol.ProtocolException error) {
                starSession.transportFailed(
                        "Invalid STAR capture indication: " + error.getMessage());
                publishStarSnapshot();
                failAmbiguousStarCapture("Invalid STAR capture indication");
                return;
            }
            StarCaptureSession.Snapshot next = starSession.snapshot();
            if (next.isTerminal() && next.isSuccessful()) {
                lastStarCaptureWallTimeMs = System.currentTimeMillis();
            }
            publishStarSnapshot();
            publishCurrent();
            if (next.isTerminal()) finishStarSessionLifecycle();
            return;
        }

        if (OlympusProtocol.COMMAND_UUID.equals(uuid)) {
            if (pending != null
                    && OlympusProtocol.isReceiptFor(value, pending.requestSequence)) {
                pending.receiptSeen = true;
            }
            return;
        }
        if (OlympusProtocol.EVENT_UUID.equals(uuid)) {
            Log.d(TAG, "Camera async event: " + OlympusProtocol.toHex(value));
            return;
        }
        if (!OlympusProtocol.RESULT_UUID.equals(uuid)) return;

        if (pending == null) {
            Log.w(TAG, "Ignoring result with no transaction: " + OlympusProtocol.toHex(value));
            return;
        }
        if (pending.replyToken >= 0) {
            Log.w(TAG, "Ignoring duplicate result for " + pending.command.name);
            return;
        }
        try {
            OlympusProtocol.ResultFrame result = OlympusProtocol.parseResult(value);
            if (result.getToken() == lastCompletedResultToken) {
                pending.protocolError = "Duplicate result token from the prior transaction";
                pending.replyToken = result.getToken();
                pending.replyIsAck = false;
            } else if (!pending.receiptSeen) {
                pending.protocolError = "Result arrived before the matching request receipt";
                pending.replyToken = result.getToken();
                pending.replyIsAck = false;
            } else if (result.getGroup() != pending.command.group
                    || result.getOpcode() != pending.command.opcode) {
                pending.protocolError = "Result did not match the outstanding command";
                pending.replyToken = result.getToken();
                pending.replyIsAck = false;
            } else {
                pending.result = result;
                pending.replyToken = result.getToken();
                pending.replyIsAck = true;
            }
        } catch (OlympusProtocol.ProtocolException error) {
            int token = OlympusProtocol.tokenIfPresent(value);
            if (token < 0) {
                rememberReleaseForCommand(pending.command);
                scheduleReconnect("Malformed camera result: " + error.getMessage());
                return;
            }
            pending.protocolError = "Malformed camera result: " + error.getMessage();
            pending.replyToken = token;
            pending.replyIsAck = false;
        }
        maybeWriteResultReply();
    }

    private void maybeWriteResultReply() {
        if (pending == null || !pending.requestWriteComplete || pending.replyToken < 0) return;
        if (writePurpose != WritePurpose.NONE) return;
        byte[] reply = pending.replyIsAck
                ? OlympusProtocol.ack(pending.replyToken)
                : OlympusProtocol.nack(pending.replyToken);
        writePurpose = pending.replyIsAck ? WritePurpose.ACK : WritePurpose.NACK;
        Pending expected = pending;
        // A result received near the request deadline still deserves a fresh bounded
        // window for its mandatory ACK/NACK ATT write.
        cancelTransactionTimeout();
        try {
            if (resultCharacteristic == null
                    || !GattCompat.writeCharacteristic(gatt, resultCharacteristic, reply)) {
                Command command = pending.command;
                rememberReleaseForCommand(command);
                clearPending();
                command.complete(false, 0, "GATT rejected the result acknowledgment");
                scheduleReconnect("Could not acknowledge camera result");
                return;
            }
            scheduleResultReplyTimeout(expected);
        } catch (SecurityException error) {
            terminalError("Bluetooth permission was revoked");
        }
    }

    private void halfPressDownInternal() {
        if (!canAcceptControl() || halfPressRequested || halfHeld
                || fullPressRequested || fullHeld
                || pending != null || !commandQueue.isEmpty()) return;
        if (!setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE)) {
            detail = "Could not persist the safety release; AF press was not sent";
            publishCurrent();
            return;
        }
        halfPressRequested = true;
        halfReleaseQueued = false;
        enqueue(shutterCommand(OlympusProtocol.ACTION_HALF_PRESS, (success, resultStatus, error) -> {
            if (success) {
                halfHeld = true;
            } else {
                // A valid camera rejection proves the requested state transition did
                // not occur. Transport/protocol failures keep the conservative marker.
                if (resultStatus != 0) setRecoveryReleaseAction(0);
                halfHeld = false;
                if (!halfReleaseQueued) halfPressRequested = false;
                showCommandError("AF press", resultStatus, error);
            }
            publishCurrent();
        }));
        publishCurrent();
    }

    private void halfPressUpInternal() {
        if ((!halfPressRequested && !halfHeld) || halfReleaseQueued) return;
        if (fullPressRequested || fullHeld) {
            // The camera's action 04 must release full press back to AF before action
            // 02 can release AF. Upgrade the eventual full release accordingly.
            releaseHalfAfterFullRelease = true;
            return;
        }
        halfReleaseQueued = true;
        enqueue(shutterCommand(OlympusProtocol.ACTION_HALF_RELEASE,
                (success, resultStatus, error) -> {
                    if (success) {
                        halfHeld = false;
                        halfPressRequested = false;
                        halfReleaseQueued = false;
                        setRecoveryReleaseAction(0);
                    } else {
                        // Keep the release operation retryable if the camera rejected it
                        // or transport delivery was ambiguous.
                        halfHeld = true;
                        halfPressRequested = true;
                        halfReleaseQueued = false;
                        setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE);
                        showCommandError("AF release", resultStatus, error);
                    }
                    publishCurrent();
                }));
        publishCurrent();
    }

    private void shutterDownInternal() {
        if (!canAcceptControl() || fullPressRequested || fullHeld
                || pending != null || !commandQueue.isEmpty()) return;
        beginAdmittedShutterPress();
    }

    private boolean beginAdmittedShutterPress() {
        boolean pressOverHalf = halfHeld;
        if (!setRecoveryReleaseAction(pressOverHalf
                ? OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF
                : OlympusProtocol.ACTION_COMBINED_RELEASE)) {
            detail = "Could not persist the safety release; shutter press was not sent";
            publishCurrent();
            return false;
        }
        fullPressOverHalf = pressOverHalf;
        fullPressRequested = true;
        releaseQueued = false;
        fullHeldSinceElapsed = SystemClock.elapsedRealtime();
        int action = fullPressOverHalf
                ? OlympusProtocol.ACTION_FULL_PRESS_OVER_HALF
                : OlympusProtocol.ACTION_COMBINED_PRESS;
        enqueue(shutterCommand(action, (success, resultStatus, error) -> {
            if (success) {
                fullHeld = true;
                if (fullHeldSinceElapsed <= 0) {
                    fullHeldSinceElapsed = SystemClock.elapsedRealtime();
                }
                if (prepositionCapture.fullPressCompleted(true)) {
                    beginPrepositionExposureInterval();
                }
            } else {
                fullHeld = false;
                if (resultStatus != 0) {
                    // Action 03 started with AF held; action 05 started fully released.
                    // Restore that prior obligation only when the camera explicitly
                    // rejected the press. Ambiguous delivery keeps the release marker.
                    setRecoveryReleaseAction(fullPressOverHalf
                            ? OlympusProtocol.ACTION_HALF_RELEASE
                            : 0);
                }
                if (!releaseQueued) fullPressRequested = false;
                showCommandError("Shutter press", resultStatus, error);
                if (prepositionPlan != null && !starPrepositionPinned) {
                    abortPreposition("Shutter press failed; pre-positioning cancelled", false);
                }
            }
            publishCurrent();
        }));
        publishCurrent();
        return true;
    }

    private void shutterUpInternal() {
        if ((!fullPressRequested && !fullHeld) || releaseQueued) return;
        releaseQueued = true;
        int action = fullPressOverHalf
                ? OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF
                : OlympusProtocol.ACTION_COMBINED_RELEASE;
        enqueue(shutterCommand(action, (success, resultStatus, error) -> {
            if (success) {
                fullHeld = false;
                fullPressRequested = false;
                releaseQueued = false;
                fullHeldSinceElapsed = 0;
                setRecoveryReleaseAction(fullPressOverHalf
                        ? OlympusProtocol.ACTION_HALF_RELEASE
                        : 0);
                // Read the live flag here. Release All may have arrived after action 04
                // was already pending and upgraded the required end state to fully up.
                boolean releaseHalfToo = fullPressOverHalf && releaseHalfAfterFullRelease;
                releaseHalfAfterFullRelease = false;
                if (releaseHalfToo) {
                    enqueue(shutterCommand(OlympusProtocol.ACTION_HALF_RELEASE,
                            (halfSuccess, halfStatus, halfError) -> {
                                if (halfSuccess) {
                                    halfHeld = false;
                                    halfPressRequested = false;
                                    halfReleaseQueued = false;
                                    setRecoveryReleaseAction(0);
                                } else {
                                    halfHeld = true;
                                    halfPressRequested = true;
                                    halfReleaseQueued = false;
                                    setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE);
                                    showCommandError("AF release", halfStatus, halfError);
                                }
                                publishCurrent();
                            }));
                }
            } else {
                // Keep the release control visible: a rejected/ambiguous release must
                // be treated conservatively as still held and remain retryable.
                fullPressRequested = true;
                fullHeld = true;
                releaseQueued = false;
                showCommandError("Shutter release", resultStatus, error);
            }
            publishCurrent();
        }));
        publishCurrent();
    }

    private boolean takePhotoInternal() {
        // A stale notification/UI click must never turn a complete-click action into
        // the release of an exposure that is already deliberately held.
        if (!canAcceptControl() || fullPressRequested || fullHeld) return false;
        if (shouldPrepositionStockCapture()) return beginPrepositionedStockCapture();
        if (!beginAdmittedShutterPress()) return false;
        shutterUpInternal();
        return true;
    }

    private boolean takeSolveCapturePhotoInternal() {
        if (!canAcceptControlWorkflow()
                || halfPressRequested || halfHeld
                || fullPressRequested || fullHeld
                || pending != null || !commandQueue.isEmpty()) {
            return false;
        }
        if (shouldPrepositionStockCapture()) return beginPrepositionedStockCapture();
        if (!beginAdmittedShutterPress()) return false;
        shutterUpInternal();
        return true;
    }

    private boolean shouldPrepositionStockCapture() {
        return prepositionEnabled && correctionEnabled && correctionFactorPercent > 0
                && !halfPressRequested && !halfHeld
                && acknowledgedCorrectionHealthy && acknowledgedCorrection != null;
    }

    private IbisPrepositionPlanner.Plan planPreposition(ExposureCatalogs.Selection exposure) {
        if (ibisPrepositionCharacteristic == null || cameraState == null
                || !cameraState.hasFocalLength() || exposure == null
                || !acknowledgedCorrectionHealthy || acknowledgedCorrection == null
                || correctionGeneration != acknowledgedCorrectionGeneration
                || !astroRateLease.wantsSetpoint()
                || astroRateWriteInFlight || astroRateLease.hasPendingWrite()
                || acknowledgedRateSetpoint == null
                || acknowledgedRateSetpoint.getGave0Offset() != 0
                || acknowledgedRateSetpoint.getGave1Offset() != 0) return null;
        return IbisPrepositionPlanner.plan(cameraState.getFocalLengthMm(),
                acknowledgedCorrection, IbisPrepositionCapture.exposureSeconds(exposure.getShutter()));
    }

    private boolean beginPrepositionedStockCapture() {
        if (halfPressRequested || halfHeld || prepositionTrajectoryPinned()) return false;
        ExposureCatalogs.Selection exposure = currentCameraSelection();
        IbisPrepositionPlanner.Plan plan = planPreposition(exposure);
        if (plan == null || !plan.isAvailable()) {
            solveCaptureTransaction.cancel();
            prepositionStatus = "Pre-positioning needs a fixed exposure and current tracking rate";
            publishCurrent();
            return false;
        }
        if (!setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE)) {
            prepositionStatus = "Could not prepare the shutter safety release";
            publishCurrent();
            return false;
        }
        if (!prepositionCapture.begin()) return false;
        prepositionPlan = plan;
        stockPrepositionExposureSeconds = IbisPrepositionCapture.exposureSeconds(exposure.getShutter());
        halfPressRequested = true;
        halfReleaseQueued = false;
        prepositionStatus = "Preparing IBIS before capture";
        enqueue(shutterCommand(OlympusProtocol.ACTION_HALF_PRESS, (success, resultStatus, error) -> {
            if (success) {
                halfHeld = true;
                if (prepositionCapture.halfPressCompleted(true)) {
                    queuePrepositionWrite(PrepositionWrite.STOCK_APPLY,
                            IbisPrepositionProtocol.ACTION_APPLY_AFTER_HALF_PRESS, prepositionPlan);
                } else {
                    halfPressUpInternal();
                }
            } else {
                if (resultStatus != 0) setRecoveryReleaseAction(0);
                halfHeld = false;
                if (!halfReleaseQueued) halfPressRequested = false;
                failPrepositionCapture("Camera half press failed; capture was not sent");
            }
            publishCurrent();
        }));
        publishCurrent();
        return true;
    }

    private void queuePrepositionWrite(
            PrepositionWrite purpose, int action, IbisPrepositionPlanner.Plan plan) {
        prepositionPendingWrite = purpose;
        prepositionPendingValue = IbisPrepositionProtocol.arm(
                plan.getLogicalX(), plan.getLogicalY(), plan.getLogicalRoll(), action);
        pumpCommands();
    }

    private void queuePrepositionCancel() {
        if (ibisPrepositionCharacteristic == null || gatt == null || phase != Phase.READY) return;
        prepositionPendingWrite = PrepositionWrite.CANCEL;
        prepositionPendingValue = IbisPrepositionProtocol.cancel();
    }

    private void startPrepositionWrite() {
        PrepositionWrite purpose = prepositionPendingWrite;
        byte[] value = prepositionPendingValue;
        prepositionPendingWrite = PrepositionWrite.NONE;
        prepositionPendingValue = null;
        if (gatt == null || ibisPrepositionCharacteristic == null || phase != Phase.READY) {
            if (purpose != PrepositionWrite.CANCEL) {
                failPrepositionCapture("IBIS pre-positioning is unavailable; capture was not sent");
            }
            return;
        }
        prepositionWriteInFlight = purpose;
        acquireWakeLock(DESCRIPTOR_TIMEOUT_MS + IbisPrepositionProtocol.SETTLE_MS + 5_000L);
        try {
            if (!GattCompat.writeCharacteristic(gatt, ibisPrepositionCharacteristic, value)) {
                prepositionWriteInFlight = PrepositionWrite.NONE;
                if (purpose != PrepositionWrite.CANCEL) {
                    failPrepositionCapture("IBIS pre-position write could not start; capture was not sent");
                }
                pumpCommands();
                return;
            }
            int epoch = connectionEpoch;
            cancelTransactionTimeout();
            transactionTimeout = () -> {
                if (epoch != connectionEpoch || prepositionWriteInFlight != purpose) return;
                transactionTimeout = null;
                prepositionWriteInFlight = PrepositionWrite.NONE;
                failPrepositionCapture("IBIS pre-position write timed out; capture was not sent");
                scheduleReconnect("IBIS pre-position delivery is unknown; it will not be retried");
            };
            bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
        } catch (SecurityException error) {
            prepositionWriteInFlight = PrepositionWrite.NONE;
            failPrepositionCapture("Bluetooth permission was revoked; capture was not sent");
            terminalError("Bluetooth permission was revoked");
        }
    }

    private void schedulePrepositionSettle() {
        prepositionStatus = "Waiting for IBIS to settle";
        int epoch = connectionEpoch;
        prepositionSettleRunnable = () -> {
            prepositionSettleRunnable = null;
            if (epoch != connectionEpoch || !prepositionCapture.isPreparing()) return;
            // Keep the renewable RATE value alive throughout settling. A due GATT operation
            // gets to retire first, so full press never overlaps a RATE or pre-position write.
            if (pending != null || writePurpose != WritePurpose.NONE
                    || astroRateWriteInFlight || astroRateLease.hasPendingWrite()
                    || prepositionWriteInFlight != PrepositionWrite.NONE
                    || prepositionPendingWrite != PrepositionWrite.NONE) {
                prepositionSettleRunnable = this::finishPrepositionSettleWhenIdle;
                bluetoothHandler.postDelayed(prepositionSettleRunnable,
                        AstroRateGattProtocol.APP_FAST_RETRY_MS);
                return;
            }
            finishPrepositionSettleWhenIdle();
        };
        bluetoothHandler.postDelayed(prepositionSettleRunnable, IbisPrepositionProtocol.SETTLE_MS);
    }

    private void finishPrepositionSettleWhenIdle() {
        prepositionSettleRunnable = null;
        if (!prepositionCapture.isPreparing()) return;
        if (pending != null || writePurpose != WritePurpose.NONE
                || astroRateWriteInFlight || astroRateLease.hasPendingWrite()
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || prepositionPendingWrite != PrepositionWrite.NONE) {
            prepositionSettleRunnable = this::finishPrepositionSettleWhenIdle;
            bluetoothHandler.postDelayed(prepositionSettleRunnable,
                    AstroRateGattProtocol.APP_FAST_RETRY_MS);
            return;
        }
        if (phase != Phase.READY || !halfHeld || !acknowledgedCorrectionHealthy
                || !prepositionCapture.settleCompleted()) {
            failPrepositionCapture("Pre-positioned capture was interrupted before full press");
            return;
        }
        IbisPrepositionPlanner.Plan capturePlan = prepositionPlan;
        int captureEpoch = connectionEpoch;
        if (!beginAdmittedShutterPress()) {
            failPrepositionCapture("Could not prepare the shutter safety release");
            return;
        }
        // enqueue() can synchronously fail and retire the connection. Do not arm a new timer
        // or dereference the discarded plan after that failure.
        if (captureEpoch != connectionEpoch || phase != Phase.READY
                || capturePlan == null || prepositionPlan != capturePlan
                || !fullPressRequested) return;
        releaseHalfAfterFullRelease = true;
        shutterUpInternal();
        prepositionStatus = "Starting capture with extra tracking travel";
        publishCurrent();
    }

    private void beginPrepositionExposureInterval() {
        // The BLE click releases buttons, not the exposure. Retain PRE ownership for the
        // nominal shutter duration plus a lag allowance after the stock fullpress response.
        // The initial travel plan stays fixed; qualified adaptive RATE updates remain enabled.
        // Queued GATT work must not consume this interval; no exposure-end feedback exists.
        long holdMs = (long) Math.ceil(stockPrepositionExposureSeconds * 1000.0) + 1_500L;
        stockPrepositionExposureUntilMs = SystemClock.elapsedRealtime() + holdMs;
        prepositionExposureRunnable = () -> {
            prepositionExposureRunnable = null;
            stockPrepositionExposureUntilMs = 0L;
            prepositionPlan = null;
            queuePrepositionCancel();
            prepositionStatus = "Pre-position capture interval finished";
            publishCurrent();
            pumpCommands();
            releaseWakeLockIfIdle();
        };
        bluetoothHandler.postDelayed(prepositionExposureRunnable, holdMs);
        prepositionStatus = prepositionPlan.isExposureClipped()
                ? "Capture started; exposure exceeds estimated tracking travel"
                : "Capture started with extra tracking travel";
        publishCurrent();
    }

    private boolean prepositionTrajectoryPinned() {
        return prepositionCapture.isPreparing() || starPrepositionPinned
                // Retire on the Handler callback, not merely wall time. Otherwise an older
                // queued UI action could start a new capture before the overdue cleanup runs.
                || stockPrepositionExposureUntilMs > 0L;
    }

    private void abortPreposition(String message, boolean releaseHalf) {
        boolean hadPreposition = prepositionTrajectoryPinned() || starPrepositionRequested
                || prepositionPlan != null
                || prepositionPendingWrite != PrepositionWrite.NONE
                || prepositionWriteInFlight != PrepositionWrite.NONE;
        boolean stockPreparing = prepositionCapture.isPreparing()
                || (prepositionPlan != null && !starPrepositionPinned
                    && (halfPressRequested || halfHeld));
        prepositionCapture.cancel();
        if (prepositionSettleRunnable != null) bluetoothHandler.removeCallbacks(prepositionSettleRunnable);
        if (prepositionExposureRunnable != null) bluetoothHandler.removeCallbacks(prepositionExposureRunnable);
        prepositionSettleRunnable = null;
        prepositionExposureRunnable = null;
        stockPrepositionExposureUntilMs = 0L;
        starPrepositionRequested = false;
        starPrepositionPinned = false;
        prepositionPlan = null;
        if (hadPreposition) {
            prepositionStatus = message;
            queuePrepositionCancel();
        }
        if (stockPreparing && releaseHalf) halfPressUpInternal();
    }

    private void failPrepositionCapture(String message) {
        boolean starWaiting = starPrepositionRequested || starPrepositionPinned;
        abortPreposition(message, true);
        prepositionStatus = message;
        solveCaptureTransaction.cancel();
        if ((starWaiting || (prepositionEnabled && isStarSessionActive()))
                && starSession != null && !starSession.snapshot().isTerminal()) {
            starSession.transportFailed(message);
            publishStarSnapshot();
            finishStarSessionLifecycle();
        }
        publishCurrent();
    }

    private void releaseAllInternal() {
        if (prepositionCapture.isPreparing()) {
            abortPreposition("Pre-positioned capture cancelled", true);
            pumpCommands();
            return;
        }
        if (fullPressRequested || fullHeld) {
            releaseHalfAfterFullRelease = fullPressOverHalf;
            shutterUpInternal();
        } else if (halfPressRequested || halfHeld) {
            halfPressUpInternal();
        }
    }

    private void toggleMovieInternal() {
        if (!canAcceptControl() || pending != null || halfPressRequested || halfHeld
                || fullPressRequested || fullHeld) return;
        enqueue(new Command("Movie toggle", 0x04, 0x10, new byte[]{0x01}, SHUTTER_TIMEOUT_MS,
                (success, resultStatus, error) -> {
                    if (success) movieState = movieState == 1 ? 0 : 1;
                    else showCommandError("Movie toggle", resultStatus, error);
                    publishCurrent();
                }));
    }

    private void powerOnInternal() {
        if (!canAcceptControl() || pending != null || halfPressRequested || halfHeld
                || fullPressRequested || fullHeld) return;
        enqueue(new Command("Power on", 0x0f, 0x01, new byte[]{0x02}, SETUP_TIMEOUT_MS,
                (success, resultStatus, error) -> {
                    if (!success) showCommandError("Wake camera", resultStatus, error);
                    publishCurrent();
                }));
    }

    private void setAppVisiblyForegroundInternal(boolean visiblyForeground) {
        if (foregroundLeaseScheduler == null) return;
        boolean wasInteractiveVisible = isAppInteractiveVisible();
        activityVisiblyForeground = visiblyForeground;
        if (visiblyForeground) screenInteractive = queryScreenInteractive();
        reconcileForegroundKeepAwakeGate();
        reconcileInteractiveVisibility(wasInteractiveVisible);
    }

    private void handleScreenInteractiveChanged(boolean announcedInteractive) {
        if (!BackgroundPowerPolicy.applyScreenInteractiveBroadcast(announcedInteractive)) {
            // SCREEN_ON is not proof that this Activity is visible: keyguard may still own the
            // display. Keep screen-off policy until MainActivity reports a fresh onResume.
            return;
        }
        boolean wasInteractiveVisible = isAppInteractiveVisible();
        screenInteractive = announcedInteractive && queryScreenInteractive();
        // SCREEN_OFF always cancels immediately. SCREEN_ON only re-arms when the independently
        // tracked Activity-visible signal is also true.
        reconcileForegroundKeepAwakeGate();
        reconcileInteractiveVisibility(wasInteractiveVisible);
    }

    private void reconcileInteractiveVisibility(boolean wasInteractiveVisible) {
        boolean interactiveVisible = isAppInteractiveVisible();
        if (interactiveVisible == wasInteractiveVisible) return;
        if (interactiveVisible) {
            handleInteractiveForeground();
        } else {
            handleInteractiveHidden();
        }
    }

    private void handleInteractiveForeground() {
        boolean stateNotificationsWereDropped = dropIdleStateNotifications;
        dropIdleStateNotifications = false;
        if (reconnectDeferredUntilForeground && desiredConnected) {
            reconnectDeferredUntilForeground = false;
            beginConfiguredConnection(true);
            return;
        }
        if (starInitializationDeferredUntilForeground
                && phase == Phase.READY
                && starTransportReady) {
            requestStarInitializationReads();
        }
        boolean correctionNeedsAuthoritativeState = backgroundCorrectionGate.isSuspended()
                && correctionRequested()
                && desiredConnected
                && !disconnectRequested
                && phase == Phase.READY
                && starTransportReady;
        if (correctionNeedsAuthoritativeState) {
            backgroundCorrectionResumeAwaitingState = true;
            correctionStatus = "Correction paused until camera state refresh completes";
            astroRateDetail = correctionStatus;
        }
        if ((stateNotificationsWereDropped
                || backgroundStateRefreshRequired
                || correctionNeedsAuthoritativeState)
                && phase == Phase.READY
                && starTransportReady) {
            backgroundStateRefreshRequired = false;
            // Keep retained RATE behind the gate until this read (or its coherent-bank recovery)
            // succeeds. Merely queuing the read is not enough because RATE has higher pump priority.
            requestStarStateRead();
        }
        if (!backgroundCorrectionResumeAwaitingState) {
            resumeBackgroundSuspendedCorrection();
        }
    }

    private void handleInteractiveHidden() {
        backgroundCorrectionResumeAwaitingState = false;
        boolean connectionAttempt = connectionAttemptInProgress(
                phase, scanRunning && scanForConnection, reconnectRunnable != null);
        boolean urgentRecovery = connectionAttempt && ensureUrgentShutterRecoveryWindow();
        if (connectionAttempt && urgentRecovery) {
            connectionAttemptUrgentRecovery = true;
            // A connection that began while visible may own a longer lock. Shorten it to the
            // same finite safety deadline as every hidden recovery attempt.
            acquireWakeLock(urgentShutterRecoveryRemainingMs());
        }
        if (desiredConnected
                && connectionAttempt
                && BackgroundPowerPolicy.deferReconnect(
                        isAppInteractiveVisible(), urgentRecovery)) {
            deferReconnectUntilForeground("Camera reconnect paused while the app is hidden");
        }
        deferredStarSolveAdmission.cancelCaptureAfterSolve();
        solveCaptureTransaction.cancel();
        if (!correctionCaptureCriticalWork()
                && (starInitializationReadBatch
                    || starReadInFlightInitialization
                    || starInitializationReadRetryRunnable != null)) {
            deferOrdinaryStarInitializationUntilForeground();
        }
        suspendIdleCorrectionForBackground();
        refreshStateNotificationPowerGate();
        releaseWakeLockIfIdle();
    }

    private boolean isAppInteractiveVisible() {
        return BackgroundPowerPolicy.interactiveVisible(
                activityVisiblyForeground, screenInteractive);
    }

    static boolean connectionAttemptInProgress(
            Phase phase, boolean connectionScanRunning, boolean reconnectScheduled) {
        if (connectionScanRunning || reconnectScheduled) return true;
        if (phase == null) return false;
        switch (phase) {
            case CONNECTING:
            case DISCOVERING:
            case SUBSCRIBING:
            case AUTHENTICATING:
            case ENTERING_REMOTE:
            case RECONNECTING:
                return true;
            default:
                return false;
        }
    }

    private void reconcileForegroundKeepAwakeGate() {
        boolean interactiveNow = queryScreenInteractive();
        if (!interactiveNow) screenInteractive = false;
        boolean eligible = ForegroundLeaseEligibility.isEligible(
                activityVisiblyForeground, screenInteractive, interactiveNow);
        if (eligible) {
            // An unsent background/screen-off cancellation is superseded before the immediate
            // renewal. A zero already in flight remains ordered before that renewal.
            if (keepAwakeLeasePendingSeconds != null
                    && keepAwakeLeasePendingSeconds == 0
                    && keepAwakeLeasePendingCompletion == null) {
                keepAwakeLeasePendingSeconds = null;
            }
            foregroundLeaseScheduler.setForeground(true);
            return;
        }

        foregroundLeaseScheduler.setForeground(false);
    }

    /** Fresh system query used at both timer admission and queued-write start. */
    private boolean verifyForegroundKeepAwakeEligibility() {
        boolean interactiveNow = queryScreenInteractive();
        boolean eligible = ForegroundLeaseEligibility.isEligible(
                activityVisiblyForeground, screenInteractive, interactiveNow);
        if (!eligible && !interactiveNow) screenInteractive = false;
        return eligible;
    }

    private boolean queryScreenInteractive() {
        try {
            return powerManager != null && powerManager.isInteractive();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not query screen-interactive state", error);
            return false;
        }
    }

    private boolean shutterRecoveryRequired() {
        return recoveryReleaseAction != 0
                || halfPressRequested
                || halfHeld
                || fullPressRequested
                || fullHeld;
    }

    private boolean ensureUrgentShutterRecoveryWindow() {
        boolean required = shutterRecoveryRequired();
        long nowMs = SystemClock.elapsedRealtime();
        if (shutterRecoveryPowerWindow.ensureStarted(required, nowMs)) {
            recoveryUrgentUntilEpochMs = saturatingAdd(
                    System.currentTimeMillis(), SHUTTER_RECOVERY_URGENT_WAKE_MS);
            recoveryUrgentUntilElapsedMs = saturatingAdd(
                    nowMs, SHUTTER_RECOVERY_URGENT_WAKE_MS);
            recoveryUrgentBootCount = currentBootCount();
            if (recoveryReleaseAction != 0) {
                persistRecoveryReleaseState();
            }
            scheduleUrgentShutterRecoveryDeadline();
        }
        return shutterRecoveryPowerWindow.isUrgent(required, nowMs);
    }

    private void restoreUrgentShutterRecoveryWindow() {
        if (recoveryReleaseAction == 0) return;
        long nowWallMs = System.currentTimeMillis();
        long nowElapsedMs = SystemClock.elapsedRealtime();
        recoveryUrgentUntilEpochMs = credentials.getPendingReleaseUrgentUntilEpochMs();
        recoveryUrgentUntilElapsedMs = credentials.getPendingReleaseUrgentUntilElapsedMs();
        recoveryUrgentBootCount = credentials.getPendingReleaseUrgentBootCount();
        int currentBootCount = currentBootCount();
        if (recoveryUrgentUntilEpochMs <= 0L || recoveryUrgentUntilElapsedMs <= 0L) {
            // One-time migration for a release marker written before the episode deadline was
            // persisted. Subsequent sticky-service recreations restore this exact deadline.
            shutterRecoveryPowerWindow.ensureStarted(true, nowElapsedMs);
            recoveryUrgentUntilEpochMs = saturatingAdd(
                    nowWallMs, SHUTTER_RECOVERY_URGENT_WAKE_MS);
            recoveryUrgentUntilElapsedMs = saturatingAdd(
                    nowElapsedMs, SHUTTER_RECOVERY_URGENT_WAKE_MS);
            recoveryUrgentBootCount = currentBootCount;
            persistRecoveryReleaseState();
            return;
        }
        long remainingMs = restoredRecoveryRemainingMs(
                SHUTTER_RECOVERY_URGENT_WAKE_MS,
                recoveryUrgentUntilElapsedMs,
                recoveryUrgentBootCount,
                nowElapsedMs,
                currentBootCount);
        shutterRecoveryPowerWindow.restoreRemaining(true, remainingMs, nowElapsedMs);
    }

    private void restartUrgentShutterRecoveryWindow() {
        boolean required = shutterRecoveryRequired();
        shutterRecoveryPowerWindow.restart(required, SystemClock.elapsedRealtime());
        recoveryUrgentUntilEpochMs = required
                ? saturatingAdd(System.currentTimeMillis(), SHUTTER_RECOVERY_URGENT_WAKE_MS)
                : 0L;
        recoveryUrgentUntilElapsedMs = required
                ? saturatingAdd(SystemClock.elapsedRealtime(), SHUTTER_RECOVERY_URGENT_WAKE_MS)
                : 0L;
        recoveryUrgentBootCount = required ? currentBootCount() : -1;
        if (recoveryReleaseAction != 0) {
            persistRecoveryReleaseState();
        }
        scheduleUrgentShutterRecoveryDeadline();
    }

    private void scheduleUrgentShutterRecoveryDeadline() {
        if (bluetoothHandler == null) return;
        cancelUrgentShutterRecoveryDeadline();
        long remainingMs = urgentShutterRecoveryRemainingMs();
        if (remainingMs <= 0L) return;
        shutterRecoveryDeadlineRunnable = () -> {
            shutterRecoveryDeadlineRunnable = null;
            boolean urgentAttemptOwnedTransport = connectionAttemptUrgentRecovery;
            connectionAttemptUrgentRecovery = false;
            if (isAppInteractiveVisible()) {
                releaseWakeLockIfIdle();
                return;
            }
            if (shutterRecoveryRequired()
                    && (urgentAttemptOwnedTransport
                        || connectionAttemptInProgress(
                            phase, scanRunning && scanForConnection, reconnectRunnable != null))) {
                deferReconnectUntilForeground(
                        "Safe release is still pending—open the app to retry");
                return;
            }
            releaseWakeLock();
        };
        // Handler delay uses uptime while the deadline uses elapsed realtime. Scheduling just
        // before the timed partial lock expires ensures this callback runs before deep sleep can
        // resume; every urgent acquisition re-arms it from the current remaining budget.
        bluetoothHandler.postDelayed(
                shutterRecoveryDeadlineRunnable,
                Math.max(1L, remainingMs - SHUTTER_RECOVERY_CLEANUP_LEAD_MS));
    }

    private void cancelUrgentShutterRecoveryDeadline() {
        if (shutterRecoveryDeadlineRunnable != null && bluetoothHandler != null) {
            bluetoothHandler.removeCallbacks(shutterRecoveryDeadlineRunnable);
        }
        shutterRecoveryDeadlineRunnable = null;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static long restoredRecoveryRemainingMs(
            long maximumDurationMs,
            long storedElapsedDeadlineMs,
            int storedBootCount,
            long nowElapsedMs,
            int currentBootCount) {
        boolean differentKnownBoot = storedBootCount >= 0
                && currentBootCount >= 0
                && storedBootCount != currentBootCount;
        if (maximumDurationMs <= 0L
                || differentKnownBoot
                || storedElapsedDeadlineMs <= nowElapsedMs) {
            return 0L;
        }
        return Math.min(maximumDurationMs, storedElapsedDeadlineMs - nowElapsedMs);
    }

    private int currentBootCount() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read Android boot count", error);
            return -1;
        }
    }

    private boolean persistRecoveryReleaseState() {
        boolean persisted = credentials.setPendingReleaseState(
                recoveryReleaseAction,
                recoveryUrgentUntilEpochMs,
                recoveryUrgentUntilElapsedMs,
                recoveryUrgentBootCount);
        if (!persisted) Log.e(TAG, "Could not persist shutter-recovery power state");
        return persisted;
    }

    private boolean urgentShutterRecoveryRequired() {
        return shutterRecoveryPowerWindow.isUrgent(
                shutterRecoveryRequired(), SystemClock.elapsedRealtime());
    }

    private long urgentShutterRecoveryRemainingMs() {
        return shutterRecoveryPowerWindow.remainingMs(
                shutterRecoveryRequired(), SystemClock.elapsedRealtime());
    }

    private boolean correctionCaptureCriticalWork() {
        return prepositionTrajectoryPinned()
                || shutterRecoveryRequired()
                || isStarSessionActive()
                || astroRateLease.isCaptureHeld()
                || astroRateLease.isCaptureRetiring()
                || deferredStarSolveAdmission.isPending()
                || starTerminalWaitingForRateRetirement
                || (postTerminalRateMutationAwaitingState
                    && (pendingCaptureRateMutation != null || solveProcessing));
    }

    private boolean correctionRequested() {
        return hasActiveRateIntent(desiredRateSetpoint);
    }

    static boolean hasActiveRateIntent(AstroRateSetpoint setpoint) {
        return setpoint != null && setpoint.isActive();
    }

    private boolean shouldSuspendCorrectionForBackground() {
        // Once hidden idle has crossed the suspension boundary, unrelated work that starts later
        // (for example a lock-screen shutter action) must not implicitly rearm retained RATE.
        if (!isAppInteractiveVisible() && backgroundCorrectionGate.isSuspended()) return true;
        return BackgroundPowerPolicy.correctionMode(
                isAppInteractiveVisible(),
                correctionRequested(),
                correctionCaptureCriticalWork())
                == BackgroundPowerPolicy.CorrectionMode.SUSPEND;
    }

    private boolean shouldProcessStateTelemetry() {
        boolean operationallyRequired = correctionCaptureCriticalWork()
                || disconnectRequested
                || shutterRecoveryRequired()
                || pending != null
                || !commandQueue.isEmpty()
                || writePurpose != WritePurpose.NONE
                || starControlWriteInFlight
                || starStateWriteInFlight
                || starStateWritePending != null
                || detectorConfigWriteInFlight
                || detectorConfigPending != null
                || keepAwakeLeaseWriteInFlight
                || keepAwakeLeasePendingSeconds != null
                || astroRateWriteInFlight
                || astroRateLease.hasPendingWrite()
                || gyroReadInFlight || starReadInFlight != null
                || !starReadQueue.isEmpty()
                || exposureWritePending()
                || solveProcessing
                || deferredStarSolveAdmission.isPending()
                || solveCaptureTransaction.isPending();
        return BackgroundPowerPolicy.processStateTelemetry(
                isAppInteractiveVisible(), operationallyRequired);
    }

    private void refreshStateNotificationPowerGate() {
        dropIdleStateNotifications = !shouldProcessStateTelemetry();
    }

    private void queueBestEffortKeepAwakeCancel() {
        // If a renewal was accepted by the scheduler but has not reached GATT, retire it before
        // queuing the best-effort zero. An already-started renewal is ordered before that zero.
        Runnable canceledRenewal = null;
        if (keepAwakeLeasePendingCompletion != null) {
            canceledRenewal = keepAwakeLeasePendingCompletion;
            keepAwakeLeasePendingCompletion = null;
            keepAwakeLeasePendingSeconds = null;
        }
        if (canceledRenewal != null) canceledRenewal.run();

        if (phase != Phase.READY || gatt == null || keepAwakeLeaseCharacteristic == null) return;
        if (keepAwakeLeaseWriteInFlight && keepAwakeLeaseInFlightSeconds == 0) return;
        keepAwakeLeasePendingSeconds = 0;
        keepAwakeLeasePendingCompletion = null;
        pumpCommands();
    }

    /** Accepts one renewal into the serialized GATT owner, even when functional work is busy. */
    private boolean queueForegroundKeepAwakeRenewal(Runnable completion) {
        if (!verifyForegroundKeepAwakeEligibility()) {
            foregroundLeaseScheduler.setForeground(false);
            return false;
        }
        if (completion == null
                || phase != Phase.READY
                || gatt == null
                || keepAwakeLeaseCharacteristic == null
                || keepAwakeLeasePendingCompletion != null) {
            return false;
        }
        keepAwakeLeasePendingSeconds = KEEP_AWAKE_LEASE_SECONDS;
        keepAwakeLeasePendingCompletion = completion;
        pumpCommands();
        return true;
    }

    /** Lowest-priority Assist write: camera-local expiry remains authoritative on every failure. */
    private void maybeStartKeepAwakeLeaseWrite() {
        if (keepAwakeLeaseWriteInFlight
                || keepAwakeLeasePendingSeconds == null
                || keepAwakeLeaseCharacteristic == null
                || gatt == null
                || phase != Phase.READY) {
            return;
        }
        if (keepAwakeLeasePendingSeconds > 0
                && !verifyForegroundKeepAwakeEligibility()) {
            foregroundLeaseScheduler.setForeground(false);
            // The ineligible callback converted the stale renewal to a best-effort zero.
            if (keepAwakeLeasePendingSeconds == null) return;
        }
        int seconds = keepAwakeLeasePendingSeconds;
        Runnable completion = keepAwakeLeasePendingCompletion;
        keepAwakeLeasePendingSeconds = null;
        keepAwakeLeasePendingCompletion = null;
        keepAwakeLeaseWriteInFlight = true;
        keepAwakeLeaseInFlightSeconds = seconds;
        keepAwakeLeaseInFlightCompletion = completion;
        byte[] value = StarProtocol.encodeLeaseSeconds(seconds);
        acquireWakeLock(DESCRIPTOR_TIMEOUT_MS + 5_000L);
        try {
            if (!GattCompat.writeCharacteristic(gatt, keepAwakeLeaseCharacteristic, value)) {
                keepAwakeLeaseWriteInFlight = false;
                keepAwakeLeaseInFlightCompletion = null;
                if (completion != null) completion.run();
                pumpCommands();
                releaseWakeLockIfIdle();
                return;
            }
        } catch (SecurityException error) {
            keepAwakeLeaseWriteInFlight = false;
            keepAwakeLeaseInFlightCompletion = null;
            terminalError("Bluetooth permission was revoked");
            return;
        }

        int epoch = connectionEpoch;
        cancelTransactionTimeout();
        transactionTimeout = () -> {
            if (epoch != connectionEpoch || !keepAwakeLeaseWriteInFlight) return;
            transactionTimeout = null;
            Runnable timedOutCompletion = keepAwakeLeaseInFlightCompletion;
            keepAwakeLeaseWriteInFlight = false;
            keepAwakeLeaseInFlightCompletion = null;
            scheduleReconnect("Camera keep-awake lease write timed out");
            if (timedOutCompletion != null) timedOutCompletion.run();
        };
        bluetoothHandler.postDelayed(transactionTimeout, DESCRIPTOR_TIMEOUT_MS);
    }

    private Command shutterCommand(int action, Completion completion) {
        return new Command(
                "Shutter action " + action,
                0x04,
                0x0f,
                new byte[]{(byte) action},
                SHUTTER_TIMEOUT_MS,
                completion);
    }

    private static boolean isShutterReleaseCommand(Command command) {
        if (command == null || command.group != 0x04 || command.opcode != 0x0f
                || command.parameters.length != 1) return false;
        int action = command.parameters[0] & 0xff;
        return action == OlympusProtocol.ACTION_HALF_RELEASE
                || action == OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF
                || action == OlympusProtocol.ACTION_COMBINED_RELEASE;
    }

    private boolean canAcceptControl() {
        return CameraFeatureAdmission.canStartStockMutation(
                canAcceptControlWorkflow(),
                solveCaptureTransaction.isPending(),
                captureRestoreBarrier.isBlocking());
    }

    private boolean canAcceptControlWorkflow() {
        return canAcceptControlBase()
                && !solveProcessing
                && !deferredStarSolveAdmission.isPending()
                && correctionPublicationPendingGeneration < 0L
                && !((astroRateWriteInFlight || astroRateLease.hasPendingWrite())
                    && !astroRateLease.wantsSetpoint())
                && !detectorConfigWriteInFlight
                && detectorConfigPending == null;
    }

    /**
     * Atomically converts the service-owned solved intent into one complete stock shutter click.
     * The transaction is consumed only on the BLE handler, after restored STATE and every earlier
     * serialized CONTROL/write/read owner have retired.
     */
    private boolean maybeAdmitSolveCapture() {
        boolean correctionRequired = correctionEnabled && correctionFactorPercent > 0;
        if (!solveCaptureTransaction.isReady(
                    correctionRequired,
                    desiredSolveGeneration,
                    acknowledgedSolveGeneration,
                    correctionGeneration,
                    acknowledgedCorrectionGeneration)
                || !settingsReadyForShutter()
                || captureRestoreBarrier.isBlocking()
                || !canAcceptControlWorkflow()
                || halfPressRequested
                || halfHeld
                || fullPressRequested
                || fullHeld) {
            return false;
        }

        // No callback can interleave on this Handler. The transaction remains live until the
        // complete shutter click has actually entered the stock-command owner.
        boolean admitted = takeSolveCapturePhotoInternal();
        boolean consumed = solveCaptureTransaction.consumeIfAdmitted(
                admitted,
                correctionRequired,
                desiredSolveGeneration,
                acknowledgedSolveGeneration,
                correctionGeneration,
                acknowledgedCorrectionGeneration);
        if (!admitted || !consumed) {
            return false;
        }
        correctionStatus = prepositionCapture.isPreparing()
                ? "Plate solved; preparing IBIS before capture"
                : "Plate solved; stock capture started";
        publishCurrent();
        return true;
    }

    private boolean canAcceptControlBase() {
        boolean connectionReady = desiredConnected && phase == Phase.READY && gatt != null;
        boolean stockCharacteristicsReady = commandCharacteristic != null
                && resultCharacteristic != null
                && eventCharacteristic != null;
        boolean controlPathIdle = pending == null && commandQueue.isEmpty()
                && !prepositionTrajectoryPinned()
                && prepositionPendingWrite == PrepositionWrite.NONE
                && prepositionWriteInFlight == PrepositionWrite.NONE
                && writePurpose == WritePurpose.NONE
                && !starControlWriteInFlight && !starStateWriteInFlight
                && !keepAwakeLeaseWriteInFlight
                && starStateWritePending == null
                && !gyroReadInFlight && starReadInFlight == null && starReadQueue.isEmpty()
                && !exposureWritePending()
                && !starTerminalWaitingForRateRetirement
                && !isStarSessionActive();
        return CameraFeatureAdmission.canUseStockControls(
                connectionReady, stockCharacteristicsReady, controlPathIdle);
    }

    private void scheduleTransactionTimeout(Pending expected) {
        cancelTransactionTimeout();
        transactionTimeout = () -> {
            if (pending != expected) return;
            Command command = expected.command;
            rememberReleaseForCommand(command);
            clearPending();
            command.complete(false, 0, "Timed out; delivery state is unknown");
            scheduleReconnect(command.name + " timed out; it will not be repeated");
        };
        bluetoothHandler.postDelayed(transactionTimeout, expected.command.timeoutMs);
    }

    private void scheduleResultReplyTimeout(Pending expected) {
        cancelTransactionTimeout();
        transactionTimeout = () -> {
            if (pending != expected
                    || (writePurpose != WritePurpose.ACK && writePurpose != WritePurpose.NACK)) return;
            Command command = expected.command;
            rememberReleaseForCommand(command);
            clearPending();
            command.complete(false, 0, "Timed out acknowledging the camera result");
            scheduleReconnect("Camera result acknowledgment timed out");
        };
        bluetoothHandler.postDelayed(transactionTimeout, RESULT_REPLY_TIMEOUT_MS);
    }

    private void clearPending() {
        cancelTransactionTimeout();
        pending = null;
        writePurpose = WritePurpose.NONE;
    }

    private void schedulePhaseTimeout(int epoch, long timeoutMs, String reason) {
        cancelPhaseTimeout();
        phaseTimeout = () -> {
            if (epoch == connectionEpoch && desiredConnected) scheduleReconnect(reason);
        };
        bluetoothHandler.postDelayed(phaseTimeout, timeoutMs);
    }

    private void cancelPhaseTimeout() {
        if (phaseTimeout != null) bluetoothHandler.removeCallbacks(phaseTimeout);
        phaseTimeout = null;
    }

    private void cancelTransactionTimeout() {
        if (transactionTimeout != null) bluetoothHandler.removeCallbacks(transactionTimeout);
        transactionTimeout = null;
    }

    private void scheduleReconnect(String reason) {
        if (disconnectRequested) {
            finishDisconnectByUser();
            return;
        }
        if (!desiredConnected) return;
        rememberReleaseForRecovery();
        stopScanInternal();
        cancelPhaseTimeout();
        cancelTransactionTimeout();
        commandQueue.clear();
        pending = null;
        writePurpose = WritePurpose.NONE;
        closeGatt();
        releaseWakeLock();
        boolean urgentRecovery = ensureUrgentShutterRecoveryWindow();
        if (BackgroundPowerPolicy.deferReconnect(
                isAppInteractiveVisible(), urgentRecovery)) {
            String deferredReason = shutterRecoveryRequired()
                    ? reason + "; safe release is still pending—open the app to retry"
                    : reason + "; retry when the app is visible";
            deferReconnectUntilForeground(deferredReason);
            return;
        }
        int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[index];
        reconnectAttempt++;
        if (BackgroundPowerPolicy.holdWakeLockForReconnectBackoff(urgentRecovery)) {
            connectionAttemptUrgentRecovery = true;
            acquireWakeLock(Math.min(
                    delay + CONNECTION_WAKE_LOCK_MS,
                    urgentShutterRecoveryRemainingMs()));
        }
        publish(Phase.RECONNECTING, "Reconnecting",
                reason + "; retrying in " + Math.max(1, delay / 1000) + " s");
        cancelReconnect();
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            boolean stillUrgentRecovery = ensureUrgentShutterRecoveryWindow();
            if (BackgroundPowerPolicy.deferReconnect(
                    isAppInteractiveVisible(), stillUrgentRecovery)) {
                deferReconnectUntilForeground(
                        shutterRecoveryRequired()
                                ? "Safe release is still pending—open the app to retry"
                                : "Camera reconnect paused while the app is hidden");
                return;
            }
            beginConfiguredConnection(true);
        };
        bluetoothHandler.postDelayed(reconnectRunnable, delay);
    }

    private void deferReconnectUntilForeground(String reason) {
        cancelReconnect();
        cancelPhaseTimeout();
        stopScanInternal();
        if (gatt != null) closeGatt();
        connectionAttemptUrgentRecovery = false;
        reconnectDeferredUntilForeground = true;
        releaseWakeLock();
        publish(Phase.RECONNECTING, "Connection paused", reason);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) bluetoothHandler.removeCallbacks(reconnectRunnable);
        reconnectRunnable = null;
    }

    private void disconnectByUser(int stopStartId) {
        if (starPrepositionRequested) failPrepositionCapture("Pre-positioned capture cancelled by disconnect");
        else if (prepositionCapture.isPreparing()) {
            abortPreposition("Pre-positioned capture cancelled by disconnect", true);
        }
        disconnectStopStartId = Math.max(disconnectStopStartId, stopStartId);
        if (disconnectRequested) {
            // A second request is an explicit immediate disconnect. The conservative
            // recovery marker below is retained if the graceful release was incomplete.
            finishDisconnectByUser();
            return;
        }
        // Fence renewals for this connection without forgetting that a still-visible Activity
        // should arm a fresh lease after a later reconnect. Then serialize one best-effort zero
        // before closing; camera detach is authoritative if the write cannot complete.
        if (foregroundLeaseScheduler != null) {
            foregroundLeaseScheduler.setConnectionReady(false);
        }
        queueBestEffortKeepAwakeCancel();
        if (phase == Phase.READY && gatt != null
                && (pending != null || !commandQueue.isEmpty()
                    || fullPressRequested || fullHeld || halfPressRequested || halfHeld
                    || starStateWriteInFlight || starStateWritePending != null
                    || gyroReadInFlight || starReadInFlight != null || !starReadQueue.isEmpty()
                    || keepAwakeLeaseWriteInFlight
                    || prepositionPendingWrite != PrepositionWrite.NONE
                    || prepositionWriteInFlight != PrepositionWrite.NONE
                    || keepAwakeLeasePendingSeconds != null
                    || exposureWritePending() || isStarSessionActive())) {
            disconnectRequested = true;
            detail = "Releasing camera controls before disconnect";
            publishCurrent();
            disconnectTimeout = this::finishDisconnectByUser;
            bluetoothHandler.postDelayed(disconnectTimeout, GRACEFUL_DISCONNECT_TIMEOUT_MS);
            // CAPTURE_RECIPE has no abort operation. Keep draining its confirmed
            // indications until the camera publishes a terminal result.
            continueRequestedDisconnect();
            return;
        }
        finishDisconnectByUser();
    }

    /** Returns true when the service was disconnected synchronously. */
    private boolean continueRequestedDisconnect() {
        if (!disconnectRequested) return false;
        if (phase != Phase.READY || gatt == null) {
            finishDisconnectByUser();
            return true;
        }
        if (pending != null || !commandQueue.isEmpty()
                || prepositionPendingWrite != PrepositionWrite.NONE
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || starStateWriteInFlight || starStateWritePending != null
                || gyroReadInFlight || starReadInFlight != null || !starReadQueue.isEmpty()
                || keepAwakeLeaseWriteInFlight
                || keepAwakeLeasePendingSeconds != null
                || exposureWritePending()) return false;
        if (isStarSessionActive()) return false;
        if (fullPressRequested || fullHeld) {
            releaseHalfAfterFullRelease = fullPressOverHalf;
            shutterUpInternal();
            return false;
        }
        if (halfPressRequested || halfHeld) {
            halfPressUpInternal();
            return false;
        }
        finishDisconnectByUser();
        return true;
    }

    private void cancelDisconnectTimeout() {
        if (disconnectTimeout != null) bluetoothHandler.removeCallbacks(disconnectTimeout);
        disconnectTimeout = null;
    }

    private void finishDisconnectByUser() {
        rememberReleaseForRecovery();
        disconnectRequested = false;
        cancelDisconnectTimeout();
        desiredConnected = false;
        reconnectDeferredUntilForeground = false;
        credentials.setDesiredConnected(false);
        cancelReconnect();
        stopScanInternal();
        cancelPhaseTimeout();
        cancelTransactionTimeout();
        commandQueue.clear();
        pending = null;
        writePurpose = WritePurpose.NONE;
        try {
            if (gatt != null && hasConnectPermission()) gatt.disconnect();
        } catch (SecurityException ignored) {
            // close() below is still attempted.
        }
        closeGatt();
        resetLogicalState();
        releaseWakeLock();
        publish(Phase.DISCONNECTED, "Disconnected", "Connection stopped by user");
        int stopStartId = disconnectStopStartId > 0
                ? disconnectStopStartId : activeConnectionStartId;
        disconnectStopStartId = 0;
        stopStartedServiceIfCurrent(stopStartId);
    }

    private void terminalError(String message) {
        disconnectRequested = false;
        cancelDisconnectTimeout();
        desiredConnected = false;
        reconnectDeferredUntilForeground = false;
        credentials.setDesiredConnected(false);
        stopScanInternal();
        cancelReconnect();
        cancelPhaseTimeout();
        cancelTransactionTimeout();
        commandQueue.clear();
        pending = null;
        writePurpose = WritePurpose.NONE;
        closeGatt();
        resetLogicalState();
        releaseWakeLock();
        publish(Phase.ERROR, "Connection error", message);
        int stopStartId = activeConnectionStartId;
        stopStartedServiceIfCurrent(stopStartId);
    }

    private void stopStartedServiceIfCurrent(int stopStartId) {
        mainHandler.post(() -> {
            if (stopStartId > 0 && !stopSelfResult(stopStartId)) return;
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
                foregroundNotificationState = null;
            }
            if (stopStartId <= 0) stopSelf();
        });
    }

    private void handleAdapterState(int state) {
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            rememberReleaseForRecovery();
            stopScanInternal();
            closeGatt();
            releaseWakeLock();
            if (desiredConnected) {
                publish(Phase.WAITING_FOR_BLUETOOTH, "Waiting for Bluetooth", "Connection will resume when Bluetooth is on");
            }
        } else if (state == BluetoothAdapter.STATE_ON && desiredConnected) {
            reconnectAttempt = 0;
            boolean urgentRecovery = ensureUrgentShutterRecoveryWindow();
            if (BackgroundPowerPolicy.deferReconnect(
                    isAppInteractiveVisible(), urgentRecovery)) {
                deferReconnectUntilForeground(
                        shutterRecoveryRequired()
                                ? "Bluetooth is on; safe release is pending—open the app to retry"
                                : "Bluetooth is on; reconnect when the app is visible");
            } else {
                beginConfiguredConnection(true);
            }
        }
    }

    private void rememberReleaseForRecovery() {
        if (pending != null) rememberReleaseForCommand(pending.command);
        if (fullPressRequested || fullHeld) {
            setRecoveryReleaseAction(fullPressOverHalf
                    ? OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF
                    : OlympusProtocol.ACTION_COMBINED_RELEASE);
        } else if ((halfPressRequested || halfHeld) && recoveryReleaseAction == 0) {
            setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE);
        }
        resetLogicalState();
        movieState = -1;
    }

    private void rememberReleaseForCommand(Command command) {
        if (command == null || command.group != 0x04 || command.opcode != 0x0f
                || command.parameters.length != 1) return;
        int action = command.parameters[0] & 0xff;
        if (action == OlympusProtocol.ACTION_HALF_PRESS
                || action == OlympusProtocol.ACTION_HALF_RELEASE) {
            setRecoveryReleaseAction(OlympusProtocol.ACTION_HALF_RELEASE);
        } else if (action == OlympusProtocol.ACTION_FULL_PRESS_OVER_HALF
                || action == OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF) {
            setRecoveryReleaseAction(OlympusProtocol.ACTION_FULL_RELEASE_TO_HALF);
        } else if (action == OlympusProtocol.ACTION_COMBINED_PRESS
                || action == OlympusProtocol.ACTION_COMBINED_RELEASE) {
            setRecoveryReleaseAction(OlympusProtocol.ACTION_COMBINED_RELEASE);
        }
    }

    private boolean setRecoveryReleaseAction(int action) {
        if (!credentials.setPendingReleaseState(
                action,
                action == 0 ? 0L : recoveryUrgentUntilEpochMs,
                action == 0 ? 0L : recoveryUrgentUntilElapsedMs,
                action == 0 ? -1 : recoveryUrgentBootCount)) {
            Log.e(TAG, "Could not persist safety-critical shutter release state");
            return false;
        }
        recoveryReleaseAction = action;
        if (action == 0) {
            shutterRecoveryPowerWindow.clear();
            cancelUrgentShutterRecoveryDeadline();
            connectionAttemptUrgentRecovery = false;
            recoveryUrgentUntilEpochMs = 0L;
            recoveryUrgentUntilElapsedMs = 0L;
            recoveryUrgentBootCount = -1;
        }
        return true;
    }

    private void resetLogicalState() {
        halfHeld = false;
        halfPressRequested = false;
        halfReleaseQueued = false;
        fullPressRequested = false;
        fullHeld = false;
        fullPressOverHalf = false;
        releaseQueued = false;
        releaseHalfAfterFullRelease = false;
        fullHeldSinceElapsed = 0;
    }

    private void clearTransportState() {
        prepositionCapture.cancel();
        if (prepositionSettleRunnable != null) bluetoothHandler.removeCallbacks(prepositionSettleRunnable);
        if (prepositionExposureRunnable != null) bluetoothHandler.removeCallbacks(prepositionExposureRunnable);
        prepositionSettleRunnable = null;
        prepositionExposureRunnable = null;
        prepositionPendingWrite = PrepositionWrite.NONE;
        prepositionWriteInFlight = PrepositionWrite.NONE;
        prepositionPendingValue = null;
        prepositionPlan = null;
        starPrepositionRequested = false;
        starPrepositionPinned = false;
        stockPrepositionExposureUntilMs = 0L;
        prepositionedSolveNeedsCenteredReference = false;
        ibisPrepositionCharacteristic = null;
        cancelPhaseTimeout();
        cancelTransactionTimeout();
        cancelStarJobTimeout();
        if (foregroundLeaseScheduler != null) {
            foregroundLeaseScheduler.setConnectionReady(false);
        }
        commandQueue.clear();
        connectionAttemptUrgentRecovery = false;
        pending = null;
        writePurpose = WritePurpose.NONE;
        commandCharacteristic = null;
        resultCharacteristic = null;
        eventCharacteristic = null;
        astroRateCharacteristic = null;
        astroRateTransport = null;
        keepAwakeLeaseCharacteristic = null;
        cancelAstroRateTimers();
        cancelAstroRateCaptureCancel();
        pendingCaptureRateMutation = null;
        postTerminalRateMutationAwaitingState = false;
        starCaptureRateArming.reset();
        astroRateLease.reset();
        astroRateWriteInFlight = false;
        astroRateActiveWriteStartedElapsedMs = -1L;
        backgroundCorrectionGate.clear();
        backgroundCorrectionResumeAwaitingState = false;
        starTerminalWaitingForRateRetirement = false;
        starTerminalRateCancelAttemptsRemaining = 0;
        starTerminalRateRetirementSession = -1L;
        deferredStarSolveAdmission.clear();
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        lastSolvedResult = null;
        lastSolvedIntentId = -1L;
        lastSolvedDurationNanos = SolveSnapshot.DURATION_UNAVAILABLE_NANOS;
        lastSolvedElapsedRealtimeNanos = -1L;
        activeAcceptedSolveIntentId = -1L;
        clearSolveQ10References();
        clearCaptureRateReference();
        correctionPublicationPendingGeneration = -1L;
        correctionSource = null;
        desiredCorrectionAttitude = null;
        desiredCorrection = null;
        desiredRateSetpoint = null;
        acknowledgedCorrection = null;
        acknowledgedRateSetpoint = null;
        acknowledgedCorrectionHealthy = false;
        desiredSolveGeneration = -1L;
        acknowledgedSolveGeneration = -1L;
        acknowledgedCorrectionGeneration = -1L;
        correctionStatus = correctionEnabled
                ? "Connection changed; solve or apply to start correction"
                : "Correction disabled";
        astroRateDetail = correctionStatus;
        astroRateSolveContext = null;
        pendingManualGravitySolveContext = null;
        detectorConfigPending = null;
        detectorConfigInFlight = null;
        detectorConfigWriteInFlight = false;
        keepAwakeLeasePendingSeconds = null;
        keepAwakeLeasePendingCompletion = null;
        keepAwakeLeaseWriteInFlight = false;
        keepAwakeLeaseInFlightCompletion = null;
        detectorConfig = StarTelemetry.DEFAULT_CONFIG;
        detectorConfigKnown = false;
        detectorStatus = "Detector settings unavailable until STAR state is read";
        ibisSample = null;
        ibisReceivedElapsedMs = 0L;
        cameraAccelerationTracker.clear();
        clearStarCharacteristics();
        starTransportReady = false;
        starNegotiatedMtu = 23;
        cameraState = null;
        starState = null;
        starCatalogFence.clear();
        fullExposureCatalogs = null;
        exposureCatalogs = null;
        clearFilteredCatalogCache();
        shutterCatalog = null;
        apertureCatalog = null;
        isoCatalog = null;
        lastMissingCatalogStateKey = "";
        awaitingStarMtu = false;
        starControlWriteInFlight = false;
        starStateWriteInFlight = false;
        starStateWritePending = null;
        starReadQueue.clear();
        starReadInFlight = null;
        starReadInFlightSettingsVerification = false;
        starReadInFlightInitialization = false;
        starInitializationReadBatch = false;
        starFenceFreshConnectionBaseline = false;
        resetStarInitializationReadRetry();
        starInitializationRecoveryGate.resetForConnection(connectionEpoch);
        settingsVerificationReadRequired = false;
        cancelStarSettingsTimeout();
        settingsSession = null;
        stateMutationKind = StateMutationKind.NONE;
        persistentSettingsIntent.clear();
        starRecipeIntent.clear();
        exposureCatalogRefreshRequired = false;
        apertureBoundsRefreshRequired = false;
        expectedDescriptor = null;
        subscriptionCharacteristics = Collections.emptyList();
        backgroundStateRefreshRequired = false;
    }

    private void closeGatt() {
        invalidatePlateSolve("Plate solution cleared because the camera connection changed");
        if (isStarSessionActive()) disconnectStarSession();
        if (settingsWritePending() && settingsSession != null) {
            settingsSession.transportFailed(
                    "Camera disconnected; exposure settings will not be retried");
        }
        if (stateMutationKind == StateMutationKind.STARTUP_RECIPE) {
            fenceAmbiguousStartupStarRecipeWrite();
            if (starRecipeStartupReset.isWriting()) {
                starRecipeStartupReset.writeFailed(
                        "Camera disconnected during the unified STAR state write; it will not be retried");
            }
        }
        if (settingsWritePending()) lastCameraSettingsIntentAccepted = false;
        if (starRecipeWritePending()) lastStarRecipeIntentAccepted = false;
        BluetoothGatt old = gatt;
        gatt = null;
        ++connectionEpoch;
        clearTransportState();
        if (old != null) {
            try {
                old.close();
            } catch (SecurityException ignored) {
                // Object is discarded regardless.
            }
        }
    }

    private boolean isCurrent(int epoch, BluetoothGatt callbackGatt) {
        return epoch == connectionEpoch && callbackGatt == gatt;
    }

    private void publish(Phase newPhase, String newStatus, String newDetail) {
        phase = newPhase;
        status = newStatus == null ? "" : newStatus;
        detail = newDetail == null ? "" : newDetail;
        if (foregroundLeaseScheduler != null) {
            foregroundLeaseScheduler.setConnectionReady(
                    phase == Phase.READY
                            && gatt != null
                            && keepAwakeLeaseCharacteristic != null);
        }
        publishCurrent();
    }

    private void publishCurrent() {
        String cameraName = configuredCamera == null ? "" : configuredCamera.name;
        boolean halfShownHeld = halfHeld || (halfPressRequested && !halfReleaseQueued);
        boolean shutterShownHeld = fullHeld || (fullPressRequested && !releaseQueued);
        boolean visiblePending = pending != null;
        boolean visibleQueue = !commandQueue.isEmpty();
        boolean visibleGattWrite = writePurpose != WritePurpose.NONE
                || prepositionPendingWrite != PrepositionWrite.NONE
                || prepositionWriteInFlight != PrepositionWrite.NONE
                || prepositionTrajectoryPinned()
                || starControlWriteInFlight || starStateWriteInFlight
                || detectorConfigWriteInFlight
                || starTerminalWaitingForRateRetirement;
        boolean settingsPending = settingsWritePending();
        boolean recipePending = starRecipeWritePending();
        boolean shutterReady = settingsReadyForShutter();
        boolean captureReady = settingsReadyForCapture();
        String publishedDetail = detail;
        if (captureRestoreBarrier.isRecoveryRequired()) {
            String recoveryDetail = captureRestoreBarrier.recoveryDetail(
                    cameraIdentity(configuredCamera), currentCameraSelection());
            publishedDetail = publishedDetail == null || publishedDetail.isEmpty()
                    ? recoveryDetail : publishedDetail + "; " + recoveryDetail;
        }
        boolean rateWorkflowBusy = correctionPublicationPendingGeneration >= 0L
                || ((!astroRateLease.wantsSetpoint())
                    && (astroRateWriteInFlight || astroRateLease.hasPendingWrite()));
        boolean astroWorkflowBusy = solveProcessing
                || prepositionTrajectoryPinned()
                || deferredStarSolveAdmission.isPending()
                || solveCaptureTransaction.isPending()
                || rateWorkflowBusy;
        long nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        boolean currentLensIdentityAuthoritative = phase == Phase.READY
                && starState != null
                && !starState.isRecoveryTarget()
                && cameraState != null;
        float currentFocalLengthMm = currentLensIdentityAuthoritative
                && cameraState.hasFocalLength()
                ? cameraState.getFocalLengthMm() : Float.NaN;
        String currentLensName = currentLensIdentityAuthoritative
                && cameraState.hasLensName() ? cameraState.getLensName() : "";
        TrackingExposureEstimator.Estimate trackingExposureEstimate =
                TrackingExposureEstimator.estimateRecoveredQ10Proxy(
                        correctionEnabled,
                        astroRateLease.isPossiblyActive(),
                        acknowledgedCorrectionHealthy,
                        correctionGeneration,
                        acknowledgedCorrectionGeneration,
                        currentFocalLengthMm,
                        acknowledgedCorrection,
                        ibisSample);
        String shownPrepositionStatus = prepositionStatus;
        if (prepositionEnabled && !prepositionTrajectoryPinned()) {
            IbisPrepositionPlanner.Plan preview = planPreposition(currentCameraSelection());
            if (preview != null && preview.isAvailable()) {
                shownPrepositionStatus += String.format(java.util.Locale.US,
                        ". Estimated tracking capacity %.1f s%s", preview.getMaximumSeconds(),
                        preview.isExposureClipped() ? "; selected exposure is longer" : "");
            }
        }
        if (prepositionedSolveNeedsCenteredReference) {
            shownPrepositionStatus += ". Tracking uses the solved image; turn pre-positioning off"
                    + " and solve again to resume automatic adjustments";
        }
        AstroSnapshot astro = new AstroSnapshot(
                correctionEnabled,
                correctionFactorPercent,
                manualOverride,
                manualRa,
                manualDec,
                lastSolvedResult != null,
                lastSolvedResult,
                lastSolvedIntentId,
                lastSolvedDurationNanos,
                lastSolvedElapsedRealtimeNanos,
                lastSolvedResult != null && cameraObserverIntentId == lastSolvedIntentId
                        ? cameraObserverContext : null,
                correctionSource,
                desiredCorrection,
                acknowledgedCorrection,
                desiredRateSetpoint,
                acknowledgedRateSetpoint,
                acknowledgedCorrectionHealthy,
                correctionGeneration,
                solveProcessing ? starSolveGeneration : -1L,
                desiredSolveGeneration,
                acknowledgedSolveGeneration,
                acknowledgedCorrectionGeneration,
                lastSolveIntentId,
                lastSolveIntentAccepted,
                astroWorkflowBusy,
                correctionStatus,
                trackingModelStatus(),
                lastStarCaptureWallTimeMs,
                starTransportReady && phase == Phase.READY,
                detectorConfig,
                detectorConfigKnown,
                detectorConfigWriteInFlight || detectorConfigPending != null,
                detectorStatus,
                plateSolverSettings,
                plateSolverSettingsStatus,
                starState != null && starState.hasIbisAvailable()
                        && ibisSample != null && phase == Phase.READY,
                ibisSample,
                ibisReceivedElapsedMs,
                currentFocalLengthMm,
                currentLensName,
                starState != null && starState.hasAccelerationAvailable()
                        && phase == Phase.READY,
                cameraAccelerationTracker.getLatestSample(),
                cameraAccelerationTracker.getLatestReceiptElapsedRealtimeNanos(),
                cameraAccelerationTracker.estimate(nowElapsedRealtimeNanos),
                cameraAttitudeEstimator.estimate(nowElapsedRealtimeNanos),
                trackingExposureEstimate,
                ibisPrepositionCharacteristic != null && phase == Phase.READY,
                prepositionEnabled,
                shownPrepositionStatus);
        Snapshot next = new Snapshot(
                phase,
                status,
                publishedDetail,
                cameraName,
                desiredConnected,
                scanRunning,
                phase == Phase.READY,
                visiblePending || visibleQueue
                        || visibleGattWrite
                        || gyroReadInFlight || starReadInFlight != null || !starReadQueue.isEmpty()
                        || settingsPending || recipePending
                        || detectorConfigWriteInFlight || detectorConfigPending != null
                        || astroWorkflowBusy
                        || isStarSessionActive(),
                !captureRestoreBarrier.isBlocking(),
                halfShownHeld,
                shutterShownHeld,
                fullPressOverHalf,
                starTransportReady && phase == Phase.READY,
                astroRateCharacteristic != null && phase == Phase.READY,
                astroRateLease.isPossiblyActive(),
                astroRateDetail,
                astro,
                starTransportReady && exposureCatalogs != null
                        && cameraState != null
                        && starState != null
                        && starState.hasCompleteMutableState()
                        && detectorConfigKnown
                        && !exposureCatalogRefreshRequired
                        && starRecipeIntent.getAuthoritative() != null
                        && phase == Phase.READY,
                settingsPending,
                shutterReady,
                captureReady,
                exposureCatalogs,
                appliedSelection(),
                persistentSettingsIntent.getDesired(),
                starRecipeIntent.getAuthoritative(),
                starRecipeIntent.getDesired(),
                recipePending,
                lastCameraSettingsIntentId,
                lastCameraSettingsIntentAccepted,
                lastStarRecipeIntentId,
                lastStarRecipeIntentAccepted,
                cameraState,
                connectionEpoch,
                movieState,
                fullHeldSinceElapsed);
        snapshot = next;
        refreshStateNotificationPowerGate();
        for (Listener listener : listeners) {
            mainHandler.post(() -> listener.onStateChanged(next));
        }
        if (foreground) {
            mainHandler.post(() -> updateForegroundNotification(next));
        }
    }

    private void updateForegroundNotification(Snapshot state) {
        if (!foreground) return;
        ForegroundNotificationState rendered = renderForegroundNotification(state);
        if (rendered.equals(foregroundNotificationState)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(NOTIFICATION_ID, buildNotification(rendered));
        foregroundNotificationState = rendered;
    }

    private boolean promoteToForeground() {
        if (foreground) return true;
        try {
            ForegroundNotificationState rendered = renderForegroundNotification(snapshot);
            Notification notification = buildNotification(rendered);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            foregroundNotificationState = rendered;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start connected-device foreground service", error);
            desiredConnected = false;
            credentials.setDesiredConnected(false);
            stopSelf();
            return false;
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private ForegroundNotificationState renderForegroundNotification(Snapshot state) {
        String notificationDetail = foregroundNotificationDetail(
                state.detail,
                state.astro.desiredRateSetpoint,
                state.astro.correctionStatus);
        return ForegroundNotificationState.create(
                getString(R.string.app_name),
                state.cameraName,
                state.status,
                notificationDetail,
                state.ready,
                state.busy,
                state.stockMutationAllowed,
                state.shutterHeld,
                state.halfHeld,
                getString(R.string.notification_take_photo),
                getString(R.string.release_shutter),
                getString(R.string.disconnect));
    }

    static String foregroundNotificationDetail(
            String connectionDetail,
            AstroRateSetpoint desiredSetpoint,
            String correctionStatus) {
        return hasActiveRateIntent(desiredSetpoint)
                && correctionStatus != null
                && correctionStatus.startsWith("Correction paused")
                ? "Correction paused—open the app before capture"
                : connectionDetail;
    }

    private Notification buildNotification(ForegroundNotificationState rendered) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_camera_notification)
                .setContentTitle(rendered.getTitle())
                .setContentText(rendered.getText())
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        if (rendered.getPrimaryAction()
                != ForegroundNotificationState.PrimaryAction.NONE) {
            boolean release = rendered.getPrimaryAction()
                    == ForegroundNotificationState.PrimaryAction.RELEASE_ALL;
            String action = release ? ACTION_RELEASE_ALL : ACTION_TAKE_PHOTO;
            PendingIntent shutterIntent = PendingIntent.getService(
                    this,
                    release ? 3 : 2,
                    new Intent(this, CameraConnectionService.class).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(
                    R.drawable.ic_camera_notification,
                    rendered.getPrimaryActionLabel(),
                    shutterIntent);
        }

        PendingIntent disconnectIntent = PendingIntent.getService(
                this,
                4,
                new Intent(this, CameraConnectionService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(
                R.drawable.ic_camera_notification,
                rendered.getDisconnectLabel(),
                disconnectIntent);
        return builder.build();
    }

    private void acquireWakeLock(long timeoutMs) {
        if (transactionWakeLock == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            long boundedTimeout = Math.max(1L, timeoutMs);
            boolean clampToRecoveryWindow = connectionAttemptUrgentRecovery
                    && !isAppInteractiveVisible();
            if (clampToRecoveryWindow) {
                long recoveryRemainingMs = urgentShutterRecoveryRemainingMs();
                if (recoveryRemainingMs <= 0L) {
                    releaseWakeLock();
                    return;
                }
                boundedTimeout = Math.min(boundedTimeout, recoveryRemainingMs);
            }
            long requestedDeadline = boundedTimeout >= Long.MAX_VALUE - now
                    ? Long.MAX_VALUE : now + boundedTimeout;
            if (transactionWakeLock.isHeld()
                    && wakeLockDeadlineElapsedMs >= requestedDeadline
                    && (!clampToRecoveryWindow
                        || wakeLockDeadlineElapsedMs <= requestedDeadline)) {
                if (clampToRecoveryWindow) scheduleUrgentShutterRecoveryDeadline();
                return;
            }
            if (transactionWakeLock.isHeld()) transactionWakeLock.release();
            wakeLockDeadlineElapsedMs = 0L;
            long remaining = Math.max(
                    1L, requestedDeadline - SystemClock.elapsedRealtime());
            transactionWakeLock.acquire(remaining);
            wakeLockDeadlineElapsedMs = requestedDeadline;
            if (clampToRecoveryWindow) scheduleUrgentShutterRecoveryDeadline();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not acquire bounded BLE wake lock", error);
        }
    }

    private void releaseWakeLockIfIdle() {
        refreshStateNotificationPowerGate();
        // A completion callback may synchronously enqueue a command whose immediate
        // write failure schedules reconnect and acquires a new wake lock. Do not let
        // the outer completion frame release that reconnect lock.
        if (pending == null && commandQueue.isEmpty()
                && !prepositionTrajectoryPinned()
                && prepositionPendingWrite == PrepositionWrite.NONE
                && prepositionWriteInFlight == PrepositionWrite.NONE
                && writePurpose == WritePurpose.NONE
                && !starControlWriteInFlight
                && !starStateWriteInFlight && starStateWritePending == null
                && !detectorConfigWriteInFlight && detectorConfigPending == null
                && !keepAwakeLeaseWriteInFlight && keepAwakeLeasePendingSeconds == null
                && !astroRateWriteInFlight && !astroRateLease.hasPendingWrite()
                && (astroRateCadenceTimers == null
                    || !astroRateCadenceTimers.hasPublicationTimer())
                && astroRateCaptureCancelRunnable == null
                && !starTerminalWaitingForRateRetirement
                && !gyroReadInFlight && starReadInFlight == null && starReadQueue.isEmpty()
                && !exposureWritePending()
                && (!solveProcessing || starInitializationBackoffMaySleep(
                    postTerminalRateMutationAwaitingState,
                    starInitializationReadRetryRunnable != null,
                    pendingCaptureRateMutation != null
                        && starSolveGeneration >= 0L
                        && lastAstroRateSolveGeneration == starSolveGeneration
                        && solvedCorrectionReferenceGeneration == starSolveGeneration))
                && !deferredStarSolveAdmission.isPending()
                && !solveCaptureTransaction.isPending()
                && correctionPublicationPendingGeneration < 0L
                && !isStarSessionActive()
                && !connectionAttemptInProgress(
                    phase, scanRunning && scanForConnection, reconnectRunnable != null)
                && reconnectRunnable == null && phase != Phase.RECONNECTING) {
            releaseWakeLock();
        }
    }

    private void releaseWakeLock() {
        if (transactionWakeLock != null && transactionWakeLock.isHeld()) {
            try {
                transactionWakeLock.release();
            } catch (RuntimeException ignored) {
                // Timed wake lock may have expired between the check and release.
            }
        }
        wakeLockDeadlineElapsedMs = 0L;
    }

    static boolean starInitializationBackoffMaySleep(
            boolean postTerminalAuthorityPending,
            boolean retryScheduled,
            boolean solvedMutationReady) {
        // The solver has already produced its result in this state. Only the delayed coherent
        // camera read remains, and that callback reacquires a bounded transactional lock.
        return postTerminalAuthorityPending && retryScheduled && solvedMutationReady;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundScanPermission() {
        return Build.VERSION.SDK_INT < 29
                || Build.VERSION.SDK_INT > 30
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void showCommandError(String operation, int resultStatus, String error) {
        detail = resultStatus == 0
                ? operation + " failed: " + error
                : operation + " was rejected (status " + resultStatus + ")";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String starInitializationRetryReason(Throwable error) {
        String message = safeMessage(error);
        if (message.contains("generation changed")
                || message.contains("availability changed")) {
            return "Exposure choices changed while they were being read; retrying";
        }
        return "Camera settings were inconsistent while being read ("
                + message + "); retrying";
    }

    private static String starInitializationFailureReason(
            StarReadPurpose purpose, Throwable error) {
        return "Camera " + purpose.displayName + " was inconsistent: "
                + safeMessage(error);
    }

    private static StarInitializationRecoveryGate.Signature starInitializationSignature(
            StarProtocol.State state) {
        if (state == null) throw new IllegalArgumentException("STAR STATE is null");
        int mutableFlags = state.getFlags()
                & (StarProtocol.FLAG_ACTUAL_AVAILABLE
                    | StarProtocol.FLAG_RECIPE_AVAILABLE
                    | StarProtocol.FLAG_DETECTOR_AVAILABLE
                    | StarProtocol.FLAG_RECOVERY_TARGET);
        int[] actualNative = state.getActualNative();
        StarTelemetry.DetectorConfig detector = state.getDetector();
        return StarInitializationRecoveryGate.Signature.of(
                state.hasCatalogAvailable(),
                state.getCatalogGeneration(),
                state.hasCompleteMutableState(),
                state.getConfigGeneration(),
                state.getWorkerGeneration(),
                mutableFlags,
                actualNative[0],
                actualNative[1],
                actualNative[2],
                state.getActualShutterIndex(),
                state.getActualApertureIndex(),
                state.getActualIsoIndex(),
                state.getRecipeShutterIndex(),
                state.getRecipeApertureIndex(),
                state.getRecipeIsoIndex(),
                state.getApertureMinimumIndex(),
                state.getApertureMaximumIndex(),
                detector == null ? -1 : detector.getSigmaQ8(),
                detector == null ? -1 : detector.getMinArea(),
                detector == null ? -1 : detector.getMaxArea(),
                detector == null ? -1 : detector.getMaxElongationQ8(),
                detector == null ? -1 : detector.getMaxSharpnessQ8(),
                detector == null ? -1 : detector.getBorderMargin());
    }

    private static String redactedHex(Command command, byte[] value) {
        if (command.group == 0x0c && command.opcode == 0x02) return "<passcode redacted>";
        return OlympusProtocol.toHex(value);
    }

    /** One unaccepted UI intent waiting only for the current provenance-marked RATE heartbeat. */
    static final class DeferredStarSolveAdmission {
        static final class Request {
            final long intentId;
            final ManualGravityContext manualGravityContext;
            final boolean captureAfterSolve;

            private Request(
                    long intentId,
                    ManualGravityContext manualGravityContext,
                    boolean captureAfterSolve) {
                this.intentId = intentId;
                this.manualGravityContext = manualGravityContext;
                this.captureAfterSolve = captureAfterSolve;
            }
        }

        private Request pending;

        boolean defer(
                long intentId,
                ManualGravityContext manualGravityContext,
                boolean captureAfterSolve) {
            if (pending != null) return false;
            pending = new Request(intentId, manualGravityContext, captureAfterSolve);
            return true;
        }

        Request consume() {
            Request consumed = pending;
            pending = null;
            return consumed;
        }

        Request reject() {
            return consume();
        }

        void cancelCaptureAfterSolve() {
            if (pending == null || !pending.captureAfterSolve) return;
            pending = new Request(
                    pending.intentId, pending.manualGravityContext, false);
        }

        void clear() {
            pending = null;
        }

        boolean isPending() {
            return pending != null;
        }
    }

    private static final class RateSolveContext {
        final long generation;
        final int connectionEpoch;
        final long solveIntentId;
        final boolean automaticCorrection;
        final ManualGravityContext manualGravityContext;
        final double focalLengthMm;
        final StarTelemetry.IbisSample solveReferenceQ10;

        RateSolveContext(
                long generation,
                int connectionEpoch,
                long solveIntentId,
                boolean automaticCorrection,
                ManualGravityContext manualGravityContext,
                double focalLengthMm,
                StarTelemetry.IbisSample solveReferenceQ10) {
            this.generation = generation;
            this.connectionEpoch = connectionEpoch;
            this.solveIntentId = solveIntentId;
            this.automaticCorrection = automaticCorrection;
            this.manualGravityContext = manualGravityContext;
            this.focalLengthMm = focalLengthMm;
            this.solveReferenceQ10 = solveReferenceQ10;
        }

        RateSolveContext withAutomaticCorrection(boolean enabled) {
            if (automaticCorrection == enabled) return this;
            return new RateSolveContext(
                    generation,
                    connectionEpoch,
                    solveIntentId,
                    enabled,
                    enabled ? manualGravityContext : null,
                    focalLengthMm,
                    solveReferenceQ10);
        }
    }

    private enum WritePurpose { NONE, REQUEST, ACK, NACK }

    private enum StateMutationKind { NONE, LIVE, RECIPE, DETECTOR, STARTUP_RECIPE }

    private enum StarReadPurpose {
        CATALOG_FENCE_STATE_A("catalog fence STATE A"),
        SHUTTER_CATALOG("shutter catalog"),
        APERTURE_CATALOG("aperture catalog"),
        ISO_CATALOG("ISO catalog"),
        CATALOG_FENCE_STATE_B("catalog fence STATE B"),
        STATE("camera state"),
        POST_TERMINAL_STATE("ordered post-terminal camera state"),
        CAPTURE_RESTORE_STATE("post-capture camera state"),
        FRESH_RECOVERY_BASELINE_STATE("fresh recovery baseline state");

        final String displayName;

        StarReadPurpose(String displayName) {
            this.displayName = displayName;
        }
    }

    private interface Completion {
        void onComplete(boolean success, int resultStatus, String error);
    }

    private static final class Command {
        final String name;
        final int group;
        final int opcode;
        final byte[] parameters;
        final long timeoutMs;
        final Completion completion;

        Command(
                String name,
                int group,
                int opcode,
                byte[] parameters,
                long timeoutMs,
                Completion completion) {
            this.name = name;
            this.group = group;
            this.opcode = opcode;
            this.parameters = parameters == null ? new byte[0] : parameters.clone();
            this.timeoutMs = timeoutMs;
            this.completion = completion;
        }

        void complete(boolean success, int resultStatus, String error) {
            complete(success, resultStatus, error, null);
        }

        void complete(
                boolean success,
                int resultStatus,
                String error,
                OlympusProtocol.ResultFrame ignored) {
            if (completion != null) {
                completion.onComplete(
                        success,
                        resultStatus,
                        error == null ? "" : error);
            }
        }
    }

    private static final class Pending {
        final Command command;
        final int requestSequence;
        boolean requestWriteComplete;
        boolean receiptSeen;
        OlympusProtocol.ResultFrame result;
        int replyToken = -1;
        boolean replyIsAck;
        String protocolError;

        Pending(Command command, int requestSequence, int ignoredConnectionEpoch) {
            this.command = command;
            this.requestSequence = requestSequence;
        }
    }

    private void handleCharacteristicRead(
            int epoch,
            BluetoothGatt callbackGatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int statusCode,
            long receivedElapsedRealtimeNanos) {
        if (isCurrent(epoch, callbackGatt)
                && gyroReadInFlight && characteristic == starStateCharacteristic) {
            handleGyroAttitudeRead(value, statusCode, receivedElapsedRealtimeNanos);
            return;
        }
        if (isCurrent(epoch, callbackGatt) && characteristic == starStateCharacteristic
                && starReadInFlight != null && isStateReadPurpose(starReadInFlight)
                && statusCode == BluetoothGatt.GATT_SUCCESS && value != null) {
            try {
                GyroAttitudeProtocol.Snapshot sample =
                        StarProtocol.parseState(value).getGyroAttitude();
                acceptGyroAttitude(sample, receivedElapsedRealtimeNanos);
                if (verifiedGyroCapture == null && starSession != null
                        && starSession.snapshot().isTerminal()
                        && starSession.snapshot().isSuccessful()) {
                    // Requiring the same next latch after DONE rejects an intervening physical
                    // capture or repeated preparation event before this STAR image completed.
                    verifiedGyroCapture = cameraGyroTracker.confirmCapture(
                            starSolveGeneration, sample, true);
                    updateSolvedGyroCapture();
                }
            } catch (StarProtocol.ProtocolException | IllegalArgumentException ignored) {
                // The ordinary STATE owner reports malformed values below.
            }
        }
        handleStarRead(epoch, callbackGatt, characteristic, value, statusCode,
                receivedElapsedRealtimeNanos);
    }

    private void handleStarRead(
            int epoch,
            BluetoothGatt callbackGatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int statusCode,
            long receivedElapsedRealtimeNanos) {
        StarReadPurpose purpose = starReadInFlight;
        if (!isCurrent(epoch, callbackGatt) || purpose == null
                || characteristicFor(purpose) != characteristic) return;
        cancelTransactionTimeout();
        boolean settingsVerificationRead = starReadInFlightSettingsVerification;
        boolean initializationRead = starReadInFlightInitialization;
        starReadInFlight = null;
        starReadInFlightSettingsVerification = false;
        starReadInFlightInitialization = false;

        if (initializationRead && !starInitializationReadBatch) {
            // The Activity hid after this read started. Retire at most this one ATT operation;
            // discard its partial bank and leave all startup mutations for foreground.
            starInitializationReadBatch = false;
            publishCurrent();
            if (!continueRequestedDisconnect()) pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (settingsVerificationRead
                && (settingsSession == null
                    || settingsSession.snapshot().isReady()
                    || settingsSession.snapshot().isTerminalFailed())) {
            startQueuedStateMutation();
            publishCurrent();
            pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        if (statusCode != BluetoothGatt.GATT_SUCCESS || value == null) {
            StarInitializationReadRetry.FailureDisposition disposition =
                    StarInitializationReadRetry.classifyFailure(
                            statusCode,
                            initializationRead,
                            settingsVerificationRead);
            if (disposition
                    == StarInitializationReadRetry.FailureDisposition.TERMINAL_INVALID_HANDLE) {
                String reason = "Camera " + purpose.displayName
                        + " read failed because its GATT handle is invalid";
                if (initializationRead) failStarInitializationReadBatch();
                handleStarReadFailure(purpose, reason);
                terminalError(
                        "Camera STAR service handles are incompatible; install matching phone and camera builds, then reconnect");
                return;
            } else if (settingsVerificationRead) {
                scheduleNextStarSettingsVerificationRead(
                        "Camera settings read returned GATT " + statusCode + "; retrying");
            } else if (recoverPostTerminalAuthorityReadFailure(
                    purpose,
                    "Camera settings read failed (GATT " + statusCode + ")",
                    initializationRead,
                    false,
                    false)) {
                // Recovery was scheduled or its bounded window was exhausted.
            } else if (disposition
                    == StarInitializationReadRetry.FailureDisposition.RETRY_INITIALIZATION) {
                if (!scheduleStarInitializationReadRetry(
                        purpose,
                        "Camera STAR worker is starting; retrying camera settings")) {
                    // Exhaustion terminates the coherent STATE/catalog initialization batch; a
                    // later callback must not arm a fresh retry window for the same epoch.
                    failStarInitializationReadBatch();
                    handleStarReadFailure(
                            purpose, "Camera settings read failed (GATT " + statusCode + ")");
                }
            } else {
                if (initializationRead) failStarInitializationReadBatch();
                handleStarReadFailure(
                        purpose, "Camera settings read failed (GATT " + statusCode + ")");
            }
            publishCurrent();
            if (!continueRequestedDisconnect()) pumpCommands();
            releaseWakeLockIfIdle();
            return;
        }

        try {
            switch (purpose) {
                case CATALOG_FENCE_STATE_A:
                    StarProtocol.State stateA = StarProtocol.parseState(value);
                    starInitializationRecoveryGate.recordStateA(
                            connectionEpoch, starInitializationSignature(stateA));
                    starCatalogFence.begin(stateA);
                    acceptStarTelemetry(stateA);
                    break;
                case SHUTTER_CATALOG:
                    shutterCatalog = StarProtocol.parseExposureCatalog(
                            ExposureCatalogs.Kind.SHUTTER, value);
                    starCatalogFence.setCatalog(
                            ExposureCatalogs.Kind.SHUTTER, shutterCatalog);
                    break;
                case APERTURE_CATALOG:
                    apertureCatalog = StarProtocol.parseExposureCatalog(
                            ExposureCatalogs.Kind.APERTURE, value);
                    starCatalogFence.setCatalog(
                            ExposureCatalogs.Kind.APERTURE, apertureCatalog);
                    break;
                case ISO_CATALOG:
                    isoCatalog = StarProtocol.parseExposureCatalog(
                            ExposureCatalogs.Kind.ISO, value);
                    starCatalogFence.setCatalog(ExposureCatalogs.Kind.ISO, isoCatalog);
                    break;
                case CATALOG_FENCE_STATE_B:
                    StarProtocol.State stateB = StarProtocol.parseState(value);
                    starInitializationRecoveryGate.recordStateB(
                            connectionEpoch, starInitializationSignature(stateB));
                    StarCatalogFence.Outcome outcome = starCatalogFence.finish(stateB);
                    if (outcome.isStableUnavailable()) {
                        withholdUnavailableStarCatalogBank(
                                stateB,
                                "Camera exposure choices are not ready; waiting for the camera worker",
                                true);
                    } else {
                        installCoherentStarBank(
                                outcome.getBank(), starFenceFreshConnectionBaseline);
                    }
                    starFenceFreshConnectionBaseline = false;
                    if (initializationRead) finishStarInitializationReadBatch();
                    break;
                case STATE:
                case POST_TERMINAL_STATE:
                case CAPTURE_RESTORE_STATE:
                case FRESH_RECOVERY_BASELINE_STATE:
                    acceptStarState(
                            StarProtocol.parseState(value),
                            settingsVerificationRead,
                            purpose == StarReadPurpose.POST_TERMINAL_STATE
                                    || purpose == StarReadPurpose.CAPTURE_RESTORE_STATE,
                            purpose == StarReadPurpose.FRESH_RECOVERY_BASELINE_STATE,
                            false,
                            receivedElapsedRealtimeNanos);
                    break;
            }
            releasePostTerminalRateMutationIfReady(purpose);
            resumeBackgroundCorrectionAfterAuthoritativeState(purpose);
        } catch (StarProtocol.ProtocolException
                | IllegalArgumentException
                | IllegalStateException error) {
            Log.w(TAG, "Invalid STAR " + purpose + " value", error);
            if (initializationRead
                    && purpose == StarReadPurpose.CATALOG_FENCE_STATE_A) {
                starInitializationRecoveryGate.recordUnknownStateA(connectionEpoch);
            }
            if (settingsVerificationRead) {
                scheduleNextStarSettingsVerificationRead(
                        "Camera returned invalid state while settings were pending; retrying");
            } else if (recoverPostTerminalAuthorityReadFailure(
                    purpose,
                    starInitializationFailureReason(purpose, error),
                    initializationRead,
                    false,
                    false)) {
                // Recovery was scheduled or its bounded window was exhausted.
            } else if (initializationRead
                    && scheduleStarInitializationReadRetry(
                            purpose,
                            starInitializationRetryReason(error))) {
                // Read-only recovery. No STATE mutation is replayed.
            } else {
                if (initializationRead) failStarInitializationReadBatch();
                handleStarReadFailure(
                        purpose, starInitializationFailureReason(purpose, error));
            }
        }
        publishCurrent();
        if (!continueRequestedDisconnect()) pumpCommands();
        releaseWakeLockIfIdle();
    }

    private void resumeBackgroundCorrectionAfterAuthoritativeState(StarReadPurpose purpose) {
        if (backgroundCorrectionResumeAwaitingState
                && (!backgroundCorrectionGate.isSuspended() || !correctionRequested())) {
            backgroundCorrectionResumeAwaitingState = false;
            return;
        }
        if (!backgroundCorrectionResumeAwaitingState
                || !isAppInteractiveVisible()
                || !desiredConnected
                || disconnectRequested) {
            return;
        }
        boolean authoritativeStateCompleted = purpose == StarReadPurpose.STATE
                || purpose == StarReadPurpose.POST_TERMINAL_STATE
                || purpose == StarReadPurpose.CAPTURE_RESTORE_STATE
                || purpose == StarReadPurpose.FRESH_RECOVERY_BASELINE_STATE
                || purpose == StarReadPurpose.CATALOG_FENCE_STATE_B;
        if (purpose == StarReadPurpose.CATALOG_FENCE_STATE_B
                && captureRestoreBarrier.isBlocking()
                && !gyroReadInFlight && starReadInFlight == null
                && starReadQueue.isEmpty()) {
            // A coherent catalog bank cannot itself certify a same-connection capture rollback.
            // Follow it with the explicitly ordered recovery STATE before releasing correction.
            requestStarStateRead();
            return;
        }
        if (!backgroundCorrectionResumeAuthorityReady(
                authoritativeStateCompleted,
                exposureCatalogRefreshRequired,
                starState != null && cameraState != null,
                starState != null && starState.isRecoveryTarget(),
                settingsSession != null,
                captureRestoreBarrier.isBlocking())) {
            return;
        }
        backgroundCorrectionResumeAwaitingState = false;
        resumeBackgroundSuspendedCorrection();
    }

    static boolean backgroundCorrectionResumeAuthorityReady(
            boolean authoritativeStateCompleted,
            boolean catalogRefreshRequired,
            boolean stateAvailable,
            boolean recoveryTarget,
            boolean settingsMutationPending,
            boolean captureRestoreBlocking) {
        return authoritativeStateCompleted
                && !catalogRefreshRequired
                && stateAvailable
                && !recoveryTarget
                && !settingsMutationPending
                && !captureRestoreBlocking;
    }

    private void installCoherentStarBank(
            StarCatalogFence.Bank bank, boolean freshConnectionBaseline) {
        if (bank == null) throw new IllegalArgumentException("STAR catalog bank is null");
        fullExposureCatalogs = bank.getCatalogs();
        exposureCatalogGeneration = bank.getGeneration();
        clearFilteredCatalogCache();
        starState = bank.getState();
        cameraState = bank.getActual();
        detectorConfig = starState.getDetector();
        detectorConfigKnown = detectorConfig != null;
        detectorStatus = detectorConfigKnown ? "Detector settings loaded from STAR state" : "";
        acceptStarTelemetry(starState);
        if (!updateFilteredExposureCatalogs(cameraState)) {
            throw new IllegalArgumentException("STAR STATE is incoherent with its catalog bank");
        }
        ExposureCatalogs.Selection actual = ExposureCatalogs.Selection.from(cameraState);
        ExposureCatalogs.Selection recipe = bank.getRecipe();
        persistentSettingsIntent.reset(actual, connectionEpoch, exposureCatalogGeneration);
        starRecipeIntent.reset(recipe, connectionEpoch, exposureCatalogGeneration);
        exposureCatalogRefreshRequired = false;
        apertureBoundsRefreshRequired = false;
        lastMissingCatalogStateKey = "";
        starInitializationRecoveryGate.clearAfterSuccess(connectionEpoch);
        detail = "";

        boolean startStartupRecipeWrite = false;
        if (starRecipeStartupReset.state() == StarRecipeStartupReset.State.PENDING) {
            StarRecipeStartupReset.Decision startup =
                    starRecipeStartupReset.observeInitialRecipe(exposureCatalogs, recipe);
            if (startup.failed()) {
                starRecipeIntent.clear();
                detail = "STAR defaults unavailable: " + startup.failure();
            } else if (startup.writeRequired()) {
                LatestExposureWrite.Offer offered = starRecipeIntent.offer(
                        startup.selection(), connectionEpoch, exposureCatalogGeneration);
                if (offered == LatestExposureWrite.Offer.START_REQUIRED) {
                    detail = "Resetting STAR recipe to 1 s, aperture "
                            + startup.selection().getAperture() + ", ISO 800";
                    startStartupRecipeWrite = true;
                } else {
                    starRecipeStartupReset.writeFailed(
                            "Could not stage the startup STAR recipe reset");
                    starRecipeIntent.clear();
                    detail = "STAR defaults unavailable: " + starRecipeStartupReset.failure();
                }
            } else {
                suppressedStartupStarRecipeCameraIdentity = "";
                detail = "";
            }
        } else if (starRecipeStartupReset.state() == StarRecipeStartupReset.State.FAILED) {
            starRecipeIntent.clear();
            detail = "STAR defaults unavailable: " + starRecipeStartupReset.failure();
        } else if (starRecipeStartupReset.isReady()
                && !starRecipeStartupReset.acceptsObservedRecipe(recipe)) {
            starRecipeStartupReset.observeAuthoritativeRecipe(recipe);
            detail = "Camera STAR recipe changed; using the authoritative STATE value";
        }

        if (freshConnectionBaseline) {
            observeCaptureRestoreState(
                    cameraState, false, true, !starState.isRecoveryTarget());
        } else {
            observeCaptureRestoreState(
                    cameraState, false, false, !starState.isRecoveryTarget());
        }
        if (startStartupRecipeWrite) startNextStarRecipeWrite();
    }

    /**
     * Installs the canonical cold/withheld STAR envelope without treating generation zero as a
     * catalog replacement. Mutable settings remain unavailable until a later STATE notification
     * advertises a nonzero catalog generation, at which point one fresh coherence fence is read.
     */
    private void withholdUnavailableStarCatalogBank(
            StarProtocol.State authoritative,
            String reason,
            boolean fencedStableUnavailable) {
        if (authoritative == null
                || authoritative.hasCatalogAvailable()
                || authoritative.getCatalogGeneration() != 0L) {
            throw new IllegalArgumentException("STAR unavailable state is noncanonical");
        }
        acceptStarTelemetry(authoritative);
        starCatalogFence.clear();
        starState = authoritative;
        cameraState = null;
        exposureCatalogGeneration = 0L;
        exposureCatalogs = null;
        fullExposureCatalogs = null;
        shutterCatalog = null;
        apertureCatalog = null;
        isoCatalog = null;
        clearFilteredCatalogCache();
        persistentSettingsIntent.clear();
        starRecipeIntent.clear();
        detectorConfigKnown = false;
        detectorStatus = "Detector settings unavailable until the camera worker is ready";
        exposureCatalogRefreshRequired = false;
        apertureBoundsRefreshRequired = false;
        lastMissingCatalogStateKey = "";
        starInitializationRecoveryGate.observeUnavailable(
                connectionEpoch, fencedStableUnavailable);
        if (PostTerminalCorrectionPolicy.unavailableIsTerminal(
                fencedStableUnavailable)) {
            discardPostTerminalRateMutationForUnavailableState();
        } else if (postTerminalRateMutationAwaitingState
                && (pendingCaptureRateMutation != null || solveProcessing)) {
            correctionStatus =
                    "Solved; waiting for authoritative camera state before applying correction";
            astroRateDetail = correctionStatus;
        }
        if (captureRestoreBarrier.isRecoveryRequired()) {
            solveCaptureTransaction.cancel();
        }
        detail = reason;
    }

    private void discardPostTerminalRateMutationForUnavailableState() {
        if (!postTerminalRateMutationAwaitingState) return;
        boolean replaceStatus = PostTerminalCorrectionPolicy.discardReplacesStatus(
                pendingCaptureRateMutation != null, solveProcessing);
        postTerminalRateMutationAwaitingState = false;
        pendingCaptureRateMutation = null;
        clearDesiredCorrectionIntent();
        acknowledgedCorrection = null;
        acknowledgedRateSetpoint = null;
        acknowledgedCorrectionHealthy = false;
        acknowledgedCorrectionGeneration = -1L;
        acknowledgedSolveGeneration = -1L;
        solveProcessing = false;
        solveCaptureTransaction.cancel();
        if (astroRateSolveContext != null) {
            astroRateSolveContext =
                    astroRateSolveContext.withAutomaticCorrection(false);
        }
        if (replaceStatus) {
            correctionStatus =
                    "Correction update discarded because post-capture camera settings are unavailable";
            astroRateDetail = correctionStatus;
        }
    }

    /** Clears a correction intent that must never seed a later body-rate mutation. */
    private void clearDesiredCorrectionIntent() {
        backgroundCorrectionGate.clear();
        desiredCorrection = null;
        desiredRateSetpoint = null;
        desiredCorrectionAttitude = null;
        correctionSource = null;
        desiredSolveGeneration = -1L;
        correctionGeneration = nextCorrectionGeneration++;
    }

    private void acceptStarState(
            StarProtocol.State authoritative,
            boolean verificationRead,
            boolean orderedPostTerminalRead,
            boolean freshConnectionBaseline,
            boolean notificationRecoveryTrigger,
            long receivedElapsedRealtimeNanos) {
        acceptStarTelemetry(authoritative, receivedElapsedRealtimeNanos);
        boolean catalogAvailable = authoritative.hasCatalogAvailable();
        long observedCatalogGeneration = authoritative.getCatalogGeneration();
        if (catalogAvailable != (observedCatalogGeneration != 0L)) {
            throw new IllegalArgumentException(
                    "STAR STATE catalog availability and generation disagree");
        }
        StarCatalogRefreshPolicy.NotificationDecision catalogDecision =
                StarCatalogRefreshPolicy.classifyNotification(
                        starInitializationReadBatch,
                        fullExposureCatalogs != null,
                        exposureCatalogGeneration,
                        catalogAvailable,
                        observedCatalogGeneration);
        if (catalogDecision
                == StarCatalogRefreshPolicy.NotificationDecision.IGNORE_IN_FLIGHT) {
            // Unified STATE telemetry can arrive between every read in the coherent
            // STATE-A/catalogs/STATE-B batch. STATE-B owns the catalog-generation decision;
            // replacing the batch here would continually restart it at STATE-A.
            return;
        }
        if (catalogDecision
                == StarCatalogRefreshPolicy.NotificationDecision.WAIT_FOR_AVAILABLE) {
            if (settingsSession != null) {
                StarStateMutationSession.Snapshot mutation = settingsSession.snapshot();
                if (mutation.isWriteComplete()
                        && !mutation.isReady()
                        && !mutation.isTerminalFailed()) {
                    failSettingsApply(
                            "Camera withdrew its exposure choices before confirming the settings",
                            false);
                }
            }
            withholdUnavailableStarCatalogBank(
                    authoritative,
                    "Camera exposure choices are not ready; waiting for the camera worker",
                    false);
            if (postTerminalRateMutationAwaitingState && orderedPostTerminalRead) {
                exposureCatalogRefreshRequired = true;
                detail = "Post-capture camera settings are still settling; "
                        + "reading one coherent camera catalog";
            }
            return;
        }
        if (catalogDecision == StarCatalogRefreshPolicy.NotificationDecision.REFRESH) {
            if (notificationRecoveryTrigger) {
                StarInitializationRecoveryGate.ProbeDecision recoveryDecision =
                        starInitializationRecoveryGate.classifyNotification(
                                connectionEpoch,
                                starInitializationSignature(authoritative));
                if (recoveryDecision
                        != StarInitializationRecoveryGate.ProbeDecision.ALLOW_PROBE) {
                    // The last bounded window already exhausted this exact STATE evidence.
                    // Preserve its cause-specific final detail until the camera publishes a new
                    // generation or completes the mutable block for this generation.
                    return;
                }
            }
            if (settingsSession != null) {
                StarStateMutationSession.Snapshot mutation = settingsSession.snapshot();
                if (mutation.isWriteComplete()
                        && !mutation.isReady()
                        && !mutation.isTerminalFailed()) {
                    failSettingsApply(
                            "STAR catalog changed before the complete state mutation was confirmed",
                            true);
                    return;
                }
            }
            exposureCatalogRefreshRequired = true;
            detail = "Exposure choices changed; reading one coherent camera catalog";
            if (phase == Phase.READY
                    && !exposureWritePending() && !isStarSessionActive()) {
                requestStarInitializationReads();
            }
            return;
        }
        // Install the authoritative envelope before resolving the mutable block. A partial STATE
        // makes stale settings unavailable to the UI while its telemetry remains usable.
        starState = authoritative;
        StarProtocol.ResolvedState resolved =
                StarProtocol.resolveActual(authoritative, fullExposureCatalogs);
        ExposureCatalogs.Selection recipe =
                StarProtocol.resolveRecipe(authoritative, fullExposureCatalogs);
        if (resolved == null || recipe == null || authoritative.getDetector() == null) {
            cameraState = null;
            detectorConfigKnown = false;
            if (settingsSession == null) {
                persistentSettingsIntent.clear();
                starRecipeIntent.clear();
            }
            detail = STAR_STATE_INCOMPLETE_DETAIL;
            PostTerminalCorrectionPolicy.AuthorityAction action =
                    PostTerminalCorrectionPolicy.afterState(
                            postTerminalRateMutationAwaitingState,
                            !orderedPostTerminalRead,
                            false,
                            exposureCatalogRefreshRequired
                                    || starInitializationReadBatch
                                    || starInitializationReadRetryRunnable != null);
            if (action
                    == PostTerminalCorrectionPolicy.AuthorityAction.REFRESH_COHERENT_BANK) {
                exposureCatalogRefreshRequired = true;
                detail = "Post-capture camera settings are still settling; "
                        + "reading one coherent camera catalog";
            }
            return;
        }
        cameraState = resolved;
        detectorConfig = authoritative.getDetector();
        detectorConfigKnown = true;
        if (!updateFilteredExposureCatalogs(resolved)) {
            exposureCatalogRefreshRequired = true;
            requestStarInitializationReads();
            return;
        }
        detail = clearRecoveredStarStateTransientDetail(
                detail,
                starRecipeStartupReset.state() == StarRecipeStartupReset.State.FAILED
                        ? starRecipeStartupReset.failure() : "");

        if (settingsSession != null) {
            StarStateMutationSession.Snapshot before = settingsSession.snapshot();
            if (before.isWriteComplete()
                    && !before.isReady()
                    && !before.isTerminalFailed()
                    && starSettingsDeadlineElapsedMs > 0L
                    && SystemClock.elapsedRealtime() >= starSettingsDeadlineElapsedMs) {
                failSettingsApply(
                        "Camera did not confirm the requested exposure settings before the deadline",
                        true);
                return;
            }
            settingsSession.onState(authoritative);
            StarStateMutationSession.Snapshot after = settingsSession.snapshot();
            if (after.isReady()) {
                finishSettingsBarrier();
            } else if (after.isWriteComplete() && !after.isTerminalFailed()) {
                if (verificationRead) {
                    scheduleNextStarSettingsVerificationRead(
                            "Camera is still applying the requested exposure settings");
                } else {
                    detail = "Waiting for authoritative camera settings confirmation";
                }
                return;
            }
        } else {
            persistentSettingsIntent.reset(
                    ExposureCatalogs.Selection.from(resolved),
                    connectionEpoch,
                    exposureCatalogGeneration);
            starRecipeIntent.reset(recipe, connectionEpoch, exposureCatalogGeneration);
            if (starRecipeStartupReset.isReady()) {
                starRecipeStartupReset.observeAuthoritativeRecipe(recipe);
            }
        }

        if (freshConnectionBaseline) {
            observeCaptureRestoreState(
                    cameraState, false, true, !authoritative.isRecoveryTarget());
        } else {
            observeCaptureRestoreState(
                    cameraState,
                    orderedPostTerminalRead,
                    false,
                    !authoritative.isRecoveryTarget());
        }
    }

    private void acceptStarTelemetry(StarProtocol.State state) {
        acceptStarTelemetry(state, SystemClock.elapsedRealtimeNanos());
    }

    private void acceptStarTelemetry(
            StarProtocol.State state, long receivedElapsedRealtimeNanos) {
        if (state == null) return;
        gyroAttitudeSupported = state.getGyroAttitude() != null;
        acceptGyroAttitude(state.getGyroAttitude(), receivedElapsedRealtimeNanos);
        ibisSample = state.getIbis();
        ibisReceivedElapsedMs = ibisSample == null ? 0L : SystemClock.elapsedRealtime();
        StarProtocol.Acceleration acceleration = state.getAcceleration();
        if (acceleration == null) {
            cameraAccelerationTracker.clear();
            return;
        }
        cameraAccelerationTracker.accept(acceleration, receivedElapsedRealtimeNanos);
    }

    static String clearRecoveredStarStateTransientDetail(
            String currentDetail, String startupResetFailure) {
        if (STAR_STATE_INVALID_DETAIL.equals(currentDetail)
                || STAR_STATE_INCOMPLETE_DETAIL.equals(currentDetail)) {
            return startupResetFailure == null || startupResetFailure.isEmpty()
                    ? ""
                    : "STAR defaults unavailable: " + startupResetFailure;
        }
        return currentDetail == null ? "" : currentDetail;
    }

    private boolean validateCurrentStateAgainstCatalogs() {
        if (cameraState == null || exposureCatalogs == null) return false;
        ExposureCatalogs.Selection current = ExposureCatalogs.Selection.from(cameraState);
        if (exposureCatalogs.contains(current)) {
            lastMissingCatalogStateKey = "";
            if (!persistentSettingsIntent.hasActive()) {
                persistentSettingsIntent.reset(
                        current, connectionEpoch, exposureCatalogGeneration);
            }
            if (settingsSession == null) detail = "";
            return true;
        }
        refreshForIncoherentCameraState();
        return false;
    }

    private void maybeBuildExposureCatalogs() {
        if (shutterCatalog == null || apertureCatalog == null || isoCatalog == null) return;
        if (fullExposureCatalogs != null) return;
        fullExposureCatalogs = new ExposureCatalogs(
                shutterCatalog, apertureCatalog, isoCatalog);
        exposureCatalogs = null;
        clearFilteredCatalogCache();
    }

    private boolean updateFilteredExposureCatalogs(
            CameraExposureState authoritative) {
        if (fullExposureCatalogs == null || authoritative == null) {
            exposureCatalogs = null;
            return false;
        }
        String minimum = authoritative.apertureMinLabel();
        String maximum = authoritative.apertureMaxLabel();
        if (filteredCatalogSource == fullExposureCatalogs
                && minimum.equals(filteredApertureMin)
                && maximum.equals(filteredApertureMax)
                && exposureCatalogs != null
                && exposureCatalogs.findByLabel(
                        ExposureCatalogs.Kind.APERTURE,
                        authoritative.getAperture()) != null) {
            return true;
        }
        ExposureCatalogs filtered = ApertureCatalogView.filter(
                fullExposureCatalogs,
                minimum,
                maximum,
                authoritative.getAperture());
        if (filtered == null) {
            exposureCatalogs = null;
            clearFilteredCatalogCache();
            return false;
        }
        exposureCatalogs = filtered;
        filteredCatalogSource = fullExposureCatalogs;
        filteredApertureMin = minimum;
        filteredApertureMax = maximum;
        return true;
    }

    private void clearFilteredCatalogCache() {
        filteredCatalogSource = null;
        filteredApertureMin = "";
        filteredApertureMax = "";
    }

    private void refreshForIncoherentCameraState() {
        if (cameraState == null) return;
        String stateKey = cameraState.getShutter() + '\u0000'
                + cameraState.getAperture() + '\u0000' + cameraState.getIso() + '\u0000'
                + cameraState.apertureMinLabel() + '\u0000'
                + cameraState.apertureMaxLabel();
        if (stateKey.equals(lastMissingCatalogStateKey)) {
            detail = "Camera exposure or aperture bounds are absent from its published choices";
            return;
        }
        lastMissingCatalogStateKey = stateKey;
        detail = "Camera exposure or aperture bounds are incoherent; refreshing choices";
        requestStarInitializationReads();
    }

    private boolean maybeStartDeferredExposureCatalogRefresh() {
        if (!StarCatalogRefreshPolicy.canStartDeferredRefresh(
                    exposureCatalogRefreshRequired,
                    starInitializationReadBatch,
                    starReadQueue.isEmpty())
                || !starTransportReady
                || gatt == null
                || disconnectRequested
                || starInitializationReadRetryRunnable != null
                || exposureWritePending()
                || isStarSessionActive()
                || pending != null
                || !commandQueue.isEmpty()
                || writePurpose != WritePurpose.NONE
                || starControlWriteInFlight
                || gyroReadInFlight || starReadInFlight != null
                || halfPressRequested
                || halfHeld
                || fullPressRequested
                || fullHeld) {
            return false;
        }
        return requestStarInitializationReads();
    }

    private boolean scheduleStarInitializationReadRetry(
            StarReadPurpose purpose, String reason) {
        if (!starInitializationReadBatch
                || !desiredConnected
                || disconnectRequested
                || !starTransportReady
                || gatt == null) {
            return false;
        }
        long delayMs = starInitializationReadRetry.nextDelay(
                connectionEpoch, SystemClock.elapsedRealtime());
        if (delayMs < 0L) {
            return false;
        }
        // A retryable coherence/read failure only invalidates the partial initialization bank.
        // In particular, do not discard an automatic correction waiting for its ordered
        // post-capture STATE: the fresh STATE-B may still release that workflow safely.
        starCatalogFence.clear();
        starInitializationDeferredUntilForeground = false;
        shutterCatalog = null;
        apertureCatalog = null;
        isoCatalog = null;
        exposureCatalogs = null;
        fullExposureCatalogs = null;
        exposureCatalogGeneration = 0L;
        clearFilteredCatalogCache();
        cameraState = null;
        starState = null;
        detectorConfigKnown = false;
        persistentSettingsIntent.clear();
        starRecipeIntent.clear();
        detail = reason;
        removeQueuedCatalogAndStateReads();
        cancelStarInitializationReadRetryCallback();
        int epoch = connectionEpoch;
        starInitializationReadRetryRunnable = () -> {
            starInitializationReadRetryRunnable = null;
            boolean connectionUsable = desiredConnected
                    && !disconnectRequested
                    && starTransportReady
                    && gatt != null;
            if (!starInitializationReadRetry.shouldRun(
                    epoch, connectionEpoch, connectionUsable)) {
                return;
            }
            // The expired attempt no longer owns a live read batch. Relinquish it before the
            // ordinary idle checks so a busy camera defers one refresh instead of leaving an
            // empty batch permanently in flight. Keep the retry owner and post-terminal
            // mutation intact: this is still the same bounded recovery window.
            starInitializationReadBatch = false;
            starReadInFlightInitialization = false;
            removeQueuedCatalogAndStateReads();
            requestStarInitializationReads();
            publishCurrent();
            pumpCommands();
            releaseWakeLockIfIdle();
        };
        bluetoothHandler.postDelayed(starInitializationReadRetryRunnable, delayMs);
        // requestStarInitializationReads() reacquires a bounded lock inside the callback. The
        // backoff itself is idle time and must not keep the CPU awake.
        releaseWakeLockIfIdle();
        return true;
    }

    private void cancelStarInitializationReadRetryCallback() {
        if (starInitializationReadRetryRunnable != null) {
            bluetoothHandler.removeCallbacks(starInitializationReadRetryRunnable);
            starInitializationReadRetryRunnable = null;
        }
    }

    private void resetStarInitializationReadRetry() {
        cancelStarInitializationReadRetryCallback();
        starInitializationReadRetry.reset();
    }

    private void finishStarInitializationReadBatch() {
        starInitializationReadBatch = false;
        starReadInFlightInitialization = false;
        resetStarInitializationReadRetry();
    }

    private void failStarInitializationReadBatch() {
        boolean terminalBatchFailure = starInitializationReadBatch
                || starReadInFlightInitialization;
        if (terminalBatchFailure) {
            starInitializationRecoveryGate.markExhausted(connectionEpoch);
            // STATE-A/catalogs/STATE-B is one atomic authority check. Failure of any member means
            // a correction waiting on that whole post-terminal check can no longer be released.
            discardPostTerminalRateMutationForUnavailableState();
        }
        starInitializationReadBatch = false;
        starReadInFlightInitialization = false;
        removeQueuedCatalogAndStateReads();
        starCatalogFence.clear();
        resetStarInitializationReadRetry();
    }

    /**
     * Routes a failed ordered post-terminal authority read into the existing bounded
     * STATE-A/catalogs/STATE-B recovery window. A member failure inside that window consumes its
     * bounded retry policy; only exhaustion may discard the deferred correction.
     */
    private boolean recoverPostTerminalAuthorityReadFailure(
            StarReadPurpose purpose,
            String reason,
            boolean initializationRead,
            boolean settingsVerificationRead,
            boolean terminalInvalidHandle) {
        PostTerminalCorrectionPolicy.ReadFailureAction action =
                PostTerminalCorrectionPolicy.afterReadFailure(
                        postTerminalRateMutationAwaitingState,
                        isStateReadPurpose(purpose),
                        initializationRead,
                        settingsVerificationRead,
                        terminalInvalidHandle);
        if (action == PostTerminalCorrectionPolicy.ReadFailureAction.FAIL) return false;
        if (action
                == PostTerminalCorrectionPolicy.ReadFailureAction.RETRY_COHERENT_BANK) {
            if (!scheduleStarInitializationReadRetry(purpose, reason)) {
                // The only usable in-connection no-retry result is bounded-window exhaustion.
                // Preserve the existing terminal behavior at that boundary.
                failStarInitializationReadBatch();
                detail = reason;
            }
            return true;
        }
        cameraState = null;
        persistentSettingsIntent.clear();
        exposureCatalogRefreshRequired = true;
        detail = reason + "; reading one coherent camera catalog";
        return true;
    }

    private void handleStarReadFailure(StarReadPurpose purpose, String reason) {
        if (purpose == StarReadPurpose.SHUTTER_CATALOG) shutterCatalog = null;
        if (purpose == StarReadPurpose.APERTURE_CATALOG) apertureCatalog = null;
        if (purpose == StarReadPurpose.ISO_CATALOG) isoCatalog = null;
        if (purpose == StarReadPurpose.SHUTTER_CATALOG
                || purpose == StarReadPurpose.APERTURE_CATALOG
                || purpose == StarReadPurpose.ISO_CATALOG) {
            exposureCatalogs = null;
            fullExposureCatalogs = null;
            clearFilteredCatalogCache();
            persistentSettingsIntent.clear();
            starRecipeIntent.clear();
        }
        if (isStateReadPurpose(purpose)) {
            cameraState = null;
            persistentSettingsIntent.clear();
            if (postTerminalRateMutationAwaitingState) {
                discardPostTerminalRateMutationForUnavailableState();
            }
            if (captureRestoreBarrier.isRecoveryRequired()) {
                solveCaptureTransaction.cancel();
            }
        }
        detail = reason;
        if (purpose == StarReadPurpose.STATE
                && backgroundCorrectionResumeAwaitingState
                && backgroundCorrectionGate.isSuspended()
                && correctionRequested()
                && isAppInteractiveVisible()
                && desiredConnected
                && !disconnectRequested
                && starInitializationReadRetryRunnable == null) {
            // One failed lightweight foreground read falls back to the existing bounded coherent
            // STATE-A/catalogs/STATE-B recovery instead of stranding retained correction.
            exposureCatalogRefreshRequired = true;
            requestStarInitializationReads();
        }
    }

    private void releasePostTerminalRateMutationIfReady(StarReadPurpose purpose) {
        if (!desiredConnected
                || disconnectRequested
                || !postTerminalRateMutationAwaitingState
                || (purpose != StarReadPurpose.POST_TERMINAL_STATE
                    && purpose != StarReadPurpose.CATALOG_FENCE_STATE_B)
                || exposureCatalogRefreshRequired
                || starState == null
                || starState.isRecoveryTarget()
                || cameraState == null) {
            return;
        }
        postTerminalRateMutationAwaitingState = false;
        Runnable deferredMutation = pendingCaptureRateMutation;
        pendingCaptureRateMutation = null;
        if (deferredMutation != null) {
            deferredMutation.run();
            return;
        }
        PlateSolverCoordinator solver = plateSolverCoordinator;
        if (solver != null) handleSolvedAstroRate(solver.getSnapshot());
    }

    private BluetoothGattCharacteristic characteristicFor(StarReadPurpose purpose) {
        if (purpose == StarReadPurpose.SHUTTER_CATALOG) {
            return starShutterCatalogCharacteristic;
        }
        if (purpose == StarReadPurpose.APERTURE_CATALOG) {
            return starApertureCatalogCharacteristic;
        }
        if (purpose == StarReadPurpose.ISO_CATALOG) return starIsoCatalogCharacteristic;
        return starStateCharacteristic;
    }

    private static boolean isStateReadPurpose(StarReadPurpose purpose) {
        return purpose == StarReadPurpose.STATE
                || purpose == StarReadPurpose.CATALOG_FENCE_STATE_A
                || purpose == StarReadPurpose.CATALOG_FENCE_STATE_B
                || purpose == StarReadPurpose.POST_TERMINAL_STATE
                || purpose == StarReadPurpose.CAPTURE_RESTORE_STATE
                || purpose == StarReadPurpose.FRESH_RECOVERY_BASELINE_STATE;
    }

    private void removeQueuedCatalogAndStateReads() {
        starReadQueue.clear();
    }

    private static boolean isReadable(BluetoothGattCharacteristic characteristic) {
        return characteristic != null
                && (characteristic.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
    }

    private void clearStarCharacteristics() {
        starControlCharacteristic = null;
        starStateCharacteristic = null;
        gyroAttitudeSupported = false;
        clearCameraAttitude();
        starShutterCatalogCharacteristic = null;
        starApertureCatalogCharacteristic = null;
        starIsoCatalogCharacteristic = null;
        starNegotiatedMtu = 23;
    }

    private final class GattCallback extends BluetoothGattCallback {
        private final int epoch;

        GattCallback(int epoch) {
            this.epoch = epoch;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            bluetoothHandler.post(() -> handleConnectionState(epoch, callbackGatt, status, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            bluetoothHandler.post(() -> handleServicesDiscovered(epoch, callbackGatt, status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            bluetoothHandler.post(() -> handleMtuChanged(
                    epoch, callbackGatt, mtu, status));
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            bluetoothHandler.post(() -> handleDescriptorWrite(epoch, callbackGatt, descriptor, status));
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, int status) {
            bluetoothHandler.post(() -> handleCharacteristicWrite(
                    epoch, callbackGatt, characteristic, status));
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            if (StarProtocol.STATE_UUID.equals(characteristic.getUuid())
                    && dropIdleStateNotifications) {
                backgroundStateRefreshRequired = true;
                return;
            }
            long receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            byte[] copy = value == null ? null : value.clone();
            bluetoothHandler.post(() -> handleNotification(
                    epoch,
                    callbackGatt,
                    characteristic.getUuid(),
                    copy,
                    receivedElapsedRealtimeNanos));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic) {
            if (StarProtocol.STATE_UUID.equals(characteristic.getUuid())
                    && dropIdleStateNotifications) {
                backgroundStateRefreshRequired = true;
                return;
            }
            long receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            byte[] value = characteristic.getValue();
            byte[] copy = value == null ? null : value.clone();
            bluetoothHandler.post(() -> handleNotification(
                    epoch,
                    callbackGatt,
                    characteristic.getUuid(),
                    copy,
                    receivedElapsedRealtimeNanos));
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            long receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            byte[] copy = value == null ? null : value.clone();
            bluetoothHandler.post(() -> handleCharacteristicRead(
                    epoch,
                    callbackGatt,
                    characteristic,
                    copy,
                    status,
                    receivedElapsedRealtimeNanos));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            long receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            byte[] value = characteristic.getValue();
            byte[] copy = value == null ? null : value.clone();
            bluetoothHandler.post(() -> handleCharacteristicRead(
                    epoch,
                    callbackGatt,
                    characteristic,
                    copy,
                    status,
                    receivedElapsedRealtimeNanos));
        }
    }
}
