package com.tamilsongbook;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * ARCHITECTURE — works for ALL cast methods (Miracast, HDMI, Chromecast, etc.)
 *
 *   Activity window      → PHONE screen  — app UI (index.html)
 *   ControllerActivity   → PHONE screen  — controller (controller.html)
 *   CastPresentation     → CAST display  — presenter content (presenter.html)
 */
public class MainActivity extends Activity {

    private WebView appWebView;
    static JSBridge jsBridge;  // static so ControllerActivity can access it
    static int presenterW = 1920, presenterH = 1080;
    static String currentTheme = "dark";
    static ValueCallback<Uri[]> fileChooser;
    static final int FILE_CHOOSER_REQUEST = 1001;
    static final int STORAGE_PERMISSION_REQUEST = 1002;

    private CastPresentation castPresentation;
    private DisplayManager   displayManager;


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooser != null) {
                fileChooser.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileChooser = null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive();

        android.webkit.WebView.setWebContentsDebuggingEnabled(true);

        appWebView = new WebView(this);
        setupWebView(appWebView);
        setContentView(appWebView);

        jsBridge = new JSBridge();
        appWebView.addJavascriptInterface(jsBridge, "Android");
        appWebView.loadUrl("file:///android_asset/index.html");

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, null);
        connectToDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appWebView.resumeTimers(); appWebView.onResume();
        setImmersive();
        connectToDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        appWebView.onPause(); appWebView.pauseTimers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        displayManager.unregisterDisplayListener(displayListener);
        dismissPresentation();
        appWebView.destroy();
    }

    @Override
    public void onBackPressed() {
        // nothing — back is disabled in main app
    }

    // ── Display management ────────────────────────────────────────────────────

    private void connectToDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length > 0) showPresentation(displays[0]);
        else dismissPresentation();
    }

    private void showPresentation(Display display) {
        if (castPresentation != null && castPresentation.isShowing()) return;
        dismissPresentation();
        castPresentation = new CastPresentation(this, display);
        castPresentation.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                castPresentation = null;
                notifyCastStatus(false);
            }
        });
        try {
            castPresentation.show();
            notifyCastStatus(true);
        } catch (WindowManager.InvalidDisplayException e) {
            castPresentation = null;
        }
    }

    private void dismissPresentation() {
        if (castPresentation != null) { castPresentation.dismiss(); castPresentation = null; }
        notifyCastStatus(false);
    }

    private void notifyCastStatus(final boolean connected) {
        runOnUiThread(new Runnable() { public void run() {
            WebView cw = ControllerActivity.ccwWebView;
            if (cw != null)
                cw.evaluateJavascript(
                    "if(typeof updateCastStatus==='function')updateCastStatus(" + connected + ")", null);
        }});
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {
            Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            for (Display d : displays) if (d.getDisplayId() == id) { showPresentation(d); return; }
        }
        @Override public void onDisplayRemoved(int id) { dismissPresentation(); }
        @Override public void onDisplayChanged(int id) {}
    };

    // ── Presentation (secondary display) ──────────────────────────────────────

    public class CastPresentation extends Presentation {
        private WebView presenterWebView;

        public CastPresentation(Context ctx, Display display) { super(ctx, display); }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            presenterWebView = new WebView(getContext());
            setupWebView(presenterWebView);
            presenterWebView.addJavascriptInterface(jsBridge, "Android");
            presenterWebView.loadUrl("file:///android_asset/presenter.html");
            setContentView(presenterWebView);
        }

        public void runJS(final String js) {
            if (presenterWebView != null)
                presenterWebView.post(new Runnable() { public void run() {
                    presenterWebView.evaluateJavascript(js, null);
                }});
        }

        @Override
        public void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (presenterWebView != null) { presenterWebView.destroy(); presenterWebView = null; }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) { return false; }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooser != null) fileChooser.onReceiveValue(null);
                fileChooser = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileChooser = null;
                    return false;
                }
                return true;
            }
        });
    }

    // ── JS Bridge ─────────────────────────────────────────────────────────────

    public class JSBridge {

        @JavascriptInterface
        public void showStanza(final String title, final String text,
                               final String bg, final String titleColor,
                               final String lyricColor, final int fontSize,
                               final String hint) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("showContent('song','" + esc(title) + "','" +
                        esc(text) + "','" + esc(bg) + "','" + esc(titleColor) + "','" +
                        esc(lyricColor) + "'," + fontSize + ",'" + esc(hint) + "')");
            }});
        }

        @JavascriptInterface
        public void showVerse(final String ref, final String text,
                              final String bg, final String refColor,
                              final String textColor, final int fontSize) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("showContent('verse','" + esc(ref) + "','" +
                        esc(text) + "','" + esc(bg) + "','" + esc(refColor) + "','" +
                        esc(textColor) + "'," + fontSize + ")");
            }});
        }

        @JavascriptInterface
        public void showSplit(final String leftRef, final String leftText,
                              final String rightRef, final String rightText,
                              final String bg, final String textColor, final int fontSize) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("showSplit('" + esc(leftRef) + "','" +
                        esc(leftText) + "','" + esc(rightRef) + "','" + esc(rightText) + "','" +
                        esc(bg) + "','#9b59b6','" + esc(textColor) + "'," + fontSize + ")");
            }});
        }

        @JavascriptInterface
        public void showGap(final String type, final String text,
                            final String bg, final String textColor, final int fontSize) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("showGap('" + esc(type) + "','" +
                        esc(text) + "','" + esc(bg) + "','" + esc(textColor) + "'," + fontSize + ")");
            }});
        }

        @JavascriptInterface
        public void showPdf(final String dataUrl) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("showPdf('" + esc(dataUrl) + "')");
            }});
        }

        @JavascriptInterface
        public void clearPresenter() {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null) castPresentation.runJS("clearDisplay()");
            }});
        }

        @JavascriptInterface
        public void syncTheme(final String themeName) {
            currentTheme = themeName;
            runOnUiThread(new Runnable() { public void run() {
                WebView cw = ControllerActivity.ccwWebView;
                if (cw != null)
                    cw.evaluateJavascript("if(typeof receiveTheme==='function')receiveTheme('" + esc(themeName) + "')", null);
            }});
        }

        @JavascriptInterface
        public void openController() {
            runOnUiThread(new Runnable() { public void run() {
                Intent intent = new Intent(MainActivity.this, ControllerActivity.class);
                intent.putExtra("theme", currentTheme);
                startActivity(intent);
            }});
        }

        @JavascriptInterface
        public void closeController() {
            // ControllerActivity handles its own close via back press or JS call
        }

        @JavascriptInterface
        public void syncData(final String jsonData) {
            runOnUiThread(new Runnable() { public void run() {
                WebView cw = ControllerActivity.ccwWebView;
                if (cw != null)
                    cw.evaluateJavascript("receiveDataSync('" + esc(jsonData) + "')", null);
            }});
        }

        @JavascriptInterface
        public void syncDataToPresenter(final String jsonData) {
            runOnUiThread(new Runnable() { public void run() {
                appWebView.evaluateJavascript("receiveDataSync('" + esc(jsonData) + "')", null);
            }});
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(new Runnable() { public void run() {
                if ("landscape".equals(mode))
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                else if ("portrait".equals(mode))
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                else
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }});
        }

        @JavascriptInterface
        public void updateFontSize(final int size) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("updateFontSize(" + size + ")");
            }});
        }

        // Live heading/reference font size (Song heading or Bible reference — both
        // share presenter.html's #title element).
        @JavascriptInterface
        public void updateTitleFontSize(final int size) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("updateTitleFontSize(" + size + ")");
            }});
        }

        // Live heading/reference font width (letter-spacing, px).
        @JavascriptInterface
        public void updateTitleFontWidth(final int px) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("updateTitleFontWidth(" + px + ")");
            }});
        }

        // Live body font width (letter-spacing, px) — Song lyrics / Bible verse.
        @JavascriptInterface
        public void updateFontWidth(final int px) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("updateFontWidth(" + px + ")");
            }});
        }

        // Live heading/reference layout width, as a percentage of the screen.
        @JavascriptInterface
        public void updateTitleWidth(final int pct) {
            runOnUiThread(new Runnable() { public void run() {
                if (castPresentation != null)
                    castPresentation.runJS("updateTitleWidth(" + pct + ")");
            }});
        }

        @JavascriptInterface
        public boolean isCastConnected() {
            return castPresentation != null && castPresentation.isShowing();
        }

        @JavascriptInterface
        public boolean isPresenterOpen() {
            return castPresentation != null && castPresentation.isShowing();
        }

        @JavascriptInterface
        public boolean isMirroring() {
            Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            return displays.length > 0;
        }

        @JavascriptInterface
        public void reportViewport(final int w, final int h) {
            presenterW = w; presenterH = h;
            runOnUiThread(new Runnable() { public void run() {
                WebView cw = ControllerActivity.ccwWebView;
                if (cw != null)
                    cw.evaluateJavascript(
                        "if(typeof onPresenterViewport==='function')onPresenterViewport(" + w + "," + h + ")", null);
            }});
        }

        @JavascriptInterface
        public String getPresenterViewport() {
            return "{\"w\":" + presenterW + ",\"h\":" + presenterH + "}";
        }

        // Returns the app's real installed label so Settings never shows a hardcoded name.
        @JavascriptInterface
        public String getAppDisplayName() {
            try {
                return getPackageManager().getApplicationLabel(getApplicationInfo()).toString();
            } catch (Exception e) {
                return "App";
            }
        }

        // Saves one exported Song Book JSON into Downloads/<appName>/<subFolder>/<fileName>
        // and returns {"ok":true} or {"ok":false,"reason":"..."} reflecting what actually
        // happened — the JS side only ever shows success once this confirms a real write.
        @JavascriptInterface
        public String saveExportFile(final String appName, final String subFolder,
                                      final String fileName, final String jsonContent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/" + appName + "/" + subFolder);
                    Uri item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (item == null) return jsonResult(false, "Could not create the file");
                    OutputStream out = getContentResolver().openOutputStream(item);
                    if (out == null) return jsonResult(false, "Could not open the file for writing");
                    out.write(jsonContent.getBytes("UTF-8"));
                    out.close();
                    return jsonResult(true, null);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            STORAGE_PERMISSION_REQUEST);
                    return jsonResult(false, "Storage permission needed - please grant it and try again");
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        appName + File.separator + subFolder);
                if (!dir.exists() && !dir.mkdirs()) return jsonResult(false, "Could not create the export folder");
                FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
                fos.write(jsonContent.getBytes("UTF-8"));
                fos.close();
                return jsonResult(true, null);
            } catch (Exception e) {
                return jsonResult(false, "Export failed: " + e.getMessage());
            }
        }

        private String jsonResult(boolean ok, String reason) {
            try {
                JSONObject o = new JSONObject().put("ok", ok);
                if (reason != null) o.put("reason", reason);
                return o.toString();
            } catch (Exception e) {
                return ok ? "{\"ok\":true}" : "{\"ok\":false,\"reason\":\"Export failed\"}";
            }
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }
}

