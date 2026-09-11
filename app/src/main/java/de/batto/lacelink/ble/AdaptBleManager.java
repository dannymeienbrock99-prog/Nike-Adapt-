package de.batto.lacelink.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import de.batto.lacelink.protocol.CoreRfProtocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns BLE scanning and up to two independent shoe sessions. */
public final class AdaptBleManager implements ShoeSession.Listener {
    public interface Listener {
        void onCandidatesChanged(List<ScanCandidate> candidates);

        void onScanStateChanged(boolean scanning, String message);

        void onSessionsChanged(Collection<ShoeSession> sessions);

        void onMessage(String message);
    }

    public static final class ScanCandidate {
        public final BluetoothDevice device;
        public final String name;
        public final String address;
        public final int rssi;
        public final boolean advertisesCoreRf;

        private ScanCandidate(BluetoothDevice device, String name, String address,
                              int rssi, boolean advertisesCoreRf) {
            this.device = device;
            this.name = name;
            this.address = address;
            this.rssi = rssi;
            this.advertisesCoreRf = advertisesCoreRf;
        }
    }

    private static final long SCAN_DURATION_MS = 12_000L;
    private static final int ADAPT_MANUFACTURER_ID = 120;
    private static final byte[] ADAPT_MANUFACTURER_PREFIX = {(byte) 0xaf, 0x28};

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Map<String, ScanCandidate> candidates = new LinkedHashMap<>();
    private final Map<String, ShoeSession> sessions = new LinkedHashMap<>();
    private boolean scanning;

    private final Runnable scanTimeout = () -> stopScan("Scan beendet");

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            acceptResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                acceptResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                scanning = false;
                handler.removeCallbacks(scanTimeout);
                listener.onScanStateChanged(false, scanFailureText(errorCode));
            });
        }
    };

    public AdaptBleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
    }

    public BluetoothAdapter getAdapter() {
        return adapter;
    }

    public boolean isAvailable() {
        return adapter != null;
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null) {
            listener.onMessage("Dieses Gerät unterstützt kein Bluetooth.");
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onMessage("Bluetooth ist noch nicht eingeschaltet.");
            return;
        }
        stopScan(null);
        candidates.clear();
        addBondedDevices();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        ScanFilter adaptFilter = new ScanFilter.Builder()
                .setManufacturerData(ADAPT_MANUFACTURER_ID, ADAPT_MANUFACTURER_PREFIX)
                .build();
        try {
            scanner.startScan(Collections.singletonList(adaptFilter), settings, scanCallback);
            scanning = true;
            listener.onScanStateChanged(true, "Suche 12 Sekunden …");
            handler.postDelayed(scanTimeout, SCAN_DURATION_MS);
        } catch (SecurityException error) {
            listener.onMessage("Bluetooth-Berechtigung fehlt.");
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan(String message) {
        handler.removeCallbacks(scanTimeout);
        if (adapter != null && scanning) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                try {
                    scanner.stopScan(scanCallback);
                } catch (SecurityException ignored) {
                    // Permission may have been revoked while scanning.
                }
            }
        }
        scanning = false;
        if (message != null) {
            listener.onScanStateChanged(false, message);
        }
    }

    @SuppressLint("MissingPermission")
    public void connect(ScanCandidate candidate) {
        ShoeSession existing = sessions.get(candidate.address);
        if (existing != null) {
            existing.connect();
            return;
        }
        if (sessions.size() >= 2) {
            listener.onMessage("Bitte zuerst einen der zwei Schuhe trennen.");
            return;
        }
        stopScan("Scan pausiert");
        ShoeSession session = new ShoeSession(context, candidate.device, candidate.name, this);
        sessions.put(candidate.address, session);
        listener.onSessionsChanged(sessions.values());
        session.connect();
    }

    public void removeSession(ShoeSession session) {
        if (session == null) {
            return;
        }
        sessions.remove(session.getAddress());
        session.close();
        listener.onSessionsChanged(sessions.values());
    }

    public Collection<ShoeSession> getSessions() {
        return new ArrayList<>(sessions.values());
    }

    public void close() {
        stopScan(null);
        for (ShoeSession session : new ArrayList<>(sessions.values())) {
            session.close();
        }
        sessions.clear();
    }

    @SuppressLint("MissingPermission")
    private void addBondedDevices() {
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = safeName(device, null);
                boolean likelyAdapt = looksLikeAdapt(name);
                if (likelyAdapt) {
                    String address = device.getAddress();
                    candidates.put(address, new ScanCandidate(device, name, address, 0, false));
                }
            }
            publishCandidates();
        } catch (SecurityException ignored) {
            // Permission result will trigger another scan.
        }
    }

    @SuppressLint("MissingPermission")
    private void acceptResult(ScanResult result) {
        handler.post(() -> {
            BluetoothDevice device = result.getDevice();
            String address;
            try {
                address = device.getAddress();
            } catch (SecurityException error) {
                return;
            }
            String recordName = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            String name = safeName(device, recordName);
            boolean coreRf = false;
            boolean adaptManufacturer = false;
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    if (CoreRfProtocol.SERVICE_UUID.equals(uuid.getUuid())) {
                        coreRf = true;
                        break;
                    }
                }
            }
            if (result.getScanRecord() != null) {
                byte[] data = result.getScanRecord().getManufacturerSpecificData(ADAPT_MANUFACTURER_ID);
                adaptManufacturer = data != null && data.length >= 2
                        && data[0] == ADAPT_MANUFACTURER_PREFIX[0]
                        && data[1] == ADAPT_MANUFACTURER_PREFIX[1];
            }
            if (adaptManufacturer && "Unbekanntes BLE-Gerät".equals(name)) {
                name = "Adapt BB";
            }
            ScanCandidate previous = candidates.get(address);
            boolean advertised = coreRf || adaptManufacturer
                    || (previous != null && previous.advertisesCoreRf);
            candidates.put(address, new ScanCandidate(device, name, address, result.getRssi(), advertised));
            publishCandidates();
        });
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device, String advertisedName) {
        if (advertisedName != null && !advertisedName.trim().isEmpty()) {
            return advertisedName.trim();
        }
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Unbekanntes BLE-Gerät" : name.trim();
        } catch (SecurityException ignored) {
            return "Unbekanntes BLE-Gerät";
        }
    }

    private boolean looksLikeAdapt(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("adapt") || normalized.contains("nike") || normalized.contains("bb");
    }

    private void publishCandidates() {
        List<ScanCandidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator
                .comparing((ScanCandidate candidate) -> !candidate.advertisesCoreRf)
                .thenComparing((ScanCandidate candidate) -> -candidate.rssi)
                .thenComparing(candidate -> candidate.name));
        listener.onCandidatesChanged(ordered);
    }

    private String scanFailureText(int code) {
        if (code == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
            return "Zu oft gescannt – bitte etwa 30 Sekunden warten.";
        }
        return "BLE-Scan fehlgeschlagen (Code " + code + ").";
    }

    @Override
    public void onSessionChanged(ShoeSession session) {
        listener.onSessionsChanged(sessions.values());
    }

    @Override
    public void onSessionMessage(ShoeSession session, String message) {
        listener.onMessage(session.getDisplayName() + ": " + message);
    }
}
