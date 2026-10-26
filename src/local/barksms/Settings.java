package local.barksms;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

final class Settings {
    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    String endpoint() { return prefs.getString("endpoint", ""); }
    String group() { return prefs.getString("group", "短信"); }
    boolean archive() { return prefs.getBoolean("archive", false); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    boolean savePushSettings(String endpoint, String group, boolean archive) {
        return prefs.edit().putString("endpoint", endpoint).putString("group", Policy.groupName(group))
                .putBoolean("archive", archive).commit();
    }
    boolean setEnabled(boolean value) { return prefs.edit().putBoolean("enabled", value).commit(); }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }
}
