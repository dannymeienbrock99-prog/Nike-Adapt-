package de.batto.lacelink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import de.batto.lacelink.ble.AdaptBleManager;
import de.batto.lacelink.ble.ShoeSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Single-screen controller for two Adapt BB / BB 2.0 shoes. */
public final class MainActivity extends Activity implements AdaptBleManager.Listener {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private static final int REQUEST_ENABLE_BLUETOOTH = 101;

    private static final int COLOR_BACKGROUND = Color.rgb(13, 15, 18);
    private static final int COLOR_SURFACE = Color.rgb(23, 27, 33);
    private static final int COLOR_RAISED = Color.rgb(34, 40, 49);
    private static final int COLOR_TEXT = Color.rgb(245, 247, 250);
    private static final int COLOR_MUTED = Color.rgb(168, 176, 189);
    private static final int COLOR_ACCENT = Color.rgb(112, 224, 0);
    private static final int COLOR_DANGER = Color.rgb(255, 93, 115);

    private AdaptBleManager bleManager;
    private LinearLayout candidateContainer;
    private LinearLayout sessionContainer;
    private TextView scanStatus;
    private Button scanButton;
    private List<AdaptBleManager.ScanCandidate> candidates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        bleManager = new AdaptBleManager(this, this);
        setContentView(buildContent());
        ensurePermissionsAndBluetooth();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root, matchWrap());

        TextView eyebrow = text("LACELINK  /  OFFLINE  ·  V0.1.3", 12, COLOR_ACCENT);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.14f);
        root.addView(eyebrow);

        TextView title = text("Deine Schuhe.\nOhne Cloud.", 34, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLineSpacing(0, 0.92f);
        root.addView(title, topMargin(wrapWrap(), 8));

        TextView subtitle = text("Experimentelle Steuerung für Adapt BB / BB 2.0. "
                + "Keine Anmeldung, kein Internet und keine Nike-Dienste.", 15, COLOR_MUTED);
        subtitle.setLineSpacing(dp(4), 1f);
        root.addView(subtitle, topMargin(matchWrap(), 12));

        LinearLayout warning = card();
        TextView warningTitle = text("Sicher testen", 17, COLOR_TEXT);
        warningTitle.setTypeface(Typeface.DEFAULT_BOLD);
        warning.addView(warningTitle);
        warning.addView(text("Beim ersten Test den Fuß aus dem Schuh nehmen. "
                + "Die App sendet nur kurze Motorbewegungen und nie automatisch beim Verbinden.",
                14, COLOR_MUTED), topMargin(matchWrap(), 8));
        root.addView(warning, topMargin(matchWrap(), 24));

        root.addView(sectionTitle("1  Schuhe finden"), topMargin(matchWrap(), 28));
        scanStatus = text("Bluetooth wird geprüft …", 14, COLOR_MUTED);
        root.addView(scanStatus, topMargin(matchWrap(), 7));
        TextView pairingHint = text("Erstes Koppeln: Schuhe aufwecken und den Android-Systemdialog "
                + "bestätigen. Eine Taste erst dann einmal kurz drücken, wenn „TASTE DRÜCKEN“ erscheint.",
                13, COLOR_MUTED);
        pairingHint.setLineSpacing(dp(2), 1f);
        root.addView(pairingHint, topMargin(matchWrap(), 8));
        scanButton = actionButton("Schuhe suchen", true);
        scanButton.setOnClickListener(view -> ensurePermissionsAndBluetooth());
        root.addView(scanButton, topMargin(matchWrap(), 12));

        candidateContainer = column();
        root.addView(candidateContainer, topMargin(matchWrap(), 12));

        root.addView(sectionTitle("2  Verbundene Schuhe"), topMargin(matchWrap(), 30));
        sessionContainer = column();
        root.addView(sessionContainer, topMargin(matchWrap(), 12));
        renderSessions(bleManager.getSessions());

        TextView footer = text("Inoffizielles Clean-Room-Projekt. Nicht mit Nike, Inc. verbunden. "
                + "Die Schlüssel bleiben ausschließlich auf diesem Gerät.", 12, COLOR_MUTED);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, topMargin(matchWrap(), 32));
        return scroll;
    }

    private void ensurePermissionsAndBluetooth() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
            return;
        }
        if (!bleManager.isAvailable()) {
            scanStatus.setText("Bluetooth LE ist auf diesem Handy nicht verfügbar.");
            return;
        }
        if (!bleManager.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
            } catch (SecurityException error) {
                showMessage("Bluetooth darf nicht eingeschaltet werden.");
            }
            return;
        }
        bleManager.startScan();
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) {
            return;
        }
        boolean granted = true;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted && grantResults.length > 0) {
            ensurePermissionsAndBluetooth();
        } else {
            scanStatus.setText("Berechtigung „Geräte in der Nähe“ wird benötigt.");
            scanButton.setText("Berechtigung erneut anfragen");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (bleManager.isEnabled()) {
                bleManager.startScan();
            } else {
                scanStatus.setText("Bluetooth ist ausgeschaltet.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && bleManager != null) {
            bleManager.close();
        }
        super.onDestroy();
    }

    @Override
    public void onCandidatesChanged(List<AdaptBleManager.ScanCandidate> value) {
        candidates = value;
        renderCandidates();
    }

    @Override
    public void onScanStateChanged(boolean scanning, String message) {
        scanStatus.setText(message);
        scanButton.setText(scanning ? "Scan stoppen" : "Erneut suchen");
        scanButton.setOnClickListener(scanning
                ? view -> bleManager.stopScan("Scan angehalten")
                : view -> ensurePermissionsAndBluetooth());
    }

    @Override
    public void onSessionsChanged(Collection<ShoeSession> sessions) {
        renderSessions(sessions);
    }

    @Override
    public void onMessage(String message) {
        showMessage(message);
    }

    private void renderCandidates() {
        candidateContainer.removeAllViews();
        if (candidates.isEmpty()) {
            candidateContainer.addView(text("Noch kein benanntes BLE-Gerät gefunden. "
                    + "Schuhe aufwecken und beide Tasten kurz drücken.", 14, COLOR_MUTED));
            return;
        }
        int count = Math.min(candidates.size(), 20);
        for (int index = 0; index < count; index++) {
            AdaptBleManager.ScanCandidate candidate = candidates.get(index);
            LinearLayout row = card();
            row.setPadding(dp(16), dp(14), dp(12), dp(14));

            LinearLayout heading = horizontal();
            LinearLayout copy = column();
            TextView name = text(candidate.name, 16, COLOR_TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            copy.addView(name);
            String details = candidate.address + (candidate.rssi == 0 ? "" : "  ·  " + candidate.rssi + " dBm")
                    + (candidate.advertisesCoreRf ? "  ·  CoreRF" : "");
            copy.addView(text(details, 12,
                    candidate.advertisesCoreRf ? COLOR_ACCENT : COLOR_MUTED), topMargin(matchWrap(), 4));
            heading.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button connect = smallButton("Verbinden");
            connect.setOnClickListener(view -> bleManager.connect(candidate));
            heading.addView(connect);
            row.addView(heading);
            candidateContainer.addView(row, topMargin(matchWrap(), index == 0 ? 0 : 9));
        }
    }

    private void renderSessions(Collection<ShoeSession> sessions) {
        sessionContainer.removeAllViews();
        if (sessions.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Noch kein Schuh verbunden", 16, COLOR_TEXT));
            empty.addView(text("Du kannst zwei Schuhe gleichzeitig hinzufügen.", 13, COLOR_MUTED),
                    topMargin(matchWrap(), 6));
            sessionContainer.addView(empty);
            return;
        }
        int index = 0;
        for (ShoeSession session : sessions) {
            sessionContainer.addView(buildSessionCard(session),
                    topMargin(matchWrap(), index++ == 0 ? 0 : 12));
        }
    }

    private View buildSessionCard(ShoeSession session) {
        LinearLayout card = card();
        LinearLayout header = horizontal();

        LinearLayout labels = column();
        TextView name = text(session.getDisplayName(), 19, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(name);
        labels.addView(text(session.getAddress(), 12, COLOR_MUTED), topMargin(matchWrap(), 3));
        header.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView battery = pill(session.getBatteryPercent() < 0
                ? "Akku –" : session.getBatteryPercent() + "%",
                session.getBatteryPercent() >= 20 ? COLOR_ACCENT : COLOR_DANGER);
        header.addView(battery);
        card.addView(header);

        TextView status = text(stateLabel(session.getState()) + "  ·  " + session.getStateDetail(),
                14, stateColor(session.getState()));
        status.setLineSpacing(dp(2), 1f);
        card.addView(status, topMargin(matchWrap(), 14));

        if (session.getState() == ShoeSession.State.BONDING) {
            TextView help = text("Bestätige den Android-Systemdialog. Für den App-Schlüssel noch "
                    + "keine Taste gedrückt halten.", 13, COLOR_MUTED);
            help.setLineSpacing(dp(2), 1f);
            card.addView(help, topMargin(matchWrap(), 9));
        } else if (session.getState() == ShoeSession.State.WAITING_FOR_CONFIRMATION) {
            TextView help = text("Jetzt eine der leuchtenden Tasten am Schuh einmal kurz drücken – "
                    + "nicht gedrückt halten.", 14, COLOR_ACCENT);
            help.setTypeface(Typeface.DEFAULT_BOLD);
            help.setLineSpacing(dp(2), 1f);
            card.addView(help, topMargin(matchWrap(), 9));
        } else if (session.getState() == ShoeSession.State.ALREADY_PAIRED) {
            TextView help = text("System-Reset für beide Schuhe:\n"
                    + "1. Beide Tasten 5 Sekunden halten; bei roten LEDs loslassen.\n"
                    + "2. Eine Taste halten. Sobald die Lichter angehen, die andere Taste dreimal "
                    + "drücken, bis die Lichter grün werden.\n"
                    + "3. Am zweiten Schuh wiederholen.\n"
                    + "4. Danach beide Schuhe in den Android-Bluetooth-Einstellungen vergessen.",
                    13, COLOR_DANGER);
            help.setLineSpacing(dp(3), 1f);
            card.addView(help, topMargin(matchWrap(), 9));
        }

        LinearLayout setupActions = horizontalScrollRow();
        Button pair = smallButton("Schlüssel koppeln");
        pair.setEnabled(session.canPairApplicationKey());
        pair.setAlpha(pair.isEnabled() ? 1f : 0.4f);
        pair.setOnClickListener(view -> session.pairApplicationKey());
        setupActions.addView(pair);

        if (session.getState() == ShoeSession.State.ALREADY_PAIRED) {
            Button bluetoothSettings = smallButton("Bluetooth-Einstellungen");
            bluetoothSettings.setOnClickListener(view -> openBluetoothSettings());
            setupActions.addView(bluetoothSettings, leftMargin(wrapWrap(), 8));
        }

        Button refresh = smallButton("Akku lesen");
        refresh.setEnabled(session.isReady());
        refresh.setAlpha(refresh.isEnabled() ? 1f : 0.4f);
        refresh.setOnClickListener(view -> session.refreshBattery());
        setupActions.addView(refresh, leftMargin(wrapWrap(), 8));

        Button reconnect = smallButton(session.canConnect() ? "Neu verbinden" : "Trennen");
        reconnect.setOnClickListener(view -> {
            if (session.canConnect()) {
                session.connect();
            } else {
                session.disconnect();
            }
        });
        setupActions.addView(reconnect, leftMargin(wrapWrap(), 8));
        card.addView(wrapHorizontal(setupActions), topMargin(matchWrap(), 14));

        TextView laceTitle = text("SCHNÜRUNG", 11, COLOR_MUTED);
        laceTitle.setLetterSpacing(0.12f);
        laceTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(laceTitle, topMargin(matchWrap(), 22));

        LinearLayout laceActions = horizontal();
        Button loosen = actionButton("−  Weiter", false);
        loosen.setEnabled(session.isReady());
        loosen.setAlpha(loosen.isEnabled() ? 1f : 0.4f);
        loosen.setOnClickListener(view -> session.loosen());
        laceActions.addView(loosen, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button tighten = actionButton("+  Enger", true);
        tighten.setEnabled(session.isReady());
        tighten.setAlpha(tighten.isEnabled() ? 1f : 0.4f);
        tighten.setOnClickListener(view -> session.tighten());
        LinearLayout.LayoutParams tightenParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        tightenParams.leftMargin = dp(9);
        laceActions.addView(tighten, tightenParams);
        card.addView(laceActions, topMargin(matchWrap(), 9));

        Button stop = actionButton("Motor stoppen", false);
        stop.setTextColor(COLOR_DANGER);
        stop.setEnabled(session.isReady());
        stop.setAlpha(stop.isEnabled() ? 1f : 0.4f);
        stop.setOnClickListener(view -> session.stopMotor());
        card.addView(stop, topMargin(matchWrap(), 8));

        TextView positionLabel = text("Zielposition: 50%", 13, COLOR_MUTED);
        card.addView(positionLabel, topMargin(matchWrap(), 16));
        SeekBar position = new SeekBar(this);
        position.setMax(100);
        position.setProgress(50);
        position.setEnabled(session.isReady());
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean touched;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                positionLabel.setText("Zielposition: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                touched = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (touched) {
                    session.setPosition(seekBar.getProgress());
                }
                touched = false;
            }
        });
        card.addView(position, topMargin(matchWrap(), 2));

        TextView lightTitle = text("LICHT", 11, COLOR_MUTED);
        lightTitle.setLetterSpacing(0.12f);
        lightTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(lightTitle, topMargin(matchWrap(), 18));
        LinearLayout colors = horizontalScrollRow();
        colors.addView(colorButton("Aus", Color.BLACK, session, 0, 0, 0));
        colors.addView(colorButton("Grün", COLOR_ACCENT, session, 112, 224, 0), leftMargin(wrapWrap(), 8));
        colors.addView(colorButton("Blau", Color.rgb(54, 133, 255), session, 54, 133, 255), leftMargin(wrapWrap(), 8));
        colors.addView(colorButton("Rot", Color.rgb(255, 75, 94), session, 255, 75, 94), leftMargin(wrapWrap(), 8));
        colors.addView(colorButton("Violett", Color.rgb(170, 95, 255), session, 170, 95, 255), leftMargin(wrapWrap(), 8));
        colors.addView(colorButton("Weiß", Color.WHITE, session, 255, 255, 255), leftMargin(wrapWrap(), 8));
        card.addView(wrapHorizontal(colors), topMargin(matchWrap(), 9));

        LinearLayout utility = horizontal();
        Button log = smallButton("Diagnoseprotokoll");
        log.setOnClickListener(view -> showLog(session));
        utility.addView(log, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button remove = smallButton("Entfernen");
        remove.setTextColor(COLOR_DANGER);
        remove.setOnClickListener(view -> bleManager.removeSession(session));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        removeParams.leftMargin = dp(8);
        utility.addView(remove, removeParams);
        card.addView(utility, topMargin(matchWrap(), 20));

        setControlsEnabled(card, session.isReady());
        // Setup and utility actions remain usable before authentication.
        setupActions.setEnabled(true);
        utility.setEnabled(true);
        return card;
    }

    private void setControlsEnabled(LinearLayout card, boolean ready) {
        // The cards are rebuilt on each state change. Explicit controls already guard
        // their commands in ShoeSession; visual dimming keeps the setup readable.
        if (!ready) {
            card.setAlpha(0.94f);
        }
    }

    private Button colorButton(String label, int swatch, ShoeSession session, int red, int green, int blue) {
        Button button = smallButton(label);
        button.setCompoundDrawablePadding(dp(6));
        GradientDrawable marker = new GradientDrawable();
        marker.setShape(GradientDrawable.OVAL);
        marker.setColor(swatch);
        marker.setStroke(dp(1), Color.rgb(100, 108, 120));
        marker.setSize(dp(14), dp(14));
        button.setCompoundDrawablesWithIntrinsicBounds(marker, null, null, null);
        button.setEnabled(session.isReady());
        button.setAlpha(button.isEnabled() ? 1f : 0.4f);
        button.setOnClickListener(view -> session.setColor(red, green, blue));
        return button;
    }

    private void showLog(ShoeSession session) {
        LinearLayout content = column();
        int padding = dp(18);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(COLOR_SURFACE);
        TextView logText = text(session.getLogText().isEmpty() ? "Noch keine Einträge." : session.getLogText(),
                12, COLOR_TEXT);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        content.addView(logText);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle(session.getDisplayName() + " – Diagnose")
                .setView(scroll)
                .setPositiveButton("Kopieren", (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("LaceLink Diagnose", session.getLogText()));
                    showMessage("Diagnoseprotokoll kopiert.");
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (RuntimeException error) {
            showMessage("Bluetooth-Einstellungen konnten nicht geöffnet werden.");
        }
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 18, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    private String stateLabel(ShoeSession.State state) {
        switch (state) {
            case CONNECTING:
                return "VERBINDEN";
            case DISCOVERING:
                return "PRÜFEN";
            case BONDING:
                return "ANDROID-KOPPLUNG";
            case ENABLING_NOTIFICATIONS:
                return "BLE-EINRICHTUNG";
            case NEEDS_APP_PAIRING:
                return "APP-KOPPLUNG";
            case KEY_EXCHANGE:
                return "SCHLÜSSEL";
            case WAITING_FOR_CONFIRMATION:
                return "TASTE DRÜCKEN";
            case ALREADY_PAIRED:
                return "BEREITS GEKOPPELT";
            case AUTHENTICATING:
                return "ANMELDUNG";
            case READY:
                return "BEREIT";
            case ERROR:
                return "FEHLER";
            default:
                return "GETRENNT";
        }
    }

    private int stateColor(ShoeSession.State state) {
        if (state == ShoeSession.State.READY
                || state == ShoeSession.State.WAITING_FOR_CONFIRMATION) {
            return COLOR_ACCENT;
        }
        if (state == ShoeSession.State.ERROR || state == ShoeSession.State.ALREADY_PAIRED) {
            return COLOR_DANGER;
        }
        return COLOR_MUTED;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_SURFACE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(47, 54, 64));
        card.setBackground(background);
        return card;
    }

    private Button actionButton(String label, boolean accent) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(accent ? COLOR_BACKGROUND : COLOR_TEXT);
        button.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent ? COLOR_ACCENT : COLOR_RAISED);
        background.setCornerRadius(dp(13));
        if (!accent) {
            background.setStroke(dp(1), Color.rgb(62, 70, 82));
        }
        button.setBackground(background);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button smallButton(String label) {
        Button button = actionButton(label, false);
        button.setTextSize(13);
        button.setMinHeight(dp(40));
        button.setMinimumWidth(0);
        return button;
    }

    private TextView pill(String value, int color) {
        TextView pill = text(value, 13, color);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(11), dp(7), dp(11), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_RAISED);
        background.setCornerRadius(dp(99));
        pill.setBackground(background);
        return pill;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout horizontalScrollRow() {
        LinearLayout row = horizontal();
        row.setPadding(0, 0, dp(4), 0);
        return row;
    }

    private HorizontalScrollView wrapHorizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, wrapWrap());
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int marginDp) {
        params.topMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams leftMargin(LinearLayout.LayoutParams params, int marginDp) {
        params.leftMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
