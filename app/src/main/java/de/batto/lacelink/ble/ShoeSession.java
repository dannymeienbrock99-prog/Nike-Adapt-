package de.batto.lacelink.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import de.batto.lacelink.protocol.CoreRfProtocol;
import de.batto.lacelink.protocol.DhKeyExchange;

import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One GATT connection and authenticated CoreRF session. */
public final class ShoeSession {
    public interface Listener {
        void onSessionChanged(ShoeSession session);

        void onSessionMessage(ShoeSession session, String message);
    }

    public enum State {
        DISCONNECTED,
        CONNECTING,
        DISCOVERING,
        BONDING,
        ENABLING_NOTIFICATIONS,
        NEEDS_APP_PAIRING,
        KEY_EXCHANGE,
        AUTHENTICATING,
        READY,
        ERROR
    }

    private interface ResponseCallback {
        void onSuccess(CoreRfProtocol.Message response);

        void onError(String message);
    }

    private interface ValueCallback {
        void accept(byte[] value);
    }

    private static final String PREFS = "shoe_keys";
    private static final long NORMAL_TIMEOUT_MS = 5_000L;
    private static final long NOTIFICATION_SETUP_TIMEOUT_MS = 30_000L;
    private static final int GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS;
    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;

    private final Context context;
    private final BluetoothDevice device;
    private final String displayName;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final CoreRfProtocol.Reassembler reassembler = new CoreRfProtocol.Reassembler();
    private final Deque<Request> requests = new ArrayDeque<>();
    private final Deque<GattOperation> gattOperations = new ArrayDeque<>();
    private final Deque<String> logLines = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothGattCharacteristic batteryCharacteristic;
    private GattOperation currentGattOperation;
    private Request currentRequest;
    private List<byte[]> outgoingFrames;
    private int outgoingFrameIndex;
    private int outstandingFrames;
    private int txSequence;
    private boolean transportWritePending;
    private boolean notificationsReady;
    private boolean notificationWritePending;
    private boolean notificationDescriptorWritten;
    private boolean receiverRegistered;
    private boolean closed;
    private int reconnectAttempts;
    private int batteryPercent = -1;
    private State state = State.DISCONNECTED;
    private String stateDetail = "Getrennt";

