package dev.youbal.callrecphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.youbal.callrec.lib.BuiltinAccess;
import dev.youbal.callrec.lib.CallRecorderSetup;
import dev.youbal.callrec.lib.RecordingStore;
import java.io.File;
import java.util.Locale;

/** Setup (five steps with ticks), recording options, how the app works, and problem reports. */
public final class SettingsActivity extends Activity {
    private static final int REQ_PERMISSIONS = 19, REQ_ROLE = 12, REQ_SHIZUKU = 71, REQ_PAIR_NOTIFICATIONS = 72, REQ_LOCAL_NETWORK = 73;
    private PhoneApp app;
    private LinearLayout content;
    private Runnable pendingLocalNetworkAction;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); Ui.init(this); app = PhoneApp.get(this); }
    @Override protected void onResume() { super.onResume(); build(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); build(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        app.reloadNames(null);
        if (request == REQ_LOCAL_NETWORK) {
            Runnable action = pendingLocalNetworkAction;
            pendingLocalNetworkAction = null;
            build();
            if (has("android.permission.ACCESS_LOCAL_NETWORK")) {
                if (action != null) action.run();
            } else {
                Toast.makeText(this, "Local network access is required for built-in Wireless Debugging recording. Enable it in App info → Permissions → Nearby devices, or use Shizuku. Microphone call recording may be limited by the phone's audio policy.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        build();
        if (request == REQ_PAIR_NOTIFICATIONS) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                launchPairingSettings();
            } else {
                Toast.makeText(this, "Notifications are required to enter the Wireless debugging pairing code.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private int dp(float n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private boolean has(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    /** Request LAN access only when the user chooses the built-in ADB recorder. */
    private void withLocalNetworkPermission(Runnable action) {
        if (Build.VERSION.SDK_INT < 37 || has("android.permission.ACCESS_LOCAL_NETWORK")) {
            action.run();
            return;
        }
        pendingLocalNetworkAction = action;
        requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, REQ_LOCAL_NETWORK);
    }
    private boolean isDefaultPhone() { return DefaultPhone.held(this); }
    private String[] permissions() {
        return Build.VERSION.SDK_INT >= 33
            ? new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
            : new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO};
    }
    private boolean permissionsDone() { for (String p : permissions()) if (!has(p)) return false; return true; }

    private void build() {
        Ui.init(this);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom); return insets; });
        else root.setOnApplyWindowInsetsListener((v, i) -> { v.setPadding(dp(16) + i.getSystemWindowInsetLeft(), dp(8) + i.getSystemWindowInsetTop(), dp(16) + i.getSystemWindowInsetRight(), dp(8) + i.getSystemWindowInsetBottom()); return i; });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = Ui.row(this);
        header.addView(Ui.icon(this, "Back", Ui.INK, v -> finish()), lp(dp(48), dp(48)));
        header.addView(Ui.title(this, "Settings", 22), lp(0, -2, 1)); root.addView(header, lp(-1, dp(56)));
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); content = Ui.column(this); scroll.addView(content); root.addView(scroll, lp(-1, 0, 1));
        setupCard(); recordingCard(); helpCard(); reportCard(); aboutCard();
    }

    private void step(LinearLayout card, int n, boolean done, String title, String text) {
        TextView t = Ui.title(this, n + "   " + title + (done ? "   ✓" : ""), 16); t.setTextColor(done ? Ui.OK : Ui.INK); t.setPadding(0, dp(10), 0, 0); card.addView(t); card.addView(Ui.text(this, text, 13, Ui.MUTED));
    }

    // ---- setup -------------------------------------------------------------------------------------------------------
    private void setupCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Setup", 18));
        BuiltinAccess.State builtin = BuiltinAccess.state(this);
        CallRecorderSetup.State shizuku = CallRecorderSetup.state(this);
        boolean access = builtin == BuiltinAccess.State.RUNNING || shizuku == CallRecorderSetup.State.READY;

        step(card, 1, permissionsDone(), "Allow restricted settings and permissions", "App info \u2192 \u22EE (top right) \u2192 Allow restricted settings. Then allow the permissions.");
        card.addView(Ui.button(this, "Open app info", false, v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        card.addView(Ui.button(this, permissionsDone() ? "Permissions allowed \u2713" : "Allow permissions", !permissionsDone(), v -> requestPermissions(permissions(), REQ_PERMISSIONS)));

        boolean isDefault = isDefaultPhone();
        step(card, 2, isDefault, "Set as default phone app", "Gives you the call screen and lets the app see your calls.");
        card.addView(Ui.button(this, isDefault ? "Default phone app \u2713" : "Set as default", !isDefault, v -> {
            if (isDefault) { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return; }
            Intent ask = DefaultPhone.request(this);
            if (ask != null) startActivityForResult(ask, REQ_ROLE);
            else Toast.makeText(this, "This phone does not offer the phone-app role.", Toast.LENGTH_LONG).show();
        }));

        step(card, 3, access, "Start recording access", "Enables the built-in recorder to capture both sides of a call where the phone supports it. Needs Wireless debugging (Android 11 or newer) and has to be started again after every reboot. Shizuku is an alternative; microphone capture is limited by Android call-audio policy.");
        if (Build.VERSION.SDK_INT >= 37)
            card.addView(Ui.text(this, "Android 17 also asks for Local network access when you start built-in Wireless Debugging recording. It is used for ADB discovery and connection. Revoking it does not stop an already-running shell recorder; use Turn off or Forget pairing to stop that process. Shizuku does not need this permission.", 13, Ui.MUTED));
        switch (builtin) {
            case UNSUPPORTED:
                card.addView(Ui.text(this, "Not available on this phone (needs Android 11 or newer). Use Shizuku below. Microphone mode may not capture call audio on Android 17.", 13, Ui.WARN));
                break;
            case NOT_PAIRED:
                card.addView(Ui.button(this, "Pair and start", true, v -> pairDialog()));
                break;
            case STOPPED:
                card.addView(Ui.button(this, "Start recording access", true, v -> startAccess()));
                card.addView(Ui.button(this, "Pair again", false, v -> pairDialog()));
                break;
            case RUNNING:
                card.addView(Ui.button(this, "Recording access is on \u2713", false, v -> Toast.makeText(this, "Running. It stops when the phone restarts.", Toast.LENGTH_SHORT).show()));
                card.addView(Ui.button(this, "Turn off", false, v -> { BuiltinAccess.stop(this); Toast.makeText(this, "Turning off\u2026", Toast.LENGTH_SHORT).show(); card.postDelayed(this::build, 1500); }));
                break;
        }
        if (builtin != BuiltinAccess.State.NOT_PAIRED && builtin != BuiltinAccess.State.UNSUPPORTED)
            card.addView(Ui.button(this, "Forget pairing", false, v -> new AlertDialog.Builder(this).setTitle("Forget pairing?").setMessage("Recording access stops and you will have to pair again.").setPositiveButton("Forget", (d, n) -> { BuiltinAccess.forget(this); build(); }).setNegativeButton("Cancel", null).show()));

        card.addView(Ui.text(this, "Prefer the Shizuku app? It works too: install it, start it, then allow this app in it.", 13, Ui.MUTED));
        card.addView(Ui.button(this, shizuku == CallRecorderSetup.State.NOT_INSTALLED ? "Install Shizuku" : "Open Shizuku", false, v -> startActivity(CallRecorderSetup.shizukuIntent(this))));
        card.addView(Ui.button(this, "Shizuku setup guide", false, v -> startActivity(CallRecorderSetup.guideIntent())));
        if (shizuku == CallRecorderSetup.State.NEEDS_PERMISSION || shizuku == CallRecorderSetup.State.READY)
            card.addView(Ui.button(this, shizuku == CallRecorderSetup.State.READY ? "Allowed in Shizuku \u2713" : "Allow in Shizuku", shizuku != CallRecorderSetup.State.READY, v -> CallRecorderSetup.requestPermission(REQ_SHIZUKU)));

        boolean tested = !new RecordingStore(app.recordingsDir()).list().isEmpty();
        step(card, 4, tested, "Make a test call", "Call someone for a few seconds. The recording appears in the Recordings tab.");
    }

    private void pairDialog() {
        new AlertDialog.Builder(this).setTitle("Pair with wireless debugging").setMessage(
            "1. Turn on Developer options, then Wireless debugging (Wi-Fi must be on).\n"
            + "2. Tap “Pair device with pairing code” and leave that box open.\n"
            + "3. Pull down the notification from this app and type the six-digit code.\n\n"
            + "You only do this once. Allow notifications first if you have not. On Android 17, also allow Local network access when prompted; it is used to find this phone's own Wireless Debugging service.")
            .setPositiveButton("Continue", (d, n) -> {
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PAIR_NOTIFICATIONS);
                } else {
                    launchPairingSettings();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void launchPairingSettings() {
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            Toast.makeText(this, "Allow notifications before pairing.", Toast.LENGTH_LONG).show();
            return;
        }
        withLocalNetworkPermission(this::launchPairingSettingsWithPermission);
    }

    private void launchPairingSettingsWithPermission() {
        startForegroundService(new Intent(this, PairingService.class));
        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    private void startAccess() {
        withLocalNetworkPermission(this::startAccessWithPermission);
    }

    private void startAccessWithPermission() {
        Toast.makeText(this, "Starting…", Toast.LENGTH_SHORT).show();
        BuiltinAccess.start(this, new BuiltinAccess.Listener() {
            @Override public void onStatus(String message) { app.main.post(() -> Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show()); }
            @Override public void onDone(boolean ok, String message) { app.prefs.lastStatus(message); app.main.post(() -> { Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show(); build(); }); }
        });
    }

    // ---- recording ---------------------------------------------------------------------------------------------------
    private void recordingCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Recording", 18));
        boolean on = app.prefs.recording();
        card.addView(Ui.text(this, on ? "On. Recording starts only after you confirm that everyone on each call was told and agreed." : "Off. Nothing is recorded.", 14, on ? Ui.OK : Ui.MUTED));
        card.addView(Ui.button(this, on ? "Turn recording off" : "Turn recording on", !on, v -> {
            if (on) {
                app.prefs.recording(false);
                build();
            } else {
                new AlertDialog.Builder(this)
                    .setTitle("Enable call recording?")
                    .setMessage("This enables the recording feature, but does not start recording calls automatically. Before each call is recorded, the call screen will require you to confirm that you have told every participant and each person has explicitly agreed. This app cannot notify the other caller or verify consent. Stop recording immediately if anyone objects.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Enable recording", (dialog, which) -> {
                        app.prefs.recording(true);
                        build();
                    })
                    .show();
            }
        }));
        String[] modes = {"AUTO", "BUILTIN", "SHIZUKU", "MIC"}, modeNames = {"Automatic: Shizuku, built-in access, then microphone", "Built-in access only: both sides where supported", "Shizuku app only: both sides where supported", "Microphone only: speakerphone; not reliable for call audio on Android 17"};
        int current = 0; for (int i = 0; i < modes.length; i++) if (modes[i].equals(app.prefs.mode())) current = i;
        card.addView(Ui.button(this, "Source: " + modeNames[current].split(":")[0], false, v -> Ui.sheet(this, "Recording source", modeNames, indexOf(modes, app.prefs.mode()), n -> { app.prefs.mode(modes[n]); build(); }).show()));
        if (Build.VERSION.SDK_INT >= 37 && app.prefs.mode().equals("MIC"))
            card.addView(Ui.text(this, "Android 17 restricts ordinary-app microphone capture during active voice calls. Microphone mode may not capture either side of the call. For call recording, use Shizuku or the built-in Wireless Debugging recorder.", 13, Ui.WARN));
        int[] days = {0, 7, 30, 90}; String[] dayNames = {"Keep forever", "Delete after 7 days", "Delete after 30 days", "Delete after 90 days"};
        int keep = 0; for (int i = 0; i < days.length; i++) if (days[i] == app.prefs.keepDays()) keep = i;
        card.addView(Ui.button(this, dayNames[keep], false, v -> Ui.sheet(this, "Keep recordings", dayNames, indexOf(days, app.prefs.keepDays()), n -> { app.prefs.keepDays(days[n]); app.io.execute(() -> new RecordingStore(app.recordingsDir()).deleteOlderThan(days[n], System.currentTimeMillis())); build(); }).show()));
        RecordingStore store = new RecordingStore(app.recordingsDir()); int count = store.list().size(); long bytes = store.totalBytes();
        card.addView(Ui.text(this, count + (count == 1 ? " recording" : " recordings") + " · " + (bytes < 1048576 ? Math.max(0, bytes / 1024) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)), 13, Ui.MUTED));
        card.addView(Ui.text(this, "Saved in this app's own folder on the phone. Nothing is uploaded.", 13, Ui.MUTED));
        String status = app.prefs.lastStatus(); if (!status.isEmpty()) card.addView(Ui.text(this, "Last: " + status, 13, Ui.MUTED));
        card.addView(Ui.text(this, "Recording calls may need everyone's consent where you live. You are responsible for following the law.", 12, Ui.WARN));
    }
    private static int indexOf(String[] values, String value) { for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i; return 0; }
    private static int indexOf(int[] values, int value) { for (int i = 0; i < values.length; i++) if (values[i] == value) return i; return 0; }

    // ---- help, problems, about ---------------------------------------------------------------------------------------
    private void helpCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "How it works", 18));
        String[][] items = {
            {"Recents", "Your calls, with names. Tap a row to call back. Search any name or number."},
            {"Contacts", "People saved in Google, on the phone or on the SIM. Tap one for numbers and a note."},
            {"Dialpad", "Type or paste a number. Saved names show as suggestions."},
            {"Call screen", "Shows who is calling, where they are saved, your last call and your note. Answer, Decline, Silence, Mute, Speaker, Hold, Keypad, End."},
            {"Notes", "A short note per number. Tap the note on the call screen or in a contact."},
            {"Recording", "For each eligible one-to-one call, the call screen asks you to confirm that everyone has been told and has agreed. A visible recording indicator and stop control remain available."},
            {"Recordings", "Play, share or delete. Old ones can delete themselves."},
            {"Something broke", "Settings → Problem report → Copy."}};
        for (String[] item : items) { TextView t = Ui.title(this, item[0], 15); t.setPadding(0, dp(10), 0, 0); card.addView(t); card.addView(Ui.text(this, item[1], 13, Ui.MUTED)); }
    }
    private void reportCard() {
        String problem = CrashLog.read(this); if (problem.isEmpty()) return;
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "Problem report", 18));
        card.addView(Ui.text(this, "Saved on this phone only. Copy it when you report a bug.", 13, Ui.MUTED));
        TextView trace = Ui.text(this, problem.length() > 1200 ? problem.substring(0, 1200) + "…" : problem, 11, Ui.MUTED); trace.setTextIsSelectable(true); card.addView(trace);
        card.addView(Ui.button(this, "Copy report", true, v -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Callrec Phone report", problem)); Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show(); }));
        card.addView(Ui.button(this, "Clear report", false, v -> { CrashLog.clear(this); build(); }));
    }
    private void aboutCard() {
        LinearLayout card = Ui.card(this); content.addView(card); card.addView(Ui.title(this, "About", 18));
        String version = ""; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { /* Shown without a version. */ }
        card.addView(Ui.text(this, "Callrec Phone " + version + ". Open source. No account, no ads, no tracking: your calls, contacts and recordings never leave the phone. The network permission is only used to talk to this phone\u2019s own wireless debugging.", 13, Ui.MUTED));
        card.addView(Ui.text(this, "Call recording by callrec. Uses this phone\u2019s own wireless debugging, or the Shizuku app if you prefer it.", 13, Ui.MUTED));
        card.addView(Ui.button(this, "Source code", false, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/samarth19-max/Phone")))));
    }
}
