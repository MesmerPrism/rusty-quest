package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.Manifest;
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
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

final class PolarSensorPanel {
    static final int REQUEST_BLE_PERMISSIONS = 9103;

    private static final String TAG = "RQSpatialCameraPanel";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_SPATIAL_CAMERA_PANEL";
    private static final String CHANNEL = "polar-sensor-panel";
    private static final String STREAM_EVENTS_FILE = "polar_stream_events.jsonl";
    private static final String STATUS_FILE = "polar_sensor_status.json";

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

    interface Host {
        void closePanelAndReturnToImmersive();
        void onPolarStreamEvent(JSONObject event);
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceEntry> devices = new ArrayList<DeviceEntry>();
    private final Object countersLock = new Object();
    private final Queue<DescriptorTask> descriptorTasks = new ArrayDeque<DescriptorTask>();

    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private Spinner pmdSpinner;
    private TextView status;
    private TextView selectedDevice;
    private TextView hrStatus;
    private TextView accStatus;
    private TextView ecgStatus;
    private TextView linkStatus;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic batteryCharacteristic;
    private BluetoothGattCharacteristic hrCharacteristic;
    private BluetoothGattCharacteristic pmdControlCharacteristic;
    private BluetoothGattCharacteristic pmdDataCharacteristic;

    private boolean scanning;
    private boolean descriptorsStarted;
    private boolean commandInFlight;
    private boolean pmdReady;
    private boolean pmdRunning;
    private boolean closing;
    private String pendingCommand = "";
    private String pendingPmdMode = "acc";
    private String activePmdMode = "none";
    private long pmdFlowGeneration;
    private long pendingCommandGeneration;
    private int pmdSettingsAttempts;
    private int pmdStartAttempts;
    private PmdSettings accSettings = PmdSettings.EMPTY;
    private PmdSettings ecgSettings = PmdSettings.EMPTY;

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

