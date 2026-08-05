package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class UserscriptManagerActivity extends Activity {
    private static final int REQUEST_IMPORT = 5101;

    private final List<UserscriptStore.Script> scripts = new ArrayList<>();
    private LinearLayout scriptList;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        buildUi();
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.applyImmersive(this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 28));
        root.setBackgroundColor(Prefs.amoled(this) ? Color.BLACK : Color.rgb(15, 15, 21));
        scroll.addView(root);

        root.addView(Ui.title(this, "Userscripts"));

        TextView info = text("Scripts run only inside Launcher Browser after matching pages finish loading. Supported metadata: @name, @match, @include and @exclude. Basic GM_addStyle and GM_log helpers are available.");
        info.setTextColor(Color.LTGRAY);
        root.addView(info);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button add = Ui.button(this, "+ New");
        add.setOnClickListener(v -> editScript(new UserscriptStore.Script(), true));
        actions.addView(add, weightedButtonParams());
        Button importButton = Ui.button(this, "Import file");
        importButton.setOnClickListener(v -> importScript());
        actions.addView(importButton, weightedButtonParams());
        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        scriptList = new LinearLayout(this);
        scriptList.setOrientation(LinearLayout.VERTICAL);
        root.addView(scriptList);

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4));
        return params;
    }

    private void reload() {
        scripts.clear();
        scripts.addAll(UserscriptStore.load(this));
        renderScripts();
    }

    private void renderScripts() {
        scriptList.removeAllViews();
        if (scripts.isEmpty()) {
            TextView empty = text("No userscripts installed.");
            empty.setTextColor(Color.GRAY);
            scriptList.addView(empty);
            return;
        }

        for (UserscriptStore.Script script : scripts) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            card.setBackground(Ui.rounded(Ui.PANEL, 18, this));

            CheckBox enabled = new CheckBox(this);
            enabled.setText(script.name + (UserscriptStore.TYPE_CSS.equals(script.type) ? "  [CSS]" : "  [JS]"));
            enabled.setTextColor(Color.WHITE);
            enabled.setChecked(script.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                script.enabled = checked;
                UserscriptStore.save(this, scripts);
            });
            card.addView(enabled);

            TextView matches = text("Matches: " + compact(script.matches));
            matches.setTextColor(Color.LTGRAY);
            card.addView(matches);

            LinearLayout row = new LinearLayout(this);
            Button edit = Ui.button(this, "Edit");
            edit.setOnClickListener(v -> editScript(script, false));
            row.addView(edit, weightedButtonParams());
            Button delete = Ui.button(this, "Delete");
            delete.setOnClickListener(v -> confirmDelete(script));
            row.addView(delete, weightedButtonParams());
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            scriptList.addView(card, cardParams);
        }
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) return "none";
        String compact = value.replace('\n', ' ');
        return compact.length() > 110 ? compact.substring(0, 110) + "…" : compact;
    }

    private void editScript(UserscriptStore.Script script, boolean isNew) {
        ScrollView editorScroll = new ScrollView(this);
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        editorScroll.addView(editor);

        EditText name = field("Name", script.name, false);
        editor.addView(name);

        TextView typeLabel = text("Type");
        editor.addView(typeLabel);
        Spinner type = new Spinner(this);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"JavaScript", "CSS userstyle"});
        type.setAdapter(typeAdapter);
        type.setSelection(UserscriptStore.TYPE_CSS.equals(script.type) ? 1 : 0);
        editor.addView(type, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        EditText matches = field("Match patterns, one per line", script.matches, true);
        matches.setMinLines(3);
        editor.addView(matches);

        EditText excludes = field("Exclude patterns, optional", script.excludes, true);
        excludes.setMinLines(2);
        editor.addView(excludes);

        EditText code = field("Code", script.code, true);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setMinLines(12);
        code.setHorizontallyScrolling(true);
        editor.addView(code, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? "New userscript" : "Edit userscript")
                .setView(editorScroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String scriptName = name.getText().toString().trim();
            String scriptCode = code.getText().toString();
            if (scriptName.isEmpty() || scriptCode.isBlank()) {
                Toast.makeText(this, "Name and code are required", Toast.LENGTH_LONG).show();
                return;
            }
            script.name = scriptName;
            script.matches = matches.getText().toString().trim();
            if (script.matches.isEmpty()) script.matches = "*://*/*";
            script.excludes = excludes.getText().toString().trim();
            script.type = type.getSelectedItemPosition() == 1
                    ? UserscriptStore.TYPE_CSS
                    : UserscriptStore.TYPE_JS;
            script.code = scriptCode;
            if (isNew) scripts.add(script);
            UserscriptStore.save(this, scripts);
            renderScripts();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private EditText field(String hint, String value, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.GRAY);
        field.setSingleLine(!multiline);
        field.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        return field;
    }

    private void confirmDelete(UserscriptStore.Script script) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + script.name + "?")
                .setMessage("This removes the local userscript from Launcher Browser.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    scripts.remove(script);
                    UserscriptStore.save(this, scripts);
                    renderScripts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importScript() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/javascript", "application/javascript", "text/css"});
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String name = displayName(uri);
            String code = readText(uri);
            UserscriptStore.Script script = UserscriptStore.parseImported(name, code);
            editScript(script, true);
        } catch (Exception error) {
            Toast.makeText(this, "Import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri uri) {
        String result = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return result == null ? "Imported userscript" : result;
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Unable to open file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 2_000_000) throw new IllegalArgumentException("Script is larger than 2 MB");
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        return view;
    }
}
