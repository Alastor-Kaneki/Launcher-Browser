package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native Opera GX Store package downloader built into Launcher Browser. */
final class GxModDownloader {
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_PAGE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_PACKAGE_BYTES = 1024L * 1024L * 1024L;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final String CDN_HOST_PATTERN =
            "(?:mods\\.store\\.gx\\.me|play\\.gxc\\.gg|play\\.gx\\.games)";

    private static final Pattern STORE_PAGE_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?store\\.gx\\.me/"
                    + "(?:[a-z]{2}(?:-[a-z]{2})?/)?mods/"
                    + "[a-z0-9]+/[a-z0-9-]+/?(?:[?#][^\\s<>]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DIRECT_CRX_PATTERN = Pattern.compile(
            "(?:https?:)?//" + CDN_HOST_PATTERN
                    + "/mods/[^\\s\\\"'<>]+?/mod\\.crx(?:\\?[^\\s\\\"'<>]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTENT_ASSET_PATTERN = Pattern.compile(
            "(?:https?:)?//" + CDN_HOST_PATTERN
                    + "/mods/[^\\s\\\"'<>]+?/(?:contents?|assets?)/[^\\s\\\"'<>]+",
            Pattern.CASE_INSENSITIVE);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "gx-native-downloader");
        thread.setDaemon(true);
        return thread;
    });

    private GxModDownloader() {}

    static boolean isGxModPage(String url) {
        if (url == null) return false;
        if (isAllowedCrxUrl(url)) return true;
        return extractStorePageUrl(url) != null;
    }

    static void open(Activity activity, WebView webView) {
        if (activity == null || webView == null) return;
        String pageUrl = webView.getUrl();
        if (pageUrl == null || (!isGxModPage(pageUrl) && !isOfficialGxHost(pageUrl))) {
            Toast.makeText(activity, "Open an Opera GX Store mod page first.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "Resolving official GX package…", Toast.LENGTH_SHORT).show();
        String snapshotScript = "(function(){try{"
                + "const out=new Set();const add=v=>{if(typeof v==='string'&&v.length&&v.length<8192)out.add(v.replace(/&amp;/g,'&'));};"
                + "add(location.href);"
                + "try{performance.getEntriesByType('resource').forEach(e=>add(e.name));}catch(e){}"
                + "document.querySelectorAll('img,source,video,meta,link,script,[style],[data-src],[data-url],[data-href],[poster]').forEach(el=>{"
                + "for(const a of (el.attributes||[]))add(a.value);});"
                + "const h=(document.documentElement&&document.documentElement.innerHTML)||'';"
                + "const r=/(?:https?:)?\\/\\/(?:mods\\.store\\.gx\\.me|play\\.gxc\\.gg|play\\.gx\\.games)\\/mods\\/[^\\s\\\"'<>]+/gi;"
                + "let m,c=0;while((m=r.exec(h))&&c++<250)add(m[0]);"
                + "return Array.from(out).slice(0,500);"
                + "}catch(e){return [location.href];}})();";

        webView.evaluateJavascript(snapshotScript, result -> {
            String detected = resolveFromJavascriptResult(result);
            if (detected != null) {
                showActions(activity, detected, pageUrl);
                return;
            }
            String storeUrl = extractStorePageUrl(pageUrl);
            if (storeUrl == null) {
                fail(activity, "No official GX package URL was detected on this page.");
                return;
            }
            String userAgent = webView.getSettings().getUserAgentString();
            String cookies = CookieManager.getInstance().getCookie(storeUrl);
            EXECUTOR.execute(() -> {
                try {
                    String html = fetchText(storeUrl, userAgent, cookies);
                    String crxUrl = resolveCrxUrlFromText(html);
                    if (crxUrl == null) {
                        fail(activity,
                                "GX Store did not expose a package URL. Reload the mod page, wait for its preview to load, then tap GX again.");
                    } else {
                        activity.runOnUiThread(() -> showActions(activity, crxUrl, storeUrl));
                    }
                } catch (IOException error) {
                    fail(activity, "Could not read GX Store: " + safeMessage(error));
                }
            });
        });
    }

    private static void showActions(Activity activity, String crxUrl, String pageUrl) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String slug = slugFromStoreUrl(pageUrl);
        String[] actions = {
                "Download raw ZIP (.zip)",
                "Download original package (.crx)",
                "Copy official package URL"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Download GX Mod")
                .setMessage(slug + "\n\nThe files are downloaded only. Launcher Browser does not install or activate GX mods.")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) downloadZip(activity, crxUrl, slug);
                    if (which == 1) enqueueCrx(activity, crxUrl, slug);
                    if (which == 2) copyUrl(activity, crxUrl);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void enqueueCrx(Activity activity, String crxUrl, String slug) {
        if (!isAllowedCrxUrl(crxUrl)) {
            fail(activity, "Blocked a non-GX package URL.");
            return;
        }
        String fileName = timestampedName(slug, ".crx");
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            fail(activity, "Android Download Manager is unavailable.");
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(crxUrl))
                    .setTitle(fileName)
                    .setDescription("Original Opera GX mod package")
                    .setMimeType("application/x-chrome-extension")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            manager.enqueue(request);
            Toast.makeText(activity, "Downloading " + fileName, Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            fail(activity, "Could not start GX download: " + safeMessage(error));
        }
    }

    private static void downloadZip(Activity activity, String crxUrl, String slug) {
        if (!isAllowedCrxUrl(crxUrl)) {
            fail(activity, "Blocked a non-GX package URL.");
            return;
        }
        String fileName = timestampedName(slug, ".zip");
        Toast.makeText(activity, "Downloading and extracting GX archive…", Toast.LENGTH_LONG).show();
        EXECUTOR.execute(() -> {
            File temp = null;
            try {
                temp = downloadToTemp(activity, crxUrl);
                long payloadOffset = findZipPayloadOffset(temp);
                savePayloadToDownloads(activity, temp, payloadOffset, fileName);
                activity.runOnUiThread(() -> Toast.makeText(
                        activity,
                        "Saved " + fileName + " to Downloads",
                        Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                fail(activity, "GX ZIP download failed: " + safeMessage(error));
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private static File downloadToTemp(Context context, String crxUrl) throws IOException {
        HttpURLConnection connection = null;
        File temp = File.createTempFile("gx-mod-", ".package", context.getCacheDir());
        try {
            connection = (HttpURLConnection) new URL(crxUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", DEFAULT_UA);
            connection.setRequestProperty("Accept", "application/octet-stream,application/x-chrome-extension,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("GX CDN returned HTTP " + status);

            long total = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_PACKAGE_BYTES) throw new IOException("package exceeded 1 GB safety limit");
                    output.write(buffer, 0, read);
                }
            }
            if (total < 4) throw new IOException("GX CDN returned an empty package");
            return temp;
        } catch (IOException error) {
            temp.delete();
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static long findZipPayloadOffset(File file) throws IOException {
        byte[] header = new byte[16];
        try (InputStream input = new FileInputStream(file)) {
            if (input.read(header) < 12) throw new IOException("package header is incomplete");
        }
        if (header[0] == 'P' && header[1] == 'K') return 0;
        if (header[0] != 'C' || header[1] != 'r' || header[2] != '2' || header[3] != '4') {
            throw new IOException("package is neither ZIP nor CRX");
        }
        long version = readLe32(header, 4);
        long offset;
        if (version == 2) {
            long publicKeyLength = readLe32(header, 8);
            long signatureLength = readLe32(header, 12);
            offset = 16L + publicKeyLength + signatureLength;
        } else if (version == 3) {
            long headerLength = readLe32(header, 8);
            offset = 12L + headerLength;
        } else {
            throw new IOException("unsupported CRX version " + version);
        }
        if (offset < 0 || offset + 4 > file.length()) throw new IOException("invalid CRX payload offset");
        try (InputStream input = new FileInputStream(file)) {
            skipFully(input, offset);
            int p = input.read();
            int k = input.read();
            if (p != 'P' || k != 'K') throw new IOException("CRX payload is not a ZIP archive");
        }
        return offset;
    }

    private static long readLe32(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24);
    }

    private static void savePayloadToDownloads(
            Context context,
            File source,
            long offset,
            String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Launcher Browser");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri destination = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values);
            if (destination == null) throw new IOException("Android could not create the Downloads file");
            boolean success = false;
            try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                 OutputStream output = new BufferedOutputStream(
                         context.getContentResolver().openOutputStream(destination, "w"))) {
                if (output == null) throw new IOException("Android could not open the Downloads file");
                skipFully(input, offset);
                copy(input, output);
                success = true;
            } finally {
                if (success) {
                    ContentValues complete = new ContentValues();
                    complete.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(destination, complete, null, null);
                } else {
                    context.getContentResolver().delete(destination, null, null);
                }
            }
        } else {
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IOException("external Downloads storage is unavailable");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("could not create Downloads directory");
            File destination = new File(directory, fileName);
            try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                skipFully(input, offset);
                copy(input, output);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void skipFully(InputStream input, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() == -1) {
                throw new IOException("unexpected end of package");
            } else {
                remaining--;
            }
        }
    }

    private static void copyUrl(Context context, String url) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("GX package URL", url));
        Toast.makeText(context, "Official GX package URL copied", Toast.LENGTH_SHORT).show();
    }

    private static String resolveFromJavascriptResult(String result) {
        if (result == null || result.equals("null")) return null;
        try {
            JSONArray array = new JSONArray(result);
            for (int i = 0; i < array.length(); i++) {
                String found = resolveCrxUrlFromText(array.optString(i, ""));
                if (found != null) return found;
            }
        } catch (Exception ignored) {
        }
        return resolveCrxUrlFromText(result);
    }

    static String resolveCrxUrlFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String normalized = normalizeEscapes(text);
        Matcher direct = DIRECT_CRX_PATTERN.matcher(normalized);
        while (direct.find()) {
            String candidate = withHttps(trimTrailingPunctuation(direct.group()));
            if (isAllowedCrxUrl(candidate)) return candidate;
        }
        Matcher content = CONTENT_ASSET_PATTERN.matcher(normalized);
        while (content.find()) {
            String assetUrl = withHttps(trimTrailingPunctuation(content.group()));
            String lower = assetUrl.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("/contents/");
            if (index < 0) index = lower.indexOf("/content/");
            if (index < 0) index = lower.indexOf("/assets/");
            if (index < 0) index = lower.indexOf("/asset/");
            if (index >= 0) {
                String candidate = assetUrl.substring(0, index) + "/mod.crx";
                if (isAllowedCrxUrl(candidate)) return candidate;
            }
        }
        return null;
    }

    static boolean isAllowedCrxUrl(String candidate) {
        if (candidate == null) return false;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(scheme) || host == null || path == null) return false;
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean allowedHost = normalizedHost.equals("mods.store.gx.me")
                    || normalizedHost.equals("play.gxc.gg")
                    || normalizedHost.equals("play.gx.games");
            return allowedHost && path.toLowerCase(Locale.ROOT).endsWith("/mod.crx");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static boolean isOfficialGxHost(String candidate) {
        try {
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("store.gx.me")
                    || host.equals("www.store.gx.me")
                    || host.equals("mods.store.gx.me")
                    || host.equals("play.gxc.gg")
                    || host.equals("play.gx.games");
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String extractStorePageUrl(String text) {
        if (text == null) return null;
        Matcher matcher = STORE_PAGE_PATTERN.matcher(normalizeEscapes(text.trim()));
        if (!matcher.find()) return null;
        String value = trimTrailingPunctuation(matcher.group());
        if (!value.regionMatches(true, 0, "http://", 0, 7)
                && !value.regionMatches(true, 0, "https://", 0, 8)) {
            value = "https://" + value;
        }
        return value;
    }

    private static String fetchText(String urlString, String userAgent, String cookies) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent",
                    userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (cookies != null && !cookies.isBlank()) connection.setRequestProperty("Cookie", cookies);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_PAGE_BYTES) throw new IOException("page exceeded 16 MB safety limit");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String slugFromStoreUrl(String storeUrl) {
        String normalized = extractStorePageUrl(storeUrl);
        if (normalized == null) return "gx-mod";
        Uri uri = Uri.parse(normalized);
        String last = uri.getLastPathSegment();
        if (last == null || last.isBlank()) return "gx-mod";
        String sanitized = last.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "gx-mod" : sanitized;
    }

    private static String timestampedName(String slug, String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return slug + "-" + timestamp + extension;
    }

    private static String normalizeEscapes(String text) {
        return text
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u003A", ":")
                .replace("\\u003a", ":")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&");
    }

    private static String withHttps(String url) {
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == ')' || c == ']' || c == '}' || c == '"' || c == '\'') end--;
            else break;
        }
        return value.substring(0, end);
    }

    private static void fail(Activity activity, String message) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
