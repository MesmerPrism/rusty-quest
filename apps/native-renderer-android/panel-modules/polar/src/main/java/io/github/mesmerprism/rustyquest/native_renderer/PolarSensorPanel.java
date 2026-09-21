package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class PolarSensorPanel {
    static final int REQUEST_BLE_PERMISSIONS = 9103;
    private static final int PENDING_BLE_NONE = 0;
    private static final int PENDING_BLE_SCAN = 1;
    private static final int PENDING_BLE_CONNECT = 2;
    private static final int PENDING_BLE_START_PMD = 3;
    // Match the proven native Study 6 discovery window. H10 advertisements can
    // be sparse enough that a single 15 second cold scan is not reliable.
    private static final long SCAN_TIMEOUT_MS = 45000L;

    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String CHANNEL = "polar-sensor-panel";
    private static final String STREAM_EVENTS_FILE = "polar_stream_events.jsonl";
    private static final String STATUS_FILE = "polar_sensor_status.json";
    private static final String PAIRING_PREFERENCES = "polar_sensor_pairing_v1";
    private static final String PAIRED_INSTANCE_KEY = "preferred_device_instance_id";
    private static final int AUTO_CONNECTION_MAX_ATTEMPTS = 2;
    private static final long AUTO_CONNECTION_TIMEOUT_MS = 15_000L;
    private static final long AUTO_CONNECTION_RETRY_DELAY_MS = 2_000L;
    private static final long AUTO_CONNECTION_CYCLE_DELAY_MS = 10_000L;

    private static final int PANEL_BG = Color.rgb(16, 18, 22);
    private static final int PANEL_SURFACE = Color.rgb(31, 35, 43);
    private static final int PANEL_FG = Color.rgb(235, 238, 244);
    private static final int PANEL_MUTED = Color.rgb(150, 158, 172);
    private static final int PANEL_ACCENT = Color.rgb(118, 209, 188);

    private static final UUID HEART_RATE_SERVICE =
        UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HEART_RATE_MEASUREMENT =
        UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID PMD_SERVICE =
        UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_CONTROL_POINT =
        UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_DATA =
        UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8");

    private static final String STREAM_HR_RR = "stream.polar_h10.hr_rr";
    private static final String STREAM_ECG = "stream.polar_h10.ecg";
    private static final String STREAM_ACC = "stream.polar_h10.acc";
    private static final String STREAM_DEVICE_STATUS = "stream.polar_h10.device_status";

    private static final String[] PMD_LABELS = new String[] {
        "ACC 200 Hz",
        "ECG 130 Hz"
    };
    private static final int PMD_SETTINGS_MAX_ATTEMPTS = 3;
    private static final long PMD_PROBE_DELAY_MS = 500L;
    private static final long PMD_SETTINGS_WAIT_MS = 1500L;
    private static final long PMD_START_ACK_WAIT_MS = 1200L;
    private static final long PMD_DATA_RECEIVING_WINDOW_NS = 2_000_000_000L;
    private static final long STREAMING_STATUS_WRITE_DELAY_MS = 250L;

    interface Host {
        void closePanelAndReturnToImmersive();
        void onPolarStreamEvent(JSONObject event);
    }

    /*
     * Acquisition is process-owned.  A panel can attach to observe/control it,
     * but must never become the lifetime owner of BLE, PMD, or a capture.
     */
    private final Context appContext;
    private Activity activity;
    private Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceEntry> devices = new ArrayList<DeviceEntry>();
    private final Object countersLock = new Object();
    private final Object connectionIdentityLock = new Object();
    private final Queue<DescriptorTask> descriptorTasks = new ArrayDeque<DescriptorTask>();
    private final ExecutorService eventWriter = Executors.newSingleThreadExecutor();
    private final ExecutorService statusWriter = Executors.newSingleThreadExecutor();
    private final AtomicBoolean streamingStatusWriteScheduled = new AtomicBoolean(false);

    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private Spinner pmdSpinner;
    private Button scanButton;
    private TextView status;
    private TextView selectedDevice;
    private TextView hrStatus;
    private TextView accStatus;
    private TextView ecgStatus;
    private TextView linkStatus;

    private BluetoothLeScanner scanner;
    private ScanCallback activeScanCallback;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic batteryCharacteristic;
    private BluetoothGattCharacteristic hrCharacteristic;
    private BluetoothGattCharacteristic pmdControlCharacteristic;
    private BluetoothGattCharacteristic pmdDataCharacteristic;

    private boolean scanning;
    private boolean descriptorsStarted;
    private boolean commandInFlight;
    private boolean pmdReady;
    private volatile boolean pmdRunning;
    private volatile boolean accPmdRunning;
    private volatile boolean ecgPmdRunning;
    private volatile boolean connected;
    private volatile boolean closing;
    private boolean startAllPending;
    private boolean stopAllPending;
    private boolean locationSettingsPromptPending;
    private long scanGeneration;
    private long scanRawCallbackCount;
    private long scanRejectedAdvertisementCount;
    private long scanLastCallbackAtUnixMs;
    private int pendingBleAction;
    private String pendingCommand = "";
    private String pendingPmdMode = "acc";
    private String activePmdMode = "none";
    private long pmdFlowGeneration;
    private long pendingCommandGeneration;
    private int pmdSettingsAttempts;
    private int pmdStartAttempts;
    private PmdSettings accSettings = PmdSettings.EMPTY;
    private PmdSettings ecgSettings = PmdSettings.EMPTY;
    private int activeAccSampleRateHz = 200;
    private int activeEcgSampleRateHz = 130;
    private long lastAccFrameReceiptNs;
    private long lastEcgFrameReceiptNs;
    private String captureSessionId = "none";
    private String accPresentationMode = "low-latency-smooth";

    private long sequenceId;
    private long heartRateEvents;
    private long rrIntervals;
    private long accFrames;
    private long accSamples;
    private long ecgFrames;
    private long ecgSamples;
    private long controlEvents;
    private long malformedFrames;
    private long streamEventsWritten;
    private int latestBpm;
    private int batteryPercent = -1;
    private String connectedLabel = "none";
    private String connectedDeviceInstanceId = "none";
    private String pendingConnectionLabel = "none";
    private String pendingConnectionDeviceInstanceId = "none";
    private int selectedDeviceIndex = -1;
    private String selectedPmdMode = "acc";
    private String statusState = "idle";
    private String statusDetail = "panel-created";
    private long statusUpdatedAtUnixMs = System.currentTimeMillis();
    private volatile long autoConnectionGeneration;
    private long autoScanGeneration = -1L;
    private int autoConnectionAttempts;
    private boolean automaticConnectionRequested;
    private boolean automaticConnectionCycleActive;
    private boolean automaticConnectionRetryScheduled;
    private boolean manualConnectionControl;
    private long automaticRecoveryGeneration;
    private String automaticConnectionState = "not-started";
    private String automaticConnectionDetail = "cold-root request not observed";
    private volatile long automaticConnectionUpdatedAtUnixMs = System.currentTimeMillis();
    private volatile long automaticConnectionEvidenceGeneration;
    private long automaticConnectionDeadlineElapsedMs;

    PolarSensorPanel(Context context) {
        this.appContext = context.getApplicationContext();
    }

    void attachPanel(Activity panelActivity, Host panelHost) {
        if (closing) return;
        this.activity = panelActivity;
        this.host = panelHost;
        updateCounters();
        writeStatus(statusState, statusDetail);
    }

    void detachPanel(Activity panelActivity) {
        if (activity != panelActivity) {
            return;
        }
        activity = null;
        host = null;
        deviceAdapter = null;
        deviceSpinner = null;
        pmdSpinner = null;
        scanButton = null;
        status = null;
        selectedDevice = null;
        hrStatus = null;
        accStatus = null;
        ecgStatus = null;
        linkStatus = null;
        writeStatus(statusState, statusDetail);
    }

    boolean isPanelAttached() {
        return activity != null;
    }

    View buildView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(PANEL_BG);
        scroll.addView(buildContentView(true));
        return scroll;
    }

    View buildEmbeddedAcquisitionView() {
        return buildContentView(false);
    }

    private LinearLayout buildContentView(boolean includeCloseButton) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = row();
        TextView title = text("Polar Sensor Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (includeCloseButton) {
            Button close = button("Close");
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Host currentHost = host;
                    if (currentHost != null) {
                        currentHost.closePanelAndReturnToImmersive();
                    }
                }
            });
            header.addView(close);
        }
        root.addView(header);
        root.addView(text("Direct BLE intake for Polar H10 streams.", 13, PANEL_MUTED));

        root.addView(sectionTitle("Device"));
        deviceAdapter = new ArrayAdapter<String>(
            activity,
            android.R.layout.simple_spinner_item,
            new ArrayList<String>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner = new Spinner(activity);
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner);
        selectedDevice = text("Selected: none", 13, PANEL_MUTED);
        root.addView(selectedDevice);

        LinearLayout scanRow = row();
        scanButton = button(scanning ? "Scanning…" : "Scan");
        scanButton.setEnabled(!scanning);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                beginManualConnectionControl();
                startScan();
            }
        });
        Button connect = button("Connect");
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                beginManualConnectionControl();
                connectSelected();
            }
        });
        Button disconnect = button("Disconnect");
        disconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnect();
            }
        });
        scanRow.addView(scanButton, rowButtonParams());
        scanRow.addView(connect, rowButtonParams());
        scanRow.addView(disconnect, rowButtonParams());
        root.addView(scanRow);
        Button locationSettings = button("Open location settings");
        locationSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openLocationSettings();
            }
        });
        root.addView(locationSettings);
        status = text(statusDetail, 14, scanning ? PANEL_ACCENT : PANEL_MUTED);
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);

        root.addView(sectionTitle("PMD Stream"));
        pmdSpinner = new Spinner(activity);
        ArrayAdapter<String> pmdAdapter =
            new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, PMD_LABELS);
        pmdAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pmdSpinner.setAdapter(pmdAdapter);
        root.addView(pmdSpinner);

        LinearLayout streamRow = row();
        Button startPmd = button("Start PMD");
        startPmd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSelectedPmd();
            }
        });
        Button stopPmd = button("Stop PMD");
        stopPmd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopPmd();
            }
        });
        Button clear = button("Clear");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearCounters();
            }
        });
        streamRow.addView(startPmd, rowButtonParams());
        streamRow.addView(stopPmd, rowButtonParams());
        streamRow.addView(clear, rowButtonParams());
        root.addView(streamRow);

        LinearLayout parallelRow = row();
        Button startAll = button("Start ACC + ECG");
        startAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startAllPmd();
            }
        });
        Button stopAll = button("Stop all PMD");
        stopAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopAllPmd();
            }
        });
        parallelRow.addView(startAll, rowButtonParams());
        parallelRow.addView(stopAll, rowButtonParams());
        root.addView(parallelRow);

        root.addView(sectionTitle("ACC presentation"));
        LinearLayout presentationRow = row();
        Button lowLatencyPresentation = button("Low-latency smooth");
        lowLatencyPresentation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setAccPresentationMode("low-latency-smooth");
            }
        });
        Button faithfulPresentation = button("Timestamp-faithful");
        faithfulPresentation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setAccPresentationMode("timestamp-faithful");
            }
        });
        presentationRow.addView(lowLatencyPresentation, rowButtonParams());
        presentationRow.addView(faithfulPresentation, rowButtonParams());
        root.addView(presentationRow);

        root.addView(sectionTitle("Synchronized capture"));
        LinearLayout captureRow = row();
        Button startCapture = button("Start recording");
        startCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startParallelCapture();
            }
        });
        Button stopCapture = button("Stop recording");
        stopCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopParallelCapture();
            }
        });
        captureRow.addView(startCapture, rowButtonParams());
        captureRow.addView(stopCapture, rowButtonParams());
        root.addView(captureRow);

        root.addView(sectionTitle("Streams"));
        linkStatus = text("", 14, PANEL_FG);
        hrStatus = text("", 14, PANEL_FG);
        accStatus = text("", 14, PANEL_FG);
        ecgStatus = text("", 14, PANEL_FG);
        root.addView(linkStatus);
        root.addView(hrStatus);
        root.addView(accStatus);
        root.addView(ecgStatus);

        updateDeviceAdapter();
        updateCounters();
        updateScanUi();
        marker("status=ui-attached acquisitionState=" + markerToken(statusState)
            + " scanning=" + scanning + " candidateCount=" + devices.size());
        return root;
    }

    void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }
        NativeRendererSelfKioskApplication.endSystemPrompt(activity);
        if (hasRequiredPermissions()) {
            setStatusState("permission-ready", "BLE/location permissions accepted.");
            marker("status=permission-accepted");
            resumePendingBleAction();
            resumeAutomaticConnectionIfRequested();
        } else {
            String missing = PolarBleRuntimeSupport.join(
                PolarBleRuntimeSupport.missingPermissions(appContext),
                ","
            );
            setStatusState("permission-required", "BLE/location permissions missing: " + missing);
            marker("status=permission-rejected missing=" + markerToken(missing));
            pendingBleAction = PENDING_BLE_NONE;
        }
    }

    void onHostResume() {
        boolean returnedFromLocationSettings = locationSettingsPromptPending;
        if (returnedFromLocationSettings) {
            locationSettingsPromptPending = false;
            if (activity != null) NativeRendererSelfKioskApplication.endSystemPrompt(activity);
        }
        String locationState = PolarBleRuntimeSupport.locationServicesState(appContext);
        if ("enabled".equals(locationState)) {
            if ("location-services-disabled".equals(automaticConnectionState)) {
                setAutomaticConnectionState(
                    "not-started",
                    automaticConnectionRequested
                        ? "Location services are enabled; automatic Polar discovery is resuming."
                        : "Location services are enabled; press Scan to discover Polar sensors."
                );
            }
            if (returnedFromLocationSettings) {
                setStatusState("ready", "Location services are enabled. Press Scan to discover Polar sensors.");
            }
        } else if ("disabled".equals(locationState)) {
            if (!connected) {
                setAutomaticConnectionState(
                    "location-services-disabled",
                    "Location services are off; enable them before scanning for Polar sensors."
                );
            }
            if (returnedFromLocationSettings) {
                setStatusState(
                    "location-services-disabled",
                    "Location services are off. Enable them in headset settings, then press Scan again."
                );
            }
        } else {
            if ("location-services-disabled".equals(automaticConnectionState)) {
                setAutomaticConnectionState(
                    "failed",
                    "Location-services state could not be confirmed."
                );
            }
            if (returnedFromLocationSettings) {
                setStatusState(
                    "location-services-unavailable",
                    "Location-services state could not be confirmed. Press Scan to retry discovery."
                );
            }
        }
        resumeAutomaticConnectionIfRequested();
    }

    void shutdown() {
        synchronized (connectionIdentityLock) {
            closing = true;
        }
        cancelAutomaticRecovery(true);
        stopScan();
        closeGatt();
        handler.removeCallbacksAndMessages(null);
        statusWriter.shutdown();
        eventWriter.shutdown();
        marker("status=stopped");
    }

    boolean awaitShutdown() throws InterruptedException {
        return statusWriter.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS)
            && eventWriter.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS);
    }

    static final class OperatorCommandStatus {
        final long operationGeneration;
        final String command;
        final String dispatchStatus;
        final String reasonCode;
        final String effectStatus;
        final String captureSessionId;
        final JSONObject freshPolarStatus;

        OperatorCommandStatus(
            long operationGeneration,
            String command,
            String dispatchStatus,
            String reasonCode,
            String effectStatus,
            String captureSessionId,
            JSONObject freshPolarStatus
        ) {
            this.operationGeneration = operationGeneration;
            this.command = command;
            this.dispatchStatus = dispatchStatus;
            this.reasonCode = reasonCode;
            this.effectStatus = effectStatus;
            this.captureSessionId = captureSessionId;
            this.freshPolarStatus = freshPolarStatus;
        }
    }

    private long operatorCommandGeneration = 0L;

    OperatorCommandStatus handleCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.US);
        long operationGeneration = ++operatorCommandGeneration;
        if ("scan".equals(command)) {
            setStatus("CLI command: scan.");
            marker("status=cli-command command=scan");
            beginManualConnectionControl();
            startScan();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("connect".equals(command)) {
            setStatus("CLI command: connect.");
            marker("status=cli-command command=connect");
            beginManualConnectionControl();
            connectSelected();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("start_acc".equals(command)) {
            setSelectedPmdMode("acc");
            setStatus("CLI command: start ACC.");
            marker("status=cli-command command=start_acc");
            startSelectedPmd();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("start_ecg".equals(command)) {
            setSelectedPmdMode("ecg");
            setStatus("CLI command: start ECG.");
            marker("status=cli-command command=start_ecg");
            startSelectedPmd();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("start_all".equals(command)) {
            marker("status=cli-command command=start_all");
            startAllPmd();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("stop_all".equals(command)) {
            marker("status=cli-command command=stop_all");
            stopAllPmd();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("start_capture".equals(command)) {
            marker("status=cli-command command=start_capture");
            return finishOperatorCommand(
                operationGeneration,
                command,
                "accepted",
                "none",
                startParallelCapture()
            );
        }
        if ("stop_capture".equals(command)) {
            marker("status=cli-command command=stop_capture");
            stopParallelCapture();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("presentation_low_latency".equals(command)) {
            marker("status=cli-command command=presentation_low_latency");
            setAccPresentationMode("low-latency-smooth");
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("presentation_timestamp_faithful".equals(command)) {
            marker("status=cli-command command=presentation_timestamp_faithful");
            setAccPresentationMode("timestamp-faithful");
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("stop_pmd".equals(command)) {
            marker("status=cli-command command=stop_pmd");
            stopPmd();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("disconnect".equals(command)) {
            marker("status=cli-command command=disconnect");
            disconnect();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("reset".equals(command)) {
            marker("status=cli-command command=reset");
            clearCounters();
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "pending");
        }
        if ("status".equals(command)) {
            marker("status=cli-command command=status");
            return finishOperatorCommand(operationGeneration, command, "accepted", "none", "observed");
        }
        setStatus("Unknown Polar CLI command: " + rawCommand);
        marker("status=cli-command-ignored command=" + markerToken(rawCommand));
        return finishOperatorCommand(operationGeneration, command, "rejected", "unknown-command", "not-started");
    }

    /**
     * Side-effect-free experimenter projection. This does not scan, connect, request a
     * permission, call native code, or write the status file.
     */
    synchronized JSONObject experimenterStatusProjection() {
        JSONObject result = new JSONObject();
        try {
            JSONObject ble = PolarBleRuntimeSupport.statusJson(appContext);
            String adapter = ble.optString("bluetooth_adapter_state", "unknown");
            String locationServices = ble.optString("location_services_state", "unknown");
            String automatic;
            String automaticDetail = automaticConnectionDetail;
            if (!ble.optBoolean("runtime_permission_ready", false)) {
                automatic = "permission-required";
                automaticDetail = "Required BLE/location permission is not effective.";
            } else if (!"on".equals(adapter)) {
                automatic = "bluetooth-unavailable";
                automaticDetail = "Bluetooth is not currently available and on.";
            } else if (connected) {
                automatic = "connected";
            } else if ("disabled".equals(locationServices)) {
                automatic = "location-services-disabled";
                automaticDetail = "Location services are off; enable them before scanning for Polar sensors.";
            } else if ("location-services-disabled".equals(automaticConnectionState)) {
                if ("enabled".equals(locationServices)) {
                    automatic = "not-started";
                    automaticDetail = "Location services are enabled; press Scan to discover Polar sensors.";
                } else {
                    automatic = "failed";
                    automaticDetail = "Location-services state could not be confirmed.";
                }
            } else {
                automatic = automaticConnectionState;
            }
            result
                .put("schema", "rusty.quest.native_renderer.polar_experimenter_projection.v1")
                .put("ble_runtime", ble)
                .put("automatic_connection_state", automatic)
                .put("automatic_connection_detail", automaticDetail)
                .put("automatic_connection_generation", currentAutoConnectionGeneration())
                .put("automatic_connection_evidence_generation", currentAutomaticConnectionEvidenceGeneration())
                .put("automatic_connection_attempts", autoConnectionAttempts)
                .put("automatic_connection_updated_at_unix_ms", automaticConnectionUpdatedAtUnixMs)
                .put("automatic_connection_deadline_elapsed_ms", automaticConnectionDeadlineElapsedMs)
                .put("candidate_count", devices.size())
                .put("scan_raw_callback_count", scanRawCallbackCount)
                .put("scan_rejected_advertisement_count", scanRejectedAdvertisementCount)
                .put("scan_last_callback_at_unix_ms", scanLastCallbackAtUnixMs)
                .put("connected", connected)
                .put("scanning", scanning)
                .put("status", statusState)
                .put("detail", statusDetail)
                .put("state_updated_at_unix_ms", statusUpdatedAtUnixMs)
                .put("observed_at_unix_ms", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return result;
    }

    void ensureAutoConnection() {
        if (closing) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    ensureAutoConnection();
                }
            });
            return;
        }
        automaticConnectionRequested = true;
        manualConnectionControl = false;
        if (!automaticConnectionCycleActive && !automaticConnectionRetryScheduled) {
            beginAutomaticConnectionCycle();
        }
        runAutomaticConnectionAttempt();
    }

    private void runAutomaticConnectionAttempt() {
        if (connected) {
            ensureAutoConnectionGeneration();
            finishAutomaticConnectionCycle();
            setAutomaticConnectionState("connected", "Existing Polar link remains active.");
            return;
        }
        if (!PolarAutoConnectionPolicy.automaticRecoveryAllowed(
                automaticConnectionRequested,
                manualConnectionControl,
                closing,
                connected) || automaticConnectionRetryScheduled) {
            return;
        }
        JSONObject ble = PolarBleRuntimeSupport.statusJson(appContext);
        PolarAutoConnectionPolicy.Decision decision = PolarAutoConnectionPolicy.preflight(
            ble.optBoolean("runtime_permission_ready", false),
            ble.optString("bluetooth_adapter_state", "unknown"),
            ble.optString("location_services_state", "unknown"),
            connected,
            scanning || "connecting".equals(automaticConnectionState),
            autoConnectionAttempts,
            AUTO_CONNECTION_MAX_ATTEMPTS
        );
        // The explicit connected guard above keeps this policy result defensive-only.
        if (decision == PolarAutoConnectionPolicy.Decision.CONNECTED) return;
        if (decision == PolarAutoConnectionPolicy.Decision.PERMISSION_REQUIRED) {
            ensureAutoConnectionGeneration();
            setAutomaticConnectionState(
                "permission-required",
                "Required BLE/location permission is not effective; automatic connection did not run."
            );
            scheduleAutomaticRecovery("Waiting for BLE/location permission.", false);
            return;
        }
        if (decision == PolarAutoConnectionPolicy.Decision.BLUETOOTH_UNAVAILABLE) {
            ensureAutoConnectionGeneration();
            setAutomaticConnectionState(
                "bluetooth-unavailable",
                "Bluetooth is unsupported, off, changing state, or permission-blocked."
            );
            scheduleAutomaticRecovery("Waiting for Bluetooth to become available.", false);
            return;
        }
        if (decision == PolarAutoConnectionPolicy.Decision.LOCATION_SERVICES_DISABLED) {
            ensureAutoConnectionGeneration();
            setAutomaticConnectionState(
                "location-services-disabled",
                "Location services are off; enable them before scanning for Polar sensors."
            );
            setStatusState(
                "location-services-disabled",
                "Location services are off. Open headset location settings, enable them, then press Scan."
            );
            scheduleAutomaticRecovery("Waiting for headset location services.", false);
            return;
        }
        if (decision == PolarAutoConnectionPolicy.Decision.WAIT_FOR_IN_FLIGHT) {
            return;
        }
        if (decision == PolarAutoConnectionPolicy.Decision.RETRY_EXHAUSTED) {
            scheduleAutomaticRecovery("Automatic connection cycle finished without a link.");
            return;
        }
        autoConnectionAttempts += 1;
        advanceAutoConnectionGeneration();
        setAutomaticConnectionState("scanning", "Scanning for an eligible Polar sensor.");
        startScan();
        if (scanning) {
            autoScanGeneration = scanGeneration;
        } else {
            autoScanGeneration = -1L;
            setAutomaticConnectionState("failed", "Automatic scan could not start.");
            scheduleAutomaticRecovery("Automatic scan could not start.");
        }
    }

    private void completeAutoScan(long completedScanGeneration) {
        PolarAutoConnectionPolicy.Decision decision = PolarAutoConnectionPolicy.afterScan(
            autoScanGeneration,
            completedScanGeneration,
            devices.size(),
            pairedCandidateMatches()
        );
        if (decision == PolarAutoConnectionPolicy.Decision.STALE_GENERATION) {
            return;
        }
        autoScanGeneration = -1L;
        if (decision == PolarAutoConnectionPolicy.Decision.NOT_FOUND) {
            setAutomaticConnectionState("not-found", "No eligible Polar sensor was found.");
            scheduleAutomaticRecovery("No eligible Polar sensor was found.");
            return;
        }
        if (decision == PolarAutoConnectionPolicy.Decision.REQUIRES_SELECTION) {
            automaticConnectionCycleActive = false;
            manualConnectionControl = true;
            setAutomaticConnectionState(
                "multiple-candidates",
                "Multiple eligible sensors were found; use the dedicated Polar page to choose one."
            );
            return;
        }
        int admittedIndex = PolarAutoConnectionPolicy.admittedCandidateIndex(
            decision,
            devices.size(),
            pairedCandidateIndex()
        );
        if (admittedIndex < 0) {
            setAutomaticConnectionState("failed", "Automatic scan admission produced no exact candidate.");
            scheduleAutomaticRecovery("Automatic scan admission produced no exact candidate.");
            return;
        }
        DeviceEntry admittedCandidate = devices.get(admittedIndex);
        if (decision == PolarAutoConnectionPolicy.Decision.CONNECT_PAIRED) {
            selectedDeviceIndex = admittedIndex;
            setAutomaticConnectionState("connecting", "Reconnecting the app-private preferred sensor.");
            connectAdmittedCandidate(admittedCandidate, true);
            return;
        }
        selectedDeviceIndex = admittedIndex;
        setAutomaticConnectionState("connecting", "Connecting the unique eligible sensor.");
        connectAdmittedCandidate(admittedCandidate, true);
    }

    private void beginAutomaticConnectionCycle() {
        autoConnectionAttempts = 0;
        automaticConnectionCycleActive = true;
        automaticConnectionRetryScheduled = false;
        automaticRecoveryGeneration += 1L;
    }

    private void resumeAutomaticConnectionIfRequested() {
        if (!PolarAutoConnectionPolicy.automaticRecoveryAllowed(
                automaticConnectionRequested,
                manualConnectionControl,
                closing,
                connected)) {
            return;
        }
        if (!automaticConnectionCycleActive && !automaticConnectionRetryScheduled) {
            beginAutomaticConnectionCycle();
        }
        runAutomaticConnectionAttempt();
    }

    private void scheduleAutomaticRecovery(String detail) {
        scheduleAutomaticRecovery(detail, true);
    }

    private void scheduleAutomaticRecovery(String detail, boolean announceRetry) {
        if (!PolarAutoConnectionPolicy.automaticRecoveryAllowed(
                automaticConnectionRequested,
                manualConnectionControl,
                closing,
                connected) || automaticConnectionRetryScheduled) {
            return;
        }
        final boolean nextCycle = autoConnectionAttempts >= AUTO_CONNECTION_MAX_ATTEMPTS;
        final long delayMs = PolarAutoConnectionPolicy.retryDelayMs(
            autoConnectionAttempts,
            AUTO_CONNECTION_MAX_ATTEMPTS,
            AUTO_CONNECTION_RETRY_DELAY_MS,
            AUTO_CONNECTION_CYCLE_DELAY_MS
        );
        final long recoveryGeneration = ++automaticRecoveryGeneration;
        automaticConnectionRetryScheduled = true;
        if (nextCycle) automaticConnectionCycleActive = false;
        if (announceRetry) {
            setAutomaticConnectionState(
                "scanning",
                detail + (nextCycle
                    ? " Automatic discovery will keep trying."
                    : " Retrying automatically.")
            );
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (recoveryGeneration != automaticRecoveryGeneration
                        || !automaticConnectionRetryScheduled) {
                    return;
                }
                automaticConnectionRetryScheduled = false;
                if (!PolarAutoConnectionPolicy.automaticRecoveryAllowed(
                        automaticConnectionRequested,
                        manualConnectionControl,
                        closing,
                        connected)) {
                    return;
                }
                if (nextCycle) {
                    autoConnectionAttempts = 0;
                    automaticConnectionCycleActive = true;
                }
                runAutomaticConnectionAttempt();
            }
        }, delayMs);
    }

    private void finishAutomaticConnectionCycle() {
        automaticRecoveryGeneration += 1L;
        automaticConnectionRetryScheduled = false;
        automaticConnectionCycleActive = false;
        autoConnectionAttempts = 0;
    }

    private void cancelAutomaticRecovery(boolean clearRequest) {
        automaticRecoveryGeneration += 1L;
        automaticConnectionRetryScheduled = false;
        automaticConnectionCycleActive = false;
        if (clearRequest) automaticConnectionRequested = false;
    }

    private void beginManualConnectionControl() {
        manualConnectionControl = true;
        cancelAutomaticRecovery(false);
        autoScanGeneration = -1L;
        if (scanning) stopScan();
        if (gatt != null && !connected) closeGatt();
        advanceAutoConnectionGeneration();
        automaticConnectionDeadlineElapsedMs = 0L;
    }

    private int pairedCandidateIndex() {
        String preferred = appContext.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .getString(PAIRED_INSTANCE_KEY, "");
        if (preferred == null || preferred.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < devices.size(); index++) {
            if (preferred.equals(devices.get(index).instanceId())) {
                return index;
            }
        }
        return -1;
    }

    private int pairedCandidateMatches() {
        return pairedCandidateIndex() >= 0 ? 1 : 0;
    }

    private void rememberConnectedPairing() {
        if (connectedDeviceInstanceId == null
                || "none".equals(connectedDeviceInstanceId)
                || connectedDeviceInstanceId.isEmpty()) {
            return;
        }
        appContext.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(PAIRED_INSTANCE_KEY, connectedDeviceInstanceId)
            .apply();
    }

    private void setAutomaticConnectionState(String state, String detail) {
        automaticConnectionState = state == null ? "failed" : state;
        automaticConnectionDetail = detail == null ? "" : detail;
        synchronized (connectionIdentityLock) {
            automaticConnectionUpdatedAtUnixMs = System.currentTimeMillis();
            automaticConnectionEvidenceGeneration = autoConnectionGeneration;
        }
        if (!"connecting".equals(automaticConnectionState)) {
            automaticConnectionDeadlineElapsedMs = 0L;
        }
    }

    private OperatorCommandStatus finishOperatorCommand(
        long operationGeneration,
        String command,
        String dispatchStatus,
        String reasonCode,
        String effectStatus
    ) {
        JSONObject freshStatus = experimenterStatusProjection();
        writeStatus(statusState, statusDetail);
        return new OperatorCommandStatus(
            operationGeneration,
            command,
            dispatchStatus,
            reasonCode,
            effectStatus,
            captureSessionId,
            freshStatus
        );
    }

    boolean isEcgReceiving() {
        synchronized (countersLock) {
            return ecgPmdRunning && ecgFrames > 0L && ecgSamples > 0L;
        }
    }

    String ecgExperimentStatusLine(boolean experimentReady) {
        long frameCount;
        long sampleCount;
        boolean running;
        String mode;
        synchronized (countersLock) {
            frameCount = ecgFrames;
            sampleCount = ecgSamples;
            running = ecgPmdRunning;
            mode = activePmdMode;
        }
        if (!experimentReady) {
            return "ECG logging: participant file not created yet.";
        }
        if (running && frameCount > 0L && sampleCount > 0L) {
            return "ECG logging: active, " + frameCount + " frames / " + sampleCount + " samples mirrored to participant files.";
        }
        if (running) {
            return "ECG logging: ECG stream active, waiting for decoded samples.";
        }
        return "ECG logging: not active. Select ECG 130 Hz and start PMD after connecting.";
    }

    private void startScan() {
        if (scanning) {
            setStatus("Scan already running. Compatible candidates found: " + devices.size() + ".");
            updateScanUi();
            marker("status=scan-already-running generation=" + scanGeneration
                + " candidateCount=" + devices.size());
            return;
        }
        if (!ensurePermissions(PENDING_BLE_SCAN)) {
            return;
        }
        pendingBleAction = PENDING_BLE_NONE;
        if ("disabled".equals(PolarBleRuntimeSupport.locationServicesState(appContext))) {
            setStatusState(
                "location-services-disabled",
                "Location services are off. Open headset location settings, enable them, then press Scan."
            );
            marker("status=scan-blocked reason=location-services-disabled");
            return;
        }
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setStatusState("bluetooth-disabled", "Bluetooth is unavailable or turned off.");
            marker("status=error reason=bluetooth-disabled");
            return;
        }
        BluetoothLeScanner nextScanner = adapter.getBluetoothLeScanner();
        if (nextScanner == null) {
            setStatusState("scanner-unavailable", "BLE scanner is unavailable.");
            marker("status=error reason=scanner-unavailable");
            return;
        }
        stopScan();
        devices.clear();
        scanRawCallbackCount = 0L;
        scanRejectedAdvertisementCount = 0L;
        scanLastCallbackAtUnixMs = 0L;
        updateDeviceAdapter();
        scanner = nextScanner;
        final long generation = scanGeneration;
        activeScanCallback = createScanCallback(generation);
        scanning = true;
        try {
            scanner.startScan(
                new ArrayList<ScanFilter>(),
                new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                activeScanCallback
            );
        } catch (SecurityException ex) {
            stopScan();
            setStatusState("permission-required", "BLE scan permission is missing.");
            marker("status=error reason=scan-security-exception");
            return;
        } catch (RuntimeException ex) {
            stopScan();
            setStatusState("scan-start-failed", "BLE scan failed to start.");
            marker("status=error reason=scan-start-failed");
            return;
        }
        setStatusState("scanning", "Scanning for Polar H10 advertisements.");
        updateScanUi();
        marker("status=scanning scanMode=low-latency platformFilter=empty");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCurrentScanGeneration(generation)) {
                    stopScan();
                    String detail = "Scan finished. Compatible devices: " + devices.size()
                        + ". BLE advertisements received: " + scanRawCallbackCount
                        + "; rejected as unrelated: " + scanRejectedAdvertisementCount + ".";
                    setStatusState("scan-finished", detail);
                    marker("status=scan-finished deviceCount=" + devices.size()
                        + " rawCallbackCount=" + scanRawCallbackCount
                        + " rejectedAdvertisementCount=" + scanRejectedAdvertisementCount
                        + " rawDeviceIdentifierLogged=false");
                    completeAutoScan(generation);
                }
            }
        }, SCAN_TIMEOUT_MS);
    }

    private void connectSelected() {
        if (!ensurePermissions(PENDING_BLE_CONNECT)) {
            return;
        }
        pendingBleAction = PENDING_BLE_NONE;
        if (activity == null && devices.size() != 1) {
            setStatusState(
                "candidate-ambiguous",
                "Headless connect requires exactly one compatible Polar candidate."
            );
            marker("status=connection-rejected reason=headless-candidate-count count=" + devices.size());
            return;
        }
        int index = selectedDeviceIndex();
        if (index < 0 || index >= devices.size()) {
            setStatus("No Polar device selected.");
            return;
        }
        connectAdmittedCandidate(devices.get(index), false);
    }

    private void connectAdmittedCandidate(DeviceEntry entry, boolean automatic) {
        if (entry == null) {
            setAutomaticConnectionState("failed", "Admitted Polar candidate is unavailable.");
            if (automatic) scheduleAutomaticRecovery("Admitted Polar candidate is unavailable.");
            return;
        }
        if (!PolarBleRuntimeSupport.hasRequiredPermissions(appContext)) {
            setAutomaticConnectionState("permission-required", "BLE connect permission is missing.");
            if (automatic) scheduleAutomaticRecovery("BLE connect permission is missing.");
            return;
        }
        stopScan();
        closeGatt();
        descriptorsStarted = false;
        commandInFlight = false;
        pmdReady = false;
        accPmdRunning = false;
        ecgPmdRunning = false;
        setStreamRunning("acc", false);
        startAllPending = false;
        stopAllPending = false;
        pmdSettingsAttempts = 0;
        pmdStartAttempts = 0;
        accSettings = PmdSettings.EMPTY;
        ecgSettings = PmdSettings.EMPTY;
        pendingPmdMode = selectedPmdMode();
        pendingConnectionLabel = entry.label();
        pendingConnectionDeviceInstanceId = entry.instanceId();
        try {
            BluetoothGatt nextGatt;
            if (Build.VERSION.SDK_INT >= 23) {
                nextGatt = entry.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                nextGatt = entry.device.connectGatt(appContext, false, gattCallback);
            }
            synchronized (connectionIdentityLock) {
                gatt = nextGatt;
                connected = false;
            }
            if (nextGatt == null) {
                closeGatt();
                setStatusState("connection-failed", "BLE connect returned no transport handle.");
                if ("connecting".equals(automaticConnectionState)) {
                    setAutomaticConnectionState("failed", "BLE connect returned no transport handle.");
                }
                if (automatic) scheduleAutomaticRecovery("BLE connect returned no transport handle.");
                marker("status=error reason=connect-null-gatt");
                return;
            }
            if (!automatic) {
                setAutomaticConnectionState("connecting", "Connecting to the manually selected sensor.");
            }
            setStatusState("connecting", "Connecting to selected Polar device.");
            if (automatic) {
                scheduleAutomaticConnectionTimeout(currentAutoConnectionGeneration(), nextGatt);
            }
            marker("status=connecting deviceInstanceId=" + markerToken(entry.instanceId())
                + " rawDeviceIdentifierLogged=false");
        } catch (SecurityException ex) {
            closeGatt();
            setStatusState("permission-required", "BLE connect permission is missing.");
            setAutomaticConnectionState("permission-required", "BLE connect permission is missing.");
            if (automatic) scheduleAutomaticRecovery("BLE connect permission is missing.");
            marker("status=error reason=connect-security-exception");
        } catch (RuntimeException ex) {
            closeGatt();
            setStatusState("connection-failed", "BLE connect failed to start.");
            if ("connecting".equals(automaticConnectionState)) {
                setAutomaticConnectionState("failed", "BLE connect failed to start.");
            }
            if (automatic) scheduleAutomaticRecovery("BLE connect failed to start.");
            marker("status=error reason=connect-start-failed");
        }
    }

    private void scheduleAutomaticConnectionTimeout(
        final long expectedGeneration,
        final BluetoothGatt expectedGatt
    ) {
        automaticConnectionDeadlineElapsedMs = SystemClock.elapsedRealtime()
            + AUTO_CONNECTION_TIMEOUT_MS;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (expectedGeneration != currentAutoConnectionGeneration()
                        || expectedGatt == null
                        || expectedGatt != gatt
                        || connected
                        || closing
                        || manualConnectionControl) {
                    return;
                }
                closeGatt();
                setAutomaticConnectionState("failed", "Polar connection timed out.");
                scheduleAutomaticRecovery("Polar connection timed out.");
            }
        }, AUTO_CONNECTION_TIMEOUT_MS);
    }

    private void disconnect() {
        manualConnectionControl = true;
        cancelAutomaticRecovery(true);
        advanceAutoConnectionGeneration();
        stopScan();
        closeGatt();
        setAutomaticConnectionState("not-started", "Polar connection was manually disconnected.");
        setStatusState("disconnected", "Disconnected from Polar device.");
        marker("status=disconnected");
        updateCounters();
    }

    private void startSelectedPmd() {
        if (!ensurePermissions(PENDING_BLE_START_PMD)) {
            return;
        }
        pendingBleAction = PENDING_BLE_NONE;
        if (!connected || gatt == null || pmdControlCharacteristic == null) {
            setStatus("PMD control point is not available.");
            return;
        }
        String selectedMode = selectedPmdMode();
        if (!pmdReady) {
            setStatus("PMD notifications are not ready yet.");
            return;
        }
        beginPmdStartFlow(selectedMode);
    }

    private void startAllPmd() {
        if (!ensurePermissions(PENDING_BLE_START_PMD)) {
            return;
        }
        pendingBleAction = PENDING_BLE_NONE;
        if (!connected || gatt == null || pmdControlCharacteristic == null || !pmdReady) {
            setStatus("PMD control point is not ready for parallel streams.");
            return;
        }
        startAllPending = true;
        if (!accPmdRunning) {
            beginPmdStartFlow("acc");
        } else if (!ecgPmdRunning) {
            beginPmdStartFlow("ecg");
        } else {
            startAllPending = false;
            setStatus("ACC and ECG PMD streams are already active.");
        }
    }

    private void stopAllPmd() {
        if (gatt == null || pmdControlCharacteristic == null) {
            setStatus("PMD control point is not available.");
            return;
        }
        stopAllPending = true;
        pmdFlowGeneration += 1L;
        if (accPmdRunning) {
            pendingPmdMode = "acc";
            writePmdCommand("stop_stream_only", buildStopCommand("acc"));
        } else if (ecgPmdRunning) {
            pendingPmdMode = "ecg";
            writePmdCommand("stop_stream_only", buildStopCommand("ecg"));
        } else {
            stopAllPending = false;
            setStatus("All PMD streams are already stopped.");
        }
    }

    private void stopPmd() {
        if (gatt == null || pmdControlCharacteristic == null) {
            setStatus("PMD control point is not available.");
            return;
        }
        String mode = selectedPmdMode();
        if (!streamRunning(mode)) {
            setStatus(mode.toUpperCase(Locale.US) + " PMD stream is already stopped.");
            return;
        }
        pmdFlowGeneration += 1L;
        pendingPmdMode = mode;
        writePmdCommand("stop_stream_only", buildStopCommand(mode));
    }

    private String startParallelCapture() {
        if (!"none".equals(captureSessionId)) {
            setStatus("A synchronized capture is already active: " + captureSessionId);
            return "rejected-already-active";
        }
        captureSessionId = "breath_capture_" + System.currentTimeMillis();
        File directory = new File(new File(appContext.getFilesDir(), "breath_source_captures"), captureSessionId);
        long startedAtElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        String response = nativeStartParallelBreathCapture(
            directory.getAbsolutePath(),
            captureSessionId,
            startedAtElapsedRealtimeNs);
        try {
            JSONObject result = new JSONObject(response == null ? "{}" : response);
            if (!"started".equals(result.optString("status", ""))) {
                captureSessionId = "none";
                setStatus("Capture start rejected: " + result.optString("reason_code", "unknown"));
                return "rejected";
            }
            marker("status=capture-started session=" + markerToken(captureSessionId));
            setStatus("Two-minute synchronized controller/Polar capture started.");
            final String startedCaptureSessionId = captureSessionId;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshCaptureCompletion(startedCaptureSessionId);
                }
            }, 120000L);
            return "started";
        } catch (Exception error) {
            captureSessionId = "none";
            setStatus("Capture start returned malformed status.");
            return "malformed";
        }
    }

    private void stopParallelCapture() {
        String response = nativeStopParallelBreathCapture();
        try {
            JSONObject result = new JSONObject(response == null ? "{}" : response);
            if (!"stopped".equals(result.optString("status", ""))) {
                setStatus("Capture stop rejected: " + result.optString("reason_code", "unknown"));
                return;
            }
            marker("status=capture-stopped session=" + markerToken(captureSessionId)
                + " written=" + result.optLong("written_records", 0L)
                + " dropped=" + result.optLong("dropped_records", 0L));
            captureSessionId = "none";
            setStatus(result.optBoolean("complete", false)
                ? "Synchronized capture stopped and finalized."
                : "Capture stopped incomplete; retain it only for transport diagnosis.");
        } catch (Exception error) {
            setStatus("Capture stop returned malformed status.");
        }
    }

    private void refreshCaptureCompletion(String expectedSessionId) {
        if (!expectedSessionId.equals(captureSessionId)) {
            return;
        }
        try {
            JSONObject result = new JSONObject(nativeReadParallelBreathCaptureStatus());
            if (result.optBoolean("active", false)) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshCaptureCompletion(expectedSessionId);
                    }
                }, 250L);
                return;
            }
            String stopReason = result.optString("stop_reason", "unknown");
            boolean complete = result.optBoolean("complete", false);
            captureSessionId = "none";
            marker("status=capture-auto-complete reason=" + markerToken(stopReason)
                + " complete=" + complete);
            setStatus(complete
                ? "Two-minute capture finalized."
                : "Two-minute capture incomplete; retain it only for transport diagnosis.");
            updateCounters();
        } catch (Exception error) {
            setStatus("Capture completion status was malformed.");
        }
    }

    private void setAccPresentationMode(String mode) {
        String response = nativeSetPolarAccPresentationMode(mode);
        try {
            JSONObject result = new JSONObject(response == null ? "{}" : response);
            if (!"accepted".equals(result.optString("status", ""))) {
                setStatus("ACC presentation rejected: " + result.optString("reason_code", "unknown"));
                return;
            }
            JSONObject presentation = result.optJSONObject("presentation");
            accPresentationMode = presentation == null
                ? mode
                : presentation.optString("mode", mode);
            marker("status=acc-presentation mode=" + markerToken(accPresentationMode));
            setStatus("ACC presentation: " + accPresentationMode + ".");
            updateCounters();
        } catch (Exception error) {
            setStatus("ACC presentation returned malformed status.");
        }
    }

    private boolean streamRunning(String mode) {
        return "ecg".equals(mode) ? ecgPmdRunning : accPmdRunning;
    }

    private void setStreamRunning(String mode, boolean running) {
        if ("ecg".equals(mode)) {
            ecgPmdRunning = running;
        } else {
            accPmdRunning = running;
        }
        pmdRunning = accPmdRunning || ecgPmdRunning;
        activePmdMode = accPmdRunning && ecgPmdRunning
            ? "acc+ecg"
            : (accPmdRunning ? "acc" : (ecgPmdRunning ? "ecg" : "none"));
    }

    private void clearCounters() {
        synchronized (countersLock) {
            sequenceId = 0L;
            heartRateEvents = 0L;
            rrIntervals = 0L;
            accFrames = 0L;
            accSamples = 0L;
            ecgFrames = 0L;
            ecgSamples = 0L;
            controlEvents = 0L;
            malformedFrames = 0L;
            streamEventsWritten = 0L;
            latestBpm = 0;
            batteryPercent = -1;
            lastAccFrameReceiptNs = 0L;
            lastEcgFrameReceiptNs = 0L;
        }
        File events = new File(appContext.getFilesDir(), STREAM_EVENTS_FILE);
        if (events.exists() && !events.delete()) {
            setStatus("Counters reset; stream-event file could not be removed.");
        } else {
            setStatus("Counters reset.");
        }
        updateCounters();
        setStatusState("ready", "Counters reset.");
        marker("status=counters-reset");
    }

    private ScanCallback createScanCallback(final long generation) {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                final long observedAtUnixMs = System.currentTimeMillis();
                String advertisedName = result == null || result.getScanRecord() == null
                    ? ""
                    : result.getScanRecord().getDeviceName();
                String cachedName = result == null || result.getDevice() == null
                    ? ""
                    : safeName(result.getDevice());
                String discoveredName = PolarAutoConnectionPolicy.preferredScanName(
                    advertisedName,
                    cachedName
                );
                boolean hasHeartRateService = scanRecordHasService(
                    result == null ? null : result.getScanRecord(),
                    HEART_RATE_SERVICE
                );
                boolean hasPmdService = scanRecordHasService(
                    result == null ? null : result.getScanRecord(),
                    PMD_SERVICE
                );
                final DeviceEntry entry = result == null || result.getDevice() == null
                    ? null
                    : new DeviceEntry(
                        result.getDevice(),
                        discoveredName,
                        safeAddress(result.getDevice()),
                        result.getRssi(),
                        hasHeartRateService,
                        hasPmdService
                    );
                final boolean accepted = entry != null
                    && PolarAutoConnectionPolicy.acceptsScanCandidate(
                        entry.name,
                        hasHeartRateService,
                        hasPmdService
                    );
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrentScanGeneration(generation)) {
                            return;
                        }
                        scanRawCallbackCount += 1L;
                        scanLastCallbackAtUnixMs = observedAtUnixMs;
                        if (!accepted) {
                            scanRejectedAdvertisementCount += 1L;
                        } else {
                            addOrUpdateDevice(entry);
                            if (autoScanGeneration == generation && pairedCandidateMatches() == 1) {
                                stopScan();
                                setStatusState(
                                    "candidate-found",
                                    "Preferred Polar sensor found; connecting automatically."
                                );
                                marker("status=auto-preferred-candidate-admitted"
                                    + " rawDeviceIdentifierLogged=false");
                                completeAutoScan(generation);
                                return;
                            }
                        }
                        if (scanRawCallbackCount == 1L) {
                            setStatusState(
                                "scanning",
                                "Scanning for Polar H10 advertisements. BLE callback received."
                            );
                            marker("status=scan-callback-received rawCallbackCount=1"
                                + " accepted=" + accepted
                                + " rawDeviceIdentifierLogged=false");
                        }
                    }
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) {
                    return;
                }
                for (ScanResult result : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                }
            }

            @Override
            public void onScanFailed(final int errorCode) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrentScanGeneration(generation)) {
                            return;
                        }
                        invalidateScanGeneration(generation);
                        setStatusState("scan-failed", "BLE scan failed with code " + errorCode + ".");
                        if (autoScanGeneration == generation) {
                            autoScanGeneration = -1L;
                            setAutomaticConnectionState("failed", "BLE scan failed.");
                            scheduleAutomaticRecovery("BLE scan failed.");
                        }
                        marker("status=scan-failed errorCode=" + errorCode);
                    }
                });
            }
        };
    }

    private boolean isCurrentScanGeneration(long generation) {
        return !closing && scanning && generation == scanGeneration;
    }

    private void invalidateScanGeneration(long generation) {
        if (generation == scanGeneration) {
            scanning = false;
            activeScanCallback = null;
            scanGeneration += 1L;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt callbackGatt, final int statusCode, final int newState) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleConnectionState(callbackGatt, statusCode, newState);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt callbackGatt, final int statusCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentConnectedGatt(callbackGatt)) {
                        return;
                    }
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                        closeGatt();
                        setStatusState("connection-failed", "Service discovery failed: " + statusCode);
                        setAutomaticConnectionState("failed", "Polar service discovery failed.");
                        scheduleAutomaticRecovery("Polar service discovery failed.");
                        marker("status=error reason=service-discovery statusCode=" + statusCode);
                        return;
                    }
                    boolean mtuRequested = false;
                    try {
                        mtuRequested = callbackGatt.requestMtu(232);
                    } catch (SecurityException ignored) {
                    }
                    if (!mtuRequested) {
                        setupAfterMtu(callbackGatt);
                    } else {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                setupAfterMtu(callbackGatt);
                            }
                        }, 1500L);
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt callbackGatt, int mtu, int statusCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentConnectedGatt(callbackGatt)) {
                        return;
                    }
                    setupAfterMtu(callbackGatt);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, final int statusCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentConnectedGatt(callbackGatt)) {
                        return;
                    }
                    commandInFlight = false;
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                        malformedFrames += 1L;
                        setStatus("Descriptor write failed: " + statusCode);
                    }
                    writeNextDescriptorOrBegin(callbackGatt);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, final int statusCode) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentConnectedGatt(callbackGatt)) {
                        return;
                    }
                    handleCharacteristicWrite(statusCode);
                }
            });
        }

        @Override
        public void onCharacteristicRead(final BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(characteristic.getUuid())) {
                final byte[] value = characteristic.getValue();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrentConnectedGatt(callbackGatt)) {
                            return;
                        }
                        handleBattery(value);
                    }
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
            dispatchCharacteristic(callbackGatt, characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            dispatchCharacteristic(callbackGatt, characteristic, value);
        }
    };

    private void dispatchCharacteristic(
        final BluetoothGatt callbackGatt,
        final BluetoothGattCharacteristic characteristic,
        byte[] value
    ) {
        final byte[] copy = value == null ? null : value.clone();
        final long admittedGeneration;
        synchronized (connectionIdentityLock) {
            if (!PolarAutoConnectionPolicy.mayPublishLiveEvidence(
                    callbackGatt,
                    gatt,
                    autoConnectionGeneration,
                    autoConnectionGeneration,
                    connected,
                    closing)) {
                return;
            }
            admittedGeneration = autoConnectionGeneration;
        }
        UUID uuid = characteristic == null ? null : characteristic.getUuid();
        if (PMD_DATA.equals(uuid) || HEART_RATE_MEASUREMENT.equals(uuid)) {
            handleCharacteristic(callbackGatt, admittedGeneration, characteristic, copy);
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentConnectedGatt(callbackGatt)) {
                    return;
                }
                handleCharacteristic(callbackGatt, admittedGeneration, characteristic, copy);
            }
        });
    }

    private boolean isCurrentConnectedGatt(BluetoothGatt callbackGatt) {
        synchronized (connectionIdentityLock) {
            return !closing && connected && callbackGatt != null && callbackGatt == gatt;
        }
    }

    private void handleConnectionState(BluetoothGatt callbackGatt, int statusCode, int newState) {
        if (closing || callbackGatt != gatt) {
            return;
        }
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            closeGatt();
            setStatusState("connection-failed", "Connection failed: " + statusCode);
            setAutomaticConnectionState("failed", "Polar connection failed.");
            scheduleAutomaticRecovery("Polar connection failed with GATT status " + statusCode + ".");
            marker("status=connection-failed statusCode=" + statusCode);
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            boolean manuallySelected = manualConnectionControl;
            synchronized (connectionIdentityLock) {
                if (closing || callbackGatt != gatt) {
                    return;
                }
                connected = true;
            }
            connectedLabel = pendingConnectionLabel;
            connectedDeviceInstanceId = pendingConnectionDeviceInstanceId;
            pendingConnectionLabel = "none";
            pendingConnectionDeviceInstanceId = "none";
            setStatusState("connected", "Connected. Discovering services.");
            rememberConnectedPairing();
            manualConnectionControl = false;
            finishAutomaticConnectionCycle();
            setAutomaticConnectionState(
                "connected",
                manuallySelected
                    ? "Manually selected Polar connection succeeded."
                    : "Automatic Polar connection succeeded."
            );
            marker("status=connected");
            try {
                callbackGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                callbackGatt.discoverServices();
            } catch (SecurityException ex) {
                closeGatt();
                setStatusState("permission-required", "BLE service discovery permission is missing.");
                setAutomaticConnectionState("permission-required", "BLE service discovery permission is missing.");
                scheduleAutomaticRecovery("Waiting for BLE service discovery permission.", false);
                marker("status=error reason=discover-security-exception");
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            closeGatt();
            setAutomaticConnectionState("failed", "Polar device disconnected.");
            setStatusState("disconnected", "Polar device disconnected.");
            scheduleAutomaticRecovery("Polar device disconnected.");
            marker("status=device-disconnected");
            updateCounters();
        }
    }

    private void setupAfterMtu(BluetoothGatt callbackGatt) {
        if (!isCurrentConnectedGatt(callbackGatt) || descriptorsStarted) {
            return;
        }
        descriptorsStarted = true;
        descriptorTasks.clear();
        BluetoothGattService hrService = callbackGatt.getService(HEART_RATE_SERVICE);
        BluetoothGattService batteryService = callbackGatt.getService(BATTERY_SERVICE);
        BluetoothGattService pmdService = callbackGatt.getService(PMD_SERVICE);
        hrCharacteristic = hrService == null ? null : hrService.getCharacteristic(HEART_RATE_MEASUREMENT);
        batteryCharacteristic = batteryService == null ? null : batteryService.getCharacteristic(BATTERY_LEVEL);
        pmdControlCharacteristic = pmdService == null ? null : pmdService.getCharacteristic(PMD_CONTROL_POINT);
        pmdDataCharacteristic = pmdService == null ? null : pmdService.getCharacteristic(PMD_DATA);

        if (hrCharacteristic != null) {
            descriptorTasks.add(new DescriptorTask(hrCharacteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        }
        if (pmdControlCharacteristic != null) {
            descriptorTasks.add(new DescriptorTask(pmdControlCharacteristic, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE));
        }
        if (pmdDataCharacteristic != null) {
            descriptorTasks.add(new DescriptorTask(pmdDataCharacteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        }
        if (hrCharacteristic == null && pmdControlCharacteristic == null) {
            closeGatt();
            setStatusState("connection-failed", "Connected device has no supported Polar HR or PMD services.");
            setAutomaticConnectionState("failed", "Connected device exposed no supported Polar services.");
            scheduleAutomaticRecovery("Connected device exposed no supported Polar services.");
            marker("status=error reason=no-supported-services");
            return;
        }
        setStatus("Subscribing to Polar characteristics.");
        writeNextDescriptorOrBegin(callbackGatt);
    }

    private void writeNextDescriptorOrBegin(BluetoothGatt callbackGatt) {
        if (!isCurrentConnectedGatt(callbackGatt) || commandInFlight) {
            return;
        }
        DescriptorTask task = descriptorTasks.poll();
        if (task == null) {
            beginStreams(callbackGatt);
            return;
        }
        BluetoothGattDescriptor descriptor = task.characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            malformedFrames += 1L;
            writeNextDescriptorOrBegin(callbackGatt);
            return;
        }
        try {
            callbackGatt.setCharacteristicNotification(task.characteristic, true);
            commandInFlight = true;
            if (!writeDescriptorCompat(callbackGatt, descriptor, task.value)) {
                commandInFlight = false;
                malformedFrames += 1L;
                marker("status=descriptor-write-not-started characteristic="
                    + markerToken(task.characteristic.getUuid().toString()));
                writeNextDescriptorOrBegin(callbackGatt);
            }
        } catch (SecurityException ex) {
            commandInFlight = false;
            setStatus("BLE descriptor permission is missing.");
            marker("status=error reason=descriptor-security-exception");
        }
    }

    private void beginStreams(BluetoothGatt callbackGatt) {
        if (!isCurrentConnectedGatt(callbackGatt)) {
            return;
        }
        appendStatusEvent("subscribed");
        scheduleBatteryRead(callbackGatt, batteryCharacteristic);
        if (pmdControlCharacteristic != null && pmdDataCharacteristic != null) {
            pmdReady = true;
            marker("status=pmd-ready");
            setStatus("HR/RR notifications active; starting " + selectedPmdMode().toUpperCase(Locale.US) + " PMD.");
            startSelectedPmd();
        } else {
            pmdReady = false;
            setStatus("HR/RR notifications active; PMD service not available.");
        }
        updateCounters();
    }

    private void handleCharacteristicWrite(int statusCode) {
        commandInFlight = false;
        final String command = pendingCommand;
        final long generation = pendingCommandGeneration;
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            setStatus("PMD command failed: " + command + " " + statusCode);
            marker("status=command-failed command=" + markerToken(command) + " statusCode=" + statusCode);
            return;
        }
        if ("probe_pmd".equals(command)) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestPmdSettings(generation);
                }
            }, PMD_PROBE_DELAY_MS);
            return;
        }
        if ("get_settings".equals(command)) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    handleSettingsTimeout(generation);
                }
            }, PMD_SETTINGS_WAIT_MS);
            return;
        }
        if ("start_stream".equals(command)) {
            setStatus("PMD start command written; waiting for " + pendingPmdMode.toUpperCase(Locale.US) + " ACK.");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    handleStartAckTimeout(generation);
                }
            }, PMD_START_ACK_WAIT_MS);
            return;
        }
        if ("stop_stream_only".equals(command)) {
            final String stoppedMode = pendingPmdMode;
            setStreamRunning(stoppedMode, false);
            setStatus(stoppedMode.toUpperCase(Locale.US) + " PMD stream stopped.");
            marker("status=pmd-stopped mode=" + markerToken(stoppedMode));
            appendStatusEvent("pmd-stopped-" + stoppedMode);
            updateCounters();
            if (stopAllPending && pmdRunning) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        String nextMode = accPmdRunning ? "acc" : "ecg";
                        pendingPmdMode = nextMode;
                        pmdFlowGeneration += 1L;
                        writePmdCommand("stop_stream_only", buildStopCommand(nextMode));
                    }
                }, 150L);
            } else {
                stopAllPending = false;
            }
        }
    }

    private void beginPmdStartFlow(String mode) {
        if (commandInFlight) {
            setStatus("PMD command is already in flight.");
            return;
        }
        pmdFlowGeneration += 1L;
        pendingPmdMode = mode;
        pmdSettingsAttempts = 0;
        pmdStartAttempts = 0;
        long generation = pmdFlowGeneration;
        if (streamRunning(mode)) {
            setStatus(mode.toUpperCase(Locale.US) + " PMD stream is already active.");
            if (startAllPending && "acc".equals(mode) && !ecgPmdRunning) {
                beginPmdStartFlow("ecg");
            }
            return;
        }
        setStatus("Preparing " + mode.toUpperCase(Locale.US) + " PMD stream.");
        writePmdCommand("probe_pmd", new byte[] {0x00});
        pendingCommandGeneration = generation;
    }

    private void requestPmdSettings(final long generation) {
        if (generation != pmdFlowGeneration || !pmdReady) {
            return;
        }
        if (commandInFlight) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestPmdSettings(generation);
                }
            }, 100L);
            return;
        }
        pmdSettingsAttempts += 1;
        setStatus("Requesting " + pendingPmdMode.toUpperCase(Locale.US)
            + " PMD settings (" + pmdSettingsAttempts + "/" + PMD_SETTINGS_MAX_ATTEMPTS + ").");
        writePmdCommand("get_settings", buildGetSettingsCommand(pendingPmdMode));
        pendingCommandGeneration = generation;
    }

    private void handleSettingsTimeout(long generation) {
        if (generation != pmdFlowGeneration || !"get_settings".equals(pendingCommand)) {
            return;
        }
        if (settingsForMode(pendingPmdMode).hasAny()) {
            startPmdWithCurrentSettings(generation);
            return;
        }
        if (pmdSettingsAttempts < PMD_SETTINGS_MAX_ATTEMPTS) {
            requestPmdSettings(generation);
            return;
        }
        setStatus("PMD settings timed out; starting " + pendingPmdMode.toUpperCase(Locale.US) + " with fallback settings.");
        startPmdWithCurrentSettings(generation);
    }

    private void startPmdWithCurrentSettings(final long generation) {
        if (generation != pmdFlowGeneration || !pmdReady) {
            return;
        }
        if (commandInFlight) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startPmdWithCurrentSettings(generation);
                }
            }, 100L);
            return;
        }
        byte[] payload = buildStartCommand(pendingPmdMode, pmdStartAttempts);
        setStatus("Starting " + pendingPmdMode.toUpperCase(Locale.US) + " PMD.");
        writePmdCommand("start_stream", payload);
        pendingCommandGeneration = generation;
    }

    private void handleStartAckTimeout(long generation) {
        if (generation != pmdFlowGeneration || !"start_stream".equals(pendingCommand) || pmdRunning) {
            return;
        }
        if (tryNextStartCandidate(generation, "timeout")) {
            return;
        }
        setStatus("PMD start ACK timed out for " + pendingPmdMode.toUpperCase(Locale.US) + ".");
        marker("status=pmd-start-timeout mode=" + markerToken(pendingPmdMode));
        appendStatusEvent("pmd-start-timeout-" + pendingPmdMode);
        updateCounters();
    }

    private boolean tryNextStartCandidate(final long generation, String reason) {
        int maxAttempts = "ecg".equals(pendingPmdMode) ? 3 : 1;
        if (pmdStartAttempts + 1 >= maxAttempts) {
            return false;
        }
        pmdStartAttempts += 1;
        marker("status=pmd-start-retry mode=" + markerToken(pendingPmdMode)
            + " attempt=" + pmdStartAttempts + " reason=" + markerToken(reason));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startPmdWithCurrentSettings(generation);
            }
        }, 200L);
        return true;
    }

    private void writePmdCommand(String command, byte[] payload) {
        if (gatt == null || pmdControlCharacteristic == null || payload == null) {
            setStatus("PMD command target is not ready.");
            return;
        }
        if (commandInFlight) {
            setStatus("PMD command is already in flight.");
            return;
        }
        pendingCommand = command;
        pendingCommandGeneration = pmdFlowGeneration;
        try {
            commandInFlight = true;
            if (!writeCharacteristicCompat(
                    gatt,
                    pmdControlCharacteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                commandInFlight = false;
                setStatus("PMD command did not start: " + command);
                marker("status=command-not-started command=" + markerToken(command));
            }
        } catch (SecurityException ex) {
            commandInFlight = false;
            setStatus("BLE write permission is missing.");
            marker("status=error reason=write-security-exception");
        }
    }

    private boolean writeDescriptorCompat(BluetoothGatt targetGatt, BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return targetGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        }
        descriptor.setValue(value);
        return targetGatt.writeDescriptor(descriptor);
    }

    private boolean writeCharacteristicCompat(
        BluetoothGatt targetGatt,
        BluetoothGattCharacteristic characteristic,
        byte[] payload,
        int writeType
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            return targetGatt.writeCharacteristic(characteristic, payload, writeType)
                == BluetoothStatusCodes.SUCCESS;
        }
        characteristic.setValue(payload);
        characteristic.setWriteType(writeType);
        return targetGatt.writeCharacteristic(characteristic);
    }

    private void scheduleBatteryRead(
        final BluetoothGatt callbackGatt,
        final BluetoothGattCharacteristic callbackBatteryCharacteristic
    ) {
        if (!isCurrentConnectedGatt(callbackGatt) || callbackBatteryCharacteristic == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                readBatteryIfIdle(callbackGatt, callbackBatteryCharacteristic);
            }
        }, 1500L);
    }

    private void readBatteryIfIdle(
        BluetoothGatt callbackGatt,
        BluetoothGattCharacteristic callbackBatteryCharacteristic
    ) {
        if (!isCurrentConnectedGatt(callbackGatt)
                || callbackBatteryCharacteristic == null
                || callbackBatteryCharacteristic != batteryCharacteristic
                || commandInFlight) {
            return;
        }
        try {
            if (!callbackGatt.readCharacteristic(callbackBatteryCharacteristic)) {
                marker("status=battery-read-not-started");
            }
        } catch (SecurityException ex) {
            marker("status=error reason=battery-read-security-exception");
        } catch (RuntimeException ex) {
            marker("status=error reason=battery-read-runtime-exception");
        }
    }

    private void handleCharacteristic(
        BluetoothGatt admittedGatt,
        long admittedGeneration,
        BluetoothGattCharacteristic characteristic,
        byte[] value
    ) {
        if (characteristic == null || value == null) {
            synchronized (countersLock) {
                malformedFrames += 1L;
            }
            updateCountersOnUiThread();
            return;
        }
        UUID uuid = characteristic.getUuid();
        try {
            if (HEART_RATE_MEASUREMENT.equals(uuid)) {
                HeartRateReading reading = PolarProtocol.decodeHeartRateMeasurement(value);
                markAutomaticConnectionEvidenceFromLiveCallback(admittedGatt, admittedGeneration);
                synchronized (countersLock) {
                    heartRateEvents += 1L;
                    latestBpm = reading.bpm;
                    rrIntervals += reading.rrIntervalsMs.size();
                }
                long hostTimeNs = System.nanoTime();
                nativeSubmitPolarHeartRateMeasurement(hostTimeNs, reading.bpm);
                for (Float rrIntervalMs : reading.rrIntervalsMs) {
                    if (rrIntervalMs != null) {
                        nativeSubmitPolarRrMeasurement(hostTimeNs, rrIntervalMs.floatValue());
                    }
                }
                appendHrEvent(reading);
            } else if (PMD_CONTROL_POINT.equals(uuid)) {
                ControlRecord record = PolarProtocol.parseControl(value);
                synchronized (countersLock) {
                    controlEvents += 1L;
                }
                appendControlEvent(record);
                handlePmdControlRecord(record);
            } else if (PMD_DATA.equals(uuid)) {
                int measurementType = PolarProtocol.unsigned(value[0]);
                if (measurementType == 0x02) {
                    PmdFrameMetric frame = PolarProtocol.decodeAcc(value);
                    markAutomaticConnectionEvidenceFromLiveCallback(admittedGatt, admittedGeneration);
                    long frameSequenceId;
                    long previousReceiptDeltaNs;
                    synchronized (countersLock) {
                        accFrames += 1L;
                        accSamples += frame.sampleCount;
                        frameSequenceId = accFrames;
                        previousReceiptDeltaNs = lastAccFrameReceiptNs == 0L
                            ? 0L
                            : Math.max(0L, frame.hostTimeNs - lastAccFrameReceiptNs);
                        lastAccFrameReceiptNs = frame.hostTimeNs;
                    }
                    nativeSubmitPolarPmdFrame(
                        measurementType,
                        frameSequenceId,
                        frame.hostTimeNs,
                        frame.sensorTimestampNs,
                        activeAccSampleRateHz,
                        frame.sampleCount,
                        previousReceiptDeltaNs);
                    for (int sampleIndex = 0; sampleIndex < frame.accSamples.size(); sampleIndex++) {
                        AccSample sample = frame.accSamples.get(sampleIndex);
                        long sampleHostTimeNs = sampleTimeNs(
                            frame.hostTimeNs,
                            activeAccSampleRateHz,
                            sampleIndex,
                            frame.sampleCount);
                        long sampleSensorTimeNs = sampleTimeNs(
                            frame.sensorTimestampNs,
                            activeAccSampleRateHz,
                            sampleIndex,
                            frame.sampleCount);
                        nativeSubmitPolarAccMeasurement(
                            sampleHostTimeNs,
                            sampleSensorTimeNs,
                            frame.hostTimeNs,
                            frameSequenceId,
                            sampleIndex,
                            frame.sampleCount,
                            System.nanoTime(),
                            sample.xMg,
                            sample.yMg,
                            sample.zMg);
                    }
                    appendAccEvent(frame);
                    scheduleStreamingStatusWrite();
                } else if (measurementType == 0x00) {
                    PmdFrameMetric frame = PolarProtocol.decodeEcg(value);
                    markAutomaticConnectionEvidenceFromLiveCallback(admittedGatt, admittedGeneration);
                    long frameSequenceId;
                    long previousReceiptDeltaNs;
                    synchronized (countersLock) {
                        ecgFrames += 1L;
                        ecgSamples += frame.sampleCount;
                        frameSequenceId = ecgFrames;
                        previousReceiptDeltaNs = lastEcgFrameReceiptNs == 0L
                            ? 0L
                            : Math.max(0L, frame.hostTimeNs - lastEcgFrameReceiptNs);
                        lastEcgFrameReceiptNs = frame.hostTimeNs;
                    }
                    nativeSubmitPolarPmdFrame(
                        measurementType,
                        frameSequenceId,
                        frame.hostTimeNs,
                        frame.sensorTimestampNs,
                        activeEcgSampleRateHz,
                        frame.sampleCount,
                        previousReceiptDeltaNs);
                    for (int sampleIndex = 0; sampleIndex < frame.ecgSamplesMicrovolts.size(); sampleIndex++) {
                        long sampleHostTimeNs = sampleTimeNs(
                            frame.hostTimeNs,
                            activeEcgSampleRateHz,
                            sampleIndex,
                            frame.sampleCount);
                        long sampleSensorTimeNs = sampleTimeNs(
                            frame.sensorTimestampNs,
                            activeEcgSampleRateHz,
                            sampleIndex,
                            frame.sampleCount);
                        nativeSubmitPolarEcgMeasurement(
                            sampleHostTimeNs,
                            sampleSensorTimeNs,
                            frame.hostTimeNs,
                            frameSequenceId,
                            sampleIndex,
                            frame.sampleCount,
                            System.nanoTime(),
                            frame.ecgSamplesMicrovolts.get(sampleIndex).intValue());
                    }
                    appendEcgEvent(frame);
                    scheduleStreamingStatusWrite();
                }
            }
        } catch (RuntimeException ex) {
            synchronized (countersLock) {
                malformedFrames += 1L;
            }
            marker("status=malformed-frame count=" + malformedFrames);
        }
        updateCountersOnUiThread();
    }

    private void handlePmdControlRecord(ControlRecord record) {
        if (record == null) {
            return;
        }
        if (record.hasSettings()) {
            savePmdSettings(record.measurementType, record.settings);
            marker("status=pmd-settings measurementType=" + record.measurementType
                + " sampleRates=" + markerToken(record.settings.joinSampleRates())
                + " resolutions=" + markerToken(record.settings.joinResolutions())
                + " ranges=" + markerToken(record.settings.joinRanges()));
            if ("get_settings".equals(pendingCommand)
                    && record.measurementType == measurementTypeInt(pendingPmdMode)
                    && record.errorCode == 0) {
                startPmdWithCurrentSettings(pendingCommandGeneration);
            }
            return;
        }
        if (record.opCode == 0x02
                && "start_stream".equals(pendingCommand)
                && record.measurementType == measurementTypeInt(pendingPmdMode)) {
            if (record.errorCode == 0) {
                final String startedMode = pendingPmdMode;
                setStreamRunning(startedMode, true);
                setStatus("PMD stream active: " + activePmdMode.toUpperCase(Locale.US));
                marker("status=pmd-started mode=" + markerToken(startedMode)
                    + " activeModes=" + markerToken(activePmdMode));
                appendStatusEvent("pmd-started-" + startedMode);
                updateCounters();
                if (startAllPending && "acc".equals(startedMode) && !ecgPmdRunning) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            beginPmdStartFlow("ecg");
                        }
                    }, 150L);
                } else if (startAllPending && accPmdRunning && ecgPmdRunning) {
                    startAllPending = false;
                    setStatus("ACC and ECG PMD streams are active in parallel.");
                    marker("status=pmd-all-started activeModes=acc_ecg");
                }
            } else if (!tryNextStartCandidate(pendingCommandGeneration, "err_" + record.errorCode)) {
                setStatus("PMD start rejected for " + pendingPmdMode.toUpperCase(Locale.US)
                    + " error " + record.errorCode + ".");
                marker("status=pmd-start-rejected mode=" + markerToken(pendingPmdMode)
                    + " errorCode=" + record.errorCode);
                appendStatusEvent("pmd-start-rejected-" + pendingPmdMode);
                updateCounters();
            }
            return;
        }
        if (record.opCode == 0x03
                && ("stop_stream_only".equals(pendingCommand) || "stop_before_start".equals(pendingCommand))) {
            setStreamRunning(pendingPmdMode, false);
            updateCounters();
        }
    }

    private void handleBattery(byte[] value) {
        if (value == null || value.length < 1) {
            return;
        }
        synchronized (countersLock) {
            batteryPercent = value[0] & 0xff;
        }
        appendStatusEvent("battery");
        updateCountersOnUiThread();
    }

    private void appendHrEvent(HeartRateReading reading) {
        try {
            JSONArray rr = new JSONArray();
            for (Float value : reading.rrIntervalsMs) {
                rr.put(value.doubleValue());
            }
            JSONObject payload = basePayload(STREAM_HR_RR)
                .put("bpm", reading.bpm)
                .put("rr_intervals_ms", rr)
                .put("rr_interval_count", reading.rrIntervalsMs.size());
            appendStreamEvent(STREAM_HR_RR, payload);
        } catch (Exception ex) {
            marker("status=event-write-failed stream=hr_rr");
        }
    }

    private static native void nativeSubmitPolarAccMeasurement(
        long sampleHostTimeNs,
        long sampleSensorTimeNs,
        long frameHostReceiptTimeNs,
        long frameSequenceId,
        int sampleIndex,
        int sampleCount,
        long jniSubmitTimeNs,
        int xMg,
        int yMg,
        int zMg);

    private static native void nativeSubmitPolarEcgMeasurement(
        long sampleHostTimeNs,
        long sampleSensorTimeNs,
        long frameHostReceiptTimeNs,
        long frameSequenceId,
        int sampleIndex,
        int sampleCount,
        long jniSubmitTimeNs,
        int microvolts);

    private static native void nativeSubmitPolarPmdFrame(
        int measurementType,
        long frameSequenceId,
        long hostReceiptTimeNs,
        long sensorFrameTimeNs,
        int sampleRateHz,
        int sampleCount,
        long previousReceiptDeltaNs);

    private static native void nativeSubmitPolarHeartRateMeasurement(long hostTimeNs, int bpm);

    private static native void nativeSubmitPolarRrMeasurement(
        long hostTimeNs,
        float rrIntervalMs);

    private static native String nativeStartParallelBreathCapture(
        String directory,
        String sessionId,
        long startedAtElapsedRealtimeNs);

    private static native String nativeStopParallelBreathCapture();

    private static native String nativeReadParallelBreathCaptureStatus();

    private static native String nativeSetPolarAccPresentationMode(String mode);

    private static native String nativeReadPolarAccPresentationStatus();

    private static long sampleTimeNs(long frameLastSampleTimeNs, int sampleRateHz, int sampleIndex, int sampleCount) {
        if (frameLastSampleTimeNs <= 0L || sampleRateHz <= 0 || sampleCount <= 0
                || sampleIndex < 0 || sampleIndex >= sampleCount) {
            return frameLastSampleTimeNs;
        }
        long periodNs = Math.max(1L, 1000000000L / sampleRateHz);
        long samplesAfter = sampleCount - 1L - sampleIndex;
        long offsetNs = periodNs > Long.MAX_VALUE / Math.max(1L, samplesAfter)
            ? Long.MAX_VALUE
            : periodNs * samplesAfter;
        return Math.max(1L, frameLastSampleTimeNs - Math.min(frameLastSampleTimeNs - 1L, offsetNs));
    }

    private void appendAccEvent(PmdFrameMetric frame) {
        try {
            JSONObject payload = basePayload(STREAM_ACC)
                .put("sensor_timestamp_ns", frame.sensorTimestampNs)
                .put("frame_sample_count", frame.sampleCount);
            if (!frame.accSamples.isEmpty()) {
                AccSample lastSampleForLowRateEvent = frame.accSamples.get(frame.accSamples.size() - 1);
                payload.put("latest_sample_mg", new JSONObject()
                    .put("x", lastSampleForLowRateEvent.xMg)
                    .put("y", lastSampleForLowRateEvent.yMg)
                    .put("z", lastSampleForLowRateEvent.zMg));
            }
            appendStreamEvent(STREAM_ACC, payload);
        } catch (Exception ex) {
            marker("status=event-write-failed stream=acc");
        }
    }

    private void appendEcgEvent(PmdFrameMetric frame) {
        try {
            JSONObject payload = basePayload(STREAM_ECG)
                .put("sensor_timestamp_ns", frame.sensorTimestampNs)
                .put("frame_sample_count", frame.sampleCount);
            if (!frame.ecgSamplesMicrovolts.isEmpty()) {
                payload.put(
                    "latest_sample_microvolts",
                    frame.ecgSamplesMicrovolts.get(frame.ecgSamplesMicrovolts.size() - 1).intValue());
            }
            appendStreamEvent(STREAM_ECG, payload);
        } catch (Exception ex) {
            marker("status=event-write-failed stream=ecg");
        }
    }

    private void appendControlEvent(ControlRecord record) {
        try {
            JSONObject payload = basePayload(STREAM_DEVICE_STATUS)
                .put("event_kind", "pmd_control_response")
                .put("op_code", record.opCode)
                .put("measurement_type", record.measurementType)
                .put("error_code", record.errorCode);
            if (record.hasSettings()) {
                payload.put("settings", record.settings.toJson());
            }
            appendStreamEvent(STREAM_DEVICE_STATUS, payload);
        } catch (Exception ex) {
            marker("status=event-write-failed stream=device_status");
        }
    }

    private void appendStatusEvent(String state) {
        try {
            JSONObject payload = basePayload(STREAM_DEVICE_STATUS)
                .put("event_kind", "panel_status")
                .put("state", state)
                .put("connected_device", connectedLabel)
                .put("active_pmd_mode", activePmdMode)
                .put("battery_percent", batteryPercent);
            appendStreamEvent(STREAM_DEVICE_STATUS, payload);
        } catch (Exception ex) {
            marker("status=event-write-failed stream=device_status");
        }
    }

    private JSONObject basePayload(String streamId) throws Exception {
        return new JSONObject()
            .put("stream_id", streamId)
            .put("stream", streamId)
            .put("source", "rusty_quest_native_polar_panel")
            .put("device", connectedLabel);
    }

    private void appendStreamEvent(final String streamId, JSONObject payload) throws Exception {
        if (closing) return;
        long nextSequence;
        synchronized (countersLock) {
            sequenceId += 1L;
            nextSequence = sequenceId;
        }
        long nowNs = System.currentTimeMillis() * 1000000L;
        final JSONObject event = new JSONObject()
            .put("type", "stream_event")
            .put("schema", "rusty.manifold.stream.event.v1")
            .put("stream", streamId)
            .put("stream_id", streamId)
            .put("sequence_id", nextSequence)
            .put("payload", payload)
            .put("transport_time_unix_ns", nowNs)
            .put("transport_receive_time_unix_ns", nowNs)
            .put("time_utc", Instant.now().toString());
        final String line = event.toString();
        eventWriter.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FileOutputStream out = appContext.openFileOutput(STREAM_EVENTS_FILE, Context.MODE_APPEND);
                    try {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                        out.write('\n');
                    } finally {
                        out.close();
                    }
                    synchronized (countersLock) {
                        streamEventsWritten += 1L;
                    }
                    if (!closing) handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Host currentHost = host;
                                if (currentHost != null) {
                                    currentHost.onPolarStreamEvent(event);
                                }
                            } catch (RuntimeException ignored) {
                            }
                        }
                    });
                } catch (Exception error) {
                    marker("status=event-write-failed stream=" + markerToken(streamId));
                }
            }
        });
    }

    private void writeStatus(String state, String detail) {
        if (closing) return;
        final String capturedState = state == null ? "unknown" : state;
        final String capturedDetail = detail == null ? "" : detail;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    writeStatus(capturedState, capturedDetail);
                }
            });
            return;
        }
        final StatusPersistenceSnapshot snapshot = captureStatusSnapshotOnOwner(
            capturedState,
            capturedDetail
        );
        statusWriter.execute(new Runnable() {
            @Override
            public void run() {
                persistStatusOnBackgroundThread(snapshot);
            }
        });
    }

    private StatusPersistenceSnapshot captureStatusSnapshotOnOwner(String state, String detail) {
        long accFrameCount;
        long accSampleCount;
        long ecgFrameCount;
        long ecgSampleCount;
        long hrCount;
        long rrCount;
        long accReceiptDeltaNs;
        long ecgReceiptDeltaNs;
        synchronized (countersLock) {
            accFrameCount = accFrames;
            accSampleCount = accSamples;
            ecgFrameCount = ecgFrames;
            ecgSampleCount = ecgSamples;
            hrCount = heartRateEvents;
            rrCount = rrIntervals;
            accReceiptDeltaNs = lastAccFrameReceiptNs;
            ecgReceiptDeltaNs = lastEcgFrameReceiptNs;
        }
        long observedAtNs = System.nanoTime();
        boolean accDataReceiving = pmdDataReceiving(connected, observedAtNs, accReceiptDeltaNs);
        boolean ecgDataReceiving = pmdDataReceiving(connected, observedAtNs, ecgReceiptDeltaNs);
        boolean accEffectiveRunning = accPmdRunning || accDataReceiving;
        boolean ecgEffectiveRunning = ecgPmdRunning || ecgDataReceiving;
        boolean pmdEffectiveRunning = accEffectiveRunning || ecgEffectiveRunning;
        String pmdEffectiveMode = accEffectiveRunning && ecgEffectiveRunning
            ? "acc+ecg"
            : (accEffectiveRunning ? "acc" : (ecgEffectiveRunning ? "ecg" : "none"));
        String pmdStateSource = (accDataReceiving || ecgDataReceiving)
            ? ((accPmdRunning || ecgPmdRunning) ? "control-ack-and-fresh-data" : "fresh-data-fallback")
            : ((accPmdRunning || ecgPmdRunning) ? "control-ack-awaiting-data" : "none");
        JSONObject presentation = new JSONObject();
        JSONObject capture = new JSONObject();
        try {
            presentation = new JSONObject(nativeReadPolarAccPresentationStatus());
            accPresentationMode = presentation.optString("mode", accPresentationMode);
        } catch (Exception ignored) {
        }
        try {
            capture = new JSONObject(nativeReadParallelBreathCaptureStatus());
        } catch (Exception ignored) {
        }
        LinkedHashMap<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("schema", "rusty.quest.native_renderer.polar_sensor_status.v2");
        fields.put("status", state);
        fields.put("detail", detail);
        fields.put("scanning", scanning);
        fields.put("candidate_count", devices.size());
        fields.put("scan_raw_callback_count", scanRawCallbackCount);
        fields.put("scan_rejected_advertisement_count", scanRejectedAdvertisementCount);
        fields.put("scan_last_callback_at_unix_ms", scanLastCallbackAtUnixMs);
        fields.put("selected_device_instance_id", selectedDeviceInstanceId());
        fields.put("connected_device_instance_id", connectedDeviceInstanceId);
        fields.put("connected", connected);
        fields.put("pmd_ready", pmdReady);
        fields.put("pmd_running", pmdEffectiveRunning);
        fields.put("pmd_mode", pmdEffectiveMode);
        fields.put("pmd_state_source", pmdStateSource);
        fields.put("pmd_command_running", pmdRunning);
        fields.put("pmd_command_mode", activePmdMode);
        fields.put("acc_pmd_running", accEffectiveRunning);
        fields.put("ecg_pmd_running", ecgEffectiveRunning);
        fields.put("acc_pmd_command_running", accPmdRunning);
        fields.put("ecg_pmd_command_running", ecgPmdRunning);
        fields.put("pmd_data_receiving", accDataReceiving || ecgDataReceiving);
        fields.put("acc_data_receiving", accDataReceiving);
        fields.put("ecg_data_receiving", ecgDataReceiving);
        fields.put("acc_sample_rate_hz", activeAccSampleRateHz);
        fields.put("ecg_sample_rate_hz", activeEcgSampleRateHz);
        fields.put("acc_frames", accFrameCount);
        fields.put("acc_samples", accSampleCount);
        fields.put("ecg_frames", ecgFrameCount);
        fields.put("ecg_samples", ecgSampleCount);
        fields.put("acc_last_receipt_time_ns", accReceiptDeltaNs);
        fields.put("ecg_last_receipt_time_ns", ecgReceiptDeltaNs);
        fields.put("heart_rate_events_observed", hrCount);
        fields.put("rr_intervals_observed", rrCount);
        fields.put("rr_consumed_by_breath", false);
        fields.put("acc_direct_same_process", true);
        fields.put("acc_presentation", accPresentationMode);
        fields.put("acc_presentation_delay_ms", presentation.optLong("timestamp_faithful_delay_ms", 0L));
        fields.put("acc_smoothing_time_constant_ms", presentation.optLong("low_latency_smoothing_time_constant_ms", 0L));
        fields.put("stream_events_file", STREAM_EVENTS_FILE);
        fields.put("updated_at_unix_ms", System.currentTimeMillis());
        return new StatusPersistenceSnapshot(
            fields,
            PolarBleRuntimeSupport.statusJson(appContext).toString(),
            presentation.toString(),
            capture.toString()
        );
    }

    private void persistStatusOnBackgroundThread(StatusPersistenceSnapshot snapshot) {
        PolarStatusPersistenceThreadPolicy.requireBackgroundThread(
            Looper.myLooper() == Looper.getMainLooper()
        );
        try {
            JSONObject body = snapshot.toJson();
            FileOutputStream out = appContext.openFileOutput(STATUS_FILE, Context.MODE_PRIVATE);
            try {
                out.write(body.toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class StatusPersistenceSnapshot {
        private final Map<String, Object> fields;
        private final String bleRuntimeJson;
        private final String presentationJson;
        private final String captureJson;

        StatusPersistenceSnapshot(
            Map<String, Object> fields,
            String bleRuntimeJson,
            String presentationJson,
            String captureJson
        ) {
            this.fields = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(fields)
            );
            this.bleRuntimeJson = bleRuntimeJson == null ? "{}" : bleRuntimeJson;
            this.presentationJson = presentationJson == null ? "{}" : presentationJson;
            this.captureJson = captureJson == null ? "{}" : captureJson;
        }

        JSONObject toJson() throws Exception {
            JSONObject body = new JSONObject();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                body.put(entry.getKey(), entry.getValue());
            }
            body.put("ble_runtime", new JSONObject(bleRuntimeJson));
            body.put("acc_presentation_status", new JSONObject(presentationJson));
            body.put("capture", new JSONObject(captureJson));
            return body;
        }
    }

    private static boolean pmdDataReceiving(boolean linkConnected, long observedAtNs, long lastReceiptNs) {
        if (!linkConnected || observedAtNs <= 0L || lastReceiptNs <= 0L || observedAtNs < lastReceiptNs) {
            return false;
        }
        return observedAtNs - lastReceiptNs <= PMD_DATA_RECEIVING_WINDOW_NS;
    }

    private void scheduleStreamingStatusWrite() {
        if (!streamingStatusWriteScheduled.compareAndSet(false, true)) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                streamingStatusWriteScheduled.set(false);
                writeStatus(statusState, statusDetail);
            }
        }, STREAMING_STATUS_WRITE_DELAY_MS);
    }

    private void updateCountersOnUiThread() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateCounters();
            }
        });
    }

    private void updateCounters() {
        long hr;
        long rr;
        long accFrameCount;
        long accSampleCount;
        long ecgFrameCount;
        long ecgSampleCount;
        long controlCount;
        long malformed;
        long eventCount;
        int bpm;
        int battery;
        synchronized (countersLock) {
            hr = heartRateEvents;
            rr = rrIntervals;
            accFrameCount = accFrames;
            accSampleCount = accSamples;
            ecgFrameCount = ecgFrames;
            ecgSampleCount = ecgSamples;
            controlCount = controlEvents;
            malformed = malformedFrames;
            eventCount = streamEventsWritten;
            bpm = latestBpm;
            battery = batteryPercent;
        }
        if (selectedDevice != null) {
            selectedDevice.setText("Selected: " + selectedDeviceLabel());
        }
        if (linkStatus != null) {
            String batteryText = battery >= 0 ? Integer.toString(battery) + "%" : "unknown";
            linkStatus.setText("Link: " + connectedLabel
                + " | PMD: " + activePmdMode
                + " | capture: " + ("none".equals(captureSessionId) ? "stopped" : captureSessionId)
                + " | battery: " + batteryText
                + " | control: " + controlCount
                + " | malformed: " + malformed
                + " | events: " + eventCount);
        }
        if (hrStatus != null) {
            hrStatus.setText("HR/RR: " + hr + " heart-rate events, " + rr + " RR intervals, latest " + bpm + " bpm");
        }
        if (accStatus != null) {
            String presentation = "low-latency-smooth".equals(accPresentationMode)
                ? "low-latency smooth at render cadence (120 ms time constant)"
                : "timestamp-faithful (180 ms sample-time buffer)";
            accStatus.setText("ACC: " + accFrameCount + " PMD batches, " + accSampleCount
                + " decoded samples @ " + activeAccSampleRateHz + " Hz; " + presentation);
        }
        if (ecgStatus != null) {
            ecgStatus.setText("ECG: " + ecgFrameCount + " PMD batches, " + ecgSampleCount
                + " decoded samples @ " + activeEcgSampleRateHz + " Hz");
        }
    }

    private String selectedDeviceLabel() {
        int index = selectedDeviceIndex();
        if (index >= 0 && index < devices.size()) {
            return devices.get(index).label();
        }
        return "none";
    }

    private void addOrUpdateDevice(DeviceEntry entry) {
        if ("scanning".equals(automaticConnectionState)) {
            automaticConnectionUpdatedAtUnixMs = System.currentTimeMillis();
        }
        String selectedBefore = selectedDeviceInstanceId();
        for (int i = 0; i < devices.size(); i++) {
            DeviceEntry existing = devices.get(i);
            if (existing.sameDevice(entry)) {
                devices.set(i, entry);
                Collections.sort(devices);
                restoreOrChooseHeadlessCandidate(selectedBefore);
                updateDeviceAdapter();
                return;
            }
        }
        devices.add(entry);
        Collections.sort(devices);
        restoreOrChooseHeadlessCandidate(selectedBefore);
        updateDeviceAdapter();
        setStatusState("candidate-found", "A compatible Polar candidate was found.");
        marker("status=device-found deviceInstanceId=" + markerToken(entry.instanceId())
            + " matchScore=" + entry.matchScore + " rawDeviceIdentifierLogged=false");
    }

    private void updateDeviceAdapter() {
        if (deviceAdapter == null) {
            return;
        }
        deviceAdapter.clear();
        for (DeviceEntry device : devices) {
            deviceAdapter.add(device.label());
        }
        deviceAdapter.notifyDataSetChanged();
        if (deviceSpinner != null && selectedDeviceIndex >= 0 && selectedDeviceIndex < devices.size()) {
            deviceSpinner.setSelection(selectedDeviceIndex);
        }
        updateCounters();
    }

    private void stopScan() {
        BluetoothLeScanner currentScanner = scanner;
        ScanCallback currentCallback = activeScanCallback;
        boolean wasScanning = scanning;
        scanner = null;
        activeScanCallback = null;
        scanning = false;
        scanGeneration += 1L;
        if (currentScanner != null && wasScanning && currentCallback != null) {
            try {
                currentScanner.stopScan(currentCallback);
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        updateScanUi();
    }

    private void closeGatt() {
        BluetoothGatt currentGatt;
        synchronized (connectionIdentityLock) {
            currentGatt = gatt;
            gatt = null;
            connected = false;
        }
        pmdFlowGeneration += 1L;
        connectedLabel = "none";
        connectedDeviceInstanceId = "none";
        pendingConnectionLabel = "none";
        pendingConnectionDeviceInstanceId = "none";
        if (currentGatt != null) {
            try {
                currentGatt.disconnect();
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
            try {
                currentGatt.close();
            } catch (RuntimeException ignored) {
            }
        }
        descriptorTasks.clear();
        descriptorsStarted = false;
        commandInFlight = false;
        pendingCommand = "none";
        pendingCommandGeneration = pmdFlowGeneration;
        pmdReady = false;
        accPmdRunning = false;
        ecgPmdRunning = false;
        setStreamRunning("acc", false);
        startAllPending = false;
        stopAllPending = false;
        batteryCharacteristic = null;
        hrCharacteristic = null;
        pmdControlCharacteristic = null;
        pmdDataCharacteristic = null;
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private void openLocationSettings() {
        Activity currentActivity = activity;
        if (currentActivity == null) {
            setStatusState(
                "location-settings-unavailable",
                "Attach the Polar page before opening headset location settings."
            );
            return;
        }
        locationSettingsPromptPending = true;
        NativeRendererSelfKioskApplication.beginSystemPrompt(currentActivity);
        try {
            currentActivity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            setStatusState(
                "location-settings-opened",
                "Enable location services in headset settings, return here, then press Scan."
            );
            marker("status=location-settings-opened");
        } catch (RuntimeException primaryError) {
            try {
                currentActivity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                setStatusState(
                    "location-settings-opened",
                    "Open Location in headset settings, enable it, return here, then press Scan."
                );
                marker("status=settings-opened fallback=general-settings");
            } catch (RuntimeException fallbackError) {
                locationSettingsPromptPending = false;
                NativeRendererSelfKioskApplication.endSystemPrompt(currentActivity);
                setStatusState(
                    "location-settings-unavailable",
                    "Headset location settings could not be opened. Enable location services manually."
                );
                marker("status=error reason=location-settings-unavailable");
            }
        }
    }

    private boolean ensurePermissions(int pendingAction) {
        if (hasRequiredPermissions()) {
            return true;
        }
        pendingBleAction = pendingAction;
        if (activity == null) {
            pendingBleAction = PENDING_BLE_NONE;
            setStatusState("permission-required", "BLE/location permission is required; attach the panel to respond.");
            marker("status=permission-required origin=headless pendingAction=" + pendingAction);
            return false;
        }
        NativeRendererSelfKioskApplication.beginSystemPrompt(activity);
        PolarBleRuntimeSupport.ensureReady(activity, REQUEST_BLE_PERMISSIONS);
        String missing = PolarBleRuntimeSupport.join(
            PolarBleRuntimeSupport.missingPermissions(appContext),
            ","
        );
        setStatusState("permission-required", "Requesting BLE/location permissions.");
        marker("status=permission-requested missing=" + markerToken(missing)
            + " pendingAction=" + pendingAction);
        return false;
    }

    private boolean hasRequiredPermissions() {
        return PolarBleRuntimeSupport.hasRequiredPermissions(appContext);
    }

    private void resumePendingBleAction() {
        int action = pendingBleAction;
        pendingBleAction = PENDING_BLE_NONE;
        if (action == PENDING_BLE_SCAN) {
            startScan();
        } else if (action == PENDING_BLE_CONNECT) {
            connectSelected();
        } else if (action == PENDING_BLE_START_PMD) {
            startSelectedPmd();
        }
    }

    private String selectedPmdMode() {
        if (pmdSpinner != null) {
            int index = pmdSpinner.getSelectedItemPosition();
            if (index == 0 || index == 1) {
                selectedPmdMode = index == 1 ? "ecg" : "acc";
            }
        }
        return selectedPmdMode;
    }

    private void setSelectedPmdMode(String mode) {
        selectedPmdMode = "ecg".equals(mode) ? "ecg" : "acc";
        if (pmdSpinner != null) {
            pmdSpinner.setSelection("ecg".equals(selectedPmdMode) ? 1 : 0);
        }
    }

    private byte[] buildGetSettingsCommand(String mode) {
        return new byte[] {0x01, measurementType(mode)};
    }

    private byte[] buildStopCommand(String mode) {
        return new byte[] {0x03, measurementType(mode)};
    }

    private byte[] buildStartCommand(String mode, int attempt) {
        if ("ecg".equals(mode)) {
            PmdSettings settings = settingsForMode(mode);
            int sampleRate = settings.hasAny() && attempt == 0
                ? settings.chooseLowestSampleRate(130)
                : (attempt >= 2 ? 256 : 130);
            activeEcgSampleRateHz = sampleRate;
            int resolution = settings.hasAny() && attempt == 0
                ? settings.chooseLowestResolution(14)
                : 14;
            return buildPmdStartRequest((byte) 0x00, sampleRate, resolution, null);
        }
        PmdSettings settings = settingsForMode(mode);
        int sampleRate = settings.chooseClosestSampleRate(200);
        activeAccSampleRateHz = sampleRate;
        int resolution = settings.chooseClosestResolution(16);
        int rangeG = settings.chooseClosestRange(8);
        return buildPmdStartRequest((byte) 0x02, sampleRate, resolution, Integer.valueOf(rangeG));
    }

    private byte[] buildPmdStartRequest(byte measurementType, int sampleRate, int resolution, Integer rangeG) {
        ArrayList<Byte> request = new ArrayList<Byte>();
        request.add(Byte.valueOf((byte) 0x02));
        request.add(Byte.valueOf(measurementType));
        if (measurementType == 0x02 && rangeG != null) {
            addPmdSetting(request, (byte) 0x02, clampInt(rangeG.intValue(), 1, 16));
        }
        addPmdSetting(request, (byte) 0x00, clampInt(sampleRate, 1, 2000));
        addPmdSetting(request, (byte) 0x01, clampInt(resolution, 1, 32));
        byte[] bytes = new byte[request.size()];
        for (int index = 0; index < request.size(); index++) {
            bytes[index] = request.get(index).byteValue();
        }
        return bytes;
    }

    private void addPmdSetting(ArrayList<Byte> request, byte type, int value) {
        request.add(Byte.valueOf(type));
        request.add(Byte.valueOf((byte) 0x01));
        request.add(Byte.valueOf((byte) (value & 0xff)));
        request.add(Byte.valueOf((byte) ((value >> 8) & 0xff)));
    }

    private void savePmdSettings(int measurementType, PmdSettings settings) {
        if (measurementType == 0x00) {
            ecgSettings = settings == null ? PmdSettings.EMPTY : settings;
        } else if (measurementType == 0x02) {
            accSettings = settings == null ? PmdSettings.EMPTY : settings;
        }
    }

    private PmdSettings settingsForMode(String mode) {
        return "ecg".equals(mode) ? ecgSettings : accSettings;
    }

    private byte measurementType(String mode) {
        return "ecg".equals(mode) ? (byte) 0x00 : (byte) 0x02;
    }

    private int measurementTypeInt(String mode) {
        return measurementType(mode) & 0xff;
    }

    private String safeName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (SecurityException ex) {
            return "";
        }
    }

    private String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException ex) {
            return "";
        }
    }

    private static boolean scanRecordHasService(
            android.bluetooth.le.ScanRecord record,
            UUID expectedService) {
        if (record == null) {
            return false;
        }
        if (parcelUuidListHasService(record.getServiceUuids(), expectedService)) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && parcelUuidListHasService(record.getServiceSolicitationUuids(), expectedService);
    }

    private static boolean parcelUuidListHasService(
            List<ParcelUuid> parcelUuids,
            UUID expectedService) {
        if (parcelUuids == null) {
            return false;
        }
        for (ParcelUuid parcelUuid : parcelUuids) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) {
                continue;
            }
            UUID uuid = parcelUuid.getUuid();
            if (expectedService.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private void setStatus(String message) {
        if (status != null) {
            status.setText(message);
        }
    }

    private void updateScanUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!closing) handler.post(new Runnable() {
                @Override public void run() { updateScanUi(); }
            });
            return;
        }
        if (closing) return;
        if (scanButton != null) {
            scanButton.setText(scanning ? "Scanning…" : "Scan");
            scanButton.setEnabled(!scanning);
        }
        if (status != null) {
            status.setTextColor(scanning ? PANEL_ACCENT : PANEL_MUTED);
        }
    }

    private void markAutomaticConnectionEvidenceFromLiveCallback(
        BluetoothGatt admittedGatt,
        long admittedGeneration
    ) {
        synchronized (connectionIdentityLock) {
            if (!PolarAutoConnectionPolicy.mayPublishLiveEvidence(
                    admittedGatt,
                    gatt,
                    admittedGeneration,
                    autoConnectionGeneration,
                    connected,
                    closing)) {
                return;
            }
            automaticConnectionUpdatedAtUnixMs = System.currentTimeMillis();
            automaticConnectionEvidenceGeneration = admittedGeneration;
        }
    }

    private long currentAutoConnectionGeneration() {
        synchronized (connectionIdentityLock) {
            return autoConnectionGeneration;
        }
    }

    private long currentAutomaticConnectionEvidenceGeneration() {
        synchronized (connectionIdentityLock) {
            return automaticConnectionEvidenceGeneration;
        }
    }

    private void ensureAutoConnectionGeneration() {
        synchronized (connectionIdentityLock) {
            if (autoConnectionGeneration <= 0L) {
                autoConnectionGeneration = 1L;
            }
        }
    }

    private void advanceAutoConnectionGeneration() {
        synchronized (connectionIdentityLock) {
            autoConnectionGeneration += 1L;
        }
    }

    private void setStatusState(String state, String detail) {
        statusState = state == null ? "unknown" : state;
        statusDetail = detail == null ? "" : detail;
        statusUpdatedAtUnixMs = System.currentTimeMillis();
        setStatus(statusDetail);
        updateScanUi();
        writeStatus(statusState, statusDetail);
    }

    private String selectedDeviceInstanceId() {
        int index = selectedDeviceIndex();
        if (index >= 0 && index < devices.size()) {
            return devices.get(index).instanceId();
        }
        return "none";
    }

    private int selectedDeviceIndex() {
        if (deviceSpinner != null) {
            int uiIndex = deviceSpinner.getSelectedItemPosition();
            if (uiIndex >= 0 && uiIndex < devices.size()) {
                selectedDeviceIndex = uiIndex;
            }
        }
        return selectedDeviceIndex;
    }

    private void restoreOrChooseHeadlessCandidate(String selectedBefore) {
        selectedDeviceIndex = -1;
        if (selectedBefore != null && !"none".equals(selectedBefore)) {
            for (int index = 0; index < devices.size(); index++) {
                if (selectedBefore.equals(devices.get(index).instanceId())) {
                    selectedDeviceIndex = index;
                    return;
                }
            }
        }
        if (devices.size() == 1) {
            selectedDeviceIndex = 0;
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 17, PANEL_FG);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(3));
        background.setStroke(dp(1), Color.rgb(80, 86, 98));
        background.setColor(PANEL_SURFACE);
        button.setTextColor(PANEL_FG);
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams rowButtonParams() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), dp(6), dp(4), dp(6));
        return params;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void marker(String detail) {
        Log.i(TAG, MARKER_PREFIX + " channel=" + CHANNEL + " " + sanitize(detail));
    }

    private static String markerToken(String value) {
        String sanitized = sanitize(value == null ? "" : value.trim())
            .replace(' ', '_')
            .replace(',', '_')
            .replace(';', '_');
        return sanitized.isEmpty() ? "none" : sanitized;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\0', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('"', '\'');
    }

    private static String pseudonymousDeviceInstanceId(String address, String name) {
        String identity = address == null || address.trim().isEmpty() ? name : address;
        if (identity == null || identity.trim().isEmpty()) {
            return "polar-unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.trim().toLowerCase(Locale.US).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("polar-");
            for (int index = 0; index < 6; index++) {
                result.append(String.format(Locale.US, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "polar-unavailable";
        }
    }

    private static final class DeviceEntry implements Comparable<DeviceEntry> {
        final BluetoothDevice device;
        final String name;
        final String address;
        final int rssi;
        final boolean hasHeartRateService;
        final boolean hasPmdService;
        final int matchScore;

        DeviceEntry(
                BluetoothDevice device,
                String name,
                String address,
                int rssi,
                boolean hasHeartRateService,
                boolean hasPmdService) {
            this.device = device;
            this.name = name == null ? "" : name.trim();
            this.address = address == null ? "" : address.trim();
            this.rssi = rssi;
            this.hasHeartRateService = hasHeartRateService;
            this.hasPmdService = hasPmdService;
            int score = Math.max(-100, Math.min(-20, rssi)) + 100;
            if (looksLikePolar()) {
                score += 80;
            }
            if (hasHeartRateService) {
                score += 100;
            }
            if (hasPmdService) {
                score += 200;
            }
            this.matchScore = score;
        }

        boolean looksLikePolar() {
            String lower = name.toLowerCase(Locale.US);
            return lower.contains("polar") || lower.contains("h10") || lower.contains("h9");
        }

        boolean sameDevice(DeviceEntry other) {
            if (!address.isEmpty() && !other.address.isEmpty()) {
                return address.equalsIgnoreCase(other.address);
            }
            return label().equals(other.label());
        }

        String instanceId() {
            return pseudonymousDeviceInstanceId(address, name);
        }

        @Override
        public int compareTo(DeviceEntry other) {
            int scoreOrder = Integer.compare(other.matchScore, matchScore);
            if (scoreOrder != 0) {
                return scoreOrder;
            }
            return instanceId().compareTo(other.instanceId());
        }

        String label() {
            String displayName = name.isEmpty() ? "Polar device" : name;
            String displayAddress = address.isEmpty() ? "unknown" : address;
            return displayName + " (" + displayAddress + ", rssi " + rssi + ")";
        }
    }

    private static final class DescriptorTask {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;

        DescriptorTask(BluetoothGattCharacteristic characteristic, byte[] value) {
            this.characteristic = characteristic;
            this.value = value;
        }
    }

    private static final class HeartRateReading {
        final int bpm;
        final List<Float> rrIntervalsMs;

        HeartRateReading(int bpm, List<Float> rrIntervalsMs) {
            this.bpm = bpm;
            this.rrIntervalsMs = rrIntervalsMs;
        }
    }

    private static final class ControlRecord {
        final int opCode;
        final int measurementType;
        final int errorCode;
        final PmdSettings settings;

        ControlRecord(int opCode, int measurementType, int errorCode) {
            this(opCode, measurementType, errorCode, PmdSettings.EMPTY);
        }

        ControlRecord(int opCode, int measurementType, int errorCode, PmdSettings settings) {
            this.opCode = opCode;
            this.measurementType = measurementType;
            this.errorCode = errorCode;
            this.settings = settings == null ? PmdSettings.EMPTY : settings;
        }

        boolean hasSettings() {
            return settings.hasAny();
        }
    }

    private static final class PmdSettings {
        static final PmdSettings EMPTY = new PmdSettings(new int[0], new int[0], new int[0]);

        final int[] sampleRates;
        final int[] resolutions;
        final int[] ranges;

        PmdSettings(int[] sampleRates, int[] resolutions, int[] ranges) {
            this.sampleRates = sampleRates == null ? new int[0] : sampleRates;
            this.resolutions = resolutions == null ? new int[0] : resolutions;
            this.ranges = ranges == null ? new int[0] : ranges;
        }

        boolean hasAny() {
            return sampleRates.length > 0 || resolutions.length > 0 || ranges.length > 0;
        }

        int chooseLowestSampleRate(int fallback) {
            return chooseLowest(sampleRates, fallback);
        }

        int chooseLowestResolution(int fallback) {
            return chooseLowest(resolutions, fallback);
        }

        int chooseClosestSampleRate(int fallback) {
            return chooseClosest(sampleRates, fallback);
        }

        int chooseClosestResolution(int fallback) {
            return chooseClosest(resolutions, fallback);
        }

        int chooseClosestRange(int fallback) {
            return chooseClosest(ranges, fallback);
        }

        String joinSampleRates() {
            return joinInts(sampleRates);
        }

        String joinResolutions() {
            return joinInts(resolutions);
        }

        String joinRanges() {
            return joinInts(ranges);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("sample_rates_hz", toJsonArray(sampleRates))
                .put("resolutions_bits", toJsonArray(resolutions))
                .put("ranges_g", toJsonArray(ranges));
        }

        static PmdSettings parse(byte[] data, int offset) {
            if (data == null || data.length <= offset) {
                return EMPTY;
            }
            ArrayList<Integer> sampleRates = new ArrayList<Integer>();
            ArrayList<Integer> resolutions = new ArrayList<Integer>();
            ArrayList<Integer> ranges = new ArrayList<Integer>();
            int index = offset;
            while (index + 1 < data.length) {
                int settingType = PolarProtocol.unsigned(data[index++]);
                int count = PolarProtocol.unsigned(data[index++]);
                int bytesNeeded = count * 2;
                if (index + bytesNeeded > data.length) {
                    break;
                }
                for (int item = 0; item < count; item++) {
                    int value = PolarProtocol.readUInt16(data, index);
                    index += 2;
                    if (settingType == 0x00) {
                        sampleRates.add(Integer.valueOf(value));
                    } else if (settingType == 0x01) {
                        resolutions.add(Integer.valueOf(value));
                    } else if (settingType == 0x02) {
                        ranges.add(Integer.valueOf(value));
                    }
                }
            }
            PmdSettings settings = new PmdSettings(
                toIntArray(sampleRates),
                toIntArray(resolutions),
                toIntArray(ranges));
            return settings.hasAny() ? settings : EMPTY;
        }

        private static int chooseLowest(int[] values, int fallback) {
            if (values.length == 0) {
                return fallback;
            }
            int best = values[0];
            for (int index = 1; index < values.length; index++) {
                if (values[index] < best) {
                    best = values[index];
                }
            }
            return best;
        }

        private static int chooseClosest(int[] values, int fallback) {
            if (values.length == 0) {
                return fallback;
            }
            int best = values[0];
            int bestScore = Math.abs(best - fallback);
            for (int index = 1; index < values.length; index++) {
                int candidate = values[index];
                int score = Math.abs(candidate - fallback);
                if (score < bestScore || (score == bestScore && candidate > best)) {
                    best = candidate;
                    bestScore = score;
                }
            }
            return best;
        }

        private static int[] toIntArray(ArrayList<Integer> values) {
            int[] result = new int[values.size()];
            for (int index = 0; index < values.size(); index++) {
                result[index] = values.get(index).intValue();
            }
            return result;
        }

        private static JSONArray toJsonArray(int[] values) {
            JSONArray array = new JSONArray();
            for (int value : values) {
                array.put(value);
            }
            return array;
        }

        private static String joinInts(int[] values) {
            if (values.length == 0) {
                return "none";
            }
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    builder.append('_');
                }
                builder.append(values[index]);
            }
            return builder.toString();
        }
    }

    private static final class PmdFrameMetric {
        final long hostTimeNs;
        final long sensorTimestampNs;
        final int sampleCount;
        final List<AccSample> accSamples;
        final List<Integer> ecgSamplesMicrovolts;

        PmdFrameMetric(long hostTimeNs, long sensorTimestampNs, int sampleCount, List<AccSample> accSamples) {
            this(hostTimeNs, sensorTimestampNs, sampleCount, accSamples, new ArrayList<Integer>());
        }

        PmdFrameMetric(
            long hostTimeNs,
            long sensorTimestampNs,
            int sampleCount,
            List<AccSample> accSamples,
            List<Integer> ecgSamplesMicrovolts
        ) {
            this.hostTimeNs = hostTimeNs;
            this.sensorTimestampNs = sensorTimestampNs;
            this.sampleCount = sampleCount;
            this.accSamples = accSamples;
            this.ecgSamplesMicrovolts = ecgSamplesMicrovolts;
        }
    }

    private static final class AccSample {
        final int xMg;
        final int yMg;
        final int zMg;

        AccSample(int xMg, int yMg, int zMg) {
            this.xMg = xMg;
            this.yMg = yMg;
            this.zMg = zMg;
        }
    }

    private static final class PolarProtocol {
        static HeartRateReading decodeHeartRateMeasurement(byte[] data) {
            if (data.length < 2) {
                throw new IllegalArgumentException("short heart-rate payload");
            }
            int flags = unsigned(data[0]);
            int offset = 1;
            int bpm;
            if ((flags & 0x01) != 0) {
                bpm = readUInt16(data, offset);
                offset += 2;
            } else {
                bpm = unsigned(data[offset]);
                offset += 1;
            }
            if ((flags & 0x08) != 0) {
                offset += 2;
            }
            List<Float> rr = new ArrayList<Float>();
            if ((flags & 0x10) != 0) {
                while (offset + 1 < data.length) {
                    rr.add(readUInt16(data, offset) * 1000.0f / 1024.0f);
                    offset += 2;
                }
            }
            return new HeartRateReading(bpm, rr);
        }

        static ControlRecord parseControl(byte[] data) {
            if (data.length < 4 || unsigned(data[0]) != 0xf0) {
                throw new IllegalArgumentException("bad control response");
            }
            int opCode = unsigned(data[1]);
            int measurementType = unsigned(data[2]);
            int errorCode = unsigned(data[3]);
            PmdSettings settings = PmdSettings.EMPTY;
            if (opCode == 0x01 && errorCode == 0) {
                settings = PmdSettings.parse(data, 4);
                if (!settings.hasAny()) {
                    settings = PmdSettings.parse(data, 5);
                }
            }
            return new ControlRecord(opCode, measurementType, errorCode, settings);
        }

        static PmdFrameMetric decodeEcg(byte[] data) {
            validatePmd(data, 0x00, 0x00);
            int body = data.length - 10;
            if (body <= 0 || body % 3 != 0) {
                throw new IllegalArgumentException("bad ECG length");
            }
            List<Integer> samples = new ArrayList<Integer>();
            for (int offset = 10; offset < data.length; offset += 3) {
                samples.add(Integer.valueOf(readInt24(data, offset)));
            }
            return new PmdFrameMetric(
                System.nanoTime(),
                readUInt64(data, 1),
                body / 3,
                new ArrayList<AccSample>(),
                samples);
        }

        static PmdFrameMetric decodeAcc(byte[] data) {
            validatePmdType(data, 0x02);
            int frameType = unsigned(data[9]);
            boolean compressed = (frameType & 0x80) != 0;
            int frameTypeBase = frameType & 0x7f;
            if (!compressed && frameTypeBase == 0x01) {
                int body = data.length - 10;
                if (body <= 0 || body % 6 != 0) {
                    throw new IllegalArgumentException("bad ACC length");
                }
                List<AccSample> samples = new ArrayList<AccSample>();
                for (int offset = 10; offset < data.length; offset += 6) {
                    samples.add(new AccSample(
                        readInt16(data, offset),
                        readInt16(data, offset + 2),
                        readInt16(data, offset + 4)));
                }
                return new PmdFrameMetric(System.nanoTime(), readUInt64(data, 1), body / 6, samples);
            }
            return decodeCompressedAcc(data);
        }

        static void validatePmd(byte[] data, int expectedType, int expectedFrameType) {
            if (data.length < 10 || unsigned(data[0]) != expectedType || unsigned(data[9]) != expectedFrameType) {
                throw new IllegalArgumentException("bad PMD frame");
            }
        }

        static void validatePmdType(byte[] data, int expectedType) {
            if (data.length < 10 || unsigned(data[0]) != expectedType) {
                throw new IllegalArgumentException("bad PMD frame");
            }
        }

        static PmdFrameMetric decodeCompressedAcc(byte[] data) {
            if (data.length < 16) {
                throw new IllegalArgumentException("short compressed ACC frame");
            }
            ArrayList<AccSample> samples = new ArrayList<AccSample>();
            int refX = readInt16(data, 10);
            int refY = readInt16(data, 12);
            int refZ = readInt16(data, 14);
            samples.add(new AccSample(refX, refY, refZ));
            if (data.length <= 16) {
                return new PmdFrameMetric(System.nanoTime(), readUInt64(data, 1), samples.size(), samples);
            }
            int[] bitOffset = new int[] {0};
            int byteOffset = 16;
            int remainingBytes = data.length - byteOffset;
            int deltaBitWidth = 16;
            int bitsPerSample = deltaBitWidth * 3;
            int totalBits = remainingBytes * 8;
            int deltaSampleCount = totalBits / bitsPerSample;
            int previousX = refX;
            int previousY = refY;
            int previousZ = refZ;
            for (int index = 0; index < deltaSampleCount; index++) {
                previousX = clampInt(previousX + readSignedBits(data, byteOffset, bitOffset, deltaBitWidth),
                    Short.MIN_VALUE,
                    Short.MAX_VALUE);
                previousY = clampInt(previousY + readSignedBits(data, byteOffset, bitOffset, deltaBitWidth),
                    Short.MIN_VALUE,
                    Short.MAX_VALUE);
                previousZ = clampInt(previousZ + readSignedBits(data, byteOffset, bitOffset, deltaBitWidth),
                    Short.MIN_VALUE,
                    Short.MAX_VALUE);
                samples.add(new AccSample(previousX, previousY, previousZ));
            }
            return new PmdFrameMetric(System.nanoTime(), readUInt64(data, 1), samples.size(), samples);
        }

        static int readSignedBits(byte[] data, int startByteOffset, int[] bitOffsetRef, int bitWidth) {
            if (bitWidth <= 0 || bitWidth > 32) {
                throw new IllegalArgumentException("bad bit width");
            }
            int totalBitPosition = bitOffsetRef[0];
            int bytePosition = startByteOffset + (totalBitPosition / 8);
            int bitInByte = totalBitPosition % 8;
            long value = 0L;
            int bitsRead = 0;
            while (bitsRead < bitWidth && bytePosition < data.length) {
                int bitsAvailable = 8 - bitInByte;
                int bitsToRead = Math.min(bitsAvailable, bitWidth - bitsRead);
                int mask = (1 << bitsToRead) - 1;
                int bits = (unsigned(data[bytePosition]) >> bitInByte) & mask;
                value |= ((long) bits) << bitsRead;
                bitsRead += bitsToRead;
                bytePosition += 1;
                bitInByte = 0;
            }
            bitOffsetRef[0] += bitWidth;
            if (bitWidth < 32 && (value & (1L << (bitWidth - 1))) != 0) {
                value |= ~((1L << bitWidth) - 1L);
            }
            return (int) value;
        }

        static int readUInt16(byte[] data, int offset) {
            if (offset + 1 >= data.length) {
                throw new IllegalArgumentException("short u16");
            }
            return unsigned(data[offset]) | (unsigned(data[offset + 1]) << 8);
        }

        static int readInt16(byte[] data, int offset) {
            int value = readUInt16(data, offset);
            return (value & 0x8000) != 0 ? value - 0x10000 : value;
        }

        static int readInt24(byte[] data, int offset) {
            if (offset + 2 >= data.length) {
                throw new IllegalArgumentException("short i24");
            }
            int value = unsigned(data[offset]) | (unsigned(data[offset + 1]) << 8) | (unsigned(data[offset + 2]) << 16);
            return (value & 0x800000) != 0 ? value - 0x1000000 : value;
        }

        static long readUInt64(byte[] data, int offset) {
            if (offset + 7 >= data.length) {
                throw new IllegalArgumentException("short u64");
            }
            long value = 0L;
            for (int index = 0; index < 8; index++) {
                value |= ((long) unsigned(data[offset + index])) << (index * 8);
            }
            return value;
        }

        static int unsigned(byte value) {
            return value & 0xff;
        }
    }
}
