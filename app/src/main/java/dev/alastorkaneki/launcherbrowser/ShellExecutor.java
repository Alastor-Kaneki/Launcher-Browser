package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import rikka.shizuku.Shizuku;

final class ShellExecutor {
    static final int REQUEST_CODE = 4102;
    private static final ExecutorService IO = Executors.newCachedThreadPool();

    static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean privileged;

        Result(int exitCode, String stdout, String stderr, boolean privileged) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.privileged = privileged;
        }
    }

    private ShellExecutor() {}

    static boolean binderAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasPermission() {
        try {
            return binderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void requestPermission(Activity activity) {
        if (!binderAvailable()) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                    && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }
    }

    static String identity() {
        if (!binderAvailable()) return "Shizuku unavailable";
        if (!hasPermission()) return "Shizuku permission required";
        try {
            int uid = Shizuku.getUid();
            return uid == 0 ? "ROOT · UID 0" : "ADB SHELL · UID " + uid;
        } catch (Throwable ignored) {
            return "Shizuku connected";
        }
    }

    @SuppressWarnings("deprecation")
    static Result executeBlocking(String command) throws Exception {
        boolean privileged = hasPermission();
        Process process;
        if (privileged) {
            process = Shizuku.newProcess(new String[]{"/system/bin/sh", "-c", command}, null, null);
        } else {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(false).start();
        }
        Future<String> stdout = IO.submit(read(process.getInputStream()));
        Future<String> stderr = IO.submit(read(process.getErrorStream()));
        int exitCode = process.waitFor();
        return new Result(exitCode, stdout.get(), stderr.get(), privileged);
    }

    private static Callable<String> read(InputStream stream) {
        return () -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            return output.toString();
        };
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
