package dev.alastorkaneki.launcherbrowser;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PrivilegedShellService extends IPrivilegedShell.Stub {
    private static final ExecutorService IO = Executors.newCachedThreadPool();

    public PrivilegedShellService() {
    }

    @Keep
    public PrivilegedShellService(Context context) {
    }

    @Override public String[] execute(String command) {
        if (command == null || command.isBlank()) return new String[]{"0", "", ""};
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(false).start();
            Future<String> stdout = IO.submit(read(process.getInputStream()));
            Future<String> stderr = IO.submit(read(process.getErrorStream()));
            int exitCode = process.waitFor();
            return new String[]{String.valueOf(exitCode), stdout.get(), stderr.get()};
        } catch (Throwable error) {
            if (process != null) process.destroy();
            return new String[]{"-1", "", error.toString()};
        }
    }

    @Override public void destroy() {
        IO.shutdownNow();
        System.exit(0);
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
}
