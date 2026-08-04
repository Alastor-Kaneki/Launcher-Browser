package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

public final class ShellActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;
    private TextView output;
    private TextView status;
    private EditText command;
    private ScrollView scroll;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == ShellExecutor.REQUEST_CODE) main.post(this::refreshStatus);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        buildUi();
        String initial = getIntent().getStringExtra("command");
        if (initial != null && !initial.isBlank()) {
            command.setText(initial);
            command.setSelection(command.length());
        }
        refreshStatus();
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.title(this, "ADB Shell");
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        status = new TextView(this);
        status.setTextColor(Ui.PURPLE);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(status);
        root.addView(header);

        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        LinearLayout quick = new LinearLayout(this);
        quick.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        String[][] commands = {
                {"Packages", "pm list packages -3"},
                {"Battery", "dumpsys battery"},
                {"Memory", "dumpsys meminfo | head -80"},
                {"Wi-Fi on", "svc wifi enable"},
                {"Wi-Fi off", "svc wifi disable"},
                {"Animations .5x", "settings put global window_animation_scale .5; settings put global transition_animation_scale .5; settings put global animator_duration_scale .5"}
        };
        for (String[] item : commands) {
            Button button = Ui.button(this, item[0]);
            button.setOnClickListener(v -> {
                command.setText(item[1]);
                command.setSelection(command.length());
                runCommand();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
            params.setMargins(0, 0, Ui.dp(this, 8), 0);
            quick.addView(button, params);
        }
        quickScroll.addView(quick);
        root.addView(quickScroll);

        output = new TextView(this);
        output.setTextColor(Color.rgb(210, 255, 220));
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setText("Launcher Browser shell\nNo webpage can invoke commands here.\n\n");
        output.setTextIsSelectable(true);
        output.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        output.setBackground(Ui.rounded(Color.rgb(4, 10, 6), 14, this));
        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout input = new LinearLayout(this);
        input.setGravity(Gravity.CENTER_VERTICAL);
        command = new EditText(this);
        command.setSingleLine(true);
        command.setTextColor(Color.WHITE);
        command.setHintTextColor(Color.GRAY);
        command.setHint("shell command");
        command.setTypeface(Typeface.MONOSPACE);
        command.setBackground(Ui.rounded(Ui.PANEL, 14, this));
        command.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        command.setOnEditorActionListener((v, actionId, event) -> {
            if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                runCommand();
                return true;
            }
            return false;
        });
        input.addView(command, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));

        Button previous = Ui.button(this, "↑");
        previous.setOnClickListener(v -> previousHistory());
        input.addView(previous, buttonParams());
        Button run = Ui.button(this, "Run");
        run.setOnClickListener(v -> runCommand());
        input.addView(run, buttonParams());
        Button clear = Ui.button(this, "Clear");
        clear.setOnClickListener(v -> output.setText(""));
        input.addView(clear, buttonParams());
        root.addView(input);

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        params.setMargins(Ui.dp(this, 6), 0, 0, 0);
        return params;
    }

    private void refreshStatus() {
        status.setText(ShellExecutor.identity());
        if (ShellExecutor.binderAvailable() && !ShellExecutor.hasPermission()) {
            status.setOnClickListener(v -> ShellExecutor.requestPermission(this));
            Toast.makeText(this, "Tap the purple status to grant Shizuku access", Toast.LENGTH_LONG).show();
        } else {
            status.setOnClickListener(null);
        }
    }

    private void runCommand() {
        String value = command.getText().toString().trim();
        if (value.isEmpty()) return;
        if (!ShellExecutor.hasPermission()) {
            if (ShellExecutor.binderAvailable()) {
                ShellExecutor.requestPermission(this);
                append("Shizuku permission requested. Run the command again after granting it.\n");
            } else {
                append("Shizuku is not running. Execution was blocked because this console is intended for ADB-shell or root identity.\n");
            }
            return;
        }
        history.add(value);
        historyIndex = history.size();
        command.setText("");
        append("$ " + value + "\n");
        new Thread(() -> {
            try {
                ShellExecutor.Result result = ShellExecutor.executeBlocking(value);
                main.post(() -> {
                    if (!result.stdout.isEmpty()) append(result.stdout);
                    if (!result.stderr.isEmpty()) append("[stderr]\n" + result.stderr);
                    append("[exit " + result.exitCode + "]\n\n");
                    refreshStatus();
                });
            } catch (Exception error) {
                main.post(() -> append("[error] " + error + "\n\n"));
            }
        }, "launcher-browser-shell").start();
    }

    private void previousHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, historyIndex - 1);
        command.setText(history.get(historyIndex));
        command.setSelection(command.length());
    }

    private void append(String text) {
        output.append(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
