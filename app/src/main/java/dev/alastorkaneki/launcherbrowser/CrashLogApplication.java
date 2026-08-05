package dev.alastorkaneki.launcherbrowser;

import android.app.Application;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

public final class CrashLogApplication extends Application {
    private static final String LAST_CRASH_FILE = "last_crash.txt";

    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash(thread, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    private void writeCrash(Thread thread, Throwable error) {
        try {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : getPackageName();
            String text = "Launcher Browser crash\n"
                    + "Time: " + DateFormat.getDateTimeInstance().format(new Date()) + "\n"
                    + "Process: " + process + "\n"
                    + "Thread: " + thread.getName() + "\n\n"
                    + stack;
            File file = new File(getFilesDir(), LAST_CRASH_FILE);
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    static String readLastCrash(android.content.Context context) {
        try {
            File file = new File(context.getFilesDir(), LAST_CRASH_FILE);
            if (!file.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void clearLastCrash(android.content.Context context) {
        try {
            new File(context.getFilesDir(), LAST_CRASH_FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
