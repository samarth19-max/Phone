package dev.youbal.callrecphone;

import android.content.Context;
import android.content.SharedPreferences;

/** Local settings. The global recording switch only enables the feature; CallService requires fresh consent per call. */
final class Prefs {
    private final SharedPreferences prefs;
    Prefs(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    boolean recording() { return prefs.getBoolean("recording", false); }
    void recording(boolean on) { prefs.edit().putBoolean("recording", on).apply(); }

    /** AUTO (Shizuku, built-in ADB, then microphone), BUILTIN, SHIZUKU or MIC. */
    String mode() { return prefs.getString("mode", "AUTO"); }
    void mode(String value) { prefs.edit().putString("mode", value).apply(); }

    /** Delete recordings older than this many days; 0 keeps everything. */
    int keepDays() { return prefs.getInt("keep_days", 30); }
    void keepDays(int days) { prefs.edit().putInt("keep_days", days).apply(); }

    String lastStatus() { return prefs.getString("status", ""); }
    void lastStatus(String text) { prefs.edit().putString("status", text).apply(); }
}
