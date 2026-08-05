package dev.alastorkaneki.launcherbrowser;

import android.content.Context;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class UserscriptStore {
    static final String TYPE_JS = "js";
    static final String TYPE_CSS = "css";

    static final class Script {
        String id;
        String name;
        String matches;
        String excludes;
        String type;
        String code;
        boolean enabled;

        Script() {
            id = String.valueOf(System.nanoTime());
            name = "New userscript";
            matches = "*://*/*";
            excludes = "";
            type = TYPE_JS;
            code = "// Your script runs after the page finishes loading.\n";
            enabled = true;
        }
    }

    private UserscriptStore() {}

    static List<Script> load(Context context) {
        List<Script> scripts = new ArrayList<>();
        String raw = Prefs.get(context).getString(Prefs.USERSCRIPTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Script script = new Script();
                script.id = object.optString("id", script.id);
                script.name = object.optString("name", script.name);
                script.matches = object.optString("matches", script.matches);
                script.excludes = object.optString("excludes", "");
                script.type = object.optString("type", TYPE_JS);
                script.code = object.optString("code", "");
                script.enabled = object.optBoolean("enabled", true);
                scripts.add(script);
            }
        } catch (Exception ignored) {
            Prefs.get(context).edit().putString(Prefs.USERSCRIPTS, "[]").apply();
        }
        return scripts;
    }

    static void save(Context context, List<Script> scripts) {
        JSONArray array = new JSONArray();
        for (Script script : scripts) {
            try {
                JSONObject object = new JSONObject();
                object.put("id", script.id);
                object.put("name", script.name);
                object.put("matches", script.matches);
                object.put("excludes", script.excludes);
                object.put("type", script.type);
                object.put("code", script.code);
                object.put("enabled", script.enabled);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        Prefs.get(context).edit().putString(Prefs.USERSCRIPTS, array.toString()).apply();
    }

    static Script parseImported(String fileName, String code) {
        Script script = new Script();
        script.name = fileName == null || fileName.isBlank() ? "Imported userscript" : fileName;
        script.code = code == null ? "" : code;
        script.type = script.name.toLowerCase(Locale.ROOT).endsWith(".css") ? TYPE_CSS : TYPE_JS;

        List<String> matches = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String line : script.code.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("@name ")) script.name = afterToken(trimmed, "@name");
            if (trimmed.contains("@match ")) matches.add(afterToken(trimmed, "@match"));
            if (trimmed.contains("@include ")) matches.add(afterToken(trimmed, "@include"));
            if (trimmed.contains("@exclude ")) excludes.add(afterToken(trimmed, "@exclude"));
        }
        if (!matches.isEmpty()) script.matches = String.join("\n", matches);
        if (!excludes.isEmpty()) script.excludes = String.join("\n", excludes);
        return script;
    }

    private static String afterToken(String line, String token) {
        int index = line.indexOf(token);
        return index < 0 ? "" : line.substring(index + token.length()).trim();
    }

    static void injectMatching(Context context, WebView webView, String url) {
        for (Script script : load(context)) {
            if (!script.enabled || script.code == null || script.code.isBlank()) continue;
            if (!matchesAny(script.matches, url) || matchesAny(script.excludes, url)) continue;
            if (TYPE_CSS.equals(script.type)) {
                String escaped = JSONObject.quote(script.code);
                String js = "(function(){try{var s=document.createElement('style');s.setAttribute('data-lb-userscript',"
                        + JSONObject.quote(script.id) + ");s.textContent=" + escaped
                        + ";(document.head||document.documentElement).appendChild(s);}catch(e){console.error(e);}})();";
                webView.evaluateJavascript(js, null);
            } else {
                String js = "(function(){'use strict';"
                        + "const GM_addStyle=(css)=>{var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);return s;};"
                        + "const GM_log=(...args)=>console.log(...args);"
                        + "try{\n" + script.code + "\n}catch(e){console.error('Userscript "
                        + escapeJsLabel(script.name) + " failed',e);}})();";
                webView.evaluateJavascript(js, null);
            }
        }
    }

    private static String escapeJsLabel(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    private static boolean matchesAny(String patterns, String url) {
        if (patterns == null || patterns.isBlank()) return false;
        for (String pattern : patterns.split("[\\n,]")) {
            String value = pattern.trim();
            if (value.isEmpty()) continue;
            if ("<all_urls>".equals(value)) return true;
            if (wildcard(value).matcher(url).matches()) return true;
        }
        return false;
    }

    private static Pattern wildcard(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*') regex.append(".*");
            else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
