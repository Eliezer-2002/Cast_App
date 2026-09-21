package com.tamilsongbook;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JavaScript bridge for the low-latency DLNA path. Registered under its OWN name
 * ("LowLatencyCast") next to — never inside — MainActivity.JSBridge ("Android"), so the
 * mirror-casting bridge is untouched.
 *
 * Threading: @JavascriptInterface methods run on the WebView's private "JavaBridge" thread,
 * NOT the UI thread. They only enqueue work on LowLatencyCastService's IO thread and return
 * at once. Results flow back the other way: the service's IO thread → webView.post() (the
 * one thread-safe View entry point) → evaluateJavascript() on the UI thread → the page's
 * window.onDirectStreamStatus({state, message, device}).
 */
public final class LowLatencyCastBridge implements LowLatencyCastService.Listener {

    private final WebView webView;
    private final LowLatencyCastService service;
    private volatile boolean released;

    public LowLatencyCastBridge(Context context, WebView webView) {
        this.webView = webView;
        this.service = new LowLatencyCastService(context, this);
    }

    // ── JS → Java ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: window.LowLatencyCast.startDirectObsStream("http://192.168.1.20:8080/live.ts")
     * @return true if the request was accepted; the outcome arrives via onDirectStreamStatus().
     */
    @JavascriptInterface
    public boolean startDirectObsStream(String url) {
        return service.executeDirectStream(url);
    }

    /** JS: window.LowLatencyCast.stopDirectObsStream() */
    @JavascriptInterface
    public void stopDirectObsStream() {
        service.stopStream();
    }

    /** JS: window.LowLatencyCast.discoverDirectRenderers() — re-scan; result via onDirectStreamStatus. */
    @JavascriptInterface
    public void discoverDirectRenderers() {
        service.discoverAsync();
    }

    /** JS: JSON.parse(window.LowLatencyCast.getDirectRenderers()) → [{id,name,manufacturer,model,host}] */
    @JavascriptInterface
    public String getDirectRenderers() {
        JSONArray arr = new JSONArray();
        try {
            for (LowLatencyCastService.RendererDevice d : service.getKnownRenderers()) {
                arr.put(new JSONObject()
                        .put("id", d.usn).put("name", d.friendlyName)
                        .put("manufacturer", d.manufacturer).put("model", d.modelName)
                        .put("host", d.host));
            }
        } catch (JSONException ignored) { }
        return arr.toString();
    }

    /** JS: window.LowLatencyCast.selectDirectRenderer("AnyCast") — name fragment or id from the list above. */
    @JavascriptInterface
    public void selectDirectRenderer(String nameOrId) {
        service.selectRenderer(nameOrId);
    }

    // ── Java → JS ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onStatus(String state, String message, String deviceName) {
        if (released) return;
        final String js;
        try {
            js = "if(typeof onDirectStreamStatus==='function')onDirectStreamStatus("
                    + new JSONObject().put("state", state)
                                      .put("message", message == null ? "" : message)
                                      .put("device", deviceName == null ? JSONObject.NULL : deviceName)
                    + ")";
        } catch (JSONException e) { return; }
        webView.post(new Runnable() { public void run() {
            if (!released) webView.evaluateJavascript(js, null);
        }});
        // Same status also goes to the cast display's presenter.html, which hides its HTML
        // layer while the TV renders the direct stream. No-op if no presentation is showing.
        MainActivity.JSBridge presenter = MainActivity.jsBridge;
        if (presenter != null) presenter.forwardToPresenter(js);
    }

    /** Call from ControllerActivity.onDestroy(), before webView.destroy(). */
    public void release() {
        released = true;
        service.release();
    }
}
