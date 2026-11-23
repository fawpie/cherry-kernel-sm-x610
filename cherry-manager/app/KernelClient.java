package dev.fawpie.cherrymanager;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class KernelClient {
    private static KernelClient instance;
    private final Context context;
    private final File script;
    private volatile boolean root;

    static synchronized KernelClient get(Context context) throws IOException {
        if (instance == null) instance = new KernelClient(context.getApplicationContext());
        return instance;
    }

    private KernelClient(Context context) throws IOException {
        this.context = context;
        script = new File(context.getFilesDir(), "cherry-kernel.sh");
        AtomicFile atomic = new AtomicFile(script);
        try (InputStream source = context.getAssets().open("cherry-kernel.sh")) {
            FileOutputStream output = atomic.startWrite();
            try {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = source.read(buffer)) != -1) output.write(buffer, 0, length);
                atomic.finishWrite(output);
            } catch (IOException failure) {
                atomic.failWrite(output);
                throw failure;
            }
        }
    }

    synchronized void authorize() throws IOException, InterruptedException {
        authorize(45);
    }

    private void authorize(int timeout) throws IOException, InterruptedException {
        root = false;
        if (!"AUTHORIZED".equals(invoke(true, timeout, "authorize"))) {
            throw new IOException("The root manager did not confirm access.");
        }
        root = true;
    }

    synchronized boolean applyStartupProfile(Context app, String profile) throws IOException, InterruptedException {
        if (!root) authorize(5);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!CherryData.settings(app).getBoolean(CherryData.STARTUP_ENABLED, false)) return false;
        String result = invoke(true, 12, "startup-profile", profile);
        if ("STARTUP_SKIPPED".equals(result)) return false;
        if ("VERIFIED".equals(result)) return true;
        throw new IOException("The startup profile could not be verified.");
    }

    synchronized Snapshot read() throws IOException, InterruptedException {
        Snapshot snapshot;
        if (root) {
            try {
                snapshot = Snapshot.decode(invoke(true, 8, "status"));
                if (snapshot.root) return snapshot;
            } catch (IOException failure) {
                // An ordinary read can still describe the device after root is revoked.
            }
            root = false;
        }
        return Snapshot.decode(invoke(false, 6, "status"));
    }

    synchronized void write(String... arguments) throws IOException, InterruptedException {
        if (!root) throw new IOException("Root access is required.");
        if (!"VERIFIED".equals(invoke(true, 12, arguments))) {
            throw new IOException("The kernel change could not be verified.");
        }
    }

    private String invoke(boolean privileged, int seconds, String... request)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        if (privileged) {
            StringBuilder invocation = new StringBuilder("exec /system/bin/sh '")
                    .append(script.getAbsolutePath()).append("'");
            for (String argument : request) {
                if (!argument.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IOException("Invalid kernel setting.");
                invocation.append(" '").append(argument).append("'");
            }
            command.addAll(List.of("su", "-c", invocation.toString()));
        } else {
            command.addAll(List.of("/system/bin/sh", script.getAbsolutePath(), "status"));
        }
        File capture = File.createTempFile("cherry-", ".txt", context.getCacheDir());
        try {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(capture).start();
            } catch (IOException failure) {
                if (privileged) throw new IOException("Magisk or KernelSU root access is unavailable on this boot.", failure);
                throw failure;
            }
            try {
                if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                    throw new IOException("The kernel request timed out.");
                }
            } catch (InterruptedException interrupted) {
                process.destroy();
                throw interrupted;
            }
            String output = new String(Files.readAllBytes(capture.toPath()), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException(output.isEmpty() ? "The kernel rejected the request." : output);
            }
            return output;
        } finally {
            Files.deleteIfExists(capture.toPath());
        }
    }

    static final class Snapshot {
        String board = "";
        String release = "";
        String abi = "";
        String variant = "";
        String manual = "";
        String session = "";
        boolean root;
        boolean kernelInterface;
        boolean undo;
        final List<String[]> cpus = new ArrayList<>();
        String[] gpu;
        final Map<String, Zone> zones = new LinkedHashMap<>();
        final Map<String, String[]> system = new LinkedHashMap<>();
        final Map<String, String> info = new LinkedHashMap<>();
        final List<String[]> cooling = new ArrayList<>();
        final Map<String, String> pressure = new LinkedHashMap<>();

        static Snapshot decode(String report) throws IOException {
            Snapshot snapshot = new Snapshot();
            for (String line : report.split("\r?\n")) {
                String[] row = line.split("\\|", -1);
                switch (row[0]) {
                    case "DEVICE":
                        if (row.length < 6) break;
                        snapshot.board = row[1];
                        snapshot.release = row[2];
                        snapshot.abi = row[3];
                        snapshot.variant = row[4];
                        snapshot.root = row[5].equals("0");
                        break;
                    case "STATE":
                        if (row.length < 4) break;
                        snapshot.manual = row[1];
                        snapshot.session = row[2];
                        snapshot.undo = row[3].equals("1");
                        break;
                    case "KERNEL_INTERFACE":
                        snapshot.kernelInterface = row.length == 2 && row[1].equals("present");
                        break;
                    case "CPU":
                        if (row.length == 12 || row.length == 11) snapshot.cpus.add(withOptionalColumns(row));
                        break;
                    case "GPU":
                        if (row.length == 12 || row.length == 9) snapshot.gpu = withOptionalColumns(row);
                        break;
                    case "TEMP":
                        if (row.length == 4) {
                            try {
                                int raw = Integer.parseInt(row[3]);
                                float value = Math.abs(raw) >= 1000 ? raw / 1000f : raw;
                                if (value > -50 && value < 180) snapshot.zones.put(row[1], new Zone(row[1], row[2], value));
                            } catch (NumberFormatException ignored) { }
                        }
                        break;
                    case "SYSTEM": if (row.length == 5) snapshot.system.put(row[1], row); break;
                    case "INFO": if (row.length == 3) snapshot.info.put(row[1], row[2]); break;
                    case "COOLING": if (row.length == 4) snapshot.cooling.add(row); break;
                    case "PRESSURE":
                        if (row.length == 4 && row[2].equals("some") && row[3].startsWith("avg10=")) {
                            snapshot.pressure.put(row[1], row[3].substring(6));
                        }
                        break;
                    default: break;
                }
            }
            if (snapshot.release.isEmpty()) throw new IOException("The kernel version could not be read.");
            return snapshot;
        }

        private static String[] withOptionalColumns(String[] row) {
            if (row.length == 12) return row;
            String[] padded = Arrays.copyOf(row, 12);
            Arrays.fill(padded, row.length, padded.length, "");
            return padded;
        }

        boolean canTune() {
            return root && board.equals("gts9fepwifi") && (abi.isEmpty() || abi.equals("1"));
        }
    }

    static final class Zone {
        final String id;
        final String name;
        final float celsius;

        Zone(String id, String name, float celsius) {
            this.id = id;
            this.name = name;
            this.celsius = celsius;
        }
    }
}
