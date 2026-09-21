package com.tamilsongbook;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.net.Uri;
import android.webkit.ValueCallback;

public class ControllerActivity extends Activity {

    public static WebView ccwWebView;
    private static final int CAMERA_PERMISSION_REQUEST = 2001;

    // No androidx dependency in this project (plain Activity, empty dependencies{}
    // block) — do the SDK_INT>=23 runtime-permission dance with the plain
    // framework APIs instead of ActivityCompat/ContextCompat.
    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // Low-latency DLNA/UPnP direct-stream path — independent of MainActivity.jsBridge
    // ("Android") above; registered under its own bridge name so it can never collide
    // with, or be mistaken for, the mirror-casting bridge.
    private LowLatencyCastBridge lowLatencyBridge;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.FILE_CHOOSER_REQUEST) {
            if (MainActivity.fileChooser != null) {
                MainActivity.fileChooser.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                MainActivity.fileChooser = null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ccwWebView = new WebView(this);
        setupWebView(ccwWebView);
        ccwWebView.addJavascriptInterface(MainActivity.jsBridge, "Android");
        ccwWebView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void closeController() {
                runOnUiThread(new Runnable() { public void run() { finish(); } });
            }
        }, "AndroidCtrl");
        lowLatencyBridge = new LowLatencyCastBridge(getApplicationContext(), ccwWebView);
        ccwWebView.addJavascriptInterface(lowLatencyBridge, "LowLatencyCast");
        ccwWebView.loadUrl("file:///android_asset/controller.html");

        // Ask up front so a runtime prompt isn't fired mid-getUserMedia() from
        // inside onPermissionRequest (which can't itself show UI). If the user
        // denies, the Sources tab's camera flow just reports "unavailable" —
        // see initCameraDeviceList()'s catch handler in controller.html.
        if (Build.VERSION.SDK_INT >= 23 && !hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }

        FrameLayout fl = new FrameLayout(this);
        fl.addView(ccwWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(fl);

        // Notify JS that controller is shown
        ccwWebView.post(new Runnable() { public void run() {
            ccwWebView.evaluateJavascript("if(typeof onControllerShown==='function')onControllerShown()", null);
        }});

        // Apply current theme
        String theme = getIntent().getStringExtra("theme");
        if (theme != null) {
            final String t = theme;
            ccwWebView.post(new Runnable() { public void run() {
                ccwWebView.evaluateJavascript("if(typeof receiveTheme==='function')receiveTheme('" + t + "')", null);
            }});
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ccwWebView != null) { ccwWebView.resumeTimers(); ccwWebView.onResume(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ccwWebView != null) { ccwWebView.onPause(); ccwWebView.pauseTimers(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lowLatencyBridge != null) { lowLatencyBridge.release(); lowLatencyBridge = null; }
        if (ccwWebView != null) { ccwWebView.destroy(); ccwWebView = null; }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void setupWebView(WebView wv) {
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

            // Grants getUserMedia() video-only (no audio source per spec) so the
            // Sources tab's USB/capture camera flow can actually open a device —
            // only when the app itself already holds the runtime CAMERA permission.
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() { public void run() {
                    if (!hasCameraPermission()) { request.deny(); return; }
                    java.util.List<String> granted = new java.util.ArrayList<>();
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) granted.add(res);
                    }
                    if (granted.isEmpty()) request.deny();
                    else request.grant(granted.toArray(new String[0]));
                }});
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.fileChooser != null) MainActivity.fileChooser.onReceiveValue(null);
                MainActivity.fileChooser = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, MainActivity.FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    MainActivity.fileChooser = null;
                    return false;
                }
                return true;
            }
        });
    }
}
