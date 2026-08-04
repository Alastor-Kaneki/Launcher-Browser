package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

final class ShellExecutor {
    static final int REQUEST_CODE = 4102;
    private static final Object SERVICE_LOCK = new Object();
    private static volatile IPrivilegedShell service;
    private static volatile CountDownLatch connectionLatch;
    private static volatile Shizuku.UserServiceArgs serviceArgs;

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

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrivilegedShell.Stub.asInterface(binder);
            CountDownLatch latch = connectionLatch;
            if (latch != null) latch.countDown();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

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

    static Result executeBlocking(Context context, String command) throws Exception {
        if (!hasPermission()) throw new SecurityException("Shizuku permission is not granted");
        IPrivilegedShell privilegedShell = ensureService(context.getApplicationContext());
        String[] response = privilegedShell.execute(command);
        if (response == null || response.length < 3) throw new IllegalStateException("Invalid shell service response");
        int exitCode;
        try {
            exitCode = Integer.parseInt(response[0]);
        } catch (NumberFormatException ignored) {
            exitCode = -1;
        }
        return new Result(exitCode, response[1] == null ? "" : response[1], response[2] == null ? "" : response[2], true);
    }

    private static IPrivilegedShell ensureService(Context context) throws Exception {
        IPrivilegedShell current = service;
        if (current != null && current.asBinder().pingBinder()) return current;

        CountDownLatch latch;
        synchronized (SERVICE_LOCK) {
            current = service;
            if (current != null && current.asBinder().pingBinder()) return current;
            if (connectionLatch == null || connectionLatch.getCount() == 0) {
                connectionLatch = new CountDownLatch(1);
                serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedShellService.class))
                        .daemon(false)
                        .processNameSuffix("privileged_shell")
                        .debuggable(BuildConfig.DEBUG)
                        .version(BuildConfig.VERSION_CODE);
                try {
                    Shizuku.bindUserService(serviceArgs, CONNECTION);
                } catch (Throwable error) {
                    connectionLatch.countDown();
                    throw error;
                }
            }
            latch = connectionLatch;
        }

        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out connecting to privileged shell service");
        current = service;
        if (current == null || !current.asBinder().pingBinder()) throw new IllegalStateException("Privileged shell service did not connect");
        return current;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