    PolarSensorPanel(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    View buildView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(PANEL_BG);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        LinearLayout header = row();
        TextView title = text("Polar Sensor Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stop();
                host.closePanelAndReturnToImmersive();
            }
        });
        header.addView(close);
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
        Button scan = button("Scan");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan();
            }
        });
        Button connect = button("Connect");
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
        scanRow.addView(scan, rowButtonParams());
        scanRow.addView(connect, rowButtonParams());
        scanRow.addView(disconnect, rowButtonParams());
        root.addView(scanRow);

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

        root.addView(sectionTitle("Streams"));
        linkStatus = text("", 14, PANEL_FG);
        hrStatus = text("", 14, PANEL_FG);
        accStatus = text("", 14, PANEL_FG);
        ecgStatus = text("", 14, PANEL_FG);
        root.addView(linkStatus);
        root.addView(hrStatus);
        root.addView(accStatus);
        root.addView(ecgStatus);

        status = text("Polar panel ready.", 13, PANEL_MUTED);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);
        updateCounters();
        writeStatus("ready", "panel-created");
        marker("status=ready");
        return scroll;
    }

    void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }
        if (hasRequiredPermissions()) {
            setStatus("BLE permissions accepted.");
            marker("status=permission-accepted");
        } else {
            setStatus("BLE permission request was not accepted.");
            marker("status=permission-rejected");
        }
    }

    void stop() {
        closing = true;
        stopScan();
        closeGatt();
        writeStatus("stopped", "panel-closed");
        marker("status=stopped");
    }

    void handleCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.US);
        if ("select_ecg".equals(command)) {
            setSelectedPmdMode("ecg");
            setStatus("CLI command: select ECG.");
            marker("status=cli-command command=select_ecg");
            return;
        }
        if ("scan".equals(command)) {
            setStatus("CLI command: scan.");
            marker("status=cli-command command=scan");
            startScan();
            return;
        }
        if ("connect".equals(command)) {
            setStatus("CLI command: connect.");
            marker("status=cli-command command=connect");
            connectSelected();
            return;
        }
        if ("start_acc".equals(command)) {
            setSelectedPmdMode("acc");
            setStatus("CLI command: start ACC.");
            marker("status=cli-command command=start_acc");
            startSelectedPmd();
            return;
        }
        if ("start_ecg".equals(command)) {
            setSelectedPmdMode("ecg");
            setStatus("CLI command: start ECG.");
            marker("status=cli-command command=start_ecg");
            startSelectedPmd();
            return;
        }
        setStatus("Unknown Polar CLI command: " + rawCommand);
        marker("status=cli-command-ignored command=" + markerToken(rawCommand));
    }

    int discoveredDeviceCount() {
        return devices.size();
    }

    void connectBestDiscovered(String preferredPmdMode) {
        if (!ensurePermissions()) {
            return;
        }
        if ("ecg".equals(preferredPmdMode) || "acc".equals(preferredPmdMode)) {
            setSelectedPmdMode(preferredPmdMode);
        }
        if (devices.isEmpty()) {
            setStatus("No Polar device discovered for automation.");
            marker("status=auto-connect-skipped reason=no-device deviceCount=0");
            return;
        }
        int bestIndex = 0;
        for (int index = 1; index < devices.size(); index++) {
            DeviceEntry candidate = devices.get(index);
            DeviceEntry best = devices.get(bestIndex);
            boolean candidatePreferred = candidate.looksLikePolar() && !best.looksLikePolar();
            boolean candidateStronger = candidate.looksLikePolar() == best.looksLikePolar()
                && candidate.rssi > best.rssi;
            if (candidatePreferred || candidateStronger) {
                bestIndex = index;
            }
        }
        if (deviceSpinner != null) {
            deviceSpinner.setSelection(bestIndex);
        }
        DeviceEntry selected = devices.get(bestIndex);
        marker("status=auto-connect-selected device=" + markerToken(selected.address)
            + " deviceCount=" + devices.size()
            + " preferredPmdMode=" + markerToken(preferredPmdMode));
        connectSelected();
    }

    boolean isEcgReceiving() {
        synchronized (countersLock) {
            return pmdRunning && "ecg".equals(activePmdMode) && ecgFrames > 0L && ecgSamples > 0L;
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
            running = pmdRunning;
            mode = activePmdMode;
        }
        if (!experimentReady) {
            return "ECG logging: participant file not created yet.";
        }
        if (running && "ecg".equals(mode) && frameCount > 0L && sampleCount > 0L) {
            return "ECG logging: active, " + frameCount + " frames / " + sampleCount + " samples mirrored to participant files.";
        }
        if (running && "ecg".equals(mode)) {
            return "ECG logging: ECG stream active, waiting for decoded samples.";
        }
        return "ECG logging: not active. Select ECG 130 Hz and start PMD after connecting.";
    }

    private void startScan() {
        if (!ensurePermissions()) {
            return;
        }
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Bluetooth is unavailable.");
            marker("status=error reason=bluetooth-unavailable");
            return;
        }
        BluetoothLeScanner nextScanner = adapter.getBluetoothLeScanner();
        if (nextScanner == null) {
            setStatus("BLE scanner is unavailable.");
            marker("status=error reason=scanner-unavailable");
            return;
        }
        stopScan();
        devices.clear();
        updateDeviceAdapter();
        scanner = nextScanner;
        scanning = true;
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException ex) {
            scanning = false;
            setStatus("BLE scan permission is missing.");
            marker("status=error reason=scan-security-exception");
            return;
        } catch (RuntimeException ex) {
            scanning = false;
            setStatus("BLE scan failed to start.");
            marker("status=error reason=scan-start-failed");
            return;
        }
        setStatus("Scanning for Polar devices.");
        marker("status=scanning");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (scanning) {
                    stopScan();
                    setStatus("Scan finished. Devices found: " + devices.size());
                    marker("status=scan-finished deviceCount=" + devices.size());
                }
            }
        }, 15000L);
    }

    private void connectSelected() {
        if (!ensurePermissions()) {
            return;
        }
        int index = deviceSpinner == null ? -1 : deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            setStatus("No Polar device selected.");
            return;
        }
        DeviceEntry entry = devices.get(index);
        stopScan();
        closeGatt();
        descriptorsStarted = false;
        commandInFlight = false;
        pmdReady = false;
        pmdRunning = false;
        activePmdMode = "none";
        pmdFlowGeneration += 1L;
        pmdSettingsAttempts = 0;
        pmdStartAttempts = 0;
        accSettings = PmdSettings.EMPTY;
        ecgSettings = PmdSettings.EMPTY;
        pendingPmdMode = selectedPmdMode();
        connectedLabel = entry.label();
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = entry.device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = entry.device.connectGatt(activity, false, gattCallback);
            }
            setStatus("Connecting to " + entry.label());
            writeStatus("connecting", entry.label());
            marker("status=connecting device=" + markerToken(entry.address));
        } catch (SecurityException ex) {
            setStatus("BLE connect permission is missing.");
            marker("status=error reason=connect-security-exception");
        } catch (RuntimeException ex) {
            setStatus("BLE connect failed to start.");
            marker("status=error reason=connect-start-failed");
        }
    }

    private void disconnect() {
        stopScan();
        closeGatt();
        setStatus("Disconnected.");
        writeStatus("disconnected", connectedLabel);
        marker("status=disconnected");
        connectedLabel = "none";
        updateCounters();
    }

    private void startSelectedPmd() {
        if (gatt == null || pmdControlCharacteristic == null) {
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

    private void stopPmd() {
        if (gatt == null || pmdControlCharacteristic == null) {
            setStatus("PMD control point is not available.");
            return;
        }
        if (!pmdRunning) {
            setStatus("PMD stream is already stopped.");
            return;
        }
        pmdFlowGeneration += 1L;
        writePmdCommand("stop_stream_only", buildStopCommand(activePmdMode));
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
        }
        File events = new File(activity.getFilesDir(), STREAM_EVENTS_FILE);
        if (events.exists() && !events.delete()) {
            setStatus("Counters reset; stream-event file could not be removed.");
        } else {
            setStatus("Counters reset.");
        }
        updateCounters();
        writeStatus("ready", "counters-reset");
        marker("status=counters-reset");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) {
                return;
            }
            String discoveredName = safeName(result.getDevice());
            if ((discoveredName == null || discoveredName.trim().isEmpty())
                    && result.getScanRecord() != null
                    && result.getScanRecord().getDeviceName() != null) {
                discoveredName = result.getScanRecord().getDeviceName();
            }
            boolean hasSupportedService = scanRecordHasSupportedService(result.getScanRecord());
            final DeviceEntry entry = new DeviceEntry(
                result.getDevice(),
                discoveredName,
                safeAddress(result.getDevice()),
                result.getRssi());
            if (!entry.looksLikePolar() && !hasSupportedService) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    addOrUpdateDevice(entry);
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    setStatus("BLE scan failed: " + errorCode);
                    marker("status=scan-failed errorCode=" + errorCode);
                }
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt callbackGatt, final int statusCode, final int newState) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleConnectionState(callbackGatt, statusCode, newState);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt callbackGatt, final int statusCode) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                        setStatus("Service discovery failed: " + statusCode);
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
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setupAfterMtu(callbackGatt);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, final int statusCode) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
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
        public void onCharacteristicWrite(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, final int statusCode) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleCharacteristicWrite(statusCode);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(characteristic.getUuid())) {
                final byte[] value = characteristic.getValue();
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleBattery(value);
                    }
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
            dispatchCharacteristic(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            dispatchCharacteristic(characteristic, value);
        }
    };

    private void dispatchCharacteristic(final BluetoothGattCharacteristic characteristic, byte[] value) {
        final byte[] copy = value == null ? null : value.clone();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleCharacteristic(characteristic, copy);
            }
        });
    }

    private void handleConnectionState(BluetoothGatt callbackGatt, int statusCode, int newState) {
        if (closing) {
            return;
        }
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            setStatus("Connection failed: " + statusCode);
            writeStatus("connection_failed", String.valueOf(statusCode));
            marker("status=connection-failed statusCode=" + statusCode);
            closeGatt();
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            setStatus("Connected. Discovering services.");
            writeStatus("connected", connectedLabel);
            marker("status=connected");
            try {
                callbackGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                callbackGatt.discoverServices();
            } catch (SecurityException ex) {
                setStatus("BLE service discovery permission is missing.");
                marker("status=error reason=discover-security-exception");
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            pmdRunning = false;
            activePmdMode = "none";
            setStatus("Polar device disconnected.");
            writeStatus("disconnected", connectedLabel);
            marker("status=device-disconnected");
            updateCounters();
        }
    }

    private void setupAfterMtu(BluetoothGatt callbackGatt) {
        if (descriptorsStarted || callbackGatt == null) {
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
            setStatus("Connected device has no supported Polar HR or PMD services.");
            marker("status=error reason=no-supported-services");
            return;
        }
        setStatus("Subscribing to Polar characteristics.");
        writeNextDescriptorOrBegin(callbackGatt);
    }

    private void writeNextDescriptorOrBegin(BluetoothGatt callbackGatt) {
        if (commandInFlight || callbackGatt == null) {
            return;
        }
        DescriptorTask task = descriptorTasks.poll();
        if (task == null) {
            beginStreams();
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

    private void beginStreams() {
        appendStatusEvent("subscribed");
        scheduleBatteryRead();
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
        if ("stop_before_start".equals(command)) {
            pmdRunning = false;
            activePmdMode = "none";
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestPmdSettings(generation);
                }
            }, 250L);
            return;
        }
        if ("stop_stream_only".equals(command)) {
            pmdRunning = false;
            activePmdMode = "none";
            setStatus("PMD stream stopped.");
            marker("status=pmd-stopped");
            appendStatusEvent("pmd-stopped");
            updateCounters();
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
        setStatus("Preparing " + mode.toUpperCase(Locale.US) + " PMD stream.");
        if (pmdRunning && !"none".equals(activePmdMode)) {
            writePmdCommand("stop_before_start", buildStopCommand(activePmdMode));
        } else {
            writePmdCommand("probe_pmd", new byte[] {0x00});
            pendingCommandGeneration = generation;
        }
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

    private void scheduleBatteryRead() {
        if (batteryCharacteristic == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                readBatteryIfIdle();
            }
        }, 1500L);
    }

    private void readBatteryIfIdle() {
        if (gatt == null || batteryCharacteristic == null || commandInFlight) {
            return;
        }
        try {
            if (!gatt.readCharacteristic(batteryCharacteristic)) {
                marker("status=battery-read-not-started");
            }
        } catch (SecurityException ex) {
            marker("status=error reason=battery-read-security-exception");
        } catch (RuntimeException ex) {
            marker("status=error reason=battery-read-runtime-exception");
        }
    }

    private void handleCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
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
                synchronized (countersLock) {
                    heartRateEvents += 1L;
                    latestBpm = reading.bpm;
                    rrIntervals += reading.rrIntervalsMs.size();
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
                    synchronized (countersLock) {
                        accFrames += 1L;
                        accSamples += frame.sampleCount;
                    }
                    appendAccEvent(frame);
                } else if (measurementType == 0x00) {
                    PmdFrameMetric frame = PolarProtocol.decodeEcg(value);
                    synchronized (countersLock) {
                        ecgFrames += 1L;
                        ecgSamples += frame.sampleCount;
                    }
                    appendEcgEvent(frame);
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
                pmdRunning = true;
                activePmdMode = pendingPmdMode;
                setStatus("PMD stream active: " + activePmdMode.toUpperCase(Locale.US));
                marker("status=pmd-started mode=" + markerToken(activePmdMode));
                appendStatusEvent("pmd-started-" + activePmdMode);
                updateCounters();
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
            pmdRunning = false;
            activePmdMode = "none";
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

    private void appendAccEvent(PmdFrameMetric frame) {
        try {
            JSONObject payload = basePayload(STREAM_ACC)
                .put("sensor_timestamp_ns", frame.sensorTimestampNs)
                .put("frame_sample_count", frame.sampleCount);
            if (!frame.accSamples.isEmpty()) {
                AccSample latest = frame.accSamples.get(frame.accSamples.size() - 1);
                payload.put("latest_sample_mg", new JSONObject()
                    .put("x", latest.xMg)
                    .put("y", latest.yMg)
                    .put("z", latest.zMg));
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
            marker("status=ecg-frame frameSampleCount=" + frame.sampleCount
                + " totalEcgFrames=" + ecgFrames
                + " totalEcgSamples=" + ecgSamples);
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
            .put("source", "rusty_quest_spatial_sdk_polar_panel")
            .put("device", connectedLabel);
    }

    private void appendStreamEvent(String streamId, JSONObject payload) throws Exception {
        long nextSequence;
        synchronized (countersLock) {
            sequenceId += 1L;
            nextSequence = sequenceId;
        }
        long nowNs = System.currentTimeMillis() * 1000000L;
        JSONObject event = new JSONObject()
            .put("type", "stream_event")
            .put("schema", "rusty.manifold.stream.event.v1")
            .put("stream", streamId)
            .put("stream_id", streamId)
            .put("sequence_id", nextSequence)
            .put("payload", payload)
            .put("transport_time_unix_ns", nowNs)
            .put("transport_receive_time_unix_ns", nowNs)
            .put("time_utc", Instant.now().toString());
        FileOutputStream out = activity.openFileOutput(STREAM_EVENTS_FILE, Context.MODE_APPEND);
        try {
            out.write(event.toString().getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } finally {
            out.close();
        }
        synchronized (countersLock) {
            streamEventsWritten += 1L;
        }
        try {
            host.onPolarStreamEvent(event);
        } catch (RuntimeException ignored) {
        }
    }

    private void writeStatus(String state, String detail) {
        try {
            JSONObject body = new JSONObject()
                .put("schema", "rusty.quest.spatial_camera_panel.polar_sensor_status.v1")
                .put("status", state)
                .put("detail", detail == null ? "" : detail)
                .put("stream_events_file", STREAM_EVENTS_FILE)
                .put("updated_at_unix_ms", System.currentTimeMillis());
            FileOutputStream out = activity.openFileOutput(STATUS_FILE, Context.MODE_PRIVATE);
            try {
                out.write(body.toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void updateCountersOnUiThread() {
        activity.runOnUiThread(new Runnable() {
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
                + " | battery: " + batteryText
                + " | control: " + controlCount
                + " | malformed: " + malformed
                + " | events: " + eventCount);
        }
        if (hrStatus != null) {
            hrStatus.setText("HR/RR: " + hr + " heart-rate events, " + rr + " RR intervals, latest " + bpm + " bpm");
        }
        if (accStatus != null) {
            accStatus.setText("ACC: " + accFrameCount + " frames, " + accSampleCount + " decoded samples");
        }
        if (ecgStatus != null) {
            ecgStatus.setText("ECG: " + ecgFrameCount + " frames, " + ecgSampleCount + " decoded samples");
        }
    }

    private String selectedDeviceLabel() {
        int index = deviceSpinner == null ? -1 : deviceSpinner.getSelectedItemPosition();
        if (index >= 0 && index < devices.size()) {
            return devices.get(index).label();
        }
        return "none";
    }

    private void addOrUpdateDevice(DeviceEntry entry) {
        for (int i = 0; i < devices.size(); i++) {
            DeviceEntry existing = devices.get(i);
            if (existing.sameDevice(entry)) {
                devices.set(i, entry);
                updateDeviceAdapter();
                return;
            }
        }
        devices.add(entry);
        updateDeviceAdapter();
        setStatus("Found " + entry.label());
        marker("status=device-found device=" + markerToken(entry.address));
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
        updateCounters();
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        scanning = false;
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
            }
            gatt = null;
        }
        descriptorTasks.clear();
        descriptorsStarted = false;
        commandInFlight = false;
        pmdRunning = false;
        activePmdMode = "none";
        batteryCharacteristic = null;
        hrCharacteristic = null;
        pmdControlCharacteristic = null;
        pmdDataCharacteristic = null;
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean ensurePermissions() {
        if (hasRequiredPermissions()) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        activity.requestPermissions(requiredPermissions(), REQUEST_BLE_PERMISSIONS);
        setStatus("Requesting BLE permissions.");
        marker("status=permission-requested");
        return false;
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        String[] required = requiredPermissions();
        for (String permission : required) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION
        };
    }

    private String selectedPmdMode() {
        int index = pmdSpinner == null ? 0 : pmdSpinner.getSelectedItemPosition();
        return index == 1 ? "ecg" : "acc";
    }

    private void setSelectedPmdMode(String mode) {
        if (pmdSpinner == null) {
            return;
        }
        pmdSpinner.setSelection("ecg".equals(mode) ? 1 : 0);
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
            int resolution = settings.hasAny() && attempt == 0
                ? settings.chooseLowestResolution(14)
                : 14;
            return buildPmdStartRequest((byte) 0x00, sampleRate, resolution, null);
        }
        PmdSettings settings = settingsForMode(mode);
        int sampleRate = settings.chooseClosestSampleRate(200);
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

    private static boolean scanRecordHasSupportedService(android.bluetooth.le.ScanRecord record) {
        if (record == null || record.getServiceUuids() == null) {
            return false;
        }
        for (ParcelUuid parcelUuid : record.getServiceUuids()) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) {
                continue;
            }
            UUID uuid = parcelUuid.getUuid();
            if (HEART_RATE_SERVICE.equals(uuid) || PMD_SERVICE.equals(uuid)) {
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

    private static final class DeviceEntry {
        final BluetoothDevice device;
        final String name;
        final String address;
        final int rssi;

        DeviceEntry(BluetoothDevice device, String name, String address, int rssi) {
            this.device = device;
            this.name = name == null ? "" : name.trim();
            this.address = address == null ? "" : address.trim();
            this.rssi = rssi;
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
