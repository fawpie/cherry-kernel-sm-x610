package dev.fawpie.cherrymanager;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class CherryData {
    static final String STARTUP_ENABLED = "startup.enabled";
    static final String STARTUP_PROFILE = "startup.profile";
    static final String GITHUB_PROFILE = "https://github.com/fawpie";
    static final String[] PROFILES = {"balanced", "eco", "deep", "fast", "max"};
    static final String[] NAMES = {"Balanced", "Battery Saver", "Ultra Battery Saver", "Performance", "Ultra Performance"};
    static final String[] DETAILS = {
        "Restore saved CPU and GPU frequency limits and CPU governors.",
        "Cap CPU frequency at 75% and GPU frequency at 72% of the baseline.",
        "Lower CPU and GPU minimums to hardware limits and apply stronger frequency caps. Use schedutil where available.",
        "Use the schedutil governor on supported CPU clusters.",
        "Use the performance governor on supported CPU clusters."
    };

    private CherryData() { }

    static String profileName(String id) {
        int index = Arrays.asList(PROFILES).indexOf(id);
        if (index >= 0) return NAMES[index];
        return "custom".equals(id) ? "Custom" : "System settings";
    }

    static String startupProfile(Context context) {
        String id = settings(context).getString(STARTUP_PROFILE, "balanced");
        return Arrays.asList(PROFILES).contains(id) ? id : "balanced";
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("cherry", Context.MODE_PRIVATE);
    }

    static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static synchronized void log(Context context, String title, String detail) {
        SharedPreferences settings = settings(context);
        String when = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date());
        String row = when + "  ·  " + title + "  —  " + detail.replace('\n', ' ');
        List<String> existing = journal(context);
        existing.add(0, row);
        settings.edit().putString("journal", String.join("\n", existing.subList(0, Math.min(40, existing.size())))).apply();
    }

    static List<String> journal(Context context) {
        String saved = settings(context).getString("journal", "");
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split("\n")));
    }
}
