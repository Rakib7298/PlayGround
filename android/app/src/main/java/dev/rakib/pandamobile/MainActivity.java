package dev.rakib.pandamobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.text.TextUtils;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.rakib.pandamobile.engine.NativeCore;

/**
 * A small, dependency-free Android browser shell.
 *
 * <p>Pixels and touch input are handled by the platform WebView (Chromium).
 * The app deliberately keeps the rendering engine behind a thin, documented
 * boundary so a native Lightpanda core can be attached later through
 * {@link NativeCore}; see docs/lightpanda-mobile/ for the port plan.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String PREFS = "panda_mobile";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 1001;
    private static final int REQ_DOWNLOAD_PERMISSION = 1002;

    private static final int MENU_NEW_TAB = 1;
    private static final int MENU_RELOAD = 2;
    private static final int MENU_SHARE = 3;
    private static final int MENU_DESKTOP = 4;
    private static final int MENU_JAVASCRIPT = 5;
    private static final int MENU_READER = 6;
    private static final int MENU_BOOKMARK = 7;
    private static final int MENU_BOOKMARKS = 8;
    private static final int MENU_DOWNLOADS = 9;
    private static final int MENU_ENGINE = 10;
    private static final int MENU_ABOUT = 11;

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private Tab activeTab;

    private LinearLayout topBar;
    private HorizontalScrollView tabStrip;
    private LinearLayout tabContainer;
    private FrameLayout content;
    private EditText urlBar;
    private ProgressBar progress;
    private TextView btnBack;
    private TextView btnForward;
    private TextView btnReload;

    private SharedPreferences prefs;
    private boolean desktopMode;
    private boolean javaScriptEnabled = true;

    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        applyInsets();
        loadPreferences();

        CookieManager.getInstance().setAcceptCookie(true);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        wireToolbar();
        restoreOrCreateTabs(savedInstanceState);
        handleIntent(getIntent());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        tabStrip = findViewById(R.id.tabStrip);
        tabContainer = findViewById(R.id.tabContainer);
        content = findViewById(R.id.content);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
    }

    private void applyInsets() {
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    private void loadPreferences() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        desktopMode = prefs.getBoolean("desktop_mode", false);
        javaScriptEnabled = prefs.getBoolean("javascript", true);
    }

    private void wireToolbar() {
        btnBack.setOnClickListener(v -> {
            if (activeTab != null) {
                activeTab.webView.goBack();
            }
        });

        btnForward.setOnClickListener(v -> {
            if (activeTab != null) {
                activeTab.webView.goForward();
            }
        });

        btnReload.setOnClickListener(v -> {
            if (activeTab == null) {
                return;
            }
            if (activeTab.loading) {
                activeTab.webView.stopLoading();
            } else {
                activeTab.webView.reload();
            }
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateFromInput();
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                urlBar.post(urlBar::selectAll);
            }
        });

        findViewById(R.id.btnMenu).setOnClickListener(v -> showMenu());
    }

    private void restoreOrCreateTabs(Bundle state) {
        if (state != null) {
            ArrayList<String> urls = state.getStringArrayList("tab_urls");
            int activeIndex = state.getInt("active_tab", 0);
            if (urls != null && !urls.isEmpty()) {
                for (String url : urls) {
                    createTab(url, false);
                }
                selectTab(tabs.get(Math.max(0, Math.min(activeIndex, tabs.size() - 1))));
                return;
            }
        }
        createTab(null, true);
    }

    private Tab createTabShell() {
        Tab tab = new Tab();
        WebView webView = new WebView(this);
        tab.webView = webView;
        configureWebView(tab);
        tabs.add(tab);
        return tab;
    }

    private Tab createTab(String url, boolean select) {
        Tab tab = createTabShell();
        if (select) {
            selectTab(tab);
        }
        if (url == null) {
            tab.webView.loadUrl(HOME_URL);
        } else {
            tab.webView.loadUrl(url);
        }
        rebuildTabs();
        return tab;
    }

    private void configureWebView(Tab tab) {
        WebView webView = tab.webView;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(javaScriptEnabled);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(desktopMode ? UA_DESKTOP : null);
        // Assets stay reachable via file:///android_asset; arbitrary local
        // files and content providers do not.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new BrowserClient(tab));
        webView.setWebChromeClient(new BrowserChrome());
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                requestDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void selectTab(Tab tab) {
        if (tab == null || !tabs.contains(tab)) {
            return;
        }
        if (activeTab != null && activeTab != tab) {
            activeTab.webView.onPause();
        }
        activeTab = tab;
        urlBar.clearFocus();
        hideKeyboard();

        content.removeAllViews();
        content.addView(tab.webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        tab.webView.onResume();
        updateUi();
        rebuildTabs();
    }

    private void closeTab(Tab tab) {
        if (tab == null) {
            return;
        }
        int index = tabs.indexOf(tab);
        if (index < 0) {
            return;
        }
        tabs.remove(tab);
        content.removeView(tab.webView);
        if (activeTab == tab) {
            activeTab = null;
        }
        try {
            tab.webView.stopLoading();
            tab.webView.loadUrl("about:blank");
            tab.webView.clearHistory();
            tab.webView.destroy();
        } catch (Exception ignored) {
            // A destroyed WebView must not take the browser down with it.
        }

        if (tabs.isEmpty()) {
            createTab(null, true);
        } else if (activeTab == null) {
            selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
        } else {
            rebuildTabs();
        }
    }

    private void updateUi() {
        if (activeTab == null) {
            return;
        }
        if (!urlBar.hasFocus()) {
            String shown = displayUrl(activeTab.readerSource != null
                    ? activeTab.readerSource : activeTab.url);
            urlBar.setText(shown);
            urlBar.setSelection(shown.length());
        }

        WebView webView = activeTab.webView;
        boolean canGoBack = webView.canGoBack();
        boolean canGoForward = webView.canGoForward();
        btnBack.setEnabled(canGoBack);
        btnBack.setAlpha(canGoBack ? 1f : 0.32f);
        btnForward.setEnabled(canGoForward);
        btnForward.setAlpha(canGoForward ? 1f : 0.32f);

        if (activeTab.loading) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(activeTab.progress);
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    private String displayUrl(String url) {
        if (url == null || HOME_URL.equals(url) || "about:blank".equals(url)) {
            return "";
        }
        return url;
    }

    private String currentSourceUrl(Tab tab) {
        if (tab == null) {
            return HOME_URL;
        }
        if (!TextUtils.isEmpty(tab.readerSource)) {
            return tab.readerSource;
        }
        return tab.url == null ? HOME_URL : tab.url;
    }

    private void rebuildTabs() {
        tabContainer.removeAllViews();
        for (Tab tab : tabs) {
            tabContainer.addView(buildTabChip(tab));
        }

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(18);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(getColor(R.color.accent));
        plus.setBackground(chipBackground(false));
        plus.setContentDescription(getString(R.string.add_tab));
        plus.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(30)));
        plus.setOnClickListener(v -> {
            createTab(null, true);
            focusUrlBar();
        });
        tabContainer.addView(plus);

        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            View child = tabContainer.getChildAt(i);
            if (child.getTag() == activeTab) {
                final int left = child.getLeft();
                tabStrip.post(() -> tabStrip.smoothScrollTo(left, 0));
                break;
            }
        }
    }

    private View buildTabChip(Tab tab) {
        boolean active = tab == activeTab;
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(5), dp(4), dp(5));
        chip.setBackground(chipBackground(active));
        chip.setTag(tab);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(6));
        chip.setLayoutParams(params);

        TextView title = new TextView(this);
        String text = TextUtils.isEmpty(tab.title) ? getString(R.string.untitled) : tab.title;
        title.setText(text);
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(150));
        title.setTextColor(getColor(active ? R.color.text : R.color.text_muted));
        title.setPadding(0, 0, dp(4), 0);
        chip.addView(title);

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(getColor(active ? R.color.text : R.color.text_muted));
        close.setPadding(dp(6), 0, dp(6), 0);
        close.setContentDescription(getString(R.string.close_tab));
        chip.addView(close);

        chip.setOnClickListener(v -> selectTab(tab));
        close.setOnClickListener(v -> closeTab(tab));
        return chip;
    }

    private GradientDrawable chipBackground(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(getColor(active ? R.color.chip_active : R.color.chip));
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private void showMenu() {
        PopupMenu popup = new PopupMenu(this, findViewById(R.id.btnMenu));
        popup.getMenu().add(0, MENU_NEW_TAB, 1, R.string.menu_new_tab);
        popup.getMenu().add(0, MENU_RELOAD, 2, R.string.menu_reload);
        popup.getMenu().add(0, MENU_SHARE, 3, R.string.menu_share);
        popup.getMenu().add(0, MENU_DESKTOP, 4,
                (desktopMode ? "✓  " : "") + getString(R.string.menu_desktop));
        popup.getMenu().add(0, MENU_JAVASCRIPT, 5,
                (javaScriptEnabled ? "✓  " : "") + getString(R.string.menu_javascript));
        popup.getMenu().add(0, MENU_READER, 6, R.string.menu_reader);
        popup.getMenu().add(0, MENU_BOOKMARK, 7, R.string.menu_bookmark);
        popup.getMenu().add(0, MENU_BOOKMARKS, 8, R.string.menu_bookmarks);
        popup.getMenu().add(0, MENU_DOWNLOADS, 9, R.string.menu_downloads);
        popup.getMenu().add(0, MENU_ENGINE, 10, R.string.menu_engine);
        popup.getMenu().add(0, MENU_ABOUT, 11, R.string.menu_about);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_NEW_TAB:
                    createTab(null, true);
                    focusUrlBar();
                    return true;
                case MENU_RELOAD:
                    if (activeTab != null) {
                        activeTab.webView.reload();
                    }
                    return true;
                case MENU_SHARE:
                    shareCurrentPage();
                    return true;
                case MENU_DESKTOP:
                    toggleDesktopMode();
                    return true;
                case MENU_JAVASCRIPT:
                    toggleJavaScript();
                    return true;
                case MENU_READER:
                    enterReaderMode();
                    return true;
                case MENU_BOOKMARK:
                    addBookmark();
                    return true;
                case MENU_BOOKMARKS:
                    showBookmarks();
                    return true;
                case MENU_DOWNLOADS:
                    startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                    return true;
                case MENU_ENGINE:
                    showEngineInfo();
                    return true;
                case MENU_ABOUT:
                    showAbout();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void navigateFromInput() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) {
            return;
        }
        String url;
        if (looksLikeUrl(input)) {
            url = input.contains("://") ? input : "https://" + input;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(input);
        }
        loadUrlInActive(url);
        urlBar.clearFocus();
        hideKeyboard();
    }

    private boolean looksLikeUrl(String input) {
        if (input.contains(" ") || input.contains("\n")) {
            return false;
        }
        String lower = input.toLowerCase(Locale.US);
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return false;
        }
        if (lower.equals("localhost") || lower.startsWith("localhost:")
                || lower.startsWith("localhost/")
                || lower.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")) {
            return true;
        }
        if (Patterns.WEB_URL.matcher(input).matches()) {
            return true;
        }
        return lower.startsWith("about:") || lower.startsWith("file:");
    }

    private void loadUrlInActive(String url) {
        if (activeTab == null) {
            createTab(url, true);
            return;
        }
        activeTab.webView.loadUrl(url);
    }

    private void focusUrlBar() {
        urlBar.post(() -> {
            urlBar.requestFocus();
            urlBar.selectAll();
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        prefs.edit().putBoolean("desktop_mode", desktopMode).apply();
        for (Tab tab : tabs) {
            tab.webView.getSettings().setUserAgentString(desktopMode ? UA_DESKTOP : null);
        }
        if (activeTab != null) {
            injectDesktopViewport(activeTab.webView);
            activeTab.webView.reload();
        }
        toast(desktopMode ? "Desktop site on" : "Desktop site off");
    }

    private void toggleJavaScript() {
        javaScriptEnabled = !javaScriptEnabled;
        prefs.edit().putBoolean("javascript", javaScriptEnabled).apply();
        for (Tab tab : tabs) {
            tab.webView.getSettings().setJavaScriptEnabled(javaScriptEnabled);
        }
        if (activeTab != null) {
            activeTab.webView.reload();
        }
        toast(javaScriptEnabled ? "JavaScript on" : "JavaScript off");
    }

    private void injectDesktopViewport(WebView webView) {
        if (!desktopMode) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){var h=document.head||document.documentElement;"
                        + "if(!h)return;var m=document.querySelector('meta[name=viewport]');"
                        + "if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m);}"
                        + "m.content='width=1280';})();",
                null);
    }

    private void enterReaderMode() {
        if (activeTab == null) {
            return;
        }
        if (!javaScriptEnabled) {
            toast("Turn on JavaScript to use reader mode");
            return;
        }
        if (activeTab.readerSource != null) {
            toast("Already in reader mode");
            return;
        }
        final Tab tab = activeTab;
        String script = readAsset("reader.js");
        tab.webView.evaluateJavascript(script + "\nReaderMode.extract();", value -> {
            if (value == null || "null".equals(value)) {
                toast(getString(R.string.reader_failed));
                return;
            }
            try {
                Object decoded = new JSONTokener(value).nextValue();
                String json = decoded instanceof String ? (String) decoded : value;
                JSONObject result = new JSONObject(json);
                if (result.has("error")) {
                    toast(getString(R.string.reader_failed));
                    return;
                }
                String html = result.optString("html", "");
                if (html.length() < 50) {
                    toast(getString(R.string.reader_failed));
                    return;
                }
                String title = result.optString("title", tab.title);
                String byline = result.optString("byline", "");
                String base = result.optString("url", tab.url);
                // Reader output is a data: document, so remember the article
                // it came from for bookmarks, sharing and state restore.
                tab.readerSource = base;
                tab.title = title;
                rebuildTabs();
                tab.webView.loadDataWithBaseURL(base, buildReaderHtml(title, byline, html),
                        "text/html", "utf-8", null);
            } catch (Exception e) {
                toast(getString(R.string.reader_failed));
            }
        });
    }

    private String buildReaderHtml(String title, String byline, String body) {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String background = dark ? "#0f0f12" : "#ffffff";
        String foreground = dark ? "#f2f2f5" : "#17171a";
        String muted = dark ? "#a0a0aa" : "#6b6b73";
        String link = dark ? "#8ab4ff" : "#2962ff";
        String codeBackground = dark ? "#1c1c22" : "#f4f4f6";
        String rule = dark ? "#2b2b33" : "#e2e2e7";
        String quote = dark ? "#c9c9d2" : "#4a4a52";

        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" "
                + "content=\"default-src 'none'; img-src http: https: data:; "
                + "style-src 'unsafe-inline'\">"
                + "<title>" + TextUtils.htmlEncode(title) + "</title>"
                + "<style>"
                + "body{margin:0;background:" + background + ";color:" + foreground + ";"
                + "font-family:Georgia,'Times New Roman',serif;}"
                + "article{max-width:720px;margin:0 auto;padding:28px 20px 64px;"
                + "font-size:19px;line-height:1.65;overflow-wrap:break-word;}"
                + "h1{font-size:30px;line-height:1.2;margin:0 0 8px;"
                + "font-family:system-ui,sans-serif;}"
                + ".byline{color:" + muted + ";font-family:system-ui,sans-serif;"
                + "font-size:14px;margin:0 0 28px;}"
                + "img{max-width:100%;height:auto;border-radius:8px;}"
                + "a{color:" + link + ";}"
                + "pre{overflow:auto;background:" + codeBackground + ";padding:14px;"
                + "border-radius:8px;font-size:14px;}"
                + "blockquote{border-left:3px solid " + rule + ";margin-left:0;"
                + "padding-left:18px;color:" + quote + ";}"
                + "figure{margin:24px 0;}figcaption{font-size:13px;color:" + muted + ";}"
                + "table{border-collapse:collapse;width:100%;font-size:15px;}"
                + "td,th{border:1px solid " + rule + ";padding:6px;}"
                + "</style></head><body><article><h1>" + TextUtils.htmlEncode(title)
                + "</h1>"
                + (TextUtils.isEmpty(byline)
                        ? ""
                        : "<p class=\"byline\">" + TextUtils.htmlEncode(byline) + "</p>")
                + body + "</article></body></html>";
    }

    private void addBookmark() {
        if (activeTab == null) {
            return;
        }
        String source = currentSourceUrl(activeTab);
        if (TextUtils.isEmpty(source) || "about:blank".equals(source)
                || source.startsWith("file:") || source.startsWith("about:")) {
            toast("Nothing to bookmark on this page");
            return;
        }
        Set<String> bookmarks = new HashSet<>(
                prefs.getStringSet("bookmarks", Collections.<String>emptySet()));
        for (String bookmark : bookmarks) {
            if (bookmark.endsWith("\n" + source)) {
                toast(getString(R.string.bookmark_exists));
                return;
            }
        }
        String title = TextUtils.isEmpty(activeTab.title)
                ? source : activeTab.title.replace("\n", " ");
        bookmarks.add(title + "\n" + source);
        prefs.edit().putStringSet("bookmarks", bookmarks).apply();
        toast(getString(R.string.bookmark_saved));
    }

    private void showBookmarks() {
        Set<String> stored = prefs.getStringSet("bookmarks", Collections.<String>emptySet());
        if (stored.isEmpty()) {
            toast(getString(R.string.no_bookmarks));
            return;
        }
        final List<String> entries = new ArrayList<>(stored);
        Collections.sort(entries, String.CASE_INSENSITIVE_ORDER);
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String[] parts = entries.get(i).split("\n", 2);
            labels[i] = parts[0];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setItems(labels, (dialog, which) -> {
                    String[] parts = entries.get(which).split("\n", 2);
                    if (parts.length == 2) {
                        loadUrlInActive(parts[1]);
                    }
                })
                .setNegativeButton(R.string.remove_all, (dialog, which) ->
                        prefs.edit().remove("bookmarks").apply())
                .setPositiveButton(R.string.cancel, null)
                .show();
    }

    private void shareCurrentPage() {
        if (activeTab == null) {
            return;
        }
        String source = currentSourceUrl(activeTab);
        if (TextUtils.isEmpty(source) || source.startsWith("about:")
                || source.startsWith("file:")) {
            toast("Nothing to share on this page");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, source);
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)));
    }

    private void showEngineInfo() {
        String core = NativeCore.isAvailable()
                ? "Lightpanda native core: loaded"
                : "Lightpanda native core: not bundled in this build";
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_engine)
                .setMessage("Rendering and touch input: Android WebView (Chromium)\n"
                        + core + "\n\n"
                        + "Panda Mobile is an independent Android shell. Lightpanda is a "
                        + "headless engine with no graphical renderer and no native Android "
                        + "target, so this build uses the platform WebView for pixels and "
                        + "input. The JNI contract in engine/NativeCore.java is ready for a "
                        + "future Lightpanda core.\n\n"
                        + "Upstream: github.com/lightpanda-io/browser (AGPL-3.0)")
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showAbout() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // Keep the fallback.
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setMessage("Panda Mobile " + version + "\n\n"
                        + "An independent, source-available Android browser shell built for "
                        + "the Lightpanda port study.\n\n"
                        + "This build does not include Lightpanda source code. If a "
                        + "Lightpanda core is linked in a future build, the combined work is "
                        + "subject to the GNU AGPL-3.0 and its source-disclosure terms.")
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void requestDownload(String url, String userAgent, String contentDisposition,
                                 String mimeType) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            toast(getString(R.string.download_unsupported));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadContentDisposition = contentDisposition;
            pendingDownloadMimeType = mimeType;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_DOWNLOAD_PERMISSION);
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition,
                                 String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (!TextUtils.isEmpty(mimeType)) {
                request.setMimeType(mimeType);
            }
            if (!TextUtils.isEmpty(userAgent)) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            request.setTitle(fileName);
            request.setDescription(getString(R.string.app_name));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) {
                toast(getString(R.string.download_failed));
                return;
            }
            manager.enqueue(request);
            toast(getString(R.string.download_started));
        } catch (Exception e) {
            toast(getString(R.string.download_failed));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_DOWNLOAD_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingDownloadUrl != null) {
                enqueueDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                        pendingDownloadContentDisposition, pendingDownloadMimeType);
            } else {
                toast(getString(R.string.download_failed));
            }
            pendingDownloadUrl = null;
            pendingDownloadUserAgent = null;
            pendingDownloadContentDisposition = null;
            pendingDownloadMimeType = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String url = data.toString();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            loadUrlInActive(url);
        }
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                || scheme.equals("about") || scheme.equals("data")
                || scheme.equals("javascript")) {
            return false;
        }

        if (scheme.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        loadUrlInActive(fallback);
                    } else {
                        toast("No app can open this link");
                    }
                }
            } catch (Exception e) {
                toast("No app can open this link");
            }
            return true;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("No app can open " + scheme + " links");
        }
        return true;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> urls = new ArrayList<>();
        for (Tab tab : tabs) {
            urls.add(currentSourceUrl(tab));
        }
        outState.putStringArrayList("tab_urls", urls);
        outState.putInt("active_tab", tabs.indexOf(activeTab));
    }

    private void handleBack() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (activeTab != null && activeTab.webView.canGoBack()) {
            activeTab.webView.goBack();
            return;
        }
        if (tabs.size() > 1) {
            closeTab(activeTab);
            return;
        }
        finish();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onPause() {
        if (activeTab != null) {
            activeTab.webView.onPause();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeTab != null) {
            activeTab.webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab tab : tabs) {
            try {
                tab.webView.destroy();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
        tabs.clear();
        activeTab = null;
        super.onDestroy();
    }

    private void showCustomView(View view) {
        if (customView != null) {
            return;
        }
        customView = view;
        content.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        topBar.setVisibility(View.GONE);
        tabStrip.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        content.removeView(customView);
        customView = null;
        topBar.setVisibility(View.VISIBLE);
        tabStrip.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private Tab findTab(WebView webView) {
        for (Tab tab : tabs) {
            if (tab.webView == webView) {
                return tab;
            }
        }
        return null;
    }

    private class Tab {
        WebView webView;
        String title = getString(R.string.new_tab);
        String url;
        // Original article URL while the tab shows extracted reader output.
        String readerSource;
        boolean loading;
        int progress;
    }

    private class BrowserClient extends WebViewClient {
        private final Tab tab;

        BrowserClient(Tab tab) {
            this.tab = tab;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            tab.loading = true;
            tab.progress = 0;
            tab.url = url;
            // Leaving the extracted article for a real navigation drops the
            // reader source; the reader document itself keeps it.
            if (tab.readerSource != null && url != null
                    && (url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("file:"))
                    && !tab.readerSource.equals(url)) {
                tab.readerSource = null;
            }
            if (tab == activeTab) {
                updateUi();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.loading = false;
            String current = view.getUrl();
            if (current != null) {
                tab.url = current;
            }
            String title = view.getTitle();
            if (!TextUtils.isEmpty(title)) {
                tab.title = title;
            }
            if (tab == activeTab) {
                updateUi();
            }
            rebuildTabs();
            injectDesktopViewport(view);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            tab.url = url;
            if (tab == activeTab) {
                updateUi();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                tab.loading = false;
                if (tab == activeTab) {
                    updateUi();
                }
            }
        }
    }

    private class BrowserChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            Tab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.progress = newProgress;
            tab.loading = newProgress < 100;
            if (tab == activeTab) {
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(newProgress);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            Tab tab = findTab(view);
            if (tab != null && !TextUtils.isEmpty(title)) {
                tab.title = title;
                rebuildTabs();
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                return false;
            }
            // The transport must receive a WebView before it is attached to the
            // content container, so build the shell first and select it after.
            Tab tab = createTabShell();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(tab.webView);
            resultMsg.sendToTarget();
            selectTab(tab);
            rebuildTabs();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams fileChooserParams) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Camera, microphone and other privileged web APIs are denied until
            // a real permission UX is implemented.
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customViewCallback = callback;
            showCustomView(view);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }
}
