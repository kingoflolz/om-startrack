package dev.omshutter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.omshutter.ble.CameraConnectionService;
import dev.omshutter.protocol.StarTelemetry;
import dev.omshutter.protocol.CameraExposureState;
import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.ExposureDraft;
import dev.omshutter.protocol.StarCaptureSession;
import dev.omshutter.protocol.StarCaptureProtocol;
import dev.omshutter.solver.PlateSolverCoordinator;
import dev.omshutter.solver.PlateSolverSettings;
import dev.omshutter.solver.CameraGravityWindow;
import dev.omshutter.solver.CorrectionScale;
import dev.omshutter.solver.ManualEquatorialPointing;
import dev.omshutter.solver.ObserverLocationFix;
import dev.omshutter.solver.ObserverCoordinateContext;
import dev.omshutter.solver.CameraAttitudeEstimator;
import dev.omshutter.solver.EquatorialCoordinates;
import dev.omshutter.solver.SolveResult;
import dev.omshutter.solver.SolveSnapshot;
import dev.omshutter.storage.CameraCredentials;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** State-driven shutter and STAR controls for the Olympus Bluetooth service. */
public final class MainActivity extends Activity implements
        CameraConnectionService.Listener,
        CameraConnectionService.StarListener,
        PlateSolverCoordinator.Listener {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private static final int REQUEST_ENABLE_BLUETOOTH = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_BACKGROUND_LOCATION = 103;
    private static final int REQUEST_MANUAL_LOCATION = 104;
    private static final int REQUEST_LAST_SOLVE_LOCATION = 105;
    private static final long LAST_SOLVE_COORDINATE_REFRESH_MS = 1_000L;
    private static final long SHUTTER_TIMER_UPDATE_MS = 100L;
    private static final String STATE_PENDING_ACTION = "pending_action";
    private static final String STATE_CONNECTION_SETUP = "connection_setup";
    private static final String STATE_STAR_TRACKER_PAGE_VISIBLE = "star_tracker_page_visible";
    private static final String DISPLAY_PREFERENCES = "display_preferences";
    private static final String PREF_NIGHT_MODE = "night_mode";
    private static final String PREF_SOLVE_DETAILS_EXPANDED = "solve_details_expanded";
    private static final String PREF_CAPTURE_SETTINGS_EXPANDED = "capture_settings_expanded";
    private static final String PREF_DETECTOR_SETTINGS_EXPANDED = "detector_settings_expanded";
    private static final String PREF_SOLVER_SETTINGS_EXPANDED = "solver_settings_expanded";
    private static final String PREF_SHUTTER_LOCKED = "shutter_locked";
    private static final String PREF_SHUTTER_LOCKED_AT = "shutter_locked_at_elapsed_ms";

    private enum PendingAction { NONE, SCAN, CONNECT, RETRY }

    private enum ManualLocationAction {
        APPLY,
        SOLVE,
        SOLVE_CAPTURE;

        boolean isSolve() {
            return this != APPLY;
        }

        boolean capturesAfterSolve() {
            return this == SOLVE_CAPTURE;
        }
    }

    /** Ephemeral tap-time pointing; observer coordinates are added only after permission. */
    private static final class PendingManualLocationRequest {
        final ManualLocationAction action;
        final String rightAscension;
        final String declination;
        final long assertedElapsedRealtimeNanos;

        PendingManualLocationRequest(
                ManualLocationAction action,
                String rightAscension,
                String declination,
                long assertedElapsedRealtimeNanos) {
            this.action = action;
            this.rightAscension = rightAscension;
            this.declination = declination;
            this.assertedElapsedRealtimeNanos = assertedElapsedRealtimeNanos;
        }
    }

    private final Map<String, CameraConnectionService.CameraCandidate> candidates = new LinkedHashMap<>();
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final LastSolveCoordinateAssociation lastSolveCoordinateAssociation =
            new LastSolveCoordinateAssociation();
    private final Runnable lastSolveCoordinateUpdater = new Runnable() {
        @Override
        public void run() {
            if (!activityStarted) return;
            renderLastSolveCoordinates(currentSnapshot == null ? null : currentSnapshot.astro);
        }
    };

    private View rootScroll;
    private View rootContent;
    private View connectionScreen;
    private View controlsScreen;
    private View shutterPage;
    private View shutterActionBar;
    private View starTrackerPanel;
    private View astroSettingsButton;
    private Button starTrackerBackButton;
    private Button captureSettingsToggle;
    private View captureSettingsPanel;
    private Button detectorSettingsToggle;
    private View detectorSettingsPanel;
    private Button solverSettingsToggle;
    private View solverSettingsPanel;
    private TextView statusView;
    private TextView detailView;
    private Button controlsStatusView;
    private TextView controlsDetailView;
    private TextView controlsCameraName;
    private TextView discoveredLabel;
    private EditText cameraName;
    private EditText passcode;
    private Spinner cameraSpinner;
    private Button scanButton;
    private Button connectButton;
    private Button controlsDisconnectButton;
    private View controlsRefreshButton;
    private Button connectionBackButton;
    private Button halfPressButton;
    private Button shutterButton;
    private Button shutterLockTarget;
    private Button nightModeButton;
    private ExposureWheelPicker cameraAperture;
    private ExposureWheelPicker cameraShutterSpeed;
    private ExposureWheelPicker cameraIso;
    private ExposureWheelPicker starAperture;
    private ExposureWheelPicker starShutterSpeed;
    private ExposureWheelPicker starIso;
    private Button detectStarsButton;
    private Button shutterSolveButton;
    private Button shutterSolveCaptureButton;
    private Switch correctionEnabledToggle;
    private TextView shutterCorrectionStatus;
    private View shutterCorrectionDiagnostics;
    private TextView shutterCorrectionStarsUsed;
    private TextView shutterCorrectionRmsError;
    private TextView shutterCorrectionMaxExposure;
    private TextView shutterCorrectionRemainingExposure;
    private View shutterLastSolveCoordinates;
    private TextView shutterLastSolveRaDec;
    private TextView shutterLastSolveAltAz;
    private TextView shutterLastSolveLatency;
    private View starTrackerLastSolveCoordinates;
    private TextView starTrackerLastSolveRaDec;
    private TextView starTrackerLastSolveAltAz;
    private TextView starTrackerLastSolveLatency;
    private TextView shutterLocationStatus;
    private TextView starTrackerLocationStatus;
    private Button shutterLocationRefreshButton;
    private Button starTrackerLocationRefreshButton;
    private Button zeroCameraAltAzButton;
    private TextView starStatusView;
    private TextView starResultSummary;
    private TextView starCaptureDetails;
    private StarFieldView starVisualization;
    private View starMatchLegend;
    private Button solveDetailsToggle;
    private View solveDetailsPanel;
    private View plateSolveResult;
    private TextView plateSolveCoordinates;
    private TextView plateSolveDuration;
    private TextView plateSolveOrientation;
    private TextView plateSolveLensSource;
    private TextView plateSolveQuality;
    private TextView plateSolveFocalComparison;
    private TextView trackingModelStatus;
    private EditText solverMaxObservations;
    private EditText solverSeedTolerance;
    private EditText solverMatchTolerance;
    private EditText solverIndependentMatches;
    private EditText solverTimeout;
    private Button solverSettingsApplyButton;
    private Button solverSettingsRestoreButton;
    private TextView solverSettingsStatus;
    private Switch manualOverrideToggle;
    private View manualAttitudeFields;
    private EditText manualRa;
    private EditText manualDec;
    private TextView manualAttitudeStatus;
    private SeekBar correctionStrength;
    private TextView correctionStrengthValue;
    private TextView correctionAcknowledgedRates;
    private Switch prepositionEnabledToggle;
    private IbisCorrectionView shutterIbisVisualization;
    private TextView shutterIbisStatus;
    private Button applyCorrectionButton;
    private Button stopCorrectionButton;
    private EditText detectorSigma;
    private EditText detectorMinArea;
    private EditText detectorMaxArea;
    private EditText detectorElongation;
    private EditText detectorSharpness;
    private EditText detectorBorder;
    private Button detectorApplyButton;
    private Button detectorRestoreButton;
    private TextView detectorStatus;

    private ArrayAdapter<CameraConnectionService.CameraCandidate> cameraAdapter;
    private final ExposureDraft cameraExposureDraft = new ExposureDraft();
    private final ExposureDraft starExposureDraft = new ExposureDraft();
    private final ExposureIntentTracker cameraSettingsIntentTracker =
            new ExposureIntentTracker();
    private final ExposureIntentTracker starRecipeIntentTracker =
            new ExposureIntentTracker();
    private ExposureCatalogs renderedExposureCatalogs;
    private int renderedExposureConnectionGeneration = -1;
    private CameraConnectionService service;
    private CameraConnectionService.Snapshot currentSnapshot;
    private StarCaptureSession.Snapshot currentStarSnapshot;
    private SolveSnapshot currentPlateSolveSnapshot;
    private CameraCredentials credentialStore;
    private ForegroundLocationAcquirer foregroundLocationAcquirer;
    private ForegroundLocationAcquirer coordinateLocationAcquirer;
    private boolean bindRegistered;
    private boolean serviceBindingUsable;
    private boolean activityStarted;
    private final Object activityVisibilityOwner = new Object();
    private boolean suppressShutterClick;
    private boolean suppressHalfPressClick;
    private boolean connectionSetupVisible;
    private boolean starTrackerPageVisible;
    private boolean starRequestPending;
    private long pendingSolveIntentId = -1L;
    private boolean cameraSettingsRejected;
    private boolean starRecipeRejected;
    private boolean nightMode;
    private final ShutterSlideGesture shutterGesture = new ShutterSlideGesture();
    private boolean shutterLocked;
    private boolean shutterPointerSequenceCancelled;
    private CameraConnectionService.Snapshot shutterGestureStartSnapshot;
    private boolean suppressAstroInputs;
    private boolean manualDraftEditing;
    private boolean plateSolverSettingsDirty;
    private boolean navigationBackCallbackRegistered;
    private android.window.OnBackInvokedCallback navigationBackCallback;
    private String selectedAddress = "";
    private String selectedName = "";
    private PendingAction pendingAction = PendingAction.NONE;
    private PendingManualLocationRequest pendingManualLocationRequest;
    private boolean coordinateLocationRequestInFlight;
    private boolean coordinateLocationPermissionPending;
    private int coordinateLocationFailure;
    private ObserverCoordinateContext latestLocationContext;
    private String foregroundManualStatus = "";

    private final Runnable heldTimerUpdater = new Runnable() {
        @Override
        public void run() {
            if (!activityStarted) return;
            updateHeldPresentation();
            if (activityStarted && (shutterGesture.isPointerActive() || shutterLocked)) {
                uiHandler.postDelayed(this, SHUTTER_TIMER_UPDATE_MS);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!activityStarted || !bindRegistered) return;
            service = ((CameraConnectionService.LocalBinder) binder).getService();
            serviceBindingUsable = true;
            service.setAppVisiblyForeground(activityVisibilityOwner, true);
            // A newly bound service may have no STAR snapshot. Clear presentation state first
            // because listeners only replay a snapshot when one actually exists.
            clearStarSnapshotPresentation();
            service.addListener(MainActivity.this);
            service.addStarListener(MainActivity.this);
            service.addPlateSolveListener(MainActivity.this);
            CameraConnectionService.Snapshot state = service.getSnapshot();
            resetExposureIntentTrackers(state);
            if (credentialStore.isDesiredConnected()
                    && state.phase == CameraConnectionService.Phase.DISCONNECTED
                    && !state.desiredConnected) {
                startForegroundService(new Intent(MainActivity.this, CameraConnectionService.class)
                        .setAction(CameraConnectionService.ACTION_CONNECT));
            }
            if (pendingAction != PendingAction.NONE && missingBluetoothPermissions().isEmpty()) {
                uiHandler.post(MainActivity.this::continueAfterForegroundPermissions);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            cancelManualLocationRequest();
            clearLastSolveCoordinateState();
            pendingSolveIntentId = -1L;
            if (service != null) {
                service.removeListener(MainActivity.this);
                service.removeStarListener(MainActivity.this);
                service.removePlateSolveListener(MainActivity.this);
            }
            service = null;
            serviceBindingUsable = false;
            currentSnapshot = null;
            cameraSettingsIntentTracker.reset(0L, false);
            starRecipeIntentTracker.reset(0L, false);
            cameraSettingsRejected = false;
            starRecipeRejected = false;
            clearStarSnapshotPresentation();
            currentPlateSolveSnapshot = null;
            renderDisconnectedFallback();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The manifest uses an always-dark launcher theme so Android's process-start
        // splash cannot flash white/teal in night mode. Switch to the real UI theme
        // before Activity creation continues.
        setTheme(R.style.Theme_OmShutter);
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences displayPreferences =
                getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE);
        nightMode = displayPreferences.getBoolean(PREF_NIGHT_MODE, false);
        shutterLocked = displayPreferences.getBoolean(PREF_SHUTTER_LOCKED, false);
        if (shutterLocked) {
            shutterGesture.restoreLocked(
                    displayPreferences.getLong(
                            PREF_SHUTTER_LOCKED_AT, SystemClock.elapsedRealtime()),
                    SystemClock.elapsedRealtime());
        }
        if (nightMode) applyEarlyNightWindow();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        credentialStore = new CameraCredentials(this);
        foregroundLocationAcquirer = new ForegroundLocationAcquirer(this);
        coordinateLocationAcquirer = new ForegroundLocationAcquirer(this);
        if (Build.VERSION.SDK_INT >= 33) {
            navigationBackCallback = this::navigateBackWithinApp;
        }
        if (savedInstanceState != null) {
            String savedAction = savedInstanceState.getString(STATE_PENDING_ACTION);
            if (savedAction != null) {
                try {
                    pendingAction = PendingAction.valueOf(savedAction);
                } catch (IllegalArgumentException ignored) {
                    pendingAction = PendingAction.NONE;
                }
            }
            connectionSetupVisible = savedInstanceState.getBoolean(STATE_CONNECTION_SETUP, false);
            starTrackerPageVisible = savedInstanceState.getBoolean(
                    STATE_STAR_TRACKER_PAGE_VISIBLE, false);
            if (shutterLocked) starTrackerPageVisible = false;
        }
        bindViews();
        configureCameraSpinner();
        configureExposurePickers();
        configureActions();
        restoreSettings();
        renderDisconnectedFallback();
        applyNightMode();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        serviceBindingUsable = false;
        disableServiceActions();
        renderCorrectionDiagnostics(null);
        restartHeldTimerFromSnapshot();
        bindRegistered = bindService(
                new Intent(this, CameraConnectionService.class),
                connection,
                Context.BIND_AUTO_CREATE);
        if (!bindRegistered) renderDisconnectedFallback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // SCREEN_ON alone is not proof that this Activity is visible (the keyguard may still own
        // the display). A fresh resumed callback is the authority that re-enables power policy.
        if (serviceBindingUsable && service != null) {
            service.setAppVisiblyForeground(activityVisibilityOwner, true);
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        if (serviceBindingUsable && service != null) {
            service.setAppVisiblyForeground(activityVisibilityOwner, false);
        }
        cancelManualLocationRequest();
        if (coordinateLocationAcquirer != null) coordinateLocationAcquirer.cancel();
        coordinateLocationRequestInFlight = false;
        coordinateLocationPermissionPending = false;
        pendingSolveIntentId = -1L;
        uiHandler.removeCallbacks(heldTimerUpdater);
        uiHandler.removeCallbacks(lastSolveCoordinateUpdater);
        if (service != null && !shutterLocked) {
            // Idempotent and covers both an in-flight full press and AF half-press.
            service.releaseAll();
        }
        resetShutterTouchVisual();
        shutterPointerSequenceCancelled = false;
        if (serviceBindingUsable && service != null) {
            service.removeListener(this);
            service.removeStarListener(this);
            service.removePlateSolveListener(this);
        }
        serviceBindingUsable = false;
        service = null;
        if (bindRegistered) {
            unbindService(connection);
            bindRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_PENDING_ACTION, pendingAction.name());
        outState.putBoolean(STATE_CONNECTION_SETUP, connectionSetupVisible);
        outState.putBoolean(STATE_STAR_TRACKER_PAGE_VISIBLE, starTrackerPageVisible);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (foregroundLocationAcquirer != null) foregroundLocationAcquirer.cancel();
        if (coordinateLocationAcquirer != null) coordinateLocationAcquirer.cancel();
        uiHandler.removeCallbacks(heldTimerUpdater);
        uiHandler.removeCallbacks(lastSolveCoordinateUpdater);
        if (Build.VERSION.SDK_INT >= 33 && navigationBackCallbackRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(navigationBackCallback);
            navigationBackCallbackRegistered = false;
        }
        super.onDestroy();
    }

    private void bindViews() {
        rootScroll = findViewById(R.id.root_scroll);
        rootContent = findViewById(R.id.root_content);
        connectionScreen = findViewById(R.id.connection_screen);
        controlsScreen = findViewById(R.id.controls_screen);
        shutterPage = findViewById(R.id.shutter_page);
        shutterActionBar = findViewById(R.id.shutter_action_bar);
        starTrackerPanel = findViewById(R.id.star_tracker_panel);
        astroSettingsButton = findViewById(R.id.astro_settings_button);
        starTrackerBackButton = findViewById(R.id.star_tracker_back_button);
        statusView = findViewById(R.id.connection_status);
        detailView = findViewById(R.id.connection_status_detail);
        controlsStatusView = findViewById(R.id.controls_status);
        controlsDetailView = findViewById(R.id.controls_status_detail);
        controlsCameraName = findViewById(R.id.controls_camera_name);
        discoveredLabel = findViewById(R.id.discovered_label);
        cameraName = findViewById(R.id.camera_name);
        passcode = findViewById(R.id.passcode);
        cameraSpinner = findViewById(R.id.camera_spinner);
        scanButton = findViewById(R.id.scan_button);
        connectButton = findViewById(R.id.connect_button);
        controlsDisconnectButton = findViewById(R.id.controls_disconnect_button);
        controlsRefreshButton = findViewById(R.id.controls_refresh_button);
        connectionBackButton = findViewById(R.id.connection_back_button);
        halfPressButton = findViewById(R.id.half_press_button);
        shutterButton = findViewById(R.id.shutter_button);
        shutterLockTarget = findViewById(R.id.shutter_lock_target);
        nightModeButton = findViewById(R.id.night_mode_button);
        cameraAperture = findViewById(R.id.camera_aperture);
        cameraShutterSpeed = findViewById(R.id.camera_shutter_speed);
        cameraIso = findViewById(R.id.camera_iso);
        starAperture = findViewById(R.id.star_aperture);
        starShutterSpeed = findViewById(R.id.star_shutter_speed);
        starIso = findViewById(R.id.star_iso);
        captureSettingsToggle = findViewById(R.id.capture_settings_toggle);
        captureSettingsPanel = findViewById(R.id.capture_settings_panel);
        detectorSettingsToggle = findViewById(R.id.detector_settings_toggle);
        detectorSettingsPanel = findViewById(R.id.detector_settings_panel);
        solverSettingsToggle = findViewById(R.id.solver_settings_toggle);
        solverSettingsPanel = findViewById(R.id.solver_settings_panel);
        detectStarsButton = findViewById(R.id.detect_stars_button);
        shutterSolveButton = findViewById(R.id.shutter_solve_button);
        shutterSolveCaptureButton = findViewById(R.id.shutter_solve_capture_button);
        correctionEnabledToggle = findViewById(R.id.shutter_correction_enabled);
        shutterCorrectionStatus = findViewById(R.id.shutter_correction_status);
        shutterCorrectionDiagnostics = findViewById(R.id.shutter_correction_diagnostics);
        shutterCorrectionStarsUsed = findViewById(R.id.shutter_correction_stars_used);
        shutterCorrectionRmsError = findViewById(R.id.shutter_correction_rms_error);
        shutterCorrectionMaxExposure = findViewById(R.id.shutter_correction_max_exposure);
        shutterCorrectionRemainingExposure =
                findViewById(R.id.shutter_correction_remaining_exposure);
        shutterLastSolveCoordinates = findViewById(R.id.shutter_last_solve_coordinates);
        shutterLastSolveRaDec = findViewById(R.id.shutter_last_solve_radec);
        shutterLastSolveAltAz = findViewById(R.id.shutter_last_solve_altaz);
        shutterLastSolveLatency = findViewById(R.id.shutter_last_solve_latency);
        starTrackerLastSolveCoordinates =
                findViewById(R.id.star_tracker_last_solve_coordinates);
        starTrackerLastSolveRaDec = findViewById(R.id.star_tracker_last_solve_radec);
        starTrackerLastSolveAltAz = findViewById(R.id.star_tracker_last_solve_altaz);
        starTrackerLastSolveLatency = findViewById(R.id.star_tracker_last_solve_latency);
        shutterLocationStatus = findViewById(R.id.shutter_location_status);
        starTrackerLocationStatus = findViewById(R.id.star_tracker_location_status);
        shutterLocationRefreshButton = findViewById(R.id.shutter_location_refresh_button);
        starTrackerLocationRefreshButton = findViewById(R.id.star_tracker_location_refresh_button);
        zeroCameraAltAzButton = findViewById(R.id.zero_camera_altaz_button);
        starStatusView = findViewById(R.id.star_status);
        starResultSummary = findViewById(R.id.star_result_summary);
        starCaptureDetails = findViewById(R.id.star_capture_details);
        starVisualization = findViewById(R.id.star_visualization);
        starMatchLegend = findViewById(R.id.star_match_legend);
        solveDetailsToggle = findViewById(R.id.solve_details_toggle);
        solveDetailsPanel = findViewById(R.id.solve_details_panel);
        trackingModelStatus = findViewById(R.id.tracking_model_status);
        plateSolveResult = findViewById(R.id.plate_solve_result);
        plateSolveCoordinates = findViewById(R.id.plate_solve_coordinates);
        plateSolveDuration = findViewById(R.id.plate_solve_duration);
        plateSolveOrientation = findViewById(R.id.plate_solve_orientation);
        plateSolveLensSource = findViewById(R.id.plate_solve_lens_source);
        plateSolveQuality = findViewById(R.id.plate_solve_quality);
        plateSolveFocalComparison = findViewById(R.id.plate_solve_focal_comparison);
        solverMaxObservations = findViewById(R.id.solver_max_observations);
        solverSeedTolerance = findViewById(R.id.solver_seed_tolerance);
        solverMatchTolerance = findViewById(R.id.solver_match_tolerance);
        solverIndependentMatches = findViewById(R.id.solver_independent_matches);
        solverTimeout = findViewById(R.id.solver_timeout);
        solverSettingsApplyButton = findViewById(R.id.solver_settings_apply_button);
        solverSettingsRestoreButton = findViewById(R.id.solver_settings_restore_button);
        solverSettingsStatus = findViewById(R.id.solver_settings_status);
        manualOverrideToggle = findViewById(R.id.manual_override_toggle);
        manualAttitudeFields = findViewById(R.id.manual_attitude_fields);
        manualRa = findViewById(R.id.manual_ra);
        manualDec = findViewById(R.id.manual_dec);
        manualAttitudeStatus = findViewById(R.id.manual_attitude_status);
        correctionStrength = findViewById(R.id.correction_strength);
        correctionStrengthValue = findViewById(R.id.correction_strength_value);
        correctionAcknowledgedRates = findViewById(R.id.correction_acknowledged_rates);
        prepositionEnabledToggle = findViewById(R.id.preposition_enabled);
        shutterIbisVisualization = findViewById(R.id.shutter_ibis_visualization);
        shutterIbisStatus = findViewById(R.id.shutter_ibis_status);
        applyCorrectionButton = findViewById(R.id.apply_correction_button);
        stopCorrectionButton = findViewById(R.id.stop_correction_button);
        detectorSigma = findViewById(R.id.detector_sigma);
        detectorMinArea = findViewById(R.id.detector_min_area);
        detectorMaxArea = findViewById(R.id.detector_max_area);
        detectorElongation = findViewById(R.id.detector_elongation);
        detectorSharpness = findViewById(R.id.detector_sharpness);
        detectorBorder = findViewById(R.id.detector_border);
        detectorApplyButton = findViewById(R.id.detector_apply_button);
        detectorRestoreButton = findViewById(R.id.detector_restore_button);
        detectorStatus = findViewById(R.id.detector_status);
    }

    private void configureCameraSpinner() {
        cameraAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new ArrayList<>());
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraAdapter);
        cameraSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                CameraConnectionService.CameraCandidate candidate = cameraAdapter.getItem(position);
                if (candidate == null) return;
                selectedAddress = candidate.address;
                selectedName = candidate.name;
                cameraName.setText(candidate.name);
                CameraCredentials.SavedCamera saved = credentialStore.load();
                if (!saved.name.equals(candidate.name)) passcode.setText("");
                passcode.requestFocus();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                // Manual entry remains available.
            }
        });
        cameraName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!s.toString().trim().equals(selectedName)) selectedAddress = "";
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void configureExposurePickers() {
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            configureExposurePicker(false, kind);
            configureExposurePicker(true, kind);
        }
    }

    private void configureExposurePicker(boolean starRecipe, ExposureCatalogs.Kind kind) {
        ExposureDraft draft = starRecipe ? starExposureDraft : cameraExposureDraft;
        ExposureWheelPicker control = exposurePicker(starRecipe, kind);
        control.setOnOptionSettledListener(option -> {
            if (option.equals(draft.getSelected(kind))) return;
            try {
                draft.selectFromUser(kind, option);
            } catch (IllegalArgumentException ignored) {
                renderExposureControls();
                return;
            }
            submitExposureSelection(starRecipe, draft.getSelection());
        });
    }

    private ExposureWheelPicker exposurePicker(
            boolean starRecipe, ExposureCatalogs.Kind kind) {
        switch (kind) {
            case SHUTTER:
                return starRecipe ? starShutterSpeed : cameraShutterSpeed;
            case APERTURE:
                return starRecipe ? starAperture : cameraAperture;
            case ISO:
                return starRecipe ? starIso : cameraIso;
            default:
                throw new IllegalArgumentException("Unknown exposure kind");
        }
    }

    private void submitExposureSelection(
            boolean starRecipe, ExposureCatalogs.Selection selection) {
        if (service == null || selection == null) return;
        if (starRecipe) {
            starRecipeRejected = false;
            starRecipeIntentTracker.begin();
        } else {
            cameraSettingsRejected = false;
            cameraSettingsIntentTracker.begin();
        }
        // Lock both actions before crossing to the service's Bluetooth handler. A Snapshot from
        // that handler is not required to close the UI-handler race.
        renderExposureControls();
        renderStarControls();
        updateShutterActionEnabled();
        if (starRecipe) {
            starRecipeIntentTracker.assign(service.setStarRecipe(selection));
        } else {
            cameraSettingsIntentTracker.assign(service.setCameraSettings(selection));
        }
    }

    @SuppressLint("ClickableViewAccessibility") // ACTION_UP calls performClick; TalkBack uses OnClick.
    private void configureActions() {
        astroSettingsButton.setOnClickListener(view -> {
            if (!isAstroNavigationEnabled(
                    shutterLocked,
                    currentSnapshot != null && currentSnapshot.halfHeld,
                    currentSnapshot != null && currentSnapshot.shutterHeld)) {
                return;
            }
            showStarTrackerPage();
        });
        starTrackerBackButton.setOnClickListener(view -> showShutterPage());
        updateControlPageVisibility();

        scanButton.setOnClickListener(view -> {
            if (!isServiceUsable()) {
                pendingAction = PendingAction.SCAN;
                return;
            }
            if (currentSnapshot != null && currentSnapshot.scanning && service != null) {
                service.stopDiscovery();
            } else {
                beginAfterPermissions(PendingAction.SCAN);
            }
        });

        connectButton.setOnClickListener(view -> {
            if (!isServiceUsable()) {
                pendingAction = PendingAction.CONNECT;
                return;
            }
            if (currentSnapshot != null && currentSnapshot.desiredConnected) {
                cancelManualLocationRequest();
                pendingSolveIntentId = -1L;
                service.disconnect();
                showControlsScreen();
            } else {
                beginAfterPermissions(PendingAction.CONNECT);
            }
        });
        controlsDisconnectButton.setOnClickListener(view -> {
            if (isServiceUsable()) {
                cancelManualLocationRequest();
                pendingSolveIntentId = -1L;
                service.disconnect();
            }
        });
        controlsStatusView.setOnClickListener(view -> showConnectionSetup());
        controlsRefreshButton.setOnClickListener(view -> {
            if (!credentialStore.load().isComplete()) {
                showConnectionSetup();
                return;
            }
            beginAfterPermissions(PendingAction.RETRY);
        });
        connectionBackButton.setOnClickListener(view -> showControlsScreen());

        halfPressButton.setOnClickListener(view -> {
            if (!suppressHalfPressClick && service != null && isReady()) {
                // Accessibility clicks have no down/up gesture, so issue a short AF pulse.
                service.halfPressDown();
                service.halfPressUp();
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        });
        halfPressButton.setOnTouchListener((view, event) -> {
            if (service == null || !isReady()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (event.getPointerId(event.getActionIndex()) != 0
                            && event.getPointerCount() > 1) {
                        return true;
                    }
                    service.halfPressDown();
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    service.halfPressUp();
                    suppressHalfPressClick = true;
                    view.performClick();
                    suppressHalfPressClick = false;
                    return true;
                default:
                    return true;
            }
        });

        shutterButton.setOnClickListener(view -> {
            if (suppressShutterClick || service == null) return;
            if (shutterLocked) {
                applyShutterGestureResult(shutterGesture.unlockForAccessibility(
                        SystemClock.elapsedRealtime()));
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else if (isReady() && currentSnapshot != null && !currentSnapshot.shutterHeld) {
                service.takePhoto();
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        });
        shutterButton.setOnTouchListener(this::handleShutterSlideTouch);
        shutterLockTarget.setOnTouchListener(this::handleShutterSlideTouch);

        // Physical taps on the right segment are deliberately inert; accessibility services
        // receive this explicit equivalent of sliding Shutter to Lock.
        shutterLockTarget.setOnClickListener(view -> {
            if (suppressShutterClick || service == null || !isReady() || shutterLocked
                    || currentSnapshot == null || currentSnapshot.busy
                    || currentSnapshot.shutterHeld) {
                return;
            }
            applyShutterGestureResult(shutterGesture.lockForAccessibility(
                    SystemClock.elapsedRealtime()));
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        });

        // TalkBack users can also lock directly from the center action.
        shutterButton.setOnLongClickListener(view -> {
            if (service == null || !isReady() || shutterLocked
                    || currentSnapshot == null || currentSnapshot.busy
                    || currentSnapshot.shutterHeld) {
                return false;
            }
            applyShutterGestureResult(shutterGesture.lockForAccessibility(
                    SystemClock.elapsedRealtime()));
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        nightModeButton.setOnClickListener(view -> {
            nightMode = !nightMode;
            getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
                    .edit().putBoolean(PREF_NIGHT_MODE, nightMode).apply();
            applyNightMode();
        });
        shutterSolveButton.setOnClickListener(view -> beginSolve(false, view));
        shutterSolveCaptureButton.setOnClickListener(view -> beginSolve(true, view));
        detectStarsButton.setOnClickListener(view -> beginSolve(false, view));
        zeroCameraAltAzButton.setOnClickListener(view -> {
            if (service != null && currentSnapshot != null) {
                service.zeroCameraAltAzForTesting(currentSnapshot.connectionGeneration);
            }
        });
        shutterLocationRefreshButton.setOnClickListener(view -> refreshCoordinateLocation());
        starTrackerLocationRefreshButton.setOnClickListener(view -> refreshCoordinateLocation());
        configureCollapsible(
                solveDetailsToggle,
                solveDetailsPanel,
                PREF_SOLVE_DETAILS_EXPANDED,
                R.string.solve_details_collapsed,
                R.string.solve_details_expanded);
        configureCollapsible(
                captureSettingsToggle,
                captureSettingsPanel,
                PREF_CAPTURE_SETTINGS_EXPANDED,
                R.string.capture_settings_collapsed,
                R.string.capture_settings_expanded);
        configureCollapsible(
                detectorSettingsToggle,
                detectorSettingsPanel,
                PREF_DETECTOR_SETTINGS_EXPANDED,
                R.string.detector_settings_collapsed,
                R.string.detector_settings_expanded);
        configureCollapsible(
                solverSettingsToggle,
                solverSettingsPanel,
                PREF_SOLVER_SETTINGS_EXPANDED,
                R.string.solver_settings_collapsed,
                R.string.solver_settings_expanded);

        correctionEnabledToggle.setOnCheckedChangeListener((button, checked) -> {
            if (suppressAstroInputs || service == null) return;
            cancelManualLocationRequest();
            service.setCorrectionEnabled(checked);
        });
        prepositionEnabledToggle.setOnCheckedChangeListener((button, checked) -> {
            if (suppressAstroInputs || service == null) return;
            service.setPrepositionEnabled(checked);
        });
        manualOverrideToggle.setOnCheckedChangeListener((button, checked) -> {
            if (suppressAstroInputs || service == null) return;
            cancelManualLocationRequest();
            service.setManualOverrideEnabled(checked);
            updateManualFieldEnabled();
        });

        TextWatcher manualWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (suppressAstroInputs || service == null) return;
                manualDraftEditing = true;
                cancelManualLocationRequest();
                service.saveManualDraft(
                        manualRa.getText().toString(),
                        manualDec.getText().toString());
            }
        };
        manualRa.addTextChangedListener(manualWatcher);
        manualDec.addTextChangedListener(manualWatcher);
        View.OnFocusChangeListener manualFocus = (view, focused) -> {
            manualDraftEditing = manualRa.hasFocus() || manualDec.hasFocus();
        };
        manualRa.setOnFocusChangeListener(manualFocus);
        manualDec.setOnFocusChangeListener(manualFocus);

        correctionStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                renderStrengthValue(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                CorrectionScale.Detent detent = CorrectionScale.detentOnRelease(
                        seekBar.getProgress());
                if (detent.getPercent() != seekBar.getProgress()) {
                    suppressAstroInputs = true;
                    seekBar.setProgress(detent.getPercent());
                    suppressAstroInputs = false;
                }
                if (detent.isSnapped()) {
                    seekBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                cancelManualLocationRequest();
                if (service != null) {
                    service.setCorrectionStrengthPercent(detent.getPercent());
                }
            }
        });

        applyCorrectionButton.setOnClickListener(view -> {
            cancelManualLocationRequest();
            manualRa.clearFocus();
            manualDec.clearFocus();
            manualDraftEditing = false;
            if (service == null) return;
            if (usesManualAttitude()) {
                beginManualLocationRequest(ManualLocationAction.APPLY);
            } else {
                setForegroundManualStatus(getString(R.string.correction_applying));
                foregroundManualStatus = "";
                service.applyCorrection();
            }
        });
        stopCorrectionButton.setOnClickListener(view -> {
            cancelManualLocationRequest();
            if (service != null) service.stopCorrection();
        });
        detectorApplyButton.setOnClickListener(view -> {
            if (service == null) return;
            try {
                service.commitDetectorConfig(readDetectorConfig());
            } catch (IllegalArgumentException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        detectorRestoreButton.setOnClickListener(view -> {
            StarTelemetry.DetectorConfig defaults =
                    StarTelemetry.DEFAULT_CONFIG;
            renderDetectorInputs(defaults);
            if (service != null) service.commitDetectorConfig(defaults);
        });

        TextWatcher solverSettingsWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(
                    CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (suppressAstroInputs) return;
                plateSolverSettingsDirty = true;
                solverSettingsStatus.setText(R.string.solver_settings_unsaved);
            }
        };
        solverMaxObservations.addTextChangedListener(solverSettingsWatcher);
        solverSeedTolerance.addTextChangedListener(solverSettingsWatcher);
        solverMatchTolerance.addTextChangedListener(solverSettingsWatcher);
        solverIndependentMatches.addTextChangedListener(solverSettingsWatcher);
        solverTimeout.addTextChangedListener(solverSettingsWatcher);
        solverSettingsApplyButton.setOnClickListener(view -> {
            try {
                PlateSolverSettings settings = readPlateSolverSettings();
                clearPlateSolverSettingsFocus();
                plateSolverSettingsDirty = false;
                if (service != null) service.commitPlateSolverSettings(settings);
            } catch (IllegalArgumentException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        solverSettingsRestoreButton.setOnClickListener(view -> {
            clearPlateSolverSettingsFocus();
            plateSolverSettingsDirty = false;
            renderPlateSolverSettings(PlateSolverSettings.defaults());
            if (service != null) service.restoreDefaultPlateSolverSettings();
        });
    }

    private boolean handleShutterSlideTouch(View source, MotionEvent event) {
        int action = event.getActionMasked();
        if (shutterPointerSequenceCancelled) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                shutterPointerSequenceCancelled = false;
                if (shutterActionBar.getParent() != null) {
                    shutterActionBar.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            shutterPointerSequenceCancelled = false;
            if (event.getPointerCount() != 1 || service == null) return false;
            if (!shutterLocked && (!isReady() || currentSnapshot == null
                    || currentSnapshot.busy || currentSnapshot.shutterHeld)) {
                return false;
            }
        } else if (!shutterGesture.isPointerActive()) {
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        ShutterSlideGesture.Result result;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                shutterGestureStartSnapshot = currentSnapshot;
                shutterActionBar.getParent().requestDisallowInterceptTouchEvent(true);
                result = shutterGesture.onPointerDown(now, shutterTargetAt(event));
                break;
            case MotionEvent.ACTION_MOVE:
                result = shutterGesture.onPointerMove(now, shutterTargetAt(event));
                break;
            case MotionEvent.ACTION_UP:
                result = shutterGesture.onPointerUp(now, shutterTargetAt(event));
                shutterActionBar.getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                result = shutterGesture.onPointerCancel(now);
                shutterActionBar.getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // A second pointer must never inherit a live camera press after the original
                // finger lifts. Cancel once, then consume the remainder of this touch stream.
                result = shutterGesture.onPointerCancel(now);
                shutterPointerSequenceCancelled = true;
                shutterActionBar.getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                return true;
        }

        boolean wasLocked = shutterLocked;
        applyShutterGestureResult(result);
        if (result.pressShutter()) {
            setShutterPressedVisual();
            source.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (result.lockChanged() || (wasLocked && result.releaseShutter())) {
            source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || isAdditionalPointerTransition(action)) {
            resetShutterTouchVisual();
            if (action == MotionEvent.ACTION_UP) {
                suppressShutterClick = true;
                source.performClick();
                suppressShutterClick = false;
            }
        } else if (!wasLocked && shutterLocked) {
            uiHandler.removeCallbacks(heldTimerUpdater);
            uiHandler.post(heldTimerUpdater);
        }
        return result.consumed();
    }

    static boolean isAdditionalPointerTransition(int actionMasked) {
        return actionMasked == MotionEvent.ACTION_POINTER_DOWN
                || actionMasked == MotionEvent.ACTION_POINTER_UP;
    }

    private ShutterSlideGesture.Target shutterTargetAt(MotionEvent event) {
        int[] location = new int[2];
        shutterActionBar.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        return ShutterSlideGesture.targetAt(
                x,
                y,
                childBounds(halfPressButton),
                childBounds(shutterButton),
                childBounds(shutterLockTarget));
    }

    private static ShutterSlideGesture.Bounds childBounds(View view) {
        return new ShutterSlideGesture.Bounds(
                view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private void applyShutterGestureResult(ShutterSlideGesture.Result result) {
        boolean wasLocked = shutterLocked;
        if (result.pressShutter() && service != null) service.shutterDown();
        if (result.releaseShutter() && service != null) {
            if (wasLocked) {
                service.releaseAll();
            } else {
                service.shutterUp();
            }
        }
        if (result.lockChanged()) setShutterLocked(result.locked());
    }

    private void configureCollapsible(
            Button toggle, View panel, String preferenceKey,
            int collapsedLabel, int expandedLabel) {
        android.content.SharedPreferences preferences =
                getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE);
        renderCollapsible(toggle, panel, collapsedLabel, expandedLabel,
                preferences.getBoolean(preferenceKey, false));
        toggle.setOnClickListener(view -> {
            boolean expand = panel.getVisibility() != View.VISIBLE;
            renderCollapsible(toggle, panel, collapsedLabel, expandedLabel, expand);
            preferences.edit().putBoolean(preferenceKey, expand).apply();
        });
    }

    private void renderCollapsible(
            Button toggle, View panel, int collapsedLabel, int expandedLabel, boolean expanded) {
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggle.setText(expanded ? expandedLabel : collapsedLabel);
        toggle.setSelected(expanded);
        if (Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(getString(expanded
                    ? R.string.settings_expanded_state
                    : R.string.settings_collapsed_state));
        }
    }

    private void beginSolve(boolean captureAfterSolve, View view) {
        if (canRefreshStarService()) {
            cancelManualLocationRequest();
            pendingSolveIntentId = -1L;
            starStatusView.setText(R.string.star_reconnecting);
            service.refreshStarService();
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        if (!canRequestStarDetection()) return;
        cancelManualLocationRequest();
        clearSolveDetails();
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        long coordinateToken = beginLastSolveCoordinateAcquisition();
        if (requiresManualContextForSolve()) {
            manualRa.clearFocus();
            manualDec.clearFocus();
            manualDraftEditing = false;
            beginManualLocationRequest(captureAfterSolve
                    ? ManualLocationAction.SOLVE_CAPTURE
                    : ManualLocationAction.SOLVE);
            return;
        }
        requestLastSolveLocation(coordinateToken, true);
        launchSolve(captureAfterSolve, null, null);
    }

    private void launchSolve(
            boolean captureAfterSolve,
            PendingManualLocationRequest manualRequest,
            ForegroundLocationAcquirer.Fix fix) {
        pendingSolveIntentId = -1L;
        clearSolveDetails();
        starRequestPending = true;
        starStatusView.setText(R.string.star_detection_started);
        renderStarControls();
        renderExposureControls();
        if (currentSnapshot != null) updateAstroControlEnabled(currentSnapshot.astro);
        try {
            if (manualRequest == null || fix == null) {
                pendingSolveIntentId = service.requestSolveWithoutManualCorrection(
                        captureAfterSolve);
            } else {
                ObserverLocationFix location = observerLocation(fix);
                Instant utc = fix.getUtc();
                pendingSolveIntentId = service.requestSolve(
                        manualRequest.rightAscension,
                        manualRequest.declination,
                        manualRequest.assertedElapsedRealtimeNanos,
                        location,
                        utc.getEpochSecond(),
                        utc.getNano(),
                        fix.getReceiptElapsedRealtimeNanos(),
                        captureAfterSolve);
            }
            long coordinateToken = lastSolveCoordinateAssociation.getActiveToken();
            lastSolveCoordinateAssociation.assignIntent(
                    coordinateToken, pendingSolveIntentId);
            if (fix != null) {
                offerLastSolveLocation(
                        coordinateToken,
                        lastSolveCoordinateAssociation.getActiveConnectionGeneration(),
                        fix);
            }
        } catch (IllegalArgumentException error) {
            pendingSolveIntentId = -1L;
            starRequestPending = false;
            failManualLocationRequest(singleLine(error.getMessage()));
        }
    }

    private void beginManualLocationRequest(ManualLocationAction action) {
        cancelManualLocationRequest();
        long assertedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        String rightAscension = manualRa.getText().toString().trim();
        String declination = manualDec.getText().toString().trim();
        try {
            ManualEquatorialPointing.parse(
                    rightAscension, declination, assertedElapsedRealtimeNanos);
        } catch (IllegalArgumentException error) {
            failManualLocationRequest(singleLine(error.getMessage()));
            return;
        }
        PendingManualLocationRequest request = new PendingManualLocationRequest(
                action, rightAscension, declination, assertedElapsedRealtimeNanos);
        pendingManualLocationRequest = request;
        if (!hasForegroundLocationPermission()) {
            setForegroundManualStatus(getString(R.string.location_permission_needed));
            refreshManualLocationControls();
            try {
                requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_MANUAL_LOCATION);
            } catch (RuntimeException error) {
                failManualLocationRequest(getString(R.string.location_unavailable));
            }
            return;
        }
        acquireForegroundLocation(request);
    }

    private void acquireForegroundLocation(PendingManualLocationRequest request) {
        if (request == null || request != pendingManualLocationRequest
                || !activityStarted || foregroundLocationAcquirer == null) return;
        setForegroundManualStatus(getString(R.string.locating));
        refreshManualLocationControls();
        foregroundLocationAcquirer.request(new ForegroundLocationAcquirer.Callback() {
            @Override
            public void onLocation(ForegroundLocationAcquirer.Fix fix) {
                completeManualLocationRequest(request, fix);
            }

            @Override
            public void onUnavailable(ForegroundLocationAcquirer.Failure failure) {
                if (request != pendingManualLocationRequest) return;
                failManualLocationRequest(getString(
                        failure == ForegroundLocationAcquirer.Failure.TIME_UNAVAILABLE
                                ? R.string.time_unavailable
                                : R.string.location_unavailable));
            }
        });
    }

    private void completeManualLocationRequest(
            PendingManualLocationRequest request,
            ForegroundLocationAcquirer.Fix fix) {
        if (request == null || request != pendingManualLocationRequest || fix == null) return;
        latestLocationContext = coordinateContext(fix);
        coordinateLocationFailure = 0;
        pendingManualLocationRequest = null;
        if (!isServiceUsable() || currentSnapshot == null || !currentSnapshot.ready) {
            failManualLocationRequest(getString(R.string.correction_unavailable));
            return;
        }
        if (request.action.isSolve()) {
            if (!requiresManualContextForSolve()) {
                launchSolve(request.action.capturesAfterSolve(), null, null);
                foregroundManualStatus = "";
                return;
            }
            if (!canRequestStarDetection()) {
                failManualLocationRequest(getString(R.string.star_camera_busy));
                return;
            }
            setForegroundManualStatus(getString(R.string.star_detection_started));
            foregroundManualStatus = "";
            launchSolve(request.action.capturesAfterSolve(), request, fix);
            return;
        }
        if (currentSnapshot.busy || starRequestPending || pendingSolveIntentId >= 0L
                || isStarSessionActive() || isPlateSolveActive()) {
            failManualLocationRequest(getString(R.string.star_camera_busy));
            return;
        }
        if (!currentSnapshot.astroRateAvailable) {
            failManualLocationRequest(getString(R.string.correction_unavailable));
            return;
        }
        if (!currentSnapshot.astro.correctionEnabled) {
            failManualLocationRequest(getString(R.string.correction_enable_first));
            return;
        }
        if (currentSnapshot.astro.factorPercent <= 0) {
            failManualLocationRequest(getString(R.string.correction_set_strength));
            return;
        }
        try {
            ObserverLocationFix location = observerLocation(fix);
            Instant utc = fix.getUtc();
            setForegroundManualStatus(getString(R.string.correction_applying));
            foregroundManualStatus = "";
            service.applyManualGravityCorrection(
                    request.rightAscension,
                    request.declination,
                    request.assertedElapsedRealtimeNanos,
                    location,
                    utc.getEpochSecond(),
                    utc.getNano(),
                    fix.getReceiptElapsedRealtimeNanos());
            refreshManualLocationControls();
        } catch (IllegalArgumentException error) {
            failManualLocationRequest(singleLine(error.getMessage()));
        }
    }

    private static ObserverLocationFix observerLocation(ForegroundLocationAcquirer.Fix fix) {
        return ObserverLocationFix.of(
                fix.getLatitudeDeg(),
                fix.getLongitudeDeg(),
                fix.getAccuracyMeters(),
                fix.getFixElapsedRealtimeNanos());
    }

    private long beginLastSolveCoordinateAcquisition() {
        if (coordinateLocationAcquirer != null) coordinateLocationAcquirer.cancel();
        coordinateLocationRequestInFlight = false;
        coordinateLocationPermissionPending = false;
        coordinateLocationFailure = 0;
        int connectionGeneration = currentSnapshot == null
                ? -1 : currentSnapshot.connectionGeneration;
        return lastSolveCoordinateAssociation.begin(connectionGeneration);
    }

    private void refreshCoordinateLocation() {
        if (!canRefreshCoordinateLocation()) return;
        long token = beginLastSolveCoordinateAcquisition();
        if (currentSnapshot != null && currentSnapshot.ready
                && currentSnapshot.astro.lastSolvedResult != null) {
            lastSolveCoordinateAssociation.assignIntent(
                    token, currentSnapshot.astro.lastSolvedIntentId);
        }
        requestLastSolveLocation(token, true);
        renderLastSolveCoordinates(currentSnapshot == null ? null : currentSnapshot.astro);
    }

    private boolean canRefreshCoordinateLocation() {
        return isServiceUsable() && currentSnapshot != null && currentSnapshot.ready
                && !coordinateLocationRequestInFlight
                && !coordinateLocationPermissionPending && pendingManualLocationRequest == null
                && pendingSolveIntentId < 0L && !starRequestPending
                && !isStarSessionActive() && !isPlateSolveActive();
    }

    private void requestLastSolveLocation(long token, boolean requestPermission) {
        if (token != lastSolveCoordinateAssociation.getActiveToken()
                || !activityStarted
                || coordinateLocationRequestInFlight
                || coordinateLocationPermissionPending) {
            return;
        }
        int connectionGeneration =
                lastSolveCoordinateAssociation.getActiveConnectionGeneration();
        if (!hasForegroundLocationPermission()) {
            if (!requestPermission) {
                coordinateLocationFailure = R.string.location_permission_needed;
                lastSolveCoordinateAssociation.markLocationUnavailable(
                        token, connectionGeneration);
                return;
            }
            coordinateLocationPermissionPending = true;
            try {
                requestPermissions(
                        new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LAST_SOLVE_LOCATION);
            } catch (RuntimeException error) {
                coordinateLocationPermissionPending = false;
                coordinateLocationFailure = R.string.location_refresh_failed;
                lastSolveCoordinateAssociation.markLocationUnavailable(
                        token, connectionGeneration);
            }
            return;
        }
        if (coordinateLocationAcquirer == null) {
            coordinateLocationFailure = R.string.location_refresh_failed;
            lastSolveCoordinateAssociation.markLocationUnavailable(
                    token, connectionGeneration);
            return;
        }
        coordinateLocationRequestInFlight = true;
        coordinateLocationAcquirer.request(new ForegroundLocationAcquirer.Callback() {
            @Override
            public void onLocation(ForegroundLocationAcquirer.Fix fix) {
                if (token != lastSolveCoordinateAssociation.getActiveToken()) return;
                coordinateLocationRequestInFlight = false;
                offerLastSolveLocation(token, connectionGeneration, fix);
            }

            @Override
            public void onUnavailable(ForegroundLocationAcquirer.Failure failure) {
                if (token != lastSolveCoordinateAssociation.getActiveToken()) return;
                coordinateLocationRequestInFlight = false;
                coordinateLocationFailure = failure == ForegroundLocationAcquirer.Failure.TIME_UNAVAILABLE
                        ? R.string.time_unavailable : R.string.location_refresh_failed;
                lastSolveCoordinateAssociation.markLocationUnavailable(
                        token, connectionGeneration);
                renderLastSolveCoordinates(
                        currentSnapshot == null ? null : currentSnapshot.astro);
            }
        });
    }

    private void offerLastSolveLocation(
            long token,
            int connectionGeneration,
            ForegroundLocationAcquirer.Fix fix) {
        if (fix == null) return;
        ObserverCoordinateContext context = coordinateContext(fix);
        if (lastSolveCoordinateAssociation.offerContext(
                token, connectionGeneration, context)) {
            latestLocationContext = context;
            coordinateLocationFailure = 0;
            renderLastSolveCoordinates(
                    currentSnapshot == null ? null : currentSnapshot.astro);
        }
    }

    private static ObserverCoordinateContext coordinateContext(ForegroundLocationAcquirer.Fix fix) {
        Instant utc = fix.getUtc();
        return ObserverCoordinateContext.of(
                fix.getLatitudeDeg(), fix.getLongitudeDeg(),
                utc.getEpochSecond(), utc.getNano(),
                fix.getReceiptElapsedRealtimeNanos(), fix.getFixElapsedRealtimeNanos());
    }

    private void clearLastSolveCoordinateState() {
        if (coordinateLocationAcquirer != null) coordinateLocationAcquirer.cancel();
        coordinateLocationRequestInFlight = false;
        coordinateLocationPermissionPending = false;
        coordinateLocationFailure = 0;
        lastSolveCoordinateAssociation.clear();
        uiHandler.removeCallbacks(lastSolveCoordinateUpdater);
    }

    private boolean hasForegroundLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void failManualLocationRequest(String status) {
        if (foregroundLocationAcquirer != null) foregroundLocationAcquirer.cancel();
        pendingManualLocationRequest = null;
        setForegroundManualStatus(status == null || status.isEmpty()
                ? getString(R.string.location_unavailable) : status);
        refreshManualLocationControls();
    }

    private void cancelManualLocationRequest() {
        if (foregroundLocationAcquirer != null) foregroundLocationAcquirer.cancel();
        pendingManualLocationRequest = null;
        foregroundManualStatus = "";
        if (manualAttitudeStatus != null) manualAttitudeStatus.setText("");
    }

    private void setForegroundManualStatus(String status) {
        foregroundManualStatus = singleLine(status);
        if (manualAttitudeStatus != null) manualAttitudeStatus.setText(foregroundManualStatus);
        if (shutterCorrectionStatus != null) shutterCorrectionStatus.setText(foregroundManualStatus);
        if (pendingManualLocationRequest != null
                && pendingManualLocationRequest.action.isSolve()
                && starStatusView != null) {
            starStatusView.setText(foregroundManualStatus);
        }
    }

    private void refreshManualLocationControls() {
        renderExposureControls();
        renderStarControls();
        if (currentSnapshot != null) updateAstroControlEnabled(currentSnapshot.astro);
    }

    private StarTelemetry.DetectorConfig readDetectorConfig() {
        double sigma = parseInRange(detectorSigma, "Sigma", 1.0 / 256.0, 64.0);
        int minArea = parseIntInRange(detectorMinArea, "Minimum area", 1, 65_535);
        int maxArea = parseIntInRange(detectorMaxArea, "Maximum area", 1, 65_535);
        double elongation = parseDetectorElongation(detectorElongation);
        double sharpness = parseInRange(detectorSharpness, "Sharpness", 0.0, 1.0);
        int border = parseIntInRange(detectorBorder, "Border", 0, 1943);
        return new StarTelemetry.DetectorConfig(
                (int) Math.round(sigma * 256.0),
                minArea,
                maxArea,
                (int) Math.round(elongation * 256.0),
                (int) Math.round(sharpness * 256.0),
                border);
    }

    private static double parseInRange(
            EditText field, String label, double minimum, double maximum) {
        try {
            double value = Double.parseDouble(field.getText().toString().trim());
            if (!Double.isFinite(value) || value < minimum || value > maximum) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
    }

    private static double parseDetectorElongation(EditText field) {
        try {
            double value = Double.parseDouble(field.getText().toString().trim());
            if (!Double.isFinite(value)
                    || value < 0.0
                    || value > 65_535.0 / 256.0
                    || (value > 0.0 && value < 1.0)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Elongation must be 0 (off) or between 1.0 and 255.996");
        }
    }

    private static int parseIntInRange(
            EditText field, String label, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
    }

    private PlateSolverSettings readPlateSolverSettings() {
        return new PlateSolverSettings(
                parseIntInRange(
                        solverMaxObservations,
                        "Maximum observations",
                        PlateSolverSettings.MIN_MAX_OBSERVATIONS,
                        PlateSolverSettings.MAX_MAX_OBSERVATIONS),
                parseIntInRange(
                        solverSeedTolerance,
                        "Seed tolerance",
                        PlateSolverSettings.MIN_TOLERANCE_ARCSEC,
                        PlateSolverSettings.MAX_TOLERANCE_ARCSEC),
                parseIntInRange(
                        solverMatchTolerance,
                        "Match tolerance",
                        PlateSolverSettings.MIN_TOLERANCE_ARCSEC,
                        PlateSolverSettings.MAX_TOLERANCE_ARCSEC),
                parseIntInRange(
                        solverIndependentMatches,
                        "Independent matches",
                        PlateSolverSettings.MIN_REQUIRED_INDEPENDENT_MATCHES,
                        PlateSolverSettings.MAX_REQUIRED_INDEPENDENT_MATCHES),
                parseIntInRange(
                        solverTimeout,
                        "Solve timeout",
                        PlateSolverSettings.MIN_TIMEOUT_MILLIS,
                        PlateSolverSettings.MAX_TIMEOUT_MILLIS));
    }

    private void clearPlateSolverSettingsFocus() {
        solverMaxObservations.clearFocus();
        solverSeedTolerance.clearFocus();
        solverMatchTolerance.clearFocus();
        solverIndependentMatches.clearFocus();
        solverTimeout.clearFocus();
    }

    private void beginAfterPermissions(PendingAction action) {
        pendingAction = action;
        if (!isServiceUsable()) return;
        List<String> missing = missingBluetoothPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
            return;
        }
        continueAfterForegroundPermissions();
    }

    private void continueAfterForegroundPermissions() {
        if ((pendingAction == PendingAction.CONNECT || pendingAction == PendingAction.RETRY)
                && Build.VERSION.SDK_INT >= 29
                && Build.VERSION.SDK_INT <= 30
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            // Android 11 ignores background location when requested alongside foreground
            // location, so this must be a separate, second permission step.
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_BACKGROUND_LOCATION);
            return;
        }
        continuePendingAction();
    }

    private void continuePendingAction() {
        if (pendingAction == PendingAction.NONE || !isServiceUsable()) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Toast.makeText(this, R.string.bluetooth_off, Toast.LENGTH_LONG).show();
            pendingAction = PendingAction.NONE;
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
                return;
            }
        } catch (SecurityException error) {
            pendingAction = PendingAction.NONE;
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            return;
        }

        if (pendingAction == PendingAction.SCAN) {
            candidates.clear();
            cameraAdapter.clear();
            discoveredLabel.setVisibility(View.GONE);
            cameraSpinner.setVisibility(View.GONE);
            service.startDiscovery();
        } else if (pendingAction == PendingAction.CONNECT) {
            connectConfiguredCamera();
        } else if (pendingAction == PendingAction.RETRY) {
            retrySavedCamera();
        }
        pendingAction = PendingAction.NONE;
        requestNotificationPermissionIfUseful();
    }

    private void connectConfiguredCamera() {
        String name = cameraName.getText().toString().trim();
        String enteredPasscode = passcode.getText().toString();
        CameraCredentials.SavedCamera saved = credentialStore.load();
        boolean reusingSavedPasscode = enteredPasscode.isEmpty()
                && saved.name.equals(name)
                && !saved.passcode.isEmpty();
        if (name.isEmpty() || (!reusingSavedPasscode && !enteredPasscode.matches("[0-9]{1,6}"))) {
            Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
            return;
        }

        String address = selectedAddress;
        if (address.isEmpty() && saved.name.equals(name)) address = saved.address;
        try {
            credentialStore.save(name, address, enteredPasscode);
        } catch (GeneralSecurityException error) {
            new AlertDialog.Builder(this)
                    .setTitle("Could not save camera passcode")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        Intent connectIntent = new Intent(this, CameraConnectionService.class)
                .setAction(CameraConnectionService.ACTION_CONNECT);
        try {
            startForegroundService(connectIntent);
            showControlsScreen();
        } catch (RuntimeException error) {
            new AlertDialog.Builder(this)
                    .setTitle("Could not start camera connection")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void retrySavedCamera() {
        if (!credentialStore.load().isComplete()) {
            showConnectionSetup();
            return;
        }
        try {
            startForegroundService(new Intent(this, CameraConnectionService.class)
                    .setAction(CameraConnectionService.ACTION_CONNECT));
        } catch (RuntimeException error) {
            new AlertDialog.Builder(this)
                    .setTitle("Could not retry camera connection")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private List<String> missingBluetoothPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
    }

    private void requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (missingBluetoothPermissions().isEmpty()) {
                continueAfterForegroundPermissions();
            } else {
                pendingAction = PendingAction.NONE;
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 30
                    || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                continuePendingAction();
            } else {
                pendingAction = PendingAction.NONE;
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_MANUAL_LOCATION) {
            PendingManualLocationRequest request = pendingManualLocationRequest;
            if (request == null) return;
            if (hasForegroundLocationPermission()) {
                acquireForegroundLocation(request);
            } else {
                failManualLocationRequest(getString(R.string.location_permission_needed));
            }
        } else if (requestCode == REQUEST_LAST_SOLVE_LOCATION) {
            coordinateLocationPermissionPending = false;
            long token = lastSolveCoordinateAssociation.getActiveToken();
            int connectionGeneration =
                    lastSolveCoordinateAssociation.getActiveConnectionGeneration();
            if (hasForegroundLocationPermission()) {
                requestLastSolveLocation(token, false);
            } else {
                coordinateLocationFailure = R.string.location_permission_needed;
                lastSolveCoordinateAssociation.markLocationUnavailable(
                        token, connectionGeneration);
                renderLastSolveCoordinates(
                        currentSnapshot == null ? null : currentSnapshot.astro);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) continuePendingAction();
            else pendingAction = PendingAction.NONE;
        }
    }

    private void restoreSettings() {
        CameraCredentials.SavedCamera saved = credentialStore.load();
        cameraName.setText(saved.name);
        passcode.setText(saved.passcode);
        selectedName = saved.name;
        selectedAddress = saved.address;
        controlsCameraName.setText(saved.name);
    }

    @Override
    public void onStateChanged(CameraConnectionService.Snapshot snapshot) {
        // Listener delivery is queued on the main thread. A callback published before binder
        // death must not restore the old camera's diagnostics after this Activity detached.
        if (!activityStarted || !serviceBindingUsable) return;
        currentSnapshot = snapshot;
        if (!snapshot.ready) {
            cancelManualLocationRequest();
            clearLastSolveCoordinateState();
            pendingSolveIntentId = -1L;
            starRequestPending = false;
        } else if (pendingSolveIntentId >= 0L
                && snapshot.astro.lastSolveIntentId >= pendingSolveIntentId) {
            if (snapshot.astro.lastSolveIntentId == pendingSolveIntentId
                    && snapshot.astro.lastSolveIntentAccepted) {
                pendingSolveIntentId = -1L;
            } else {
                pendingSolveIntentId = -1L;
                starRequestPending = false;
            }
        }
        if (!snapshot.ready && shutterGesture.isPointerActive()) resetShutterTouchVisual();
        reconcileShutterPresentation(snapshot);
        if (shouldReturnToShutterPage(
                connectionSetupVisible, starTrackerPageVisible, snapshot.shutterHeld)) {
            showShutterPage();
        }
        String connectionStatus = singleLine(snapshot.status);
        String connectionDetail = singleLine(snapshot.detail);
        statusView.setText(connectionStatus);
        detailView.setText(connectionDetail);
        controlsDetailView.setText(connectionDetail);
        controlsDetailView.setVisibility(
                connectionDetail.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        CameraCredentials.SavedCamera saved = credentialStore.load();
        controlsCameraName.setText(snapshot.cameraName.isEmpty() ? saved.name : snapshot.cameraName);
        statusView.setBackgroundResource(
                snapshot.ready ? R.drawable.bg_status_ready : R.drawable.bg_status_idle);
        statusView.setContentDescription(
                getString(snapshot.ready ? R.string.accessibility_ready : R.string.accessibility_not_ready)
                        + ": " + connectionStatus);

        boolean stopped = !snapshot.ready && !snapshot.desiredConnected && !snapshot.scanning;
        // A saved-camera attempt can sit in scan/connect/setup timeouts for several
        // seconds. Keep an explicit retry available throughout those phases, while
        // leaving a user-started discovery scan alone.
        boolean showRefresh = !snapshot.ready
                && (!snapshot.scanning || snapshot.desiredConnected);
        controlsStatusView.setBackgroundTintList(null);
        if (snapshot.ready) {
            controlsStatusView.setText(R.string.ready);
            controlsStatusView.setBackgroundResource(R.drawable.bg_status_ready);
            controlsStatusView.setContentDescription("Ready. Open connection settings");
        } else if (stopped) {
            controlsStatusView.setText(R.string.not_connected);
            controlsStatusView.setBackgroundResource(R.drawable.bg_status_not_connected);
            controlsStatusView.setContentDescription("Not connected. Open connection settings");
        } else {
            controlsStatusView.setText(connectionStatus);
            controlsStatusView.setBackgroundResource(R.drawable.bg_status_idle);
            controlsStatusView.setContentDescription(
                    connectionStatus + ". Open connection settings");
        }
        setRefreshVisible(showRefresh);
        controlsRefreshButton.setEnabled(showRefresh);
        updateScreenVisibility();

        boolean connectionActive = snapshot.desiredConnected;
        cameraName.setEnabled(!connectionActive);
        passcode.setEnabled(!connectionActive);
        cameraSpinner.setEnabled(!connectionActive);
        boolean serviceUsable = isServiceUsable();
        scanButton.setEnabled(serviceUsable && (!connectionActive || snapshot.scanning));
        scanButton.setText(snapshot.scanning ? R.string.stop_scan : R.string.scan);
        connectButton.setText(connectionActive ? R.string.disconnect : R.string.connect);
        connectButton.setBackgroundTintList(ColorStateList.valueOf(getColor(
                connectionActive ? R.color.danger : R.color.accent)));
        connectButton.setTextColor(getColor(connectionActive ? R.color.text_primary : R.color.black));
        connectButton.setEnabled(serviceUsable);
        controlsDisconnectButton.setVisibility(connectionActive ? View.VISIBLE : View.GONE);
        controlsDisconnectButton.setEnabled(serviceUsable && connectionActive);

        boolean ready = snapshot.ready && serviceUsable;
        // Keep a physically held button enabled so Android can deliver ACTION_UP even
        // while its BLE command or a full-shutter command is in flight.
        halfPressButton.setEnabled(ready
                && pendingManualLocationRequest == null
                && (snapshot.halfHeld
                        || (snapshot.stockMutationAllowed
                                && !snapshot.busy
                                && !snapshot.shutterHeld)));
        halfPressButton.setText(R.string.half_press);
        halfPressButton.setSelected(snapshot.halfHeld);
        updateAstroNavigationEnabled();
        nightModeButton.setEnabled(true);

        synchronizeExposureSelections(snapshot);
        // Captured labels are bound to the capture Snapshot. Re-rendering after live STATE
        // changes must keep those labels rather than replacing them with restored settings.
        if (currentStarSnapshot != null
                && currentStarSnapshot.getCapturedMetadata() != null) {
            renderStarMetadata(
                    currentStarSnapshot.getCapturedMetadata(),
                    currentStarSnapshot.getCapturedSelection());
        }
        restartHeldTimerFromSnapshot();
        renderExposureControls();
        renderPlateSolve(currentPlateSolveSnapshot);
        renderAstro(snapshot.astro);
        renderStarControls();
    }

    @Override
    public void onStarJobChanged(StarCaptureSession.Snapshot snapshot) {
        // Listener delivery is queued on the main thread. Ignore a callback that was
        // already queued when this Activity detached from the service.
        if (!activityStarted || !serviceBindingUsable) return;
        if (snapshot == null) {
            clearStarSnapshotPresentation();
            renderExposureControls();
            renderStarControls();
            return;
        }
        currentStarSnapshot = snapshot;
        starRequestPending = false;

        if (snapshot.isSolveReady()) {
            starVisualization.setPeaks(snapshot.getPeaks());
            applyCurrentStarMatchOverlay();
            int count = snapshot.getPeaks().size();
            starResultSummary.setText(getResources().getQuantityString(
                    R.plurals.star_points_found, count, count));
        } else {
            starVisualization.setPeaks(null);
            starMatchLegend.setVisibility(View.GONE);
            starResultSummary.setText(R.string.star_no_results);
        }
        renderStarMetadata(
                snapshot.getCapturedMetadata(), snapshot.getCapturedSelection());
        renderExposureControls();
        renderStarControls();
    }

    private void clearStarSnapshotPresentation() {
        currentStarSnapshot = null;
        starRequestPending = false;
        starVisualization.setPeaks(null);
        starMatchLegend.setVisibility(View.GONE);
        starResultSummary.setText(R.string.star_no_results);
        starCaptureDetails.setText(R.string.star_metadata_unavailable);
    }

    private void clearSolveDetails() {
        // Clear cached snapshots as well as widgets so STATE updates cannot restore old details.
        clearStarSnapshotPresentation();
        currentPlateSolveSnapshot = null;
        renderPlateSolve(null);
    }

    @Override
    public void onSolveChanged(SolveSnapshot snapshot) {
        // Listener delivery is queued on the main thread. Ignore a callback that was
        // already queued when this Activity detached from the service.
        if (!activityStarted || !serviceBindingUsable) return;
        currentPlateSolveSnapshot = snapshot;
        renderPlateSolve(snapshot);
        if (currentSnapshot != null) {
            updateAstroControlEnabled(currentSnapshot.astro);
        }
        renderExposureControls();
        renderStarControls();
    }

    @Override
    public void onCameraFound(CameraConnectionService.CameraCandidate camera) {
        candidates.put(camera.address, camera);
        cameraAdapter.clear();
        cameraAdapter.addAll(candidates.values());
        cameraAdapter.notifyDataSetChanged();
        discoveredLabel.setVisibility(View.VISIBLE);
        cameraSpinner.setVisibility(View.VISIBLE);
    }

    static String formatShutterTimer(long elapsedMs) {
        long elapsed = Math.max(0L, elapsedMs);
        long minutes = elapsed / 60_000;
        long seconds = (elapsed / 1_000) % 60;
        long tenths = (elapsed / 100) % 10;
        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    static boolean reconciledShutterLockState(
            boolean pointerActive, boolean currentlyLocked, boolean snapshotHeld) {
        // While the user's finger owns the gesture, its state is authoritative. Once the
        // sequence ends, the latest service snapshot always wins, including release failures.
        return pointerActive ? currentlyLocked : snapshotHeld;
    }

    private void reconcileShutterPresentation(CameraConnectionService.Snapshot snapshot) {
        if (snapshot == null || shutterGesture.isPointerActive()) return;
        boolean shouldBeLocked = reconciledShutterLockState(
                false, shutterLocked, snapshot.shutterHeld);
        if (shouldBeLocked == shutterLocked) return;

        long now = SystemClock.elapsedRealtime();
        if (shouldBeLocked) {
            long heldAt = snapshot.shutterHeldSinceElapsedMs > 0L
                    ? snapshot.shutterHeldSinceElapsedMs : now;
            shutterGesture.restoreLocked(heldAt, now);
            setShutterLocked(true);
        } else {
            shutterGesture.confirmReleased(now);
            setShutterLocked(false);
        }
    }

    private void updateHeldPresentation() {
        long now = SystemClock.elapsedRealtime();
        if (shutterLocked) {
            long elapsedMs = shutterGesture.lockedElapsedMs(now);
            String timer = formatShutterTimer(elapsedMs);
            shutterButton.setText(R.string.shutter_unlock);
            shutterButton.setContentDescription(getString(R.string.shutter_unlock_accessibility));
            shutterLockTarget.setText(timer);
            shutterLockTarget.setContentDescription(
                    getString(R.string.shutter_locked_duration_accessibility, timer));
        } else {
            shutterButton.setText(R.string.shutter);
            shutterButton.setContentDescription(getString(R.string.shutter_accessibility));
            shutterLockTarget.setText(R.string.shutter_lock_target);
            shutterLockTarget.setContentDescription(
                    getString(R.string.shutter_lock_accessibility));
        }
        shutterLockTarget.setSelected(shutterLocked);
    }

    private void setShutterPressedVisual() {
        shutterButton.setPressed(true);
        updateHeldPresentation();
    }

    private void resetShutterTouchVisual() {
        boolean snapshotAdvancedDuringGesture = shutterGestureStartSnapshot == null
                || currentSnapshot != shutterGestureStartSnapshot;
        if (shutterGesture.isPointerActive()) {
            applyShutterGestureResult(shutterGesture.onPointerCancel(
                    SystemClock.elapsedRealtime()));
        }
        shutterGestureStartSnapshot = null;
        if (shutterActionBar.getParent() != null) {
            shutterActionBar.getParent().requestDisallowInterceptTouchEvent(false);
        }
        uiHandler.removeCallbacks(heldTimerUpdater);
        shutterButton.setPressed(false);
        shutterLockTarget.setPressed(false);
        if (activityStarted && currentSnapshot != null && snapshotAdvancedDuringGesture) {
            reconcileShutterPresentation(currentSnapshot);
        }
        uiHandler.removeCallbacks(heldTimerUpdater);
        updateHeldPresentation();
        if (activityStarted && shutterLocked) {
            uiHandler.postDelayed(heldTimerUpdater, SHUTTER_TIMER_UPDATE_MS);
        }
    }

    private void setShutterLocked(boolean locked) {
        long now = SystemClock.elapsedRealtime();
        if (locked && !shutterGesture.isLocked()) {
            shutterGesture.restoreLocked(now, now);
        } else if (!locked && shutterGesture.isLocked()) {
            shutterGesture.reset(now);
        }
        if (shutterLocked == locked) {
            updateHeldPresentation();
            return;
        }
        shutterLocked = locked;
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean(PREF_SHUTTER_LOCKED, locked);
        if (locked) {
            editor.putLong(PREF_SHUTTER_LOCKED_AT, shutterGesture.lockedAtElapsedMs());
        } else {
            editor.remove(PREF_SHUTTER_LOCKED_AT);
        }
        editor.apply();
        updateAstroNavigationEnabled();
        updateHeldPresentation();
        uiHandler.removeCallbacks(heldTimerUpdater);
        if (activityStarted && locked) uiHandler.post(heldTimerUpdater);
    }

    private boolean isReady() {
        return currentSnapshot != null && currentSnapshot.ready;
    }

    private void synchronizeExposureSelections(CameraConnectionService.Snapshot snapshot) {
        CameraExposureState cameraState = snapshot.cameraState;
        ExposureCatalogs catalogs = cameraState == null
                ? null
                : ApertureCatalogView.filter(
                        snapshot.exposureCatalogs,
                        cameraState.apertureMinLabel(),
                        cameraState.apertureMaxLabel(),
                        cameraState.getAperture());
        if (snapshot.connectionGeneration != renderedExposureConnectionGeneration) {
            cameraExposureDraft.invalidate();
            starExposureDraft.invalidate();
            resetExposureIntentTrackers(snapshot);
            for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
                exposurePicker(false, kind).clearOptions();
                exposurePicker(true, kind).clearOptions();
            }
            renderedExposureCatalogs = null;
            renderedExposureConnectionGeneration = snapshot.connectionGeneration;
        }
        observeExposureIntentResults(snapshot);
        if (catalogs == null) {
            cameraExposureDraft.suspend();
            starExposureDraft.suspend();
            if (renderedExposureCatalogs != null) {
                for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
                    exposurePicker(false, kind).clearOptions();
                    exposurePicker(true, kind).clearOptions();
                }
                renderedExposureCatalogs = null;
            }
            return;
        }

        boolean catalogsChanged = !haveSameExposureOptions(renderedExposureCatalogs, catalogs);
        if (catalogsChanged) {
            for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
                replaceExposurePickerOptions(false, kind, catalogs.get(kind));
                replaceExposurePickerOptions(true, kind, catalogs.get(kind));
            }
        }
        renderedExposureCatalogs = catalogs;

        boolean cameraDesiredRemoved = snapshot.desiredSelection != null
                && !catalogs.contains(snapshot.desiredSelection);
        boolean recipeDesiredRemoved = snapshot.desiredStarRecipeSelection != null
                && !catalogs.contains(snapshot.desiredStarRecipeSelection);
        ExposureCatalogs.Selection currentSelection = currentSelection(catalogs, snapshot.cameraState);
        ExposureCatalogs.Selection cameraDisplaySelection = cameraDesiredRemoved
                ? currentSelection : snapshot.desiredSelection;
        ExposureCatalogs.Selection recipeDisplaySelection = recipeDesiredRemoved
                ? currentSelection : snapshot.desiredStarRecipeSelection;

        synchronizeExposureDraft(
                cameraExposureDraft,
                catalogs,
                cameraDisplaySelection,
                cameraSettingsIntentTracker.isAwaitingService());
        synchronizeExposureDraft(
                starExposureDraft,
                catalogs,
                recipeDisplaySelection,
                starRecipeIntentTracker.isAwaitingService());

        boolean cameraReviewRequired = cameraExposureDraft.consumeSelectionReviewRequired();
        if ((catalogsChanged && cameraDesiredRemoved) || cameraReviewRequired) {
            Toast.makeText(
                    this,
                    R.string.camera_settings_choice_removed,
                    Toast.LENGTH_LONG).show();
        }
        boolean recipeReviewRequired = starExposureDraft.consumeSelectionReviewRequired();
        if ((catalogsChanged && recipeDesiredRemoved) || recipeReviewRequired) {
            Toast.makeText(
                    this,
                    R.string.star_recipe_choice_removed,
                    Toast.LENGTH_LONG).show();
        }

        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            ExposureCatalogs.Option cameraSelected = cameraExposureDraft.getSelected(kind);
            if (cameraSelected != null) {
                setExposureSelectionProgrammatically(false, kind, cameraSelected);
            }
            ExposureCatalogs.Option selected = starExposureDraft.getSelected(kind);
            if (selected != null) {
                setExposureSelectionProgrammatically(true, kind, selected);
            }
        }
    }

    private static boolean haveSameExposureOptions(
            ExposureCatalogs first, ExposureCatalogs second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            if (!first.get(kind).equals(second.get(kind))) return false;
        }
        return true;
    }

    private void replaceExposurePickerOptions(
            boolean starRecipe,
            ExposureCatalogs.Kind kind,
            List<ExposureCatalogs.Option> options) {
        exposurePicker(starRecipe, kind).setOptions(options);
    }

    private static void synchronizeExposureDraft(
            ExposureDraft draft,
            ExposureCatalogs catalogs,
            ExposureCatalogs.Selection serviceSelection,
            boolean awaitingService) {
        if (serviceSelection == null || !catalogs.contains(serviceSelection)) {
            draft.suspend();
            return;
        }
        draft.update(catalogs, serviceSelection);
        if (!draft.isSynchronized()) return;

        ExposureCatalogs.Selection displayed = draft.getSelection();
        if (!awaitingService && !displayed.equals(serviceSelection)) {
            draft.invalidate();
            draft.update(catalogs, serviceSelection);
        }
    }

    private void resetExposureIntentTrackers(CameraConnectionService.Snapshot snapshot) {
        cameraSettingsIntentTracker.reset(
                snapshot.lastCameraSettingsIntentId,
                snapshot.lastCameraSettingsIntentAccepted);
        starRecipeIntentTracker.reset(
                snapshot.lastStarRecipeIntentId,
                snapshot.lastStarRecipeIntentAccepted);
        // A setter records its issued ID synchronously, before the Bluetooth handler handles it.
        // Restore that narrow queued interval after rotation/rebind so actions never unlock on an
        // older Snapshot and the request is not submitted a second time.
        if (service != null) {
            long issuedCameraIntentId = service.getLastIssuedCameraSettingsIntentId();
            if (issuedCameraIntentId > snapshot.lastCameraSettingsIntentId) {
                cameraSettingsIntentTracker.begin();
                cameraSettingsIntentTracker.assign(issuedCameraIntentId);
            }
            long issuedRecipeIntentId = service.getLastIssuedStarRecipeIntentId();
            if (issuedRecipeIntentId > snapshot.lastStarRecipeIntentId) {
                starRecipeIntentTracker.begin();
                starRecipeIntentTracker.assign(issuedRecipeIntentId);
            }
        }
        cameraSettingsRejected = false;
        starRecipeRejected = false;
    }

    private void observeExposureIntentResults(CameraConnectionService.Snapshot snapshot) {
        ExposureIntentTracker.Resolution cameraResolution =
                cameraSettingsIntentTracker.observe(
                        snapshot.lastCameraSettingsIntentId,
                        snapshot.lastCameraSettingsIntentAccepted);
        if (cameraResolution == ExposureIntentTracker.Resolution.REJECTED) {
            cameraSettingsRejected = true;
        }
        ExposureIntentTracker.Resolution recipeResolution =
                starRecipeIntentTracker.observe(
                        snapshot.lastStarRecipeIntentId,
                        snapshot.lastStarRecipeIntentAccepted);
        if (recipeResolution == ExposureIntentTracker.Resolution.REJECTED) {
            starRecipeRejected = true;
        }
    }

    private static ExposureCatalogs.Selection currentSelection(
            ExposureCatalogs catalogs, CameraExposureState state) {
        if (catalogs == null || state == null) return null;
        ExposureCatalogs.Selection selection = ExposureCatalogs.Selection.from(state);
        return catalogs.contains(selection) ? selection : null;
    }

    private void setExposureSelectionProgrammatically(
            boolean starRecipe,
            ExposureCatalogs.Kind kind,
            ExposureCatalogs.Option option) {
        exposurePicker(starRecipe, kind).setSelectedOption(option);
    }

    private void renderStarMetadata(
            StarCaptureProtocol.CaptureMetadata metadata,
            ExposureCatalogs.Selection capturedSelection) {
        if (metadata == null) {
            starCaptureDetails.setText(R.string.star_metadata_unavailable);
            return;
        }
        StringBuilder details = new StringBuilder();
        if (capturedSelection != null) {
            details.append("Captured · ")
                    .append(CapturedExposureDisplay.format(capturedSelection));
        } else {
            details.append(getString(R.string.captured_exposure_labels_unavailable));
        }
        if (metadata.hasFocalLength()) {
            details.append(" · ");
            if (metadata.hasLensName()) details.append(metadata.getLensName()).append(" · ");
            details.append(FocalLengthDisplay.format(metadata.getFocalLengthMm()))
                    .append(" mm");
        } else if (metadata.hasLensName()) {
            details.append(" · ").append(metadata.getLensName());
        }
        starCaptureDetails.setText(details.toString());
    }

    private void renderPlateSolve(SolveSnapshot snapshot) {
        starVisualization.clearMatchSourceIndices();
        starMatchLegend.setVisibility(View.GONE);
        plateSolveResult.setVisibility(View.GONE);
        plateSolveCoordinates.setText("");
        plateSolveDuration.setText("");
        plateSolveDuration.setVisibility(View.GONE);
        plateSolveOrientation.setText("");
        plateSolveLensSource.setText("");
        if (snapshot == null || snapshot.getState() != SolveSnapshot.State.SOLVED
                || snapshot.getResult() == null) return;

        SolveResult result = snapshot.getResult();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        plateSolveCoordinates.setText(getString(
                R.string.last_solve_radec_format,
                PlateSolveFormatting.rightAscension(result.getRightAscensionDeg(), locale),
                PlateSolveFormatting.declination(result.getDeclinationDeg(), locale)));
        if (snapshot.hasSolveDuration()) {
            plateSolveDuration.setText(getString(R.string.plate_solve_duration,
                    formatSolveDuration(snapshot.getSolveDurationNanos(), locale)));
            plateSolveDuration.setVisibility(View.VISIBLE);
        }
        plateSolveOrientation.setText(getString(
                R.string.plate_solve_orientation,
                PlateSolveFormatting.degrees(result.getRollDeg(), locale),
                PlateSolveFormatting.degrees(result.getFovDeg(), locale)));
        plateSolveLensSource.setText(snapshot.isDistortionFallback()
                ? R.string.plate_solve_rectilinear_fallback
                : R.string.plate_solve_exact_profile);
        starVisualization.setMatchSourceIndices(
                result.getPatternSourceIndices(), result.getMatchedSourceIndices());
        starMatchLegend.setVisibility(
                starVisualization.hasMatchOverlay() ? View.VISIBLE : View.GONE);
        plateSolveResult.setVisibility(View.VISIBLE);
    }

    /** Latest attempt owns status; a retained successful result never masks a failure. */
    static int solveStatusLabel(
            SolveSnapshot snapshot, long latestIntentId, boolean latestIntentAccepted) {
        if (latestIntentId >= 0L && !latestIntentAccepted) {
            return R.string.plate_solve_rejected;
        }
        if (snapshot == null) return 0;
        switch (snapshot.getState()) {
            case QUEUED: return R.string.plate_solve_queued;
            case SOLVING: return R.string.plate_solve_solving;
            case SOLVED: return R.string.plate_solve_solved;
            case NO_MATCH: return R.string.plate_solve_no_match;
            case MISSING_METADATA:
                return snapshot.isLensMetadataMissing()
                        ? R.string.plate_solve_missing_focal_and_lens
                        : R.string.plate_solve_missing_focal;
            case ERROR: return R.string.plate_solve_error;
            case CLOSED: return R.string.plate_solve_unavailable;
            default: return 0;
        }
    }

    private void renderCorrectionDiagnostics(CameraConnectionService.AstroSnapshot astro) {
        SolveResult result = astro == null ? null : astro.lastSolvedResult;
        boolean currentCameraReady = serviceBindingUsable
                && currentSnapshot != null
                && currentSnapshot.ready;
        if (!currentCameraReady || result == null) {
            shutterCorrectionStarsUsed.setText(R.string.shutter_correction_stars_unavailable);
            shutterCorrectionRmsError.setText(R.string.shutter_correction_rms_unavailable);
            shutterCorrectionMaxExposure.setText("Centered ≈—");
            shutterCorrectionRemainingExposure.setText("Remaining ≈—");
            shutterCorrectionDiagnostics.setContentDescription(
                    getString(R.string.shutter_correction_diagnostics_unavailable));
            shutterCorrectionDiagnostics.setVisibility(View.GONE);
            return;
        }

        Locale locale = getResources().getConfiguration().getLocales().get(0);
        int verificationMatches = Math.max(0, result.getMatchedStars());
        String fit = formatFitSupport(verificationMatches);
        String verificationRms = formatVerificationRms(result.getResidualArcsec(), locale);
        double centeredSeconds = astro.trackingExposureEstimate.getCenteredSeconds();
        double remainingSeconds = astro.trackingExposureEstimate.getRemainingSeconds();
        String centered = formatApproximateExposure("Centered", centeredSeconds, locale);
        String remaining = formatApproximateExposure("Remaining", remainingSeconds, locale);

        shutterCorrectionStarsUsed.setText(fit);
        shutterCorrectionRmsError.setText(verificationRms);
        shutterCorrectionMaxExposure.setText(centered);
        shutterCorrectionRemainingExposure.setText(remaining);
        shutterCorrectionDiagnostics.setContentDescription(
                correctionDiagnosticsDescription(
                        verificationMatches,
                        result.getResidualArcsec(),
                        centeredSeconds,
                        remainingSeconds,
                        locale));
        shutterCorrectionDiagnostics.setVisibility(View.VISIBLE);
    }

    private void renderLastSolveCoordinates(CameraConnectionService.AstroSnapshot astro) {
        uiHandler.removeCallbacks(lastSolveCoordinateUpdater);
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        renderLocationStatus(astro, nowNanos);
        if (activityStarted) {
            uiHandler.postDelayed(lastSolveCoordinateUpdater, LAST_SOLVE_COORDINATE_REFRESH_MS);
        }
        SolveResult result = astro == null ? null : astro.lastSolvedResult;
        boolean ready = activityStarted
                && serviceBindingUsable
                && currentSnapshot != null
                && currentSnapshot.ready;
        CameraAttitudeEstimator.Estimate attitude = astro == null
                ? null : astro.cameraAttitudeEstimate;
        long gyroAge = attitude == null ? -1L : nowNanos
                - attitude.getReceivedElapsedRealtimeNanos();
        boolean freshGyro = attitude != null
                && (attitude.getState() == CameraAttitudeEstimator.State.AVAILABLE
                    || attitude.getState() == CameraAttitudeEstimator.State.UNANCHORED)
                && gyroAge >= 0L && gyroAge <= CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS;
        zeroCameraAltAzButton.setEnabled(ready && freshGyro);
        int connectionGeneration = currentSnapshot == null
                ? -1 : currentSnapshot.connectionGeneration;
        long solvedIntentId = astro == null ? -1L : astro.lastSolvedIntentId;
        lastSolveCoordinateAssociation.observe(
                ready ? result : null, solvedIntentId, connectionGeneration);
        result = lastSolveCoordinateAssociation.getDisplayedResult();
        if (!ready || (result == null && (attitude == null || !attitude.isAvailable()))) {
            plateSolveQuality.setText("");
            plateSolveQuality.setVisibility(View.GONE);
            plateSolveFocalComparison.setText("");
            plateSolveFocalComparison.setVisibility(View.GONE);
            setLastSolveCoordinateVisibility(View.GONE);
            return;
        }

        boolean matchingAcquisition = lastSolveCoordinateAssociation.isActiveFor(
                solvedIntentId, connectionGeneration);
        if (result != null && lastSolveCoordinateAssociation.getDisplayedContext() == null
                && astro.cameraObserverContext == null
                && !coordinateLocationRequestInFlight
                && !coordinateLocationPermissionPending
                && !astro.solveBusy) {
            if (!matchingAcquisition && hasForegroundLocationPermission()) {
                long token = beginLastSolveCoordinateAcquisition();
                lastSolveCoordinateAssociation.assignIntent(token, solvedIntentId);
                requestLastSolveLocation(token, false);
            } else if (matchingAcquisition
                    && !lastSolveCoordinateAssociation.isActiveLocationComplete()) {
                requestLastSolveLocation(
                        lastSolveCoordinateAssociation.getActiveToken(), false);
            }
        }

        Locale locale = getResources().getConfiguration().getLocales().get(0);
        EquatorialCoordinates equatorial = liveEquatorial(
                attitude, astro.cameraObserverContext, nowNanos);
        String rightAscension = equatorial == null ? "—" : PlateSolveFormatting.rightAscension(
                equatorial.getRightAscensionDeg(), locale);
        String declination = equatorial == null ? "—" : PlateSolveFormatting.declination(
                equatorial.getDeclinationDeg(), locale);
        String raDec = getString(
                R.string.camera_radec_format, rightAscension, declination);

        String altitude = "—";
        String azimuth = "—";
        String roll = "—";
        ObserverCoordinateContext observer =
                lastSolveCoordinateAssociation.getDisplayedContext();
        if (result != null && observer != null && service != null) {
            service.setCameraObserverContext(observer, solvedIntentId, connectionGeneration);
        }
        if (freshGyro && attitude.isAvailable()) {
            altitude = PlateSolveFormatting.attitudeDegrees(attitude.getAltitudeDeg(), locale);
            azimuth = PlateSolveFormatting.attitudeDegrees(attitude.getAzimuthDeg(), locale);
            roll = PlateSolveFormatting.attitudeDegrees(attitude.getRollDeg(), locale);
        }
        String altAz = getString(R.string.last_solve_altaz_format, altitude, azimuth, roll);
        String latency = getString(R.string.last_solve_age_format,
                PlateSolveFormatting.relativeAge(astro.lastSolvedElapsedRealtimeNanos,
                        nowNanos, locale));
        String description = getString(
                R.string.camera_pointing_accessibility,
                rightAscension,
                declination,
                "—".equals(altitude) ? "unavailable" : altitude,
                "—".equals(azimuth) ? "unavailable" : azimuth,
                "—".equals(roll) ? "unavailable" : roll,
                result == null ? "" : latency);

        String quality = result == null ? "" : getResources().getQuantityString(
                R.plurals.plate_solve_match_quality,
                result.getMatchedStars(),
                result.getMatchedStars(),
                PlateSolveFormatting.arcseconds(result.getResidualArcsec(), locale));
        plateSolveQuality.setText(quality);
        plateSolveQuality.setVisibility(result == null ? View.GONE : View.VISIBLE);
        String focalComparison = PlateSolveFormatting.focalComparison(result,
                getString(R.string.plate_solve_focal_comparison), locale);
        plateSolveFocalComparison.setText(focalComparison);
        plateSolveFocalComparison.setVisibility(
                focalComparison.isEmpty() ? View.GONE : View.VISIBLE);
        int solvedVisibility = result == null ? View.GONE : View.VISIBLE;
        shutterLastSolveRaDec.setVisibility(solvedVisibility);
        starTrackerLastSolveRaDec.setVisibility(solvedVisibility);
        int latencyVisibility = result != null && astro.lastSolvedElapsedRealtimeNanos >= 0L
                ? View.VISIBLE : View.GONE;
        shutterLastSolveLatency.setVisibility(latencyVisibility);
        starTrackerLastSolveLatency.setVisibility(latencyVisibility);

        setLastSolveCoordinateText(
                shutterLastSolveCoordinates,
                shutterLastSolveRaDec,
                shutterLastSolveAltAz,
                shutterLastSolveLatency,
                raDec,
                altAz,
                latency,
                description);
        setLastSolveCoordinateText(
                starTrackerLastSolveCoordinates,
                starTrackerLastSolveRaDec,
                starTrackerLastSolveAltAz,
                starTrackerLastSolveLatency,
                raDec,
                altAz,
                latency,
                description + (quality.isEmpty() ? "" : " " + quality)
                        + (focalComparison.isEmpty() ? "" : " " + focalComparison));
    }

    /** Live celestial coordinates require a real solve anchor, a current sample, and its UTC fix. */
    static EquatorialCoordinates liveEquatorial(
            CameraAttitudeEstimator.Estimate attitude, ObserverCoordinateContext observer,
            long nowNanos) {
        if (attitude == null || !attitude.isAvailable() || attitude.getCaptureSequence() < 0L
                || observer == null || nowNanos < attitude.getReceivedElapsedRealtimeNanos()
                || nowNanos - attitude.getReceivedElapsedRealtimeNanos()
                    > CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS) return null;
        try {
            return observer.equatorialAt(
                    attitude.getAltitudeDeg(), attitude.getAzimuthDeg(), nowNanos);
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
    }

    private void renderLocationStatus(CameraConnectionService.AstroSnapshot astro, long nowNanos) {
        if (shutterLocationStatus == null) return;
        ObserverCoordinateContext context = astro == null ? null : astro.cameraObserverContext;
        if (context != null && (latestLocationContext == null
                || context.getLocationFixElapsedRealtimeNanos()
                    > latestLocationContext.getLocationFixElapsedRealtimeNanos())) {
            latestLocationContext = context;
        }
        String status = latestLocationContext == null ? getString(R.string.location_unavailable)
                : getString(R.string.location_age_format, PlateSolveFormatting.relativeAge(
                        latestLocationContext.getLocationFixElapsedRealtimeNanos(), nowNanos,
                        getResources().getConfiguration().getLocales().get(0)));
        int notice = coordinateLocationPermissionPending ? R.string.location_permission_needed
                : coordinateLocationRequestInFlight || pendingManualLocationRequest != null
                    ? R.string.location_refreshing : coordinateLocationFailure;
        if (notice != 0) {
            status = latestLocationContext == null ? getString(notice)
                    : status + " · " + getString(notice);
        }
        shutterLocationStatus.setText(status);
        starTrackerLocationStatus.setText(status);
        boolean refreshEnabled = canRefreshCoordinateLocation();
        shutterLocationRefreshButton.setEnabled(refreshEnabled);
        starTrackerLocationRefreshButton.setEnabled(refreshEnabled);
    }

    private static void setLastSolveCoordinateText(
            View container,
            TextView raDecView,
            TextView altAzView,
            TextView latencyView,
            String raDec,
            String altAz,
            String latency,
            String description) {
        raDecView.setText(raDec);
        altAzView.setText(altAz);
        latencyView.setText(latency);
        container.setContentDescription(description);
        container.setVisibility(View.VISIBLE);
    }

    private void setLastSolveCoordinateVisibility(int visibility) {
        shutterLastSolveCoordinates.setVisibility(visibility);
        starTrackerLastSolveCoordinates.setVisibility(visibility);
    }

    static String formatSolveDuration(long durationNanos, Locale locale) {
        if (durationNanos < 0L) return "—";
        double milliseconds = durationNanos / 1_000_000.0;
        if (milliseconds < 1_000.0) {
            return String.format(locale, "%.0f ms", milliseconds);
        }
        double seconds = milliseconds / 1_000.0;
        if (seconds < 10.0) return String.format(locale, "%.2f s", seconds);
        if (seconds < 100.0) return String.format(locale, "%.1f s", seconds);
        return String.format(locale, "%.0f s", seconds);
    }

    static String formatFitSupport(int verificationMatches) {
        return "Fit 4+" + Math.max(0, verificationMatches);
    }

    static String formatVerificationRms(double residualArcsec, Locale locale) {
        return "Verify RMS " + PlateSolveFormatting.arcseconds(residualArcsec, locale);
    }

    static String formatApproximateExposure(String label, double seconds, Locale locale) {
        if (!Double.isFinite(seconds) || seconds < 0.0) return label + " ≈—";
        return label + " ≈" + formatExposureDuration(seconds, locale);
    }

    static String correctionDiagnosticsDescription(
            int verificationMatches,
            double residualArcsec,
            double centeredSeconds,
            double remainingSeconds,
            Locale locale) {
        String rms = PlateSolveFormatting.arcseconds(residualArcsec, locale);
        String centered = Double.isFinite(centeredSeconds) && centeredSeconds >= 0.0
                ? "Centered-stage exposure estimate about "
                        + formatExposureDuration(centeredSeconds, locale)
                : "Centered-stage exposure estimate unavailable";
        String remaining = Double.isFinite(remainingSeconds) && remainingSeconds >= 0.0
                ? "Remaining exposure estimate about "
                        + formatExposureDuration(remainingSeconds, locale)
                : "Remaining exposure estimate unavailable";
        return "Fit used 4 seed stars plus " + Math.max(0, verificationMatches)
                + " verification stars. Verification RMS " + rms + ". "
                + centered + ". " + remaining + ".";
    }

    private static String formatExposureDuration(double seconds, Locale locale) {
        return seconds < 10.0
                ? String.format(locale, "%.1f s", seconds)
                : String.format(locale, "%.0f s", seconds);
    }

    private void renderAstro(CameraConnectionService.AstroSnapshot astro) {
        renderLastSolveCoordinates(astro);
        renderCorrectionDiagnostics(astro);
        if (astro == null) return;
        suppressAstroInputs = true;
        correctionEnabledToggle.setChecked(astro.correctionEnabled);
        prepositionEnabledToggle.setChecked(astro.prepositionEnabled);
        manualOverrideToggle.setChecked(astro.manualOverride);
        if (!manualDraftEditing
                && !manualRa.hasFocus() && !manualDec.hasFocus()) {
            setTextIfDifferent(manualRa, astro.manualRa);
            setTextIfDifferent(manualDec, astro.manualDec);
        }
        if (!correctionStrength.isPressed()) {
            correctionStrength.setProgress(astro.factorPercent);
        }
        suppressAstroInputs = false;
        renderStrengthValue(astro.factorPercent);

        String acknowledgedRates = formatAcknowledgedRates(astro);
        trackingModelStatus.setText(astro.trackingModelStatus);
        trackingModelStatus.setVisibility(astro.trackingModelStatus.isEmpty()
                ? View.GONE : View.VISIBLE);
        String correctionStatus = compactCorrectionStatus(astro.correctionStatus);
        String manualWarning = manualGravityWarning(astro, correctionStatus);
        boolean acknowledgedCurrentGeneration = hasCurrentAcknowledgedRate(
                astro.acknowledgedRate,
                astro.correctionGeneration,
                astro.acknowledgedCorrectionGeneration,
                astro.acknowledgedRateHealthy);
        correctionAcknowledgedRates.setText(acknowledgedCurrentGeneration
                ? acknowledgedRates
                : (correctionStatus.isEmpty() ? acknowledgedRates : correctionStatus));
        if (!foregroundManualStatus.isEmpty()) {
            shutterCorrectionStatus.setText(foregroundManualStatus);
            manualAttitudeStatus.setText(foregroundManualStatus);
        } else {
            shutterCorrectionStatus.setText(!manualWarning.isEmpty()
                    ? manualWarning
                    : (acknowledgedCurrentGeneration
                            ? acknowledgedRates
                            : (correctionStatus.isEmpty()
                                    ? getString(R.string.correction_idle)
                                    : correctionStatus)));
            manualAttitudeStatus.setText(manualWarning);
        }

        StarTelemetry.IbisSample ibis = astro.ibisSample;
        boolean qualifiedIbisSample = astro.ibisTelemetryAvailable
                && ibis != null
                && ibis.isSourceMapped()
                && ibis.isMirrorEnabled()
                && ibis.isDoubleReadStable();
        String compactIbis = compactIbisStatus(ibis);
        shutterIbisStatus.setText(compactIbis);
        shutterIbisVisualization.setTelemetry(
                qualifiedIbisSample ? ibis : null,
                astro.authoritativeFocalLengthMm);

        if (!detectorFieldsFocused() && !astro.detectorWritePending) {
            renderDetectorInputs(astro.detectorConfig);
        }
        detectorStatus.setText(compactSettingsStatus(astro.detectorStatus));
        if (!plateSolverSettingsDirty && !plateSolverSettingsFieldsFocused()) {
            renderPlateSolverSettings(astro.plateSolverSettings);
            solverSettingsStatus.setText(
                    compactSettingsStatus(astro.plateSolverSettingsStatus));
        }
        updateAstroControlEnabled(astro);
    }

    private String manualGravityWarning(
            CameraConnectionService.AstroSnapshot astro,
            String compactCorrectionStatus) {
        if (!usesManualAttitude() || !astro.correctionEnabled || astro.factorPercent <= 0) {
            return "";
        }
        String status = singleLine(compactCorrectionStatus);
        String lower = status.toLowerCase(Locale.US);
        if (lower.contains("too close to vertical")
                || lower.contains("hold camera still")
                || lower.contains("camera tilt unavailable")
                || lower.contains("location unavailable")
                || lower.contains("time unavailable")
                || lower.contains("ra/dec")
                || lower.contains("doesn't match camera tilt")) {
            return status;
        }
        CameraGravityWindow.Estimate gravity = astro.cameraGravityEstimate;
        if (!isCameraGravityQualified(astro)) {
            if (isCameraGravityCurrent(astro)
                    && gravity.getState() == CameraGravityWindow.State.UNSTABLE) {
                return getString(R.string.hold_camera_still);
            }
            return getString(R.string.camera_tilt_unavailable);
        }
        return "";
    }

    private static boolean isCameraGravityQualified(
            CameraConnectionService.AstroSnapshot astro) {
        CameraGravityWindow.Estimate gravity = astro.cameraGravityEstimate;
        return isCameraGravityCurrent(astro)
                && gravity.getState() == CameraGravityWindow.State.QUALIFIED;
    }

    private static boolean isCameraGravityCurrent(
            CameraConnectionService.AstroSnapshot astro) {
        CameraGravityWindow.Estimate gravity = astro.cameraGravityEstimate;
        long now = SystemClock.elapsedRealtimeNanos();
        return astro.cameraAccelerationAvailable
                && gravity != null
                && gravity.getState() != CameraGravityWindow.State.UNAVAILABLE
                && gravity.getNewestElapsedRealtimeNanos() >= 0L
                && gravity.getNewestElapsedRealtimeNanos() <= now
                && now - gravity.getNewestElapsedRealtimeNanos()
                        <= CameraGravityWindow.MAX_SAMPLE_AGE_NANOS;
    }

    private static void setTextIfDifferent(EditText field, String value) {
        String next = value == null ? "" : value;
        if (!field.getText().toString().equals(next)) field.setText(next);
    }

    private void renderStrengthValue(int percent) {
        correctionStrengthValue.setText(String.format(
                Locale.US, "Strength · %.2f×", percent / 100.0));
    }

    private String formatAcknowledgedRates(CameraConnectionService.AstroSnapshot astro) {
        dev.omshutter.solver.SiderealRateCorrection rate = astro.acknowledgedRate;
        if (!hasCurrentAcknowledgedRate(
                rate,
                astro.correctionGeneration,
                astro.acknowledgedCorrectionGeneration,
                astro.acknowledgedRateHealthy)) {
            return getString(R.string.correction_rates_unavailable);
        }
        return getString(
                R.string.correction_acknowledged_rates,
                formatMicroRadians(rate.getYawRadiansPerSecond()),
                formatMicroRadians(rate.getPitchRadiansPerSecond()),
                formatMicroRadians(rate.getRollRadiansPerSecond()));
    }

    static boolean hasCurrentAcknowledgedRate(
            dev.omshutter.solver.SiderealRateCorrection rate,
            long correctionGeneration,
            long acknowledgedCorrectionGeneration) {
        return hasCurrentAcknowledgedRate(
                rate, correctionGeneration, acknowledgedCorrectionGeneration, true);
    }

    static boolean hasCurrentAcknowledgedRate(
            dev.omshutter.solver.SiderealRateCorrection rate,
            long correctionGeneration,
            long acknowledgedCorrectionGeneration,
            boolean acknowledgedRateHealthy) {
        return acknowledgedRateHealthy
                && rate != null
                && acknowledgedCorrectionGeneration == correctionGeneration;
    }

    static String formatMicroRadians(double radiansPerSecond) {
        return String.format(Locale.US, "%.4f", radiansPerSecond * 1.0e6)
                .replace('-', '−');
    }

    static String compactCorrectionStatus(String status) {
        String value = singleLine(status);
        if (value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("camera is busy") || lower.contains("not idle")) {
            return "Camera busy";
        }
        if (lower.contains("capturing")) return "Capturing…";
        if (lower.contains("publishing calculated")
                || lower.contains("best-effort")
                || lower.contains("before applying correction")
                || lower.contains("terminal correction stop")) {
            return "Applying…";
        }
        if (lower.contains("published to linux")) return "Correction sent";
        if (lower.contains("correction disabled")) return "Correction off";
        if (lower.contains("correction enabled")) return "Ready";
        if (lower.contains("correction stopped")) return "Stopped";
        if (lower.contains("stopping correction")) return "Stopping…";
        if (lower.contains("enable correction")) return "Enable correction";
        if (lower.contains("strength is 0") || lower.startsWith("strength set")) {
            return "Set strength, then apply";
        }
        if (lower.contains("service is unavailable")) return "Unavailable";
        if (lower.contains("too close to vertical")) return "Too close to vertical";
        if (lower.contains("hold camera still")) return "Hold camera still";
        if (lower.contains("camera tilt unavailable")) return "Camera tilt unavailable";
        if (lower.contains("location unavailable")) return "Location unavailable";
        if (lower.contains("time unavailable")) return "Time unavailable";
        if (lower.contains("doesn't match camera tilt")) {
            return "RA/Dec doesn't match camera tilt";
        }
        if (lower.contains("ra/dec")) return "RA/Dec required";
        if (lower.startsWith("correction not applied")) return "Invalid attitude";
        if (lower.contains("rejected") && lower.contains("correction")) {
            return "Correction rejected";
        }
        if (lower.contains("cancelled")) return "Cancelled";
        if (lower.contains("timed out")) return "Timed out";
        if (lower.startsWith("plate solved")) return "Solved";
        if (lower.contains("plate solver did not accept")
                || lower.contains("plate-solver input was rejected")) {
            return "Solve rejected";
        }
        return value;
    }

    private static String compactSettingsStatus(String status) {
        String value = singleLine(status);
        if (value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("saving")) return "Saving…";
        if (lower.contains("defaults restored")) return "Defaults restored";
        if (lower.contains("loaded")) return "Loaded";
        if (lower.contains("saved")) return "Saved";
        if (lower.contains("unavailable")) return "Unavailable";
        if (lower.contains("idle")) return "Camera busy";
        if (lower.contains("timed out")) return "Timed out";
        if (lower.contains("permission")) return "Bluetooth permission lost";
        if (lower.contains("rejected")) return "Rejected";
        if (lower.contains("invalid") || lower.contains("could not be read")
                || lower.contains("read failed")) {
            return "Read failed";
        }
        return value;
    }

    static String compactIbisStatus(StarTelemetry.IbisSample sample) {
        if (sample == null || !sample.isSourceMapped()) return "Unavailable";
        if (!sample.isMirrorEnabled()) return "Disabled";
        if (!sample.isDoubleReadStable()) return "Unstable";
        return sample.hasObservedChangeSinceEnable() ? "Observed" : "Stable";
    }

    private static String singleLine(String text) {
        return text == null
                ? ""
                : text.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private void renderDetectorInputs(StarTelemetry.DetectorConfig config) {
        if (config == null) return;
        suppressAstroInputs = true;
        detectorSigma.setText(formatQ8(config.getSigmaQ8()));
        detectorMinArea.setText(Integer.toString(config.getMinArea()));
        detectorMaxArea.setText(Integer.toString(config.getMaxArea()));
        detectorElongation.setText(formatQ8(config.getMaxElongationQ8()));
        detectorSharpness.setText(formatQ8(config.getMaxSharpnessQ8()));
        detectorBorder.setText(Integer.toString(config.getBorderMargin()));
        suppressAstroInputs = false;
    }

    private static String formatQ8(int value) {
        return String.format(Locale.US, "%.3f", value / 256.0);
    }

    private boolean detectorFieldsFocused() {
        return detectorSigma.hasFocus() || detectorMinArea.hasFocus()
                || detectorMaxArea.hasFocus() || detectorElongation.hasFocus()
                || detectorSharpness.hasFocus() || detectorBorder.hasFocus();
    }

    private void renderPlateSolverSettings(PlateSolverSettings settings) {
        if (settings == null) return;
        suppressAstroInputs = true;
        solverMaxObservations.setText(Integer.toString(settings.getMaxObservations()));
        solverSeedTolerance.setText(Integer.toString(settings.getSeedToleranceArcsec()));
        solverMatchTolerance.setText(Integer.toString(settings.getMatchToleranceArcsec()));
        solverIndependentMatches.setText(
                Integer.toString(settings.getRequiredIndependentMatches()));
        solverTimeout.setText(Integer.toString(settings.getTimeoutMillis()));
        suppressAstroInputs = false;
    }

    private boolean plateSolverSettingsFieldsFocused() {
        return solverMaxObservations.hasFocus()
                || solverSeedTolerance.hasFocus()
                || solverMatchTolerance.hasFocus()
                || solverIndependentMatches.hasFocus()
                || solverTimeout.hasFocus();
    }

    private void updateManualFieldEnabled() {
        boolean showManualDetails = shouldShowManualAttitudeDetails(
                manualOverrideToggle.isChecked(), hasSolvedAttitude());
        boolean enabled = showManualDetails
                && isServiceUsable()
                && currentSnapshot != null
                && currentSnapshot.ready
                && !currentSnapshot.busy
                && !starRequestPending
                && pendingSolveIntentId < 0L
                && pendingManualLocationRequest == null;
        manualRa.setEnabled(enabled);
        manualDec.setEnabled(enabled);
        if (!showManualDetails) {
            manualRa.clearFocus();
            manualDec.clearFocus();
            manualDraftEditing = false;
        }
        manualAttitudeFields.setVisibility(showManualDetails ? View.VISIBLE : View.GONE);
    }

    private boolean hasSolvedAttitude() {
        return currentSnapshot != null && currentSnapshot.astro.solvedAttitudeAvailable;
    }

    private boolean usesManualAttitude() {
        return manualOverrideToggle != null && shouldUseManualAttitude(
                manualOverrideToggle.isChecked(), hasSolvedAttitude());
    }

    private boolean requiresManualContextForSolve() {
        return shouldRequestManualSolveContext(
                manualOverrideToggle.isChecked(),
                correctionEnabledToggle.isChecked(),
                correctionStrength.getProgress());
    }

    static boolean shouldUseManualAttitude(boolean manualOverride, boolean hasSolvedAttitude) {
        return manualOverride || !hasSolvedAttitude;
    }

    static boolean shouldShowManualAttitudeDetails(
            boolean manualOverride, boolean hasSolvedAttitude) {
        // Source fallback and editing disclosure are deliberately separate. With no solved
        // attitude the service may still require saved manual context, but the editor is an
        // explicit-override detail and must not expand by itself.
        return manualOverride;
    }

    static boolean shouldRequestManualSolveContext(
            boolean manualOverride,
            boolean correctionEnabled,
            int correctionStrengthPercent) {
        return manualOverride && correctionEnabled && correctionStrengthPercent > 0;
    }

    private void updateAstroControlEnabled(CameraConnectionService.AstroSnapshot astro) {
        boolean ready = isServiceUsable() && currentSnapshot != null
                && currentSnapshot.ready;
        boolean localSolveAdmissionPending = starRequestPending
                || pendingSolveIntentId >= 0L
                || pendingManualLocationRequest != null;
        boolean idle = ready && !currentSnapshot.busy && !localSolveAdmissionPending;
        boolean canSolve = canRequestStarDetection();
        shutterSolveButton.setEnabled(canSolve);
        shutterSolveCaptureButton.setEnabled(canSolve);
        correctionEnabledToggle.setEnabled(ready);
        manualOverrideToggle.setEnabled(idle);
        correctionStrength.setEnabled(idle);
        prepositionEnabledToggle.setEnabled(idle && astro.prepositionAvailable);
        updateManualFieldEnabled();
        boolean applySourceReady = !usesManualAttitude() || isCameraGravityQualified(astro);
        applyCorrectionButton.setEnabled(idle && astro.correctionEnabled
                && astro.factorPercent > 0
                && applySourceReady
                && currentSnapshot.astroRateAvailable);
        stopCorrectionButton.setEnabled(ready && currentSnapshot.astroRateAvailable);
        boolean detectorEditable = idle && astro.starStateAvailable
                && astro.detectorConfigKnown && !astro.detectorWritePending;
        detectorSigma.setEnabled(detectorEditable);
        detectorMinArea.setEnabled(detectorEditable);
        detectorMaxArea.setEnabled(detectorEditable);
        detectorElongation.setEnabled(detectorEditable);
        detectorSharpness.setEnabled(detectorEditable);
        detectorBorder.setEnabled(detectorEditable);
        detectorApplyButton.setEnabled(detectorEditable);
        detectorRestoreButton.setEnabled(idle && astro.starStateAvailable
                && !astro.detectorWritePending);
        boolean solverSettingsEditable = isServiceUsable()
                && currentSnapshot != null && !currentSnapshot.busy
                && !localSolveAdmissionPending;
        solverMaxObservations.setEnabled(solverSettingsEditable);
        solverSeedTolerance.setEnabled(solverSettingsEditable);
        solverMatchTolerance.setEnabled(solverSettingsEditable);
        solverIndependentMatches.setEnabled(solverSettingsEditable);
        solverTimeout.setEnabled(solverSettingsEditable);
        solverSettingsApplyButton.setEnabled(solverSettingsEditable);
        solverSettingsRestoreButton.setEnabled(solverSettingsEditable);
    }

    private void applyCurrentStarMatchOverlay() {
        starVisualization.clearMatchSourceIndices();
        SolveSnapshot snapshot = currentPlateSolveSnapshot;
        if (snapshot != null && snapshot.getState() == SolveSnapshot.State.SOLVED
                && snapshot.getResult() != null) {
            starVisualization.setMatchSourceIndices(
                    snapshot.getResult().getPatternSourceIndices(),
                    snapshot.getResult().getMatchedSourceIndices());
        }
        starMatchLegend.setVisibility(
                starVisualization.hasMatchOverlay() ? View.VISIBLE : View.GONE);
    }

    private boolean isStarSessionActive() {
        return currentStarSnapshot != null && !currentStarSnapshot.isTerminal();
    }

    private boolean isPlateSolveActive() {
        if (currentPlateSolveSnapshot == null) return false;
        switch (currentPlateSolveSnapshot.getState()) {
            case QUEUED:
            case SOLVING:
                return true;
            default:
                return false;
        }
    }

    private boolean canRequestStarDetection() {
        return isServiceUsable()
                && currentSnapshot != null
                && currentSnapshot.ready
                && currentSnapshot.starAvailable
                && !currentSnapshot.busy
                && !currentSnapshot.halfHeld
                && !currentSnapshot.shutterHeld
                && isStarRecipeReadyForCapture()
                && !isStarSessionActive()
                && !isPlateSolveActive()
                && !starRequestPending
                && pendingSolveIntentId < 0L
                && pendingManualLocationRequest == null;
    }

    private boolean areShutterSettingsReady() {
        return currentSnapshot != null && ExposureActionGate.isShutterReady(
                cameraExposureDraft,
                currentSnapshot.cameraState,
                currentSnapshot.desiredSelection,
                cameraSettingsIntentTracker.isAwaitingService(),
                starRecipeIntentTracker.isAwaitingService(),
                currentSnapshot.settingsWritePending,
                currentSnapshot.starRecipeWritePending,
                currentSnapshot.settingsReadyForShutter);
    }

    private boolean isStarRecipeReadyForCapture() {
        return currentSnapshot != null && ExposureActionGate.isDetectReady(
                cameraExposureDraft,
                starExposureDraft,
                currentSnapshot.cameraState,
                currentSnapshot.desiredSelection,
                currentSnapshot.starRecipeSelection,
                currentSnapshot.desiredStarRecipeSelection,
                cameraSettingsIntentTracker.isAwaitingService(),
                starRecipeIntentTracker.isAwaitingService(),
                currentSnapshot.settingsWritePending,
                currentSnapshot.starRecipeWritePending,
                currentSnapshot.settingsReadyForShutter,
                currentSnapshot.settingsReadyForCapture);
    }

    private boolean exposureWritePending() {
        return cameraSettingsIntentTracker.isAwaitingService()
                || starRecipeIntentTracker.isAwaitingService()
                || (currentSnapshot != null
                        && (currentSnapshot.settingsWritePending
                                || currentSnapshot.starRecipeWritePending));
    }

    private void updateShutterActionEnabled() {
        if (shutterButton == null) return;
        boolean mayRelease = currentSnapshot != null && currentSnapshot.shutterHeld;
        boolean mayPress = isServiceUsable()
                && currentSnapshot != null
                && currentSnapshot.ready
                && !currentSnapshot.busy
                && !starRequestPending
                && pendingSolveIntentId < 0L
                && pendingManualLocationRequest == null
                // Connection admission already required STAR. The independent stock shutter
                // remains usable while its authoritative STATE snapshot is still initializing.
                && ExposureActionGate.isStockShutterReady(
                        currentSnapshot.settingsAvailable,
                        areShutterSettingsReady(),
                        currentSnapshot.stockMutationAllowed);
        shutterButton.setEnabled(mayRelease || mayPress);
        shutterLockTarget.setEnabled(shutterLocked ? mayRelease && isServiceUsable() : mayPress);
        updateShutterSegmentAlpha();
    }

    private void updateShutterSegmentAlpha() {
        if (halfPressButton == null || shutterButton == null || shutterLockTarget == null) return;
        halfPressButton.setAlpha(halfPressButton.isEnabled() ? 1f : 0.45f);
        shutterButton.setAlpha(shutterButton.isEnabled() ? 1f : 0.45f);
        shutterLockTarget.setAlpha(shutterLockTarget.isEnabled() ? 1f : 0.45f);
    }

    private void renderExposureControls() {
        if (cameraAperture == null) return;
        boolean editable = isServiceUsable()
                && currentSnapshot != null
                && currentSnapshot.ready
                && currentSnapshot.starAvailable
                && !currentSnapshot.busy
                && !currentSnapshot.halfHeld
                && !currentSnapshot.shutterHeld
                && !exposureWritePending()
                && !isStarSessionActive()
                && !isPlateSolveActive()
                && !starRequestPending
                && pendingSolveIntentId < 0L
                && pendingManualLocationRequest == null;
        boolean cameraEditable = editable && cameraExposureDraft.isSynchronized();
        boolean recipeEditable = editable && starExposureDraft.isSynchronized();
        cameraAperture.setEnabled(cameraEditable);
        cameraShutterSpeed.setEnabled(cameraEditable);
        cameraIso.setEnabled(cameraEditable);
        starAperture.setEnabled(recipeEditable);
        starShutterSpeed.setEnabled(recipeEditable);
        starIso.setEnabled(recipeEditable);
        updateShutterActionEnabled();
    }

    private boolean canRefreshStarService() {
        return isServiceUsable()
                && currentSnapshot != null
                && currentSnapshot.ready
                && !currentSnapshot.starAvailable
                && !currentSnapshot.busy
                && !exposureWritePending()
                && !currentSnapshot.halfHeld
                && !currentSnapshot.shutterHeld
                && !isStarSessionActive()
                && !isPlateSolveActive()
                && !starRequestPending
                && pendingSolveIntentId < 0L
                && pendingManualLocationRequest == null;
    }

    private void renderStarControls() {
        if (detectStarsButton == null) return;
        boolean active = isStarSessionActive();
        boolean solveActive = isPlateSolveActive();
        boolean refreshAvailable = canRefreshStarService();
        detectStarsButton.setEnabled(canRequestStarDetection() || refreshAvailable);
        boolean locatingForSolve = pendingManualLocationRequest != null
                && pendingManualLocationRequest.action.isSolve();
        if (locatingForSolve) {
            detectStarsButton.setText(R.string.locating);
        } else if (active || starRequestPending) {
            detectStarsButton.setText(R.string.plate_solve_button_solving);
        } else if (solveActive) {
            detectStarsButton.setText(R.string.plate_solve_button_solving);
        } else if (refreshAvailable) {
            detectStarsButton.setText(R.string.reconnect_for_star);
        } else {
            detectStarsButton.setText(R.string.solve);
        }

        if (!isServiceUsable() || currentSnapshot == null || !currentSnapshot.ready) {
            starStatusView.setText(R.string.star_not_connected);
            return;
        }
        if (locatingForSolve) {
            starStatusView.setText(foregroundManualStatus.isEmpty()
                    ? getString(R.string.locating) : foregroundManualStatus);
            return;
        }
        if (starRequestPending) {
            starStatusView.setText(R.string.star_detection_started);
            return;
        }
        int solveStatus = solveStatusLabel(
                currentPlateSolveSnapshot,
                currentSnapshot.astro.lastSolveIntentId,
                currentSnapshot.astro.lastSolveIntentAccepted);
        if (solveStatus == R.string.plate_solve_rejected) {
            starStatusView.setText(solveStatus);
            return;
        }
        if (active) {
            String detail = currentStarSnapshot.getDetail();
            starStatusView.setText(detail.isEmpty()
                    ? getString(R.string.detecting_stars)
                    : compactStarStatus(detail));
            return;
        }
        if (solveActive) {
            starStatusView.setText(solveStatus);
            return;
        }
        if (currentSnapshot.settingsWritePending
                || cameraSettingsIntentTracker.isAwaitingService()) {
            starStatusView.setText(R.string.camera_settings_applying);
            return;
        }
        if (currentSnapshot.starRecipeWritePending
                || starRecipeIntentTracker.isAwaitingService()) {
            starStatusView.setText(R.string.star_recipe_saving);
            return;
        }
        if (!currentSnapshot.starAvailable) {
            starStatusView.setText(R.string.star_unavailable);
            return;
        }
        if (!starExposureDraft.isSynchronized()) {
            starStatusView.setText(R.string.star_recipe_synchronizing);
            return;
        }
        if (starRecipeRejected) {
            starStatusView.setText(R.string.star_recipe_rejected);
            return;
        }
        if (!isStarRecipeReadyForCapture()) {
            starStatusView.setText(R.string.star_recipe_confirmation_pending);
            return;
        }
        if (currentStarSnapshot != null
                && currentStarSnapshot.isTerminal()
                && !currentStarSnapshot.isSuccessful()) {
            String detail = currentStarSnapshot.getDetail();
            starStatusView.setText(
                    detail.isEmpty()
                            ? getString(R.string.star_detection_failed)
                            : compactStarStatus(detail));
            return;
        }
        if (currentSnapshot.busy || currentSnapshot.halfHeld || currentSnapshot.shutterHeld) {
            starStatusView.setText(R.string.star_camera_busy);
            return;
        }
        if (solveStatus != 0) {
            starStatusView.setText(solveStatus);
            return;
        }
        if (currentStarSnapshot != null && currentStarSnapshot.isTerminal()
                && !currentStarSnapshot.getDetail().isEmpty()) {
            starStatusView.setText(compactStarStatus(currentStarSnapshot.getDetail()));
        } else {
            starStatusView.setText(R.string.star_ready);
        }
    }

    static String compactStarStatus(String status) {
        String value = singleLine(status);
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("ready")) return "Ready";
        if (lower.contains("waiting for terminal") || lower.contains("received ")) {
            return "Capturing…";
        }
        if (lower.contains("capture complete")) return "Capture complete";
        if (lower.contains("capture rejected")) return "Capture rejected";
        if (lower.contains("capture failed")) return "Capture failed";
        if (lower.contains("transport ended")) return "STAR stopped";
        if (lower.contains("protocol") || lower.contains("peak stream")
                || lower.contains("peak flux") || lower.contains("terminal total")
                || lower.contains("unknown parsed")) {
            return "STAR error";
        }
        return value;
    }

    private void updateControlPageVisibility() {
        shutterPage.setVisibility(!connectionSetupVisible && !starTrackerPageVisible
                ? View.VISIBLE : View.GONE);
        starTrackerPanel.setVisibility(!connectionSetupVisible && starTrackerPageVisible
                ? View.VISIBLE : View.GONE);
        astroSettingsButton.setSelected(false);
        astroSettingsButton.setRotation(0f);
        if (Build.VERSION.SDK_INT >= 30) astroSettingsButton.setStateDescription(null);
        updateAstroNavigationEnabled();
        updateNavigationBackCallback();
    }

    private void updateAstroNavigationEnabled() {
        if (astroSettingsButton == null) return;
        boolean halfHeld = currentSnapshot != null && currentSnapshot.halfHeld;
        boolean fullHeld = currentSnapshot != null && currentSnapshot.shutterHeld;
        boolean enabled = isAstroNavigationEnabled(shutterLocked, halfHeld, fullHeld);
        astroSettingsButton.setEnabled(enabled);
        astroSettingsButton.setAlpha(enabled ? 1f : 0.45f);
    }

    static boolean isAstroNavigationEnabled(
            boolean shutterLocked, boolean halfHeld, boolean shutterHeld) {
        return !shutterLocked && !halfHeld && !shutterHeld;
    }

    static boolean shouldReturnToShutterPage(
            boolean connectionSetupVisible,
            boolean starTrackerPageVisible,
            boolean shutterHeld) {
        return !connectionSetupVisible && starTrackerPageVisible && shutterHeld;
    }

    private boolean isServiceUsable() {
        return activityStarted && bindRegistered && serviceBindingUsable && service != null;
    }

    private void restartHeldTimerFromSnapshot() {
        uiHandler.removeCallbacks(heldTimerUpdater);
        updateHeldPresentation();
        if (activityStarted && shutterLocked) {
            uiHandler.postDelayed(heldTimerUpdater, SHUTTER_TIMER_UPDATE_MS);
        }
    }

    private void disableServiceActions() {
        scanButton.setEnabled(false);
        connectButton.setEnabled(false);
        controlsDisconnectButton.setEnabled(false);
        halfPressButton.setEnabled(false);
        shutterButton.setEnabled(false);
        shutterLockTarget.setEnabled(false);
        cameraAperture.setEnabled(false);
        cameraShutterSpeed.setEnabled(false);
        cameraIso.setEnabled(false);
        starAperture.setEnabled(false);
        starShutterSpeed.setEnabled(false);
        starIso.setEnabled(false);
        detectStarsButton.setEnabled(false);
        shutterSolveButton.setEnabled(false);
        shutterSolveCaptureButton.setEnabled(false);
        correctionEnabledToggle.setEnabled(false);
        manualOverrideToggle.setEnabled(false);
        manualRa.setEnabled(false);
        manualDec.setEnabled(false);
        correctionStrength.setEnabled(false);
        applyCorrectionButton.setEnabled(false);
        stopCorrectionButton.setEnabled(false);
        detectorSigma.setEnabled(false);
        detectorMinArea.setEnabled(false);
        detectorMaxArea.setEnabled(false);
        detectorElongation.setEnabled(false);
        detectorSharpness.setEnabled(false);
        detectorBorder.setEnabled(false);
        detectorApplyButton.setEnabled(false);
        detectorRestoreButton.setEnabled(false);
        solverMaxObservations.setEnabled(false);
        solverSeedTolerance.setEnabled(false);
        solverMatchTolerance.setEnabled(false);
        solverIndependentMatches.setEnabled(false);
        solverTimeout.setEnabled(false);
        solverSettingsApplyButton.setEnabled(false);
        solverSettingsRestoreButton.setEnabled(false);
        updateShutterSegmentAlpha();
    }

    private void renderDisconnectedFallback() {
        if (shutterGesture.isPointerActive()) resetShutterTouchVisual();
        updateScreenVisibility();
        if (currentSnapshot == null) {
            statusView.setText(R.string.status_disconnected);
            detailView.setText("");
        }
        controlsStatusView.setBackgroundTintList(null);
        controlsStatusView.setBackgroundResource(R.drawable.bg_status_not_connected);
        controlsStatusView.setText(R.string.not_connected);
        controlsStatusView.setContentDescription("Not connected. Open connection settings");
        controlsStatusView.setEnabled(true);
        setRefreshVisible(true);
        controlsRefreshButton.setEnabled(true);
        controlsDetailView.setText("");
        controlsDetailView.setVisibility(View.INVISIBLE);
        controlsDisconnectButton.setVisibility(View.GONE);
        controlsCameraName.setText(credentialStore.load().name);
        disableServiceActions();
        nightModeButton.setEnabled(true);
        updateHeldPresentation();
        renderPlateSolve(currentPlateSolveSnapshot);
        renderCorrectionDiagnostics(null);
        renderLastSolveCoordinates(null);
        renderStarControls();
    }

    private void setRefreshVisible(boolean visible) {
        android.widget.LinearLayout.LayoutParams statusParams =
                (android.widget.LinearLayout.LayoutParams) controlsStatusView.getLayoutParams();
        statusParams.width = 0;
        statusParams.weight = 1f;
        controlsStatusView.setLayoutParams(statusParams);
        controlsRefreshButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showConnectionSetup() {
        connectionSetupVisible = true;
        updateScreenVisibility();
        rootScroll.scrollTo(0, 0);
    }

    private void showControlsScreen() {
        connectionSetupVisible = false;
        updateScreenVisibility();
        rootScroll.scrollTo(0, 0);
    }

    private void showStarTrackerPage() {
        connectionSetupVisible = false;
        starTrackerPageVisible = true;
        updateScreenVisibility();
        rootScroll.scrollTo(0, 0);
    }

    private void showShutterPage() {
        connectionSetupVisible = false;
        starTrackerPageVisible = false;
        updateScreenVisibility();
        rootScroll.scrollTo(0, 0);
    }

    private void updateScreenVisibility() {
        connectionScreen.setVisibility(connectionSetupVisible ? View.VISIBLE : View.GONE);
        controlsScreen.setVisibility(connectionSetupVisible ? View.GONE : View.VISIBLE);
        updateControlPageVisibility();
    }

    enum BackDestination { EXIT, CONTROLS, SHUTTER }

    static BackDestination backDestination(
            boolean connectionSetupVisible, boolean starTrackerPageVisible) {
        if (connectionSetupVisible) return BackDestination.CONTROLS;
        if (starTrackerPageVisible) return BackDestination.SHUTTER;
        return BackDestination.EXIT;
    }

    private void navigateBackWithinApp() {
        switch (backDestination(connectionSetupVisible, starTrackerPageVisible)) {
            case CONTROLS:
                showControlsScreen();
                break;
            case SHUTTER:
                showShutterPage();
                break;
            case EXIT:
                // This callback is registered only while an in-app destination exists.
                break;
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33
                && backDestination(connectionSetupVisible, starTrackerPageVisible)
                        != BackDestination.EXIT) {
            navigateBackWithinApp();
            return;
        }
        super.onBackPressed();
    }

    private void updateNavigationBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || navigationBackCallback == null) return;
        boolean shouldRegister = backDestination(
                connectionSetupVisible, starTrackerPageVisible) != BackDestination.EXIT;
        if (shouldRegister && !navigationBackCallbackRegistered) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    navigationBackCallback);
            navigationBackCallbackRegistered = true;
        } else if (!shouldRegister && navigationBackCallbackRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(navigationBackCallback);
            navigationBackCallbackRegistered = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void applyEarlyNightWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff080000));
        getWindow().setStatusBarColor(0xff080000);
        getWindow().setNavigationBarColor(0xff080000);
    }

    @SuppressWarnings("deprecation")
    private void applyNightMode() {
        nightModeButton.setText(nightMode ? R.string.normal_mode : R.string.night_mode);

        int windowBackground = nightMode ? 0xff080000 : getColor(R.color.bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(windowBackground));
        rootScroll.setBackgroundColor(windowBackground);

        if (nightMode) {
            // Convert every rendered pixel to low-intensity red-only luminance.
            ColorMatrix redOnly = new ColorMatrix(new float[]{
                    0.0957f, 0.3218f, 0.0325f, 0f, 0f,
                    0f,      0f,      0f,      0f, 0f,
                    0f,      0f,      0f,      0f, 0f,
                    0f,      0f,      0f,      1f, 0f
            });
            Paint layerPaint = new Paint();
            layerPaint.setColorFilter(new ColorMatrixColorFilter(redOnly));
            rootContent.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint);
            getWindow().setStatusBarColor(0xff080000);
            getWindow().setNavigationBarColor(0xff080000);
        } else {
            rootContent.setLayerType(View.LAYER_TYPE_NONE, null);
            getWindow().setStatusBarColor(getColor(R.color.bg));
            getWindow().setNavigationBarColor(getColor(R.color.bg));
        }
        updateNightSystemBars();
        rootScroll.requestApplyInsets();
        rootContent.invalidate();
    }

    @SuppressWarnings("deprecation")
    private void updateNightSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            int appearanceMask =
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            int appearance = nightMode ? appearanceMask : 0;
            if (Build.VERSION.SDK_INT >= 35) {
                appearanceMask |=
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
                                | android.view.WindowInsetsController
                                        .APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;
                appearance |= android.view.WindowInsetsController
                        .APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;
                if (nightMode) {
                    appearance |= android.view.WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS;
                }
            }
            controller.setSystemBarsAppearance(
                    appearance,
                    appearanceMask);
            if (nightMode) {
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = nightMode
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : View.SYSTEM_UI_FLAG_VISIBLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        View root = findViewById(R.id.root_scroll);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