    private final Runnable notificationSetupTimeout = () -> {
        if (notificationsReady || gatt == null) {
            return;
        }
        String error = "System-Kopplung nicht abgeschlossen. Schuhtaste gedrückt halten, "
                + "Android-Dialog bestätigen und erneut verbinden.";
        disconnectAndCloseGatt();
        resetConnection();
        fail(error);
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (changed == null || !device.getAddress().equals(changed.getAddress())) {
                return;
            }
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            handler.post(() -> onBondStateChanged(bondState));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            handler.post(() -> handleConnectionState(callbackGatt, status, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            handler.post(() -> handleServicesDiscovered(status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> completeGattOperation(status, null));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> completeGattOperation(status, null));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            byte[] value = characteristic.getValue();
            handler.post(() -> completeGattOperation(status, copy(value)));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                         BluetoothGattCharacteristic characteristic,
                                         byte[] value, int status) {
            handler.post(() -> completeGattOperation(status, copy(value)));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            handler.post(() -> handleNotification(copy(value)));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handler.post(() -> handleNotification(copy(value)));
        }
    };

    public ShoeSession(Context context, BluetoothDevice device, String displayName, Listener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.displayName = displayName;
        this.listener = listener;
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getDisplayName() {
        return displayName;
    }

    @SuppressLint("MissingPermission")
    public String getAddress() {
        return device.getAddress();
    }

    public State getState() {
        return state;
    }

    public String getStateDetail() {
        return stateDetail;
    }

    public int getBatteryPercent() {
        return batteryPercent;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean canPairApplicationKey() {
        return notificationsReady && (state == State.NEEDS_APP_PAIRING || state == State.ERROR);
    }

    public String getLogText() {
        StringBuilder result = new StringBuilder();
        for (String line : logLines) {
            result.append(line).append('\n');
        }
        return result.toString();
    }

    @SuppressLint("MissingPermission")
    public void connect() {
        handler.post(() -> {
            if (closed) {
                return;
            }
            if (state != State.DISCONNECTED && state != State.ERROR) {
                return;
            }
            if (gatt != null) {
                disconnectAndCloseGatt();
                resetConnection();
            }
            registerBondReceiver();
            setState(State.CONNECTING, "Verbinde …");
            log("GATT-Verbindung wird geöffnet");
            try {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) {
                    fail("GATT-Verbindung konnte nicht geöffnet werden.");
                }
            } catch (SecurityException error) {
                fail("Bluetooth-Berechtigung fehlt.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        handler.post(() -> {
            disconnectAndCloseGatt();
            resetConnection();
            setState(State.DISCONNECTED, "Getrennt");
        });
    }

    public void close() {
        handler.post(() -> {
            closed = true;
            disconnectAndCloseGatt();
            unregisterBondReceiver();
            cryptoExecutor.shutdownNow();
            requests.clear();
            gattOperations.clear();
            handler.removeCallbacksAndMessages(null);
        });
    }

    public void pairApplicationKey() {
        handler.post(() -> {
            if (!notificationsReady) {
                message("Erst die Bluetooth-Verbindung vollständig herstellen.");
                return;
            }
            if (currentRequest != null || state == State.KEY_EXCHANGE || state == State.AUTHENTICATING) {
                message("Die Kopplung läuft bereits.");
                return;
            }
            preferences.edit().remove(keyPreference()).apply();
            setState(State.KEY_EXCHANGE, "Schuhtaste bestätigen – Schlüsselaustausch läuft …");
            log("CoreRF-Schlüsselaustausch gestartet");
            sendRequest(CoreRfProtocol.OP_START_KEY_EXCHANGE, null, 40_000L, new ResponseCallback() {
                @Override
                public void onSuccess(CoreRfProtocol.Message response) {
                    Long field = CoreRfProtocol.readVarintField(response.payload, 1);
                    int group = field == null && response.payload.length == 0 ? 0 :
                            field == null ? -1 : field.intValue();
                    createDhKey(group);
                }

                @Override
                public void onError(String error) {
                    pairingFailed(error);
                }
            });
        });
    }

    public void refreshBattery() {
        handler.post(() -> {
            readStandardBattery();
            if (!isReady()) {
                return;
            }
            sendRequest(CoreRfProtocol.OP_BATTERY, null, NORMAL_TIMEOUT_MS, new ResponseCallback() {
                @Override
                public void onSuccess(CoreRfProtocol.Message response) {
                    Integer value = CoreRfProtocol.batteryPercent(response.payload);
                    if (value != null) {
                        updateBattery(value);
                    } else {
                        log("Akkudaten ohne Prozentwert empfangen");
                    }
                }

                @Override
                public void onError(String error) {
                    message("Akku konnte nicht gelesen werden: " + error);
                }
            });
        });
    }

    public void tighten() {
        sendReadyCommand("Enger", CoreRfProtocol.OP_SERVO_MOVE,
                CoreRfProtocol.laceMovePayload(CoreRfProtocol.SERVO_SHORT_TIGHTEN));
    }

    public void loosen() {
        sendReadyCommand("Weiter", CoreRfProtocol.OP_SERVO_MOVE,
                CoreRfProtocol.laceMovePayload(CoreRfProtocol.SERVO_SHORT_LOOSEN));
    }

    public void stopMotor() {
        sendReadyCommand("Motorstopp", CoreRfProtocol.OP_SERVO_MOVE,
                CoreRfProtocol.laceMovePayload(CoreRfProtocol.SERVO_STOP));
    }

    public void setPosition(int percent) {
        sendReadyCommand("Position " + percent + "%", CoreRfProtocol.OP_SET_POSITION,
                CoreRfProtocol.positionPayload(percent));
    }

    public void setColor(int red, int green, int blue) {
        sendReadyCommand("Licht", CoreRfProtocol.OP_LED_SET_COLOR,
                CoreRfProtocol.modernColorPayload(red, green, blue));
    }

    private void sendReadyCommand(String label, int opcode, byte[] payload) {
        handler.post(() -> {
            if (!isReady()) {
                message("Der Schuh ist noch nicht vollständig authentifiziert.");
                return;
            }
            sendRequest(opcode, payload, NORMAL_TIMEOUT_MS, new ResponseCallback() {
                @Override
                public void onSuccess(CoreRfProtocol.Message response) {
                    log(label + " bestätigt");
                }

                @Override
                public void onError(String error) {
                    message(label + " fehlgeschlagen: " + error);
                }
            });
        });
    }

    @SuppressLint("MissingPermission")
    private void handleConnectionState(BluetoothGatt callbackGatt, int status, int newState) {
        if (closed || callbackGatt != gatt) {
            return;
        }
        if (status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            reconnectAttempts = 0;
            setState(State.DISCOVERING, "Dienste werden gelesen …");
            log("GATT verbunden");
            try {
                callbackGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                if (!callbackGatt.discoverServices()) {
                    fail("Dienste konnten nicht abgefragt werden.");
                }
            } catch (SecurityException error) {
                fail("Bluetooth-Berechtigung fehlt.");
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            log("GATT getrennt, Status " + status);
            State disconnectedDuring = state;
            boolean shouldRetry = !closed && status == 133 && reconnectAttempts < 1;
            disconnectAndCloseGatt();
            resetConnection();
            if (shouldRetry) {
                reconnectAttempts++;
                setState(State.DISCONNECTED, "Bluetooth-Fehler 133 – neuer Versuch …");
                handler.postDelayed(this::connect, 900L);
            } else {
                String detail;
                if (status == GATT_SUCCESS) {
                    detail = "Getrennt";
                } else if (status == 19 && disconnectedDuring == State.BONDING) {
                    detail = "Schuh hat die Kopplung beendet (Status 19). Schuhtaste halten und neu verbinden.";
                } else if (status == 19) {
                    detail = "Schuh hat die Verbindung beendet (Status 19). Schuhtaste halten und neu verbinden.";
                } else {
                    detail = "Bluetooth-Fehler " + status;
                }
                setState(status == GATT_SUCCESS ? State.DISCONNECTED : State.ERROR, detail);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void handleServicesDiscovered(int status) {
        if (status != GATT_SUCCESS || gatt == null) {
            fail("BLE-Dienste konnten nicht gelesen werden (" + status + ").");
            return;
        }
        BluetoothGattService coreRf = gatt.getService(CoreRfProtocol.SERVICE_UUID);
        if (coreRf == null) {
            fail("Kein kompatibler CoreRF-Dienst gefunden.");
            return;
        }
        writeCharacteristic = coreRf.getCharacteristic(CoreRfProtocol.WRITE_UUID);
        notifyCharacteristic = coreRf.getCharacteristic(CoreRfProtocol.NOTIFY_UUID);
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            fail("Die benötigten BLE-Merkmale fehlen.");
            return;
        }
        BluetoothGattService batteryService = gatt.getService(CoreRfProtocol.BATTERY_SERVICE_UUID);
        batteryCharacteristic = batteryService == null ? null :
                batteryService.getCharacteristic(CoreRfProtocol.BATTERY_LEVEL_UUID);
        log("CoreRF-Dienst erkannt");
        try {
            int bondState = device.getBondState();
            log("Android-Bondstatus: " + bondStateName(bondState));
            if (bondState != BluetoothDevice.BOND_BONDED) {
                setState(State.BONDING,
                        "Geschützte BLE-Verbindung wird angefordert – Systemdialog bestätigen …");
            }
            // Do not call createBond() here. The original CoreRF flow first writes the
            // protected CCCD; Android then starts the shoe's required system pairing.
            enableNotifications();
        } catch (SecurityException error) {
            fail("Bluetooth-Berechtigung fehlt.");
        }
    }

    private void onBondStateChanged(int bondState) {
        if (bondState == BluetoothDevice.BOND_BONDED) {
            log("Android-Kopplung bestätigt");
            if (notificationDescriptorWritten) {
                onNotificationsReady();
            } else if (notificationWritePending) {
                setState(State.ENABLING_NOTIFICATIONS,
                        "Android gekoppelt – BLE-Benachrichtigungen werden aktiviert …");
            } else {
                enableNotifications();
            }
        } else if (bondState == BluetoothDevice.BOND_BONDING) {
            log("Android-Systemdialog für Kopplung geöffnet");
            setState(State.BONDING, "System-Kopplung am Smartphone bestätigen …");
        } else if (bondState == BluetoothDevice.BOND_NONE && state == State.BONDING) {
            fail("Android-Kopplung abgebrochen. Beide Schuhtasten drücken und erneut verbinden.");
        }
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications() {
        if (notificationsReady || notificationWritePending || gatt == null
                || notifyCharacteristic == null) {
            return;
        }
        int bondState = device.getBondState();
        if (bondState == BluetoothDevice.BOND_BONDED) {
            setState(State.ENABLING_NOTIFICATIONS, "Benachrichtigungen werden aktiviert …");
        } else {
            setState(State.BONDING,
                    "Schuhtaste halten – geschützte BLE-Verbindung wird aktiviert …");
        }
        if (!gatt.setCharacteristicNotification(notifyCharacteristic, true)) {
            fail("BLE-Benachrichtigungen konnten nicht aktiviert werden.");
            return;
        }
        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CoreRfProtocol.CCCD_UUID);
        if (descriptor == null) {
            fail("CCCD für BLE-Benachrichtigungen fehlt.");
            return;
        }
        notificationWritePending = true;
        handler.removeCallbacks(notificationSetupTimeout);
        handler.postDelayed(notificationSetupTimeout, NOTIFICATION_SETUP_TIMEOUT_MS);
        log("Geschützte CoreRF-Benachrichtigung wird aktiviert");
        enqueueGatt(GattOperation.descriptor(descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                this::onNotificationDescriptorWritten,
                this::handleNotificationSetupError));
    }

    @SuppressLint("MissingPermission")
    private void onNotificationDescriptorWritten() {
        notificationWritePending = false;
        notificationDescriptorWritten = true;
        log("CoreRF-CCCD geschrieben");
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            onNotificationsReady();
        } else {
            setState(State.BONDING, "System-Kopplung bestätigen …");
        }
    }

    @SuppressLint("MissingPermission")
    private void handleNotificationSetupError(int status) {
        notificationWritePending = false;
        if ((status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION)
                && device.getBondState() != BluetoothDevice.BOND_BONDED) {
            log("CoreRF wartet auf Android-Kopplung (GATT " + status + ")");
            setState(State.BONDING, "System-Kopplung bestätigen …");
            return;
        }
        if ((status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION)
                && device.getBondState() == BluetoothDevice.BOND_BONDED) {
            log("Kopplung abgeschlossen; CCCD wird erneut geschrieben");
            handler.postDelayed(this::enableNotifications, 250L);
            return;
        }
        handler.removeCallbacks(notificationSetupTimeout);
        fail("Benachrichtigungen fehlgeschlagen (GATT " + status + ").");
    }

    private void onNotificationsReady() {
        if (notificationsReady) {
            return;
        }
        handler.removeCallbacks(notificationSetupTimeout);
        notificationWritePending = false;
        notificationsReady = true;
        log("CoreRF-Benachrichtigungen aktiv");
        readStandardBattery();
        byte[] storedKey = readStoredKey();
        if (storedKey == null) {
            setState(State.NEEDS_APP_PAIRING,
                    "Schuhtaste halten, dann „Schlüssel koppeln“ tippen");
        } else {
            startAuthentication(storedKey, false);
        }
    }

    private void createDhKey(int group) {
        if (group < 0 || group > 3) {
            pairingFailed("Unbekannte Schlüsselgruppe vom Schuh.");
            return;
        }
        cryptoExecutor.execute(() -> {
            try {
                DhKeyExchange exchange = new DhKeyExchange(group);
                byte[] publicKey = exchange.publicKey();
                handler.post(() -> sendPublicKey(exchange, publicKey));
            } catch (Exception error) {
                handler.post(() -> pairingFailed("Schlüsselerzeugung: " + safeError(error)));
            }
        });
    }

    private void sendPublicKey(DhKeyExchange exchange, byte[] publicKey) {
        log("MODP-Gruppe akzeptiert; öffentlicher Schlüssel wird übertragen");
        sendRequest(CoreRfProtocol.OP_PUBLIC_KEY,
                CoreRfProtocol.protobufBytesField(1, publicKey), 40_000L,
                new ResponseCallback() {
                    @Override
                    public void onSuccess(CoreRfProtocol.Message response) {
                        byte[] peerKey = CoreRfProtocol.readBytesField(response.payload, 1);
                        if (peerKey == null || peerKey.length == 0) {
                            pairingFailed("Der Schuh hat keinen öffentlichen Schlüssel geliefert.");
                            return;
                        }
                        cryptoExecutor.execute(() -> {
                            try {
                                byte[] authenticationKey = exchange.deriveAuthenticationKey(peerKey);
                                handler.post(() -> startAuthentication(authenticationKey, true));
                            } catch (Exception error) {
                                handler.post(() -> pairingFailed("Schlüsselberechnung: " + safeError(error)));
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        pairingFailed(error);
                    }
                });
    }

    private void startAuthentication(byte[] key, boolean saveWhenSuccessful) {
        if (key == null || key.length != 16) {
            pairingFailed("Ungültiger App-Schlüssel.");
            return;
        }
        setState(State.AUTHENTICATING, "Schuh wird authentifiziert …");
        byte[] phoneNonce = new byte[16];
        new SecureRandom().nextBytes(phoneNonce);
        sendRequest(CoreRfProtocol.OP_START_AUTH,
                CoreRfProtocol.protobufBytesField(1, phoneNonce), 10_000L,
                new ResponseCallback() {
                    @Override
                    public void onSuccess(CoreRfProtocol.Message response) {
                        byte[] deviceNonce = CoreRfProtocol.readBytesField(response.payload, 1);
                        if (deviceNonce == null || deviceNonce.length != 16) {
                            authenticationFailed("Ungültige Challenge-Antwort.");
                            return;
                        }
                        cryptoExecutor.execute(() -> {
                            try {
                                byte[] encrypted = DhKeyExchange.encryptChallenge(key, deviceNonce);
                                handler.post(() -> finishAuthentication(key, encrypted, saveWhenSuccessful));
                            } catch (Exception error) {
                                handler.post(() -> authenticationFailed("Verschlüsselung: " + safeError(error)));
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        authenticationFailed(error);
                    }
                });
    }

    private void finishAuthentication(byte[] key, byte[] encryptedChallenge, boolean saveWhenSuccessful) {
        sendRequest(CoreRfProtocol.OP_AUTH_CHALLENGE,
                CoreRfProtocol.protobufBytesField(1, encryptedChallenge), 10_000L,
                new ResponseCallback() {
                    @Override
                    public void onSuccess(CoreRfProtocol.Message response) {
                        if (saveWhenSuccessful) {
                            saveKey(key);
                        }
                        setState(State.READY, "Bereit");
                        log("CoreRF-Authentifizierung erfolgreich");
                        refreshBattery();
                    }

                    @Override
                    public void onError(String error) {
                        authenticationFailed(error);
                    }
                });
    }

    private void pairingFailed(String error) {
        preferences.edit().remove(keyPreference()).apply();
        setState(State.NEEDS_APP_PAIRING, "Kopplung fehlgeschlagen – Schuhtaste erneut halten");
        message(error);
    }

    private void authenticationFailed(String error) {
        preferences.edit().remove(keyPreference()).apply();
        setState(State.NEEDS_APP_PAIRING, "Schlüssel nicht akzeptiert – neu koppeln");
        message("Authentifizierung fehlgeschlagen: " + error);
    }

    private void sendRequest(int opcode, byte[] payload, long timeoutMs, ResponseCallback callback) {
        if (!notificationsReady || gatt == null || writeCharacteristic == null) {
            callback.onError("BLE-Transport ist nicht bereit.");
            return;
        }
        requests.addLast(new Request(opcode, payload, timeoutMs, callback));
        pumpRequests();
    }

    private void pumpRequests() {
        if (currentRequest != null || requests.isEmpty()) {
            return;
        }
        currentRequest = requests.removeFirst();
        byte[] message = CoreRfProtocol.request(currentRequest.opcode, currentRequest.payload);
        CoreRfProtocol.SegmentedMessage segmented = CoreRfProtocol.segment(message, txSequence);
        txSequence = segmented.nextSequence;
        outgoingFrames = new ArrayList<>(segmented.frames);
        outgoingFrameIndex = 0;
        outstandingFrames = 0;
        transportWritePending = false;
        Request scheduled = currentRequest;
        scheduled.timeoutRunnable = () -> {
            if (currentRequest == scheduled) {
                finishCurrentWithError("Zeitüberschreitung");
            }
        };
        handler.postDelayed(scheduled.timeoutRunnable, scheduled.timeoutMs);
        log("TX Befehl 0x" + Integer.toHexString(scheduled.opcode).toUpperCase());
        pumpTransport();
    }

    private void pumpTransport() {
        if (transportWritePending || outgoingFrames == null || outgoingFrameIndex >= outgoingFrames.size()) {
            return;
        }
        if (outstandingFrames >= 4) {
            log("Warte auf Transportbestätigung");
            return;
        }
        byte[] frame = outgoingFrames.get(outgoingFrameIndex);
        transportWritePending = true;
        enqueueGatt(GattOperation.write(writeCharacteristic, frame, () -> {
            transportWritePending = false;
            outstandingFrames++;
            outgoingFrameIndex++;
            if (outgoingFrameIndex >= outgoingFrames.size()) {
                outgoingFrames = null;
            } else {
                pumpTransport();
            }
        }, status -> {
            transportWritePending = false;
            finishCurrentWithError("GATT-Schreibfehler " + status);
        }));
    }

    private void handleNotification(byte[] frame) {
        if (frame == null || frame.length < 2) {
            return;
        }
        log("RX " + CoreRfProtocol.hex(frame));
        if (CoreRfProtocol.isFlowControl(frame)) {
            int type = CoreRfProtocol.flowControlType(frame);
            if (type == 0) {
                outstandingFrames = 0;
                pumpTransport();
            } else if (type == 1) {
                finishCurrentWithError("Schuh fordert Paketwiederholung an");
            } else {
                finishCurrentWithError("Transportfehler " + type);
            }
            return;
        }
        int sequence = CoreRfProtocol.sequenceOf(frame);
        byte[] messageBytes = reassembler.accept(frame);
        enqueueFlowControl(CoreRfProtocol.flowControlAck(sequence));
        if (messageBytes == null) {
            return;
        }
        CoreRfProtocol.Message message = CoreRfProtocol.parseMessage(messageBytes);
        if (message == null) {
            log("Ungültige CoreRF-Nachricht: " + CoreRfProtocol.hex(messageBytes));
            return;
        }
        handleMessage(message);
    }

    private void handleMessage(CoreRfProtocol.Message message) {
        if (message.opcode == CoreRfProtocol.OP_BATTERY && message.payload.length > 0) {
            Integer percent = CoreRfProtocol.batteryPercent(message.payload);
            if (percent != null) {
                updateBattery(percent);
            }
        }
        if (currentRequest == null) {
            log("Ereignis 0x" + Integer.toHexString(message.opcode).toUpperCase());
            return;
        }
        boolean keyExchangeEvent = currentRequest.opcode == CoreRfProtocol.OP_START_KEY_EXCHANGE
                && message.opcode == CoreRfProtocol.OP_PUBLIC_KEY;
        if (message.opcode != currentRequest.opcode && !keyExchangeEvent) {
            log("Antwort für anderen Befehl 0x" + Integer.toHexString(message.opcode).toUpperCase());
            return;
        }
        if (message.action == CoreRfProtocol.ACTION_NAK) {
            finishCurrentWithError("Vom Schuh abgelehnt (NAK)");
            return;
        }
        Request completed = currentRequest;
        clearCurrent();
        completed.callback.onSuccess(message);
        pumpRequests();
    }

    private void finishCurrentWithError(String error) {
        if (currentRequest == null) {
            return;
        }
        Request failed = currentRequest;
        clearCurrent();
        failed.callback.onError(error);
        pumpRequests();
    }

    private void clearCurrent() {
        if (currentRequest != null && currentRequest.timeoutRunnable != null) {
            handler.removeCallbacks(currentRequest.timeoutRunnable);
        }
        currentRequest = null;
        outgoingFrames = null;
        outgoingFrameIndex = 0;
        outstandingFrames = 0;
        transportWritePending = false;
    }

    private void enqueueFlowControl(byte[] value) {
        if (writeCharacteristic == null) {
            return;
        }
        enqueueGatt(GattOperation.write(writeCharacteristic, value, () -> {
        }, status -> log("Flow-Control-Antwort fehlgeschlagen: " + status)));
    }

    private void readStandardBattery() {
        if (batteryCharacteristic == null || gatt == null) {
            return;
        }
        enqueueGatt(GattOperation.read(batteryCharacteristic, value -> {
            if (value != null && value.length > 0) {
                updateBattery(value[0] & 0xff);
            }
        }, status -> log("Standard-Akkulesen fehlgeschlagen: " + status)));
    }

    private void updateBattery(int value) {
        batteryPercent = Math.max(0, Math.min(100, value));
        changed();
    }

    private void enqueueGatt(GattOperation operation) {
        gattOperations.addLast(operation);
        pumpGattOperations();
    }

    @SuppressLint("MissingPermission")
    private void pumpGattOperations() {
        if (currentGattOperation != null || gattOperations.isEmpty() || gatt == null) {
            return;
        }
        currentGattOperation = gattOperations.removeFirst();
        boolean started;
        try {
            if (currentGattOperation.type == GattOperation.TYPE_WRITE) {
                BluetoothGattCharacteristic characteristic = currentGattOperation.characteristic;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = gatt.writeCharacteristic(characteristic, currentGattOperation.value,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    characteristic.setValue(currentGattOperation.value);
                    started = gatt.writeCharacteristic(characteristic);
                }
            } else if (currentGattOperation.type == GattOperation.TYPE_DESCRIPTOR) {
                BluetoothGattDescriptor descriptor = currentGattOperation.descriptor;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = gatt.writeDescriptor(descriptor, currentGattOperation.value)
                            == BluetoothGatt.GATT_SUCCESS;
                } else {
                    descriptor.setValue(currentGattOperation.value);
                    started = gatt.writeDescriptor(descriptor);
                }
            } else {
                started = gatt.readCharacteristic(currentGattOperation.characteristic);
            }
        } catch (SecurityException error) {
            started = false;
        }
        if (!started) {
            GattOperation failed = currentGattOperation;
            currentGattOperation = null;
            failed.errorCallback.accept(-1);
            pumpGattOperations();
        }
    }

    private void completeGattOperation(int status, byte[] value) {
        if (currentGattOperation == null) {
            return;
        }
        GattOperation completed = currentGattOperation;
        currentGattOperation = null;
        if (status == GATT_SUCCESS) {
            if (completed.valueCallback != null) {
                completed.valueCallback.accept(value);
            }
            completed.success.run();
        } else {
            completed.errorCallback.accept(status);
        }
        pumpGattOperations();
    }

    @SuppressLint("MissingPermission")
    private void registerBondReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already removed by Android.
        }
        receiverRegistered = false;
    }

    @SuppressLint("MissingPermission")
    private void disconnectAndCloseGatt() {
        if (gatt == null) {
            return;
        }
        try {
            gatt.disconnect();
        } catch (SecurityException ignored) {
        }
        gatt.close();
        gatt = null;
    }

    private void resetConnection() {
        handler.removeCallbacks(notificationSetupTimeout);
        notificationsReady = false;
        notificationWritePending = false;
        notificationDescriptorWritten = false;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        batteryCharacteristic = null;
        currentGattOperation = null;
        gattOperations.clear();
        requests.clear();
        clearCurrent();
        reassembler.reset();
        batteryPercent = -1;
    }

    private byte[] readStoredKey() {
        String encoded = preferences.getString(keyPreference(), null);
        if (encoded == null) {
            return null;
        }
        try {
            byte[] key = Base64.decode(encoded, Base64.NO_WRAP);
            return key.length == 16 ? key : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void saveKey(byte[] key) {
        preferences.edit().putString(keyPreference(), Base64.encodeToString(key, Base64.NO_WRAP)).apply();
    }

    private String keyPreference() {
        return "key_" + getAddress();
    }

    private void setState(State next, String detail) {
        state = next;
        stateDetail = detail;
        changed();
    }

    private void fail(String error) {
        log(error);
        setState(State.ERROR, error);
        message(error);
    }

    private void changed() {
        listener.onSessionChanged(this);
    }

    private void message(String value) {
        listener.onSessionMessage(this, value);
    }

    private void log(String line) {
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        logLines.addLast(time + "  " + line);
        while (logLines.size() > 80) {
            logLines.removeFirst();
        }
        changed();
    }

    private static String safeError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String bondStateName(int bondState) {
        if (bondState == BluetoothDevice.BOND_BONDED) {
            return "gekoppelt";
        }
        if (bondState == BluetoothDevice.BOND_BONDING) {
            return "Kopplung läuft";
        }
        return "nicht gekoppelt";
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static final class Request {
        final int opcode;
        final byte[] payload;
        final long timeoutMs;
        final ResponseCallback callback;
        Runnable timeoutRunnable;

        Request(int opcode, byte[] payload, long timeoutMs, ResponseCallback callback) {
            this.opcode = opcode;
            this.payload = payload;
            this.timeoutMs = timeoutMs;
            this.callback = callback;
        }
    }

    private static final class GattOperation {
        static final int TYPE_WRITE = 1;
        static final int TYPE_DESCRIPTOR = 2;
        static final int TYPE_READ = 3;

        final int type;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] value;
        final Runnable success;
        final ValueCallback valueCallback;
        final StatusCallback errorCallback;

        private GattOperation(int type, BluetoothGattCharacteristic characteristic,
                              BluetoothGattDescriptor descriptor, byte[] value,
                              Runnable success, ValueCallback valueCallback,
                              StatusCallback errorCallback) {
            this.type = type;
            this.characteristic = characteristic;
            this.descriptor = descriptor;
            this.value = value;
            this.success = success;
            this.valueCallback = valueCallback;
            this.errorCallback = errorCallback;
        }

        static GattOperation write(BluetoothGattCharacteristic characteristic, byte[] value,
                                   Runnable success, StatusCallback error) {
            return new GattOperation(TYPE_WRITE, characteristic, null, copy(value),
                    success, null, error);
        }

        static GattOperation descriptor(BluetoothGattDescriptor descriptor, byte[] value,
                                        Runnable success, StatusCallback error) {
            return new GattOperation(TYPE_DESCRIPTOR, null, descriptor, copy(value),
                    success, null, error);
        }

        static GattOperation read(BluetoothGattCharacteristic characteristic,
                                  ValueCallback callback, StatusCallback error) {
            return new GattOperation(TYPE_READ, characteristic, null, null,
                    () -> {
                    }, callback, error);
        }
    }

    private interface StatusCallback {
        void accept(int status);
    }
}
