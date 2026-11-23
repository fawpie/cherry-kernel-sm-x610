package dev.fawpie.cherrymanager;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.system.Os;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private static final int MAX_CONTENT_WIDTH = 1120;
    private static final int TILE_GAP = 10;
    private static final String[] APPEARANCE_CHOICES = {"System default", "Light", "Dark"};
    private static final String[] APPEARANCE_VALUES = {"system", "light", "dark"};
    private static final Palette LIGHT = new Palette("#FFF7F8", "#FFFCFD", "#30232B", "#685461",
            "#993956", "#F1DFE5", "#F9E8ED", "#F7DFE7", "#EDF2E6", "#40533B", "#FFFFFF");
    private static final Palette DARK = new Palette("#1D161A", "#271E23", "#F8EBF0", "#CFB7C3",
            "#FFA9C3", "#4E3743", "#3B2732", "#462C3A", "#29372A", "#C2DFBC", "#381322");
    private static final int SETTINGS_PAGE = 6;
    private static final String[] PAGE_NAMES = {"Overview", "Profiles", "Hardware", "Thermal", "System", "Activity log", "Settings"};
    private static final String[] NAV_NAMES = {"Overview", "Profiles", "Hardware", "Thermal", "System", "Log"};
    private static final String[] PAGE_DESCRIPTIONS = {
        "Device at a glance", "Power profiles and per-app rules", "CPU clusters and graphics controls",
        "Sensors and temperature-based caps", "Display, power, memory, storage and network", "Setting changes and session results",
        "Appearance, startup and project links"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Runnable> bindings = new ArrayList<>();
    private final ArrayDeque<Integer> cpuHistory = new ArrayDeque<>();
    private final Runnable tick = this::sample;
    private final int[] scrollPositions = new int[PAGE_NAMES.length];
    private KernelClient kernel;
    private KernelClient.Snapshot state = new KernelClient.Snapshot();
    private LinearLayout body;
    private ScrollView scroll;
    private HorizontalScrollView navigationScroll;
    private Palette palette;
    private String appearance;
    private boolean wide;
    private int contentWidthDp;
    private boolean started;
    private boolean busy;
    private boolean sampling;
    private int page;
    private String error = "";
    private String automation = "";
    private String journalHead = "";
    private Runnable notificationAction;

    @Override public void onCreate(Bundle restored) {
        appearance = CherryData.settings(this).getString("appearance", "system");
        Configuration config = getResources().getConfiguration();
        boolean systemDark = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(appearance) || (!"light".equals(appearance) && systemDark);
        palette = dark ? DARK : LIGHT;
        String style = dark ? "CherryDarkTheme" : "CherryTheme";
        setTheme(getResources().getIdentifier(style, "style", getPackageName()));
        super.onCreate(restored);
        if (restored != null) {
            page = restored.getInt("page");
            int[] offsets = restored.getIntArray("scrollPositions");
            if (offsets != null) System.arraycopy(offsets, 0, scrollPositions, 0, Math.min(offsets.length, scrollPositions.length));
        }
        try {
            kernel = KernelClient.get(this);
        } catch (IOException failure) {
            error = "Could not start the kernel controller.";
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (scroll != null) scrollPositions[page] = scroll.getScrollY();
        out.putInt("page", page);
        out.putIntArray("scrollPositions", scrollPositions.clone());
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        started = true;
        render(true);
        schedule(0);
    }

    @Override protected void onPause() {
        started = false;
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        if (started && kernel != null) main.postDelayed(tick, delay);
    }

    private void sample() {
        if (!started || isDestroyed()) return;
        if (sampling || busy) {
            schedule(1200);
            return;
        }
        sampling = true;
        worker.execute(() -> {
            KernelClient.Snapshot updated = null;
            String failure = "";
            try {
                updated = kernel.read();
            } catch (Exception problem) {
                failure = problem.getMessage() == null ? "Could not read kernel data." : problem.getMessage();
            }
            KernelClient.Snapshot result = updated;
            String message = failure;
            main.post(() -> {
                sampling = false;
                if (isDestroyed()) return;
                boolean redraw = !error.equals(message) || !automation.equals(SessionService.active);
                error = message;
                automation = SessionService.active;
                if (page == 5) {
                    List<String> journal = CherryData.journal(this);
                    String newest = journal.isEmpty() ? "" : journal.get(0);
                    redraw |= !newest.equals(journalHead);
                    journalHead = newest;
                }
                if (result != null) {
                    redraw |= !shape(state).equals(shape(result));
                    state = result;
                    rememberFrequency();
                } else {
                    state = new KernelClient.Snapshot();
                }
                if (started) {
                    if (redraw) render(true);
                    else updateBindings();
                    schedule(3200);
                }
            });
        });
    }

    private String shape(KernelClient.Snapshot snapshot) {
        return snapshot.release + snapshot.abi + snapshot.root + snapshot.kernelInterface + snapshot.session + snapshot.undo
                + snapshot.cpus.size() + (snapshot.gpu == null) + snapshot.zones.keySet()
                + snapshot.system.keySet() + snapshot.info.keySet() + snapshot.cooling.size()
                + snapshot.manual;
    }

    private void rememberFrequency() {
        int frequency = peak();
        if (frequency <= 0) return;
        if (cpuHistory.size() == 24) cpuHistory.removeFirst();
        cpuHistory.addLast(frequency);
    }

    private void updateBindings() {
        for (Runnable update : bindings) update.run();
    }

    private void render(boolean preserveScroll) {
        int position = preserveScroll && scroll != null ? scroll.getScrollY() : scrollPositions[page];
        int navPosition = preserveScroll && navigationScroll != null ? navigationScroll.getScrollX() : -1;
        bindings.clear();
        Configuration config = getResources().getConfiguration();
        int width = config.screenWidthDp;
        int textAllowance = Math.round(100 * Math.max(0, config.fontScale - 1));
        wide = width >= 820 + textAllowance;
        int railWidth = wide ? Math.min(230 + textAllowance, Math.max(180 + textAllowance, width / 5)) : 0;
        int outerWidth = width - railWidth;
        int gutter = wide ? 28 : 16;
        contentWidthDp = Math.min(MAX_CONTENT_WIDTH, outerWidth) - 2 * gutter;
        navigationScroll = null;

        LinearLayout root = box(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.base);
        if (wide) root.addView(navigationRail(), new LinearLayout.LayoutParams(dp(railWidth), -1));
        else root.addView(navigationHeader(navPosition), match());

        scroll = new ScrollView(this);
        if (wide) root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        else root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setFillViewport(true);
        body = box(LinearLayout.VERTICAL);
        body.setPadding(dp(gutter), dp(wide ? 25 : 14), dp(gutter), dp(23));
        int bodyWidth = outerWidth > MAX_CONTENT_WIDTH ? dp(MAX_CONTENT_WIDTH) : -1;
        scroll.addView(body, new ScrollView.LayoutParams(bodyWidth, -2, Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        pageHeader();

        if (!error.isEmpty()) {
            LinearLayout notice = card("Connection status", body, palette.pink);
            notice.addView(text(error, 14, palette.ink, false), match());
        }
        if (!state.session.isEmpty() && SessionService.active.isEmpty() && canTune()) {
            LinearLayout notice = card("Pending restore", body, palette.pink);
            note(notice, "Restore the CPU and GPU settings saved before the previous session.");
            action(notice, "Restore session settings", true, () -> apply("Session restore", "session-restore"));
        }
        switch (page) {
            case 0: overview(); break;
            case 1: profiles(); break;
            case 2: hardware(); break;
            case 3: thermal(); break;
            case 4: system(); break;
            case 5: history(); break;
            case SETTINGS_PAGE: settingsPage(); break;
            default: break;
        }
        if (position > 0) {
            ScrollView restored = scroll;
            restored.post(() -> restored.scrollTo(0, position));
        }
    }

    private LinearLayout navigationRail() {
        LinearLayout rail = box(LinearLayout.VERTICAL);
        rail.setBackgroundColor(palette.paper);
        rail.setPadding(dp(16), dp(22), dp(16), dp(15));
        rail.addView(brand(true), match());
        space(rail, 22);

        ScrollView navigation = new ScrollView(this);
        LinearLayout links = box(LinearLayout.VERTICAL);
        for (int index = 0; index < NAV_NAMES.length; index++) {
            final int destination = index;
            links.addView(navButton(NAV_NAMES[index], index == page, () -> go(destination)), spaced(0, 0, 0, 5));
        }
        navigation.addView(links, new ScrollView.LayoutParams(-1, -2));
        rail.addView(navigation, new LinearLayout.LayoutParams(-1, 0, 1));
        space(rail, 10);
        rail.addView(settingsButton(), match());
        space(rail, 7);
        rail.addView(tipText("SM-X610  ·  Android " + Build.VERSION.RELEASE), spaced(10, 0, 0, 0));
        return rail;
    }

    private LinearLayout navigationHeader(int previousOffset) {
        LinearLayout header = box(LinearLayout.VERTICAL);
        header.setBackgroundColor(palette.paper);
        header.setPadding(dp(16), dp(9), dp(16), dp(6));
        LinearLayout heading = box(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(brand(false), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(settingsButton(), new LinearLayout.LayoutParams(-2, -2));
        header.addView(heading, match());
        space(header, 4);

        HorizontalScrollView selector = new HorizontalScrollView(this);
        selector.setHorizontalScrollBarEnabled(false);
        LinearLayout links = box(LinearLayout.HORIZONTAL);
        for (int index = 0; index < NAV_NAMES.length; index++) {
            final int destination = index;
            TextView item = navButton(NAV_NAMES[index], index == page, () -> go(destination));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
            layout.setMarginEnd(dp(5));
            links.addView(item, layout);
        }
        selector.addView(links, new ScrollView.LayoutParams(-2, -2));
        header.addView(selector, match());
        navigationScroll = selector;
        int destination = page;
        selector.post(() -> {
            if (previousOffset >= 0) selector.scrollTo(previousOffset, 0);
            else if (destination < links.getChildCount()) {
                selector.scrollTo(links.getChildAt(destination).getLeft() - dp(12), 0);
            }
        });
        return header;
    }

    private TextView settingsButton() {
        return navButton("Settings", page == SETTINGS_PAGE, () -> go(SETTINGS_PAGE));
    }

    private void settingsPage() {
        SharedPreferences preferences = CherryData.settings(this);
        LinearLayout ui = card("Appearance", body, palette.paper);
        row(ui, "Theme", APPEARANCE_CHOICES[appearanceIndex()], this::chooseAppearance);

        LinearLayout startup = card("Startup", body, palette.paper);
        Switch toggle = new Switch(this);
        toggle.setText("Apply profile at boot");
        toggle.setTextColor(palette.ink);
        toggle.setMinimumHeight(dp(48));
        toggle.setChecked(preferences.getBoolean(CherryData.STARTUP_ENABLED, false));
        toggle.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(CherryData.STARTUP_ENABLED, checked).apply());
        startup.addView(toggle, match());
        row(startup, "Startup profile", CherryData.profileName(CherryData.startupProfile(this)),
                () -> showStartupProfiles(preferences));
        note(startup, "Applies after Android starts and root is available. A profile applied manually in the current boot takes priority.");

        LinearLayout project = card("Project", body, palette.paper);
        row(project, "GitHub", "fawpie", () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CherryData.GITHUB_PROFILE)));
            } catch (ActivityNotFoundException unavailable) {
                toast(CherryData.GITHUB_PROFILE);
            }
        });
    }

    private void showStartupProfiles(SharedPreferences preferences) {
        new AlertDialog.Builder(this).setTitle("Startup profile")
                .setSingleChoiceItems(CherryData.NAMES,
                        Arrays.asList(CherryData.PROFILES).indexOf(CherryData.startupProfile(this)), (dialog, position) -> {
                            preferences.edit().putString(CherryData.STARTUP_PROFILE, CherryData.PROFILES[position]).apply();
                            dialog.dismiss();
                            render(true);
                        })
                .setNegativeButton("Cancel", null).show();
    }

    private int appearanceIndex() {
        return Math.max(0, Arrays.asList(APPEARANCE_VALUES).indexOf(appearance));
    }

    private void chooseAppearance() {
        new AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(APPEARANCE_CHOICES, appearanceIndex(), (dialog, which) -> {
                    dialog.dismiss();
                    String choice = APPEARANCE_VALUES[which];
                    if (choice.equals(appearance)) return;
                    CherryData.settings(this).edit().putString("appearance", choice).apply();
                    recreate();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private LinearLayout brand(boolean stacked) {
        LinearLayout block = box(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        int iconId = getResources().getIdentifier("cherry_icon", "drawable", getPackageName());
        icon.setImageResource(iconId);
        int iconSize = dp(stacked ? 46 : 40);
        block.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView name = text(stacked ? "Cherry\nManager" : "Cherry Manager", 16, palette.ink, true);
        name.setLineSpacing(0, .9f);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -2, 1);
        nameLayout.setMarginStart(dp(9));
        block.addView(name, nameLayout);
        return block;
    }

    private void pageHeader() {
        boolean stack = contentWidthDp < 320 * getResources().getConfiguration().fontScale;
        LinearLayout top = box(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (!stack) top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = box(LinearLayout.VERTICAL);
        heading.addView(text(PAGE_NAMES[page], wide ? 29 : 24, palette.ink, true), match());
        heading.addView(tipText(PAGE_DESCRIPTIONS[page]), match());
        top.addView(heading, stack ? match() : new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = pill(state.root ? "Root enabled" : "Read only", state.root ? palette.leaf : palette.pink, state.root ? palette.leafInk : palette.accent);
        addHorizontal(top, badge, stack ? 8 : 0, 0, stack ? 0 : 10, 0);
        body.addView(top, match());
        space(body, 17);
    }

    private void overview() {
        LinearLayout hero = box(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(19), dp(22), dp(17));
        hero.setBackground(rounded(palette.hero, 23, 0));
        hero.addView(text(Build.MODEL.equals("SM-X610") ? "Galaxy Tab S9 FE+" : Build.MODEL, wide ? 35 : 30, palette.ink, true), match());
        space(hero, 4);
        hero.addView(text(Build.MODEL + "  ·  Android " + Build.VERSION.RELEASE, 14, palette.muted, false), match());
        space(hero, 15);
        String connection;
        if (state.release.isEmpty()) connection = "Waiting for kernel information";
        else if (state.abi.isEmpty() && state.kernelInterface) connection = "Kernel interface detected · Root required for version";
        else if (state.abi.isEmpty()) connection = "System kernel  ·  " + state.release;
        else connection = (Os.uname().version.contains("Cherry Kernel") ? "Cherry Kernel  ·  " : "")
                + "Kernel ABI " + state.abi + "  ·  " + variantName(state.variant);
        hero.addView(text(connection, 14, palette.accent, true), match());
        live(hero, () -> "Active profile  ·  " + manualProfile(), 14, palette.ink, false);
        if (!state.root) {
            space(hero, 9);
            actionCompact(hero, "Check root access", true, this::authorize);
        }
        body.addView(hero, separated());

        List<LinearLayout> metrics = new ArrayList<>();
        LinearLayout cpu = metric("Peak CPU clock", () -> frequency(Integer.toString(peak())));
        Sparkline graph = new Sparkline(this);
        cpu.addView(graph, new LinearLayout.LayoutParams(-1, dp(25)));
        bindings.add(graph::invalidate);
        metrics.add(cpu);
        metrics.add(metric("GPU clock", () -> state.gpu == null ? "—" : frequency(state.gpu[2])));
        metrics.add(metric("Battery", () -> batteryPercent() < 0 ? "—" : batteryPercent() + "%"));
        metrics.add(metric("Available RAM", () -> bytes(memory()[0])));
        grid(body, metrics, 4);
        smartCard();
    }

    private void smartCard() {
        LinearLayout card = card("Smart suggestion", body, palette.paper);
        live(card, () -> suggestion()[1], 14, palette.muted, false);
        space(card, 8);
        if (canTune() && state.session.isEmpty()) {
            actionCompact(card, "Review suggestion", false, () -> {
                String[] advice = suggestion();
                new AlertDialog.Builder(this).setTitle(CherryData.profileName(advice[0]))
                        .setMessage(advice[1] + "\n\n" + profileDetails(advice[0]))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply profile", (dialog, which) -> applyProfile(advice[0]))
                        .show();
            });
        }
    }

    private String[] suggestion() {
        int battery = batteryPercent();
        float temperature = hottestChip();
        int thermal = getSystemService(PowerManager.class).getCurrentThermalStatus();
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE || temperature >= 65) {
            String condition = temperature >= 65 ? "Hottest chip sensor: " + celsius(temperature) + "." : "Android reports elevated thermal load.";
            return new String[]{"eco", condition + " Battery Saver lowers the CPU and GPU frequency caps."};
        }
        if (battery >= 0 && battery <= 22 && !batteryConnected()) {
            return new String[]{"eco", "Battery is " + battery + "% and unplugged. Battery Saver lowers the frequency caps."};
        }
        if (battery < 0 && !Float.isFinite(temperature)) {
            return new String[]{"balanced", "Waiting for readings. Balanced restores the saved baseline."};
        }
        return new String[]{"balanced", "Battery and temperature readings are in the normal range. Balanced is suitable for everyday use."};
    }

    private void profiles() {
        if (!state.cpus.isEmpty() || state.gpu != null) {
            LinearLayout limits = card("Active CPU and GPU settings", body, palette.paper);
            for (String[] cpu : state.cpus) {
                String id = cpu[1];
                row(limits, clusterName(id), () -> {
                    String[] cluster = cpu(id);
                    return frequency(cluster[4]) + " – " + frequency(cluster[5]) + "\n" + available(cluster[6]);
                }, null);
            }
            if (state.gpu != null) {
                row(limits, "GPU", () -> frequency(state.gpu[3]) + " – " + frequency(state.gpu[4]), null);
            }
        }
        List<LinearLayout> cards = new ArrayList<>();
        for (int index = 0; index < CherryData.PROFILES.length; index++) {
            final String id = CherryData.PROFILES[index];
            final String name = CherryData.NAMES[index];
            boolean active = id.equals(state.manual);
            LinearLayout card = tile(name, active ? palette.pink : palette.paper);
            note(card, CherryData.DETAILS[index]);
            space(card, 5);
            if (canWriteManually()) {
                action(card, active ? "Active · Reapply" : "Apply", active, () -> applyProfile(id));
            }
            cards.add(card);
        }
        grid(body, cards, 3);
        appRules();
    }

    private void appRules() {
        LinearLayout card = card("Per-app profiles", body, palette.paper);
        note(card, "Apply a profile while a selected app is in the foreground. Restore the baseline when you leave it.");
        String status = SessionService.active.equals("apps") ? SessionService.message : "";
        if (!status.isEmpty()) live(card, () -> SessionService.message, 13, palette.accent, true);
        Map<String, String> rules = rules();
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            String name = appName(entry.getKey());
            row(card, name, CherryData.profileName(entry.getValue()),
                    () -> editRule(entry.getKey(), name));
        }
        if (rules.isEmpty()) {
            space(card, 6);
            note(card, "No per-app rules yet.");
        }
        space(card, 6);
        actionCompact(card, "Choose app", false, this::selectApp);
        if (SessionService.active.equals("apps")) {
            actionCompact(card, "Stop per-app profiles", true, this::stopAutomation);
        } else if (canTune() && state.session.isEmpty() && SessionService.active.isEmpty()) {
            if (!CherryData.hasUsageAccess(this)) {
                actionCompact(card, "Grant usage access", false,
                        () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            } else if (!rules.isEmpty()) {
                actionCompact(card, "Start per-app profiles", true,
                        () -> launchSession(new Intent(this, SessionService.class).putExtra("mode", "apps")));
            }
        }
    }

    private Map<String, String> rules() {
        Map<String, String> found = new LinkedHashMap<>();
        CherryData.settings(this).getAll().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("rule.") && entry.getValue() instanceof String)
                .sorted(Comparator.comparing(entry -> appName(entry.getKey().substring(5)), String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> found.put(entry.getKey().substring(5), (String) entry.getValue()));
        return found;
    }

    private void selectApp() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> found = new LinkedHashMap<>();
        for (ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            String name = info.activityInfo.packageName;
            if (!name.equals(getPackageName())) found.put(name, info.loadLabel(getPackageManager()).toString());
        }
        List<Map.Entry<String, String>> apps = new ArrayList<>(found.entrySet());
        apps.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
        String[] labels = apps.stream().map(Map.Entry::getValue).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Choose app").setItems(labels, (dialog, which) -> {
            Map.Entry<String, String> choice = apps.get(which);
            editRule(choice.getKey(), choice.getValue());
        }).setNegativeButton("Close", null).show();
    }

    private void editRule(String packageName, String label) {
        SharedPreferences settings = CherryData.settings(this);
        String key = "rule." + packageName;
        new AlertDialog.Builder(this).setTitle(label).setItems(CherryData.NAMES, (dialog, position) -> {
            settings.edit().putString(key, CherryData.PROFILES[position]).apply();
            render(true);
        }).setNeutralButton("Remove rule", (dialog, which) -> {
            settings.edit().remove(key).apply();
            render(true);
        }).setNegativeButton("Cancel", null).show();
    }

    private String appName(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private void hardware() {
        if (state.info.containsKey("cpu-online")) {
            LinearLayout topology = card("CPU topology", body, palette.paper);
            row(topology, "Online cores", () -> available(info("cpu-online")), null);
            row(topology, "CPU pressure", () -> pressure("/proc/pressure/cpu"), null);
        }
        if (state.cpus.isEmpty()) {
            note(body, "CPU cluster readings unavailable.");
        } else {
            List<LinearLayout> cards = new ArrayList<>();
            for (String[] cpu : state.cpus) {
                String id = cpu[1];
                LinearLayout tile = tile(clusterName(id), palette.paper);
                note(tile, "Cores " + available(cpu[2]));
                live(tile, () -> frequency(cpu(id)[3]), 30, palette.accent, true);
                live(tile, () -> "Minimum " + frequency(cpu(id)[4]) + "  ·  Maximum " + frequency(cpu(id)[5]), 14, palette.muted, false);
                live(tile, () -> "Governor  ·  " + available(cpu(id)[6]), 14, palette.ink, false);
                space(tile, 6);
                if (canWriteManually() && cpu[9].equals("1") && !cpu[7].isEmpty()) {
                    actionCompact(tile, "Maximum frequency", false,
                            () -> chooseFrequency("CPU maximum frequency", cpu(id)[7], cpu(id)[4], target -> apply("CPU maximum · " + frequency(target), "cpu-max", id, target)));
                }
                if (canWriteManually() && cpu[11].equals("1") && !cpu[7].isEmpty()) {
                    actionCompact(tile, "Minimum frequency", false,
                            () -> chooseFrequency("CPU minimum frequency", cpu(id)[7], "0", cpu(id)[5],
                                    target -> apply("CPU minimum · " + frequency(target), "cpu-min", id, target)));
                }
                if (canWriteManually() && cpu[10].equals("1") && !cpu[8].isEmpty()) {
                    actionCompact(tile, "Choose governor", false,
                            () -> choose("CPU governor", words(cpu(id)[8]), target -> apply("CPU governor · " + target, "governor", id, target)));
                }
                cards.add(tile);
            }
            grid(body, cards, 2);
        }
        if (state.gpu != null) {
            LinearLayout gpu = card("GPU", body, palette.paper);
            note(gpu, available(state.gpu[1]));
            live(gpu, () -> frequency(state.gpu[2]), 30, palette.accent, true);
            live(gpu, () -> "Minimum " + frequency(state.gpu[3]) + "  ·  Maximum " + frequency(state.gpu[4]), 14, palette.muted, false);
            live(gpu, () -> "Governor " + available(state.gpu[5]) + "  ·  Load " + available(state.gpu[7]), 14, palette.ink, false);
            if (canWriteManually() && state.gpu[8].equals("1") && !state.gpu[6].isEmpty()) {
                actionCompact(gpu, "Maximum frequency", false,
                        () -> chooseFrequency("GPU maximum frequency", state.gpu[6], state.gpu[3], target -> apply("GPU maximum · " + frequency(target), "gpu-max", target)));
            }
            if (canWriteManually() && state.gpu[9].equals("1") && !state.gpu[6].isEmpty()) {
                actionCompact(gpu, "Minimum frequency", false,
                        () -> chooseFrequency("GPU minimum frequency", state.gpu[6], "0", state.gpu[4],
                                target -> apply("GPU minimum · " + frequency(target), "gpu-min", target)));
            }
            if (canWriteManually() && state.gpu[11].equals("1") && !state.gpu[10].isEmpty()) {
                actionCompact(gpu, "Choose governor", false,
                        () -> choose("GPU governor", words(state.gpu[10]), target -> apply("GPU governor · " + target, "gpu-governor", target)));
            }
        }
    }

    private String clusterName(String id) {
        if (state.board.equals("gts9fepwifi")) {
            if (id.equals("policy0")) return "Efficiency cluster";
            if (id.equals("policy4")) return "Performance cluster";
        }
        return id.startsWith("policy") ? "Cluster " + id.substring(6) : id;
    }

    private void thermal() {
        LinearLayout summary = card("Thermal status", body, palette.leaf);
        live(summary, this::thermalLabel, 20, palette.leafInk, true);
        live(summary, () -> "Battery temperature  ·  " + celsius(batteryTemperature()), 14, palette.leafInk, false);
        if (!state.zones.isEmpty()) {
            List<LinearLayout> cards = new ArrayList<>();
            for (KernelClient.Zone zone : state.zones.values()) {
                cards.add(metric(zone.name, () -> {
                    KernelClient.Zone value = state.zones.get(zone.id);
                    return value == null ? "—" : celsius(value.celsius);
                }));
            }
            grid(body, cards, 4);
        }
        if (!state.cooling.isEmpty()) {
            LinearLayout cooling = card("Cooling devices", body, palette.paper);
            for (String[] device : state.cooling) {
                String name = device[1];
                row(cooling, name, () -> state.cooling.stream().filter(item -> item[1].equals(name))
                        .map(item -> "Level " + item[2] + " / " + item[3]).findFirst().orElse("—"), null);
            }
        }
        LinearLayout safe = card("Temperature-based cap", body, palette.paper);
        note(safe, "Lower the CPU and GPU caps when the selected sensor reaches its threshold.");
        if (SessionService.active.equals("thermal")) {
            live(safe, () -> SessionService.message, 14, palette.accent, true);
            actionCompact(safe, "Stop monitoring", false, this::stopAutomation);
        } else if (canWriteManually() && !state.zones.isEmpty()) {
            actionCompact(safe, "Gaming", false, () -> thermalDialog("Gaming", 72, 80));
            actionCompact(safe, "Extreme", false, () -> thermalDialog("Extreme", 80, 90));
            actionCompact(safe, "Custom threshold", false, () -> thermalDialog("Custom thermal threshold", 70, 75));
        } else if (state.zones.isEmpty()) {
            note(safe, "Kernel temperature sensors unavailable.");
        }
    }

    private void thermalDialog(String title, int threshold, int cap) {
        List<KernelClient.Zone> zones = new ArrayList<>(state.zones.values());
        if (zones.isEmpty()) return;
        LinearLayout content = box(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), 0);
        List<String> labels = new ArrayList<>();
        for (KernelClient.Zone zone : zones) labels.add(zone.name + " · " + celsius(zone.celsius));
        Spinner sensors = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        sensors.setAdapter(adapter);
        content.addView(tipText("Sensor"), match());
        content.addView(sensors, match());
        content.addView(tipText("Threshold (°C)"), match());
        EditText temperature = numberInput(threshold);
        content.addView(temperature, match());
        content.addView(tipText("Frequency cap (%)"), match());
        EditText percentage = numberInput(cap);
        content.addView(percentage, match());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(content)
                .setNegativeButton("Cancel", null).setPositiveButton("Start monitoring", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int value = integer(temperature.getText().toString());
            int percent = integer(percentage.getText().toString());
            if (value < 40 || value > 85 || percent < 50 || percent > 90) {
                toast("Use a threshold of 40–85 °C and a frequency cap of 50–90%.");
                return;
            }
            KernelClient.Zone selected = zones.get(sensors.getSelectedItemPosition());
            String name = selected.name.toLowerCase(Locale.ROOT);
            if (value > 45 && (name.contains("batt") || name.contains("skin") || name.equals("ac"))) {
                toast("The maximum threshold for this sensor is 45 °C.");
                return;
            }
            dialog.dismiss();
            launchSession(new Intent(this, SessionService.class).putExtra("mode", "thermal")
                    .putExtra("sensor", selected.id).putExtra("threshold", value).putExtra("cap", percent));
        }));
        dialog.show();
    }

    private void system() {
        List<LinearLayout> cards = new ArrayList<>();
        LinearLayout display = tile("Display", palette.paper);
        live(display, () -> getDisplay() == null ? "—" : Math.round(getDisplay().getRefreshRate()) + " Hz", 28, palette.accent, true);
        if (getDisplay() != null) {
            String rates = Arrays.stream(getDisplay().getSupportedModes()).map(mode -> Math.round(mode.getRefreshRate()) + " Hz")
                    .distinct().reduce((left, right) -> left + " / " + right).orElse("—");
            note(display, rates);
        }
        if (canWriteManually() && systemCanWrite("display") && getDisplay() != null) {
            live(display, () -> "Preference  ·  " + displayPref(), 14, palette.muted, false);
            actionCompact(display, "Refresh rate", false, this::displayDialog);
        }
        tuningRows(display, TuningOption.WINDOW_SCALE, TuningOption.TRANSITION_SCALE, TuningOption.ANIMATOR_SCALE);
        cards.add(display);
        LinearLayout ram = tile("Memory", palette.paper);
        live(ram, () -> bytes(memory()[0]) + " free of " + bytes(memory()[1]), 19, palette.ink, true);
        live(ram, () -> "Memory pressure  ·  " + pressure("/proc/pressure/memory"), 14, palette.muted, false);
        live(ram, () -> "Swappiness  ·  " + available(sys("vm")[2]), 14, palette.muted, false);
        if (canWriteManually() && systemCanWrite("vm")) {
            actionCompact(ram, "Set swappiness", false,
                    () -> editNumber("Swappiness", integer(sys("vm")[2]), 0, 200, value -> apply("Swappiness · " + value, "vm", value)));
        }
        tuningRows(ram, TuningOption.CACHE_PRESSURE);
        if (!info("zram-size").isEmpty()) {
            row(ram, "ZRAM capacity", () -> bytes(longValue(info("zram-size"))), null);
            row(ram, "ZRAM compressor", () -> available(info("zram-compressor")), null);
            if (words(info("zram-stat")).length >= 3) {
                row(ram, "ZRAM data", () -> zramStat(0, 1), null);
                row(ram, "ZRAM RAM usage", () -> zramStat(2, -1), null);
            }
        }
        tuningRows(ram, TuningOption.RAM_PLUS);
        cards.add(ram);
        LinearLayout storage = tile("Storage", palette.paper);
        StatFs filesystem = new StatFs(getFilesDir().getAbsolutePath());
        storage.addView(text(bytes(filesystem.getAvailableBytes()) + " free of " + bytes(filesystem.getTotalBytes()), 19, palette.ink, true), match());
        live(storage, () -> "I/O scheduler  ·  " + available(sys("io")[2]), 14, palette.muted, false);
        if (canWriteManually() && systemCanWrite("io") && words(sys("io")[3]).length > 1) {
            actionCompact(storage, "Choose I/O scheduler", false,
                    () -> choose("I/O scheduler", words(sys("io")[3]), target -> apply("I/O · " + target, "io", target)));
        }
        tuningRows(storage, TuningOption.READ_AHEAD, TuningOption.QUEUE_DEPTH);
        cards.add(storage);
        LinearLayout net = tile("Network", palette.paper);
        live(net, this::network, 19, palette.ink, true);
        live(net, () -> "TCP algorithm  ·  " + available(sys("tcp")[2]), 14, palette.muted, false);
        if (canWriteManually() && systemCanWrite("tcp") && words(sys("tcp")[3]).length > 1) {
            actionCompact(net, "Choose TCP algorithm", false,
                    () -> choose("TCP algorithm", words(sys("tcp")[3]), target -> apply("TCP · " + target, "tcp", target)));
        }
        tuningRows(net, TuningOption.ECN, TuningOption.SACK, TuningOption.TIMESTAMPS, TuningOption.FAST_OPEN);
        cards.add(net);
        LinearLayout power = tile("Battery and power", palette.paper);
        live(power, () -> (batteryPercent() < 0 ? "—" : batteryPercent() + "%") + "  ·  " + batteryStatus(), 19, palette.ink, true);
        row(power, "Battery temperature", () -> celsius(batteryTemperature()), null);
        row(power, "Voltage", this::batteryVoltage, null);
        row(power, "Battery current", this::batteryCurrent, null);
        tuningRows(power, TuningOption.POWER_SAVER);
        cards.add(power);
        grid(body, cards, 2);
    }

    private void tuningRows(LinearLayout panel, TuningOption... options) {
        for (TuningOption option : options) {
            String[] reported = sys(option.key);
            if (reported[2].isEmpty()) continue;
            boolean editable = canWriteManually() && systemCanWrite(option.key)
                    && (option.maximum != 0 || !reported[3].isEmpty());
            row(panel, option.title, () -> option.display(sys(option.key)[2]),
                    editable ? () -> editTuningOption(option) : null);
        }
    }

    private void editTuningOption(TuningOption option) {
        String[] reported = sys(option.key);
        Consumer<String> applyValue = target -> {
            String title = option.title + " · " + option.display(target);
            if (option == TuningOption.RAM_PLUS) {
                run(title, () -> kernel.write("tune", option.key, target), "RAM Plus size saved. Applies after reboot.");
            } else {
                apply(title, "tune", option.key, target);
            }
        };
        if (option.maximum != 0) {
            editNumber(option.title, integer(reported[2]), option.minimum, option.maximum, applyValue);
            return;
        }
        String[] values = words(reported[3]);
        String[] labels = Arrays.stream(values).map(option::display).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(option.title)
                .setSingleChoiceItems(labels, Arrays.asList(values).indexOf(reported[2]), (dialog, index) -> {
                    dialog.dismiss();
                    applyValue.accept(values[index]);
                }).setNegativeButton("Cancel", null).show();
    }

    private void history() {
        LinearLayout controls = card("Undo the last change", body, palette.paper);
        note(controls, "Undo checks the current values against the last verified change.");
        if (canWriteManually() && state.undo) actionCompact(controls, "Undo last change", true, () -> apply("Undo", "undo"));
        LinearLayout log = card("Events", body, palette.paper);
        List<String> entries = CherryData.journal(this);
        if (entries.isEmpty()) note(log, "No activity yet.");
        for (String entry : entries) {
            log.addView(text(entry, 14, palette.ink, false), spaced(0, 5, 0, 5));
        }
        LinearLayout about = card("About", body, palette.paper);
        note(about, "Cherry Manager 1.3.0 · SM-X610");
        note(about, "Inspired by MaxManager by nader295");
    }

    private void displayDialog() {
        List<String> modes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        modes.add("default");
        names.add("System default");
        if (getDisplay() == null) return;
        for (android.view.Display.Mode mode : getDisplay().getSupportedModes()) {
            if (mode.getPhysicalWidth() != 1600 || mode.getPhysicalHeight() != 2560) continue;
            String key = Float.toString(mode.getRefreshRate());
            if (!Arrays.asList("30.0", "60.0", "90.0").contains(key) || modes.contains(key)) continue;
            modes.add(key);
            names.add(Math.round(mode.getRefreshRate()) + " Hz");
        }
        new AlertDialog.Builder(this).setTitle("Display refresh rate").setItems(names.toArray(new String[0]), (dialog, index) ->
                apply("Display · " + names.get(index), "display", modes.get(index)))
                .setNegativeButton("Cancel", null).show();
    }

    private void authorize() {
        run("Root access", () -> kernel.authorize(), "Root access enabled.");
    }

    private void applyProfile(String id) {
        String name = CherryData.profileName(id);
        run(name, () -> kernel.write("profile", id), name + " applied. CPU and GPU settings verified.");
    }

    private void apply(String name, String... arguments) {
        run(name, () -> kernel.write(arguments), "Change verified.");
    }

    private interface Operation { void perform() throws Exception; }

    private void run(String title, Operation action, String success) {
        if (kernel == null || busy) { toast("The controller is busy."); return; }
        busy = true;
        worker.execute(() -> {
            String result;
            try {
                action.perform();
                result = success;
            } catch (Exception failure) {
                result = failure.getMessage() == null ? "The operation could not be completed." : failure.getMessage();
            }
            CherryData.log(this, title, result);
            KernelClient.Snapshot latest = null;
            try { latest = kernel.read(); } catch (Exception ignored) { }
            KernelClient.Snapshot refreshed = latest;
            String message = result;
            main.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (refreshed != null) state = refreshed;
                if (started) {
                    render(true);
                    toast(message);
                    schedule(2500);
                }
            });
        });
    }

    private void launchSession(Intent intent) {
        if (!canWriteManually()) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAction = () -> startForegroundService(intent);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
        } else {
            startForegroundService(intent);
            schedule(500);
        }
    }

    private void stopAutomation() {
        if (SessionService.active.isEmpty()) return;
        startService(new Intent(this, SessionService.class).setAction(SessionService.STOP));
        schedule(500);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 33) return;
        Runnable pending = notificationAction;
        notificationAction = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
            pending.run();
            schedule(500);
        } else {
            toast("Allow notifications to stop the session from the notification.");
        }
    }

    private void chooseFrequency(String title, String data, String min, Consumer<String> select) {
        chooseFrequency(title, data, min, Integer.toString(Integer.MAX_VALUE), select);
    }

    private void chooseFrequency(String title, String data, String min, String max, Consumer<String> select) {
        List<String> options = new ArrayList<>();
        for (String value : words(data)) {
            int clock = integer(value);
            if (clock > 0 && clock >= integer(min) && clock <= integer(max)) options.add(value);
        }
        if (options.isEmpty()) { toast("No compatible frequencies available."); return; }
        String[] labels = options.stream().map(this::frequency).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(labels, (dialog, index) -> select.accept(options.get(index))).setNegativeButton("Cancel", null).show();
    }

    private void choose(String title, String[] options, Consumer<String> select) {
        new AlertDialog.Builder(this).setTitle(title).setItems(options, (dialog, index) -> select.accept(options[index]))
                .setNegativeButton("Cancel", null).show();
    }

    private void editNumber(String title, int initial, int minimum, int maximum, Consumer<String> select) {
        LinearLayout form = box(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), 0);
        EditText input = numberInput(Math.max(minimum, initial));
        form.addView(input, match());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int value = integer(input.getText().toString());
            if (value < minimum || value > maximum) {
                input.setError(minimum + "–" + maximum + " is the allowed range.");
                return;
            }
            dialog.dismiss();
            select.accept(Integer.toString(value));
        }));
        dialog.show();
    }

    private EditText numberInput(int value) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(Integer.toString(value));
        input.setSelectAllOnFocus(true);
        return input;
    }

    private boolean canTune() { return state.canTune(); }

    private boolean canWriteManually() {
        return canTune() && state.session.isEmpty() && SessionService.active.isEmpty();
    }

    private boolean systemCanWrite(String key) {
        return sys(key)[4].equals("1") && !sys(key)[2].isEmpty();
    }

    private String[] sys(String key) { return state.system.getOrDefault(key, new String[]{"", "", "", "", ""}); }

    private String[] cpu(String id) {
        for (String[] item : state.cpus) if (item[1].equals(id)) return item;
        return new String[]{"", "", "", "", "", "", "", "", "", "", "", ""};
    }

    private String info(String name) { return state.info.getOrDefault(name, ""); }

    private String zramStat(int first, int second) {
        String[] fields = words(info("zram-stat"));
        if (fields.length < 3) return "—";
        String size = bytes(longValue(fields[first]));
        return second < 0 ? size : size + " → " + bytes(longValue(fields[second]));
    }

    private String manualProfile() {
        if (!SessionService.active.isEmpty()) return SessionService.message;
        return CherryData.profileName(state.manual);
    }

    private String profileDetails(String id) {
        int index = Arrays.asList(CherryData.PROFILES).indexOf(id);
        return index < 0 ? "" : CherryData.DETAILS[index];
    }

    private String variantName(String name) {
        if (name.equals("ksun")) return "KernelSU Next";
        if (name.equals("standart")) return "Standard";
        return available(name);
    }

    private String displayPref() {
        String pref = sys("display")[2];
        if (pref.equals("default")) return "System default";
        return pref.isEmpty() ? "—" : pref + " Hz";
    }

    private String thermalLabel() {
        int level = getSystemService(PowerManager.class).getCurrentThermalStatus();
        switch (level) {
            case PowerManager.THERMAL_STATUS_NONE: return "Normal";
            case PowerManager.THERMAL_STATUS_LIGHT: return "Light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Severe";
            case PowerManager.THERMAL_STATUS_CRITICAL:
            case PowerManager.THERMAL_STATUS_EMERGENCY:
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Critical";
            default: return "Unavailable";
        }
    }

    private float hottestChip() {
        float highest = Float.NEGATIVE_INFINITY;
        for (KernelClient.Zone zone : state.zones.values()) {
            String category = zone.name.toUpperCase(Locale.ROOT);
            if (category.equals("BIG") || category.equals("LITTLE") || category.equals("G3D")) highest = Math.max(highest, zone.celsius);
        }
        return highest;
    }

    private int peak() {
        int max = -1;
        for (String[] cpu : state.cpus) max = Math.max(max, integer(cpu[3]));
        return max;
    }

    private Intent battery() { return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }

    private int batteryPercent() {
        Intent battery = battery();
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : (100 * level / scale);
    }

    private boolean batteryConnected() {
        Intent battery = battery();
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private float batteryTemperature() {
        Intent battery = battery();
        int measured = battery == null ? Integer.MIN_VALUE : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return measured == Integer.MIN_VALUE ? Float.NaN : measured / 10f;
    }

    private String batteryStatus() {
        Intent battery = battery();
        if (battery == null) return "Status unavailable";
        switch (battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Status unavailable";
        }
    }

    private String batteryVoltage() {
        Intent battery = battery();
        int millivolts = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        return millivolts > 0 ? millivolts + " mV" : "—";
    }

    private String batteryCurrent() {
        BatteryManager manager = getSystemService(BatteryManager.class);
        int value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        return value == Integer.MIN_VALUE ? "—" : Math.round(value / 1000f) + " mA";
    }

    private long[] memory() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        return new long[]{info.availMem, info.totalMem};
    }

    private String network() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities connection = manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (connection == null) return "Disconnected";
        if (connection.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        if (connection.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (connection.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (connection.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile network";
        return "Connected";
    }

    private String pressure(String interfaceName) {
        String signal = state.pressure.get(interfaceName);
        return signal == null ? "—" : signal + "%";
    }

    private String frequency(String raw) {
        int value = integer(raw);
        if (value < 0) return "—";
        if (value == 0) return "0 MHz";
        if (value >= 1_000_000) return String.format(Locale.forLanguageTag("en"), "%.2f GHz", value / 1_000_000d);
        return (value / 1000) + " MHz";
    }

    private String celsius(float raw) {
        return Float.isFinite(raw) ? String.format(Locale.forLanguageTag("en"), "%.1f °C", raw) : "—";
    }

    private String bytes(long raw) {
        if (raw < 0) return "—";
        if (raw < 1_073_741_824L) return String.format(Locale.ENGLISH, "%.0f MiB", raw / 1_048_576d);
        return String.format(Locale.ENGLISH, "%.1f GiB", raw / 1_073_741_824d);
    }

    private static long longValue(String raw) {
        try { return Long.parseLong(raw); } catch (NumberFormatException ignored) { return -1; }
    }

    private static int integer(String raw) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return -1; }
    }

    private static String[] words(String raw) { return raw.isEmpty() ? new String[0] : raw.trim().split("\\s+"); }

    private static String available(String value) { return value.isEmpty() ? "—" : value; }

    private void go(int selected) {
        if (page == selected) return;
        if (scroll != null) scrollPositions[page] = scroll.getScrollY();
        page = selected;
        render(false);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private LinearLayout metric(String title, Supplier<String> value) {
        LinearLayout tile = box(LinearLayout.VERTICAL);
        tile.setPadding(dp(15), dp(13), dp(15), dp(12));
        tile.setBackground(rounded(palette.paper, 17, palette.line));
        tile.addView(tipText(title), match());
        live(tile, value, 23, palette.ink, true);
        return tile;
    }

    private LinearLayout card(String title, LinearLayout parent, int fill) {
        LinearLayout card = tile(title, fill);
        parent.addView(card, separated());
        return card;
    }

    private LinearLayout tile(String title, int fill) {
        LinearLayout card = box(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(14), dp(17), dp(14));
        card.setBackground(rounded(fill, 19, fill == palette.paper ? palette.line : 0));
        card.addView(text(title, 19, palette.ink, true), match());
        space(card, 4);
        return card;
    }

    private void grid(LinearLayout parent, List<LinearLayout> cards, int columnLimit) {
        int baseCardWidth = columnLimit == 4 ? 152 : columnLimit == 3 ? 192 : 246;
        float fontScale = Math.max(1, getResources().getConfiguration().fontScale);
        int cardWidth = Math.round(baseCardWidth * fontScale);
        int columns = Math.max(1, Math.min(columnLimit, (contentWidthDp + TILE_GAP) / (cardWidth + TILE_GAP)));
        for (int start = 0; start < cards.size(); start += columns) {
            LinearLayout row = box(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setWeightSum(columns);
            int items = Math.min(columns, cards.size() - start);
            for (int column = 0; column < items; column++) {
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1);
                if (column != 0) layout.setMarginStart(dp(TILE_GAP));
                row.addView(cards.get(start + column), layout);
            }
            parent.addView(row, separated());
        }
    }

    private void row(LinearLayout parent, String title, String value, Runnable action) {
        row(parent, title, () -> value, action);
    }

    private void row(LinearLayout parent, String title, Supplier<String> value, Runnable action) {
        LinearLayout row = box(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setMinimumHeight(dp(48));
        row.setBackground(action == null ? rounded(palette.base, 12, 0) : tappable(palette.pink, 12));
        TextView titleView = text(title, 14, palette.ink, true);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView current = text(value.get(), 13, action == null ? palette.muted : palette.accent, true);
        current.setGravity(Gravity.END);
        current.setMaxWidth(dp(154));
        addHorizontal(row, current, 0, 0, 10, 0);
        if (action != null) row.setOnClickListener(view -> action.run());
        bindings.add(() -> {
            String incoming = value.get();
            if (!incoming.contentEquals(current.getText())) current.setText(incoming);
        });
        parent.addView(row, spaced(0, 4, 0, 0));
    }

    private void note(LinearLayout parent, String content) { parent.addView(tipText(content), match()); }

    private void live(LinearLayout parent, Supplier<String> value, int size, int ink, boolean bold) {
        TextView text = text(value.get(), size, ink, bold);
        parent.addView(text, spaced(0, 3, 0, 0));
        bindings.add(() -> {
            String incoming = value.get();
            if (!incoming.contentEquals(text.getText())) text.setText(incoming);
        });
    }

    private TextView navButton(String name, boolean selected, Runnable navigate) {
        TextView button = text(name, 14, selected ? palette.accent : palette.muted, selected);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(tappable(selected ? palette.pink : Color.TRANSPARENT, 11));
        button.setOnClickListener(view -> navigate.run());
        button.setSelected(selected);
        return button;
    }

    private TextView pill(String value, int fill, int ink) {
        TextView badge = text(value, 12, ink, true);
        badge.setPadding(dp(11), dp(6), dp(11), dp(6));
        badge.setBackground(rounded(fill, 99, 0));
        return badge;
    }

    private void action(LinearLayout parent, String title, boolean mainAction, Runnable select) {
        parent.addView(button(title, mainAction, select), spaced(0, 5, 0, 0));
    }

    private void actionCompact(LinearLayout parent, String title, boolean mainAction, Runnable select) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.topMargin = dp(6);
        parent.addView(button(title, mainAction, select), layout);
    }

    private Button button(String title, boolean mainAction, Runnable select) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(mainAction ? palette.onAccent : palette.accent);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(13), dp(4), dp(13), dp(4));
        button.setStateListAnimator(null);
        button.setBackground(tappable(mainAction ? palette.accent : palette.pink, 11));
        if (select != null) button.setOnClickListener(view -> select.run());
        else button.setClickable(false);
        return button;
    }

    private TextView tipText(String value) { return text(value, 13, palette.muted, false); }

    private TextView text(String value, int size, int ink, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ink);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(1), 1f);
        return view;
    }

    private void addHorizontal(LinearLayout parent, View view, int top, int end, int start, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.setMargins(dp(start), dp(top), dp(end), dp(bottom));
        parent.addView(view, layout);
    }

    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }

    private LinearLayout box(int orientation) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(orientation);
        return layout;
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }

    private LinearLayout.LayoutParams separated() { return spaced(0, 0, 0, 11); }

    private LinearLayout.LayoutParams spaced(int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams layout = match();
        layout.setMargins(dp(start), dp(top), dp(end), dp(bottom));
        return layout;
    }

    private GradientDrawable rounded(int fill, int radius, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private RippleDrawable tappable(int fill, int radius) {
        int ripple = (palette.accent & 0xFFFFFF) | 0x30000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), rounded(fill, radius, 0), rounded(Color.WHITE, radius, 0));
    }

    private int dp(int space) { return Math.round(space * getResources().getDisplayMetrics().density); }

    private static final class Palette {
        final int base, paper, ink, muted, accent, line, pink, hero, leaf, leafInk, onAccent;

        Palette(String base, String paper, String ink, String muted, String accent, String line,
                String pink, String hero, String leaf, String leafInk, String onAccent) {
            this.base = Color.parseColor(base);
            this.paper = Color.parseColor(paper);
            this.ink = Color.parseColor(ink);
            this.muted = Color.parseColor(muted);
            this.accent = Color.parseColor(accent);
            this.line = Color.parseColor(line);
            this.pink = Color.parseColor(pink);
            this.hero = Color.parseColor(hero);
            this.leaf = Color.parseColor(leaf);
            this.leafInk = Color.parseColor(leafInk);
            this.onAccent = Color.parseColor(onAccent);
        }
    }

    private class Sparkline extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Sparkline(Context context) { super(context); }

        @Override public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (cpuHistory.size() < 2) return;
            Path path = new Path();
            float width = getWidth() - dp(4);
            float height = getHeight() - dp(6);
            int index = 0;
            for (int value : cpuHistory) {
                float x = dp(2) + width * index / (cpuHistory.size() - 1);
                float y = dp(3) + height * (1f - Math.min(value, 2_400_000) / 2_400_000f);
                if (index++ == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setColor(palette.accent);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(path, paint);
        }
    }
}
