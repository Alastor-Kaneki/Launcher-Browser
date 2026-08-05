package dev.alastorkaneki.launcherbrowser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class BrowserActivity extends Activity {
    private static final String HOME = "launcher://home";
    private static final int MAX_TABS = 12;
    private static final String MOBILE_UA_TOKEN = " Mobile";

    private final List<BrowserTab> tabs = new ArrayList<>();
    private FrameLayout content;
    private LinearLayout tabStrip;
    private EditText address;
    private int selected = -1;

    private static final class BrowserTab {
        final WebView webView;
        final Button tabButton;
        boolean desktop;

        BrowserTab(WebView webView, Button tabButton) {
            this.webView = webView;
            this.tabButton = tabButton;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyImmersive(this);
        buildUi();
        restoreTabs();
        Uri incoming = getIntent().getData();
        if (incoming != null) {
            if (tabs.isEmpty()) addTab(incoming.toString(), true);
            else {
                current().webView.loadUrl(incoming.toString());
                address.setText(incoming.toString());
            }
        }
        if (tabs.isEmpty()) addTab(HOME, true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent.getData();
        if (data != null) {
            if (tabs.isEmpty()) addTab(data.toString(), true);
            else {
                current().webView.loadUrl(data.toString());
                address.setText(data.toString());
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.applyImmersive(this);
        BrowserTab tab = current();
        if (tab != null) tab.webView.onResume();
    }

    @Override protected void onPause() {
        BrowserTab tab = current();
        if (tab != null) tab.webView.onPause();
        saveTabs();
        super.onPause();
    }

    @Override protected void onDestroy() {
        for (BrowserTab tab : new ArrayList<>(tabs)) {
            try { tab.webView.destroy(); } catch (Throwable ignored) {}
        }
        tabs.clear();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4));
        toolbar.setBackgroundColor(Color.rgb(7, 7, 10));

        Button back = compact("‹");
        back.setOnClickListener(v -> {
            BrowserTab tab = current();
            if (tab != null && tab.webView.canGoBack()) tab.webView.goBack();
            else finish();
        });
        toolbar.addView(back, compactParams());

        Button forward = compact("›");
        forward.setOnClickListener(v -> {
            BrowserTab tab = current();
            if (tab != null && tab.webView.canGoForward()) tab.webView.goForward();
        });
        toolbar.addView(forward, compactParams());

        Button home = compact("⌂");
        home.setOnClickListener(v -> {
            BrowserTab tab = current();
            if (tab != null) loadHome(tab.webView);
        });
        toolbar.addView(home, compactParams());

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.GRAY);
        address.setHint("Search or enter address");
        address.setTextSize(13);
        address.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        address.setBackground(Ui.rounded(Ui.PANEL_LIGHT, 18, this));
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                navigate(address.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        addressParams.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        toolbar.addView(address, addressParams);

        Button reload = compact("↻");
        reload.setOnClickListener(v -> {
            BrowserTab tab = current();
            if (tab != null) tab.webView.reload();
        });
        toolbar.addView(reload, compactParams());

        Button menu = compact("⋮");
        menu.setOnClickListener(this::showMenu);
        toolbar.addView(menu, compactParams());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.TRANSPARENT);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private Button compact(String text) {
        Button button = Ui.button(this, text);
        button.setTextSize(19);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private LinearLayout.LayoutParams compactParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46));
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return params;
    }

    private void addTab(String initialUrl, boolean select) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Maximum " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        WebView webView;
        try {
            webView = new WebView(this);
        } catch (Throwable error) {
            Toast.makeText(this, "WebView failed: " + safeMessage(error), Toast.LENGTH_LONG).show();
            return;
        }
        configureWebView(webView);
        Button tabButton = Ui.button(this, "New tab  ×");
        tabButton.setSingleLine(true);
        tabButton.setTextSize(11);
        BrowserTab tab = new BrowserTab(webView, tabButton);
        tabs.add(tab);
        int index = tabs.size() - 1;
        tabButton.setOnClickListener(v -> selectTab(tabs.indexOf(tab)));
        tabButton.setOnLongClickListener(v -> {
            closeTab(tabs.indexOf(tab));
            return true;
        });
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(Ui.dp(this, 135), Ui.dp(this, 38));
        tabParams.setMargins(0, 0, Ui.dp(this, 6), 0);
        tabStrip.addView(tabButton, tabParams);
        if (select) selectTab(index);
        if (initialUrl == null || HOME.equals(initialUrl)) loadHome(webView);
        else webView.loadUrl(initialUrl);
    }

    private void configureWebView(WebView webView) {
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("launcher".equalsIgnoreCase(scheme)) {
                    loadHome(view);
                    return true;
                }
                if ("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme)
                        || "data".equalsIgnoreCase(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Throwable error) {
                    Toast.makeText(BrowserActivity.this, "No app can open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (BrowserExtensions.shouldBlock(BrowserActivity.this, request.getUrl())) {
                        return BrowserExtensions.blockedResponse();
                    }
                } catch (Throwable ignored) {
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view == currentWebView()) address.setText(displayUrl(url));
                try { BrowserExtensions.apply(BrowserActivity.this, view, url); }
                catch (Throwable error) {
                    Toast.makeText(BrowserActivity.this, "Extension failed safely: " + safeMessage(error), Toast.LENGTH_SHORT).show();
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                BrowserTab crashed = tabFor(view);
                int index = crashed == null ? -1 : tabs.indexOf(crashed);
                if (index >= 0) {
                    tabs.remove(index);
                    tabStrip.removeView(crashed.tabButton);
                    try { crashed.webView.destroy(); } catch (Throwable ignored) {}
                }
                content.removeAllViews();
                selected = -1;
                addTab(HOME, true);
                Toast.makeText(BrowserActivity.this, "A page renderer crashed; the browser recovered.", Toast.LENGTH_LONG).show();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                BrowserTab tab = tabFor(view);
                if (tab != null) {
                    String value = title == null || title.isBlank() ? "Tab" : title;
                    if (value.length() > 16) value = value.substring(0, 16) + "…";
                    tab.tabButton.setText(value + "  ×");
                }
            }

            @Override public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    android.os.Message resultMsg) {
                if (!isUserGesture || tabs.size() >= MAX_TABS) return false;
                addTab(HOME, true);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(currentWebView());
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.setDownloadListener(downloadListener());
    }

    private DownloadListener downloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(filename);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, "Download failed: " + safeMessage(error), Toast.LENGTH_LONG).show();
            }
        };
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        selected = index;
        content.removeAllViews();
        WebView view = tabs.get(index).webView;
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        content.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).tabButton.setAlpha(i == selected ? 1f : .62f);
        }
        address.setText(displayUrl(view.getUrl()));
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        BrowserTab tab = tabs.remove(index);
        tabStrip.removeView(tab.tabButton);
        try { tab.webView.destroy(); } catch (Throwable ignored) {}
        if (tabs.isEmpty()) {
            selected = -1;
            addTab(HOME, true);
        } else {
            selectTab(Math.min(index, tabs.size() - 1));
        }
    }

    private void navigate(String input) {
        if (current() == null) addTab(HOME, true);
        BrowserTab tab = current();
        if (tab == null) return;
        String normalized = normalizeInput(input);
        if (HOME.equals(normalized)) loadHome(tab.webView);
        else tab.webView.loadUrl(normalized);
    }

    public static String normalizeInput(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return HOME;
        Uri parsed = Uri.parse(value);
        if (parsed.getScheme() != null) return value;
        if (!value.contains(" ") && (value.contains(".") || value.startsWith("localhost"))) {
            return "https://" + value;
        }
        return "https://www.google.com/search?q=" + Uri.encode(value);
    }

    private String displayUrl(String url) {
        if (url == null || url.equals(HOME) || url.startsWith("data:text/html")) return "";
        return url;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("New tab");
        menu.getMenu().add("Close tab");
        menu.getMenu().add("Desktop site");
        menu.getMenu().add("Userscripts");
        menu.getMenu().add("Extensions");
        menu.getMenu().add("Share");
        menu.getMenu().add("Copy URL");
        menu.getMenu().add("Downloads");
        menu.getMenu().add("Launcher home");
        menu.getMenu().add("Settings");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            switch (title) {
                case "New tab" -> addTab(HOME, true);
                case "Close tab" -> closeTab(selected);
                case "Desktop site" -> toggleDesktop();
                case "Userscripts" -> startActivity(new Intent(this, UserscriptManagerActivity.class));
                case "Extensions" -> startActivity(new Intent(this, ExtensionsActivity.class));
                case "Share" -> shareUrl();
                case "Copy URL" -> copyUrl();
                case "Downloads" -> startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                case "Launcher home" -> startActivity(new Intent(this, StableHomeActivity.class));
                case "Settings" -> startActivity(new Intent(this, SettingsActivity.class));
            }
            return true;
        });
        menu.show();
    }

    private void toggleDesktop() {
        BrowserTab tab = current();
        if (tab == null) return;
        tab.desktop = !tab.desktop;
        String ua = tab.webView.getSettings().getUserAgentString();
        if (tab.desktop) {
            ua = ua.replace(MOBILE_UA_TOKEN, "").replace("Android", "X11; Linux x86_64");
            tab.webView.getSettings().setUseWideViewPort(true);
            tab.webView.getSettings().setLoadWithOverviewMode(true);
            tab.webView.getSettings().setUserAgentString(ua);
        } else {
            tab.webView.getSettings().setUserAgentString(null);
            tab.webView.getSettings().setUseWideViewPort(false);
        }
        tab.webView.reload();
        Toast.makeText(this, tab.desktop ? "Desktop site enabled" : "Mobile site enabled", Toast.LENGTH_SHORT).show();
    }

    private void shareUrl() {
        WebView webView = currentWebView();
        if (webView == null || webView.getUrl() == null) return;
        startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, webView.getUrl()),
                "Share page"));
    }

    private void copyUrl() {
        WebView webView = currentWebView();
        if (webView == null || webView.getUrl() == null) return;
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("URL", webView.getUrl()));
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
    }

    private BrowserTab current() {
        return selected >= 0 && selected < tabs.size() ? tabs.get(selected) : null;
    }

    private WebView currentWebView() {
        BrowserTab tab = current();
        return tab == null ? null : tab.webView;
    }

    private BrowserTab tabFor(WebView view) {
        for (BrowserTab tab : tabs) if (tab.webView == view) return tab;
        return null;
    }

    private void saveTabs() {
        List<String> urls = new ArrayList<>();
        for (BrowserTab tab : tabs) {
            String url = tab.webView.getUrl();
            urls.add(url == null || url.startsWith("data:text/html") ? HOME : url);
        }
        Prefs.get(this).edit()
                .putString(Prefs.BROWSER_URLS, String.join("\n", urls))
                .putInt(Prefs.BROWSER_SELECTED, selected)
                .apply();
    }

    private void restoreTabs() {
        String stored = Prefs.get(this).getString(Prefs.BROWSER_URLS, "");
        if (stored == null || stored.isEmpty()) return;
        String[] urls = stored.split("\n");
        int wanted = Math.max(0, Math.min(
                urls.length - 1,
                Prefs.get(this).getInt(Prefs.BROWSER_SELECTED, 0)));
        for (int i = 0; i < Math.min(urls.length, MAX_TABS); i++) {
            addTab(urls[i], i == wanted);
        }
    }

    @Override public void onBackPressed() {
        BrowserTab tab = current();
        if (tab != null && tab.webView.canGoBack()) tab.webView.goBack();
        else finish();
    }

    private static String homeHtml() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{height:100%;margin:0;background:transparent;color:white;font-family:sans-serif}"
                + "body{display:grid;place-items:center}.card{padding:28px;text-align:center;border:1px solid #8b5cf6;border-radius:28px;background:#0a0a0ecc;max-width:82%}"
                + "h1{margin:0 0 8px}p{color:#c7c7d2}.small{font-size:12px;color:#8f8fa3}</style></head><body><div class='card'><h1>Launcher Browser</h1>"
                + "<p>Use the address bar to search or open a site.</p>"
                + "<p class='small'>Userscripts and Lite Extensions are available from the menu.</p></div></body></html>";
    }

    private void loadHome(WebView webView) {
        webView.loadDataWithBaseURL(HOME, homeHtml(), "text/html", "utf-8", null);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
