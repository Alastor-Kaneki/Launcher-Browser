package dev.alastorkaneki.launcherbrowser;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class BrowserExtensions {
    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "amazon-adsystem.com",
            "scorecardresearch.com",
            "taboola.com",
            "outbrain.com",
            "adsrvr.org",
            "criteo.com",
            "criteo.net",
            "appsflyer.com",
            "branch.io"
    );

    private BrowserExtensions() {}

    static boolean shouldBlock(Context context, Uri uri) {
        if (!Prefs.get(context).getBoolean(Prefs.EXT_CONTENT_BLOCKER, true) || uri == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        for (String blocked : BLOCKED_HOST_SUFFIXES) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        return false;
    }

    static WebResourceResponse blockedResponse() {
        return new WebResourceResponse(
                "text/plain",
                StandardCharsets.UTF_8.name(),
                new ByteArrayInputStream(new byte[0]));
    }

    static void apply(Context context, WebView webView, String url) {
        if (webView == null || url == null || url.startsWith("launcher:") || url.startsWith("data:")) return;

        StringBuilder javascript = new StringBuilder("(function(){try{");
        if (Prefs.get(context).getBoolean(Prefs.EXT_DARK_PAGES, false)) {
            javascript.append("if(!document.getElementById('lb-dark-pages')){")
                    .append("var s=document.createElement('style');s.id='lb-dark-pages';")
                    .append("s.textContent='html{filter:invert(.90) hue-rotate(180deg)!important;background:#111!important}img,video,picture,canvas,iframe,svg{filter:invert(1) hue-rotate(180deg)!important}';")
                    .append("(document.head||document.documentElement).appendChild(s);}");
        }
        if (Prefs.get(context).getBoolean(Prefs.EXT_HIDE_COOKIE_BANNERS, false)) {
            javascript.append("if(!document.getElementById('lb-cookie-cleaner')){")
                    .append("var c=document.createElement('style');c.id='lb-cookie-cleaner';")
                    .append("c.textContent='[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[aria-label*=cookie i]{display:none!important}body{overflow:auto!important}';")
                    .append("(document.head||document.documentElement).appendChild(c);}");
        }
        javascript.append("}catch(e){console.error('Launcher Browser extension error',e);}})();");
        webView.evaluateJavascript(javascript.toString(), null);
        UserscriptStore.injectMatching(context, webView, url);
    }
}
