package com.tamilsongbook;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LOW-LATENCY DLNA/UPnP PUSH PATH — completely separate from the Presentation-API mirror
 * casting in MainActivity. Nothing here touches DisplayManager, CastPresentation or JSBridge.
 *
 * <pre>
 *   JS button ──► LowLatencyCastBridge ──► LowLatencyCastService
 *                                            │ 1. SSDP M-SEARCH (UDP multicast)  → who is a MediaRenderer?
 *                                            │ 2. HTTP GET  device description   → AVTransport controlURL
 *                                            │ 3. SOAP SetAVTransportURI + Play  → TV pulls the OBS stream itself
 * </pre>
 *
 * The phone only sends a few small control packets. The video never passes through the
 * phone: the renderer (the AnyCast dongle) opens the OBS URL directly.
 *
 * ── THREADING MODEL ───────────────────────────────────────────────────────────────────────
 * Every socket / HTTP operation runs on ONE dedicated daemon thread ("LowLatencyCast-IO",
 * a single-thread executor). Consequences:
 *   • No network I/O ever happens on the UI thread or on the WebView's JavaBridge thread.
 *   • Discovery and SOAP commands are strictly serialised (FIFO), so a Stop can never
 *     overtake the Play it was issued after, and two SetAVTransportURI calls never interleave.
 *   • All mutable state that is touched only by tasks lives on that thread and needs no lock.
 *   • The few fields read from OTHER threads (knownRenderers, selected*, activeSocket,
 *     released) are volatile / atomic and always replaced wholesale with immutable values —
 *     readers see either the old snapshot or the new one, never a half-built one.
 *
 * ── SOCKET LIFECYCLE (one discovery pass, see runDiscovery()) ─────────────────────────────
 *   1. Acquire WifiManager.MulticastLock   (many Android Wi-Fi drivers drop inbound
 *                                           multicast/unsolicited UDP while the screen is on
 *                                           unless an app holds this lock)
 *   2. Open MulticastSocket on an EPHEMERAL port (never 1900: that port is usually held by
 *      another SSDP stack and binding it fails with EADDRINUSE). SSDP search replies are
 *      unicast back to the port the M-SEARCH came from, so no group join is needed.
 *   3. Publish the socket in `activeSocket` so release() can close() it from any thread —
 *      closing a socket is the only way to interrupt a blocking receive().
 *   4. Send the M-SEARCH (3× spaced out, UDP is lossy) out of every usable IPv4 interface,
 *      then receive with a short SO_TIMEOUT until the discovery window ends.
 *   5. finally { close socket; release MulticastLock }  — runs on success, timeout, error
 *      and release(), so neither the socket nor the lock can leak (a leaked MulticastLock
 *      keeps the Wi-Fi radio in a higher-power state).
 * The HTTP/SOAP steps use short-lived HttpURLConnections (Connection: close) that are
 * disconnect()ed in finally blocks; nothing long-lived is kept open between casts.
 */
public final class LowLatencyCastService {

    // ── Public status vocabulary (forwarded to JS by LowLatencyCastBridge) ────────────────
    public static final String STATE_DISCOVERING = "discovering";
    public static final String STATE_FOUND       = "found";
    public static final String STATE_CONNECTING  = "connecting";
    public static final String STATE_STREAMING   = "streaming";
    public static final String STATE_STOPPED     = "stopped";
    public static final String STATE_ERROR       = "error";

    /** Called from the IO thread. Implementations must not block. */
    public interface Listener {
        void onStatus(String state, String message, String deviceName);
    }

    /** Immutable description of one MediaRenderer — safe to hand across threads. */
    public static final class RendererDevice {
        public final String usn;          // unique id from the SSDP reply
        public final String friendlyName;
        public final String manufacturer;
        public final String modelName;
        public final String host;         // IPv4 address the reply came from
        public final String controlUrl;   // absolute AVTransport control URL
        public final String serviceType;  // exact AVTransport type the device advertises (…:1 / …:2)

        RendererDevice(String usn, String friendlyName, String manufacturer, String modelName,
                       String host, String controlUrl, String serviceType) {
            this.usn = usn; this.friendlyName = friendlyName; this.manufacturer = manufacturer;
            this.modelName = modelName; this.host = host; this.controlUrl = controlUrl;
            this.serviceType = serviceType;
        }
    }

    // ── SSDP / UPnP constants ─────────────────────────────────────────────────────────────
    private static final String SSDP_GROUP = "239.255.255.250";
    private static final int    SSDP_PORT  = 1900;
    private static final String ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVTRANSPORT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:";

    private static final int  SSDP_MX = 2;                 // renderers randomise replies over 0..MX seconds
    private static final long DISCOVERY_WINDOW_MS = 3000;  // MX + slack
    private static final long SEARCH_RESEND_MS = 700;
    private static final int  SEARCH_SENDS = 3;

    private static final int HTTP_CONNECT_TIMEOUT_MS = 3000;
    private static final int HTTP_READ_TIMEOUT_MS = 5000;
    private static final int MAX_HTTP_BODY_BYTES = 256 * 1024;

    /** Name fragments (lower-case) preferred when several renderers answer. Add your dongle's own name here. */
    private static final String[] PREFERRED_NAME_HINTS = { "anycast", "ezcast" };

    private static final String[] ALLOWED_STREAM_SCHEMES =
            { "http", "https", "rtsp", "rtmp", "srt", "udp", "rtp" };

    // ── State ─────────────────────────────────────────────────────────────────────────────
    private final Context appContext;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LowLatencyCast-IO");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean released = new AtomicBoolean(false);
    private volatile MulticastSocket activeSocket;                 // closed by release() to unblock receive()
    private volatile List<RendererDevice> knownRenderers = Collections.emptyList(); // immutable snapshots
    private volatile RendererDevice selected;                      // last renderer we successfully used / were told to use
    private volatile String preferredName;                         // from selectRenderer()

    public LowLatencyCastService(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();  // never hold an Activity
        this.listener = listener;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Public, thread-safe API — every method returns immediately; work happens on `io`.
    // ═════════════════════════════════════════════════════════════════════════════════════

    /** Re-scans the LAN for MediaRenderers. Result arrives via Listener (STATE_FOUND / STATE_ERROR). */
    public void discoverAsync() {
        submit(() -> {
            try {
                List<RendererDevice> found = runDiscovery();
                if (found.isEmpty()) emit(STATE_ERROR, noRendererMessage(), null);
                else emit(STATE_FOUND, found.size() + " renderer(s) found", describe(found));
            } catch (Exception e) {
                emit(STATE_ERROR, "Discovery failed: " + e.getMessage(), null);
            }
        });
    }

    /**
     * Pushes {@code obsStreamUrl} to the renderer: SetAVTransportURI immediately followed by Play.
     * Thread-safe; callable from the JS bridge thread. Discovers a renderer first if none is known.
     *
     * @return false if the URL was rejected up-front (an STATE_ERROR is also emitted) or the
     *         service was released; true if the request was queued. The real outcome is
     *         reported via Listener (STATE_STREAMING or STATE_ERROR).
     */
    public boolean executeDirectStream(final String obsStreamUrl) {
        final String url = obsStreamUrl == null ? "" : obsStreamUrl.trim();
        String problem = validateStreamUrl(url);
        if (problem != null) { emit(STATE_ERROR, problem, null); return false; }
        return submit(() -> streamTask(url));
    }

    /** Sends UPnP Stop to the renderer used last. Safe to call when nothing is playing. */
    public void stopStream() {
        submit(() -> {
            RendererDevice dev = selected;
            if (dev == null) { emit(STATE_STOPPED, "Nothing to stop", null); return; }
            try {
                soap(dev, "Stop", "<InstanceID>0</InstanceID>");
                emit(STATE_STOPPED, "Stopped", dev.friendlyName);
            } catch (Exception e) {
                emit(STATE_ERROR, "Stop failed: " + e.getMessage(), dev.friendlyName);
            }
        });
    }

    /** Prefer the renderer whose friendly name / model / USN contains {@code nameOrUsn} (case-insensitive). */
    public void selectRenderer(String nameOrUsn) {
        String v = nameOrUsn == null ? "" : nameOrUsn.trim();
        preferredName = v.isEmpty() ? null : v;
        selected = null;   // force re-pick on the next cast
    }

    /** Immutable snapshot of the renderers from the most recent discovery pass. */
    public List<RendererDevice> getKnownRenderers() { return knownRenderers; }

    /**
     * Idempotent shutdown: refuses new work, cancels queued tasks and closes the discovery
     * socket so a blocked receive() returns immediately. Call from Activity.onDestroy().
     * A stream already playing on the TV is NOT stopped — the renderer owns playback.
     */
    public void release() {
        if (!released.compareAndSet(false, true)) return;
        MulticastSocket s = activeSocket;
        if (s != null) s.close();
        io.shutdownNow();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Tasks (all run on the single IO thread)
    // ═════════════════════════════════════════════════════════════════════════════════════

    private void streamTask(String url) {
        try {
            RendererDevice dev = resolveTarget();
            if (dev == null) return;                       // resolveTarget already emitted the reason
            try {
                pushAndPlay(dev, url);
            } catch (IOException staleConnection) {
                // Cached device may have rebooted / changed IP: rediscover once and retry.
                selected = null;
                dev = resolveTarget();
                if (dev == null) return;
                pushAndPlay(dev, url);
            }
            selected = dev;
            emit(STATE_STREAMING, "Streaming to " + dev.friendlyName, dev.friendlyName);
        } catch (UpnpFault f) {
            emit(STATE_ERROR, "Renderer rejected the stream: " + f.getMessage(), null);
        } catch (Exception e) {
            emit(STATE_ERROR, "Could not start stream: " + e.getMessage(), null);
        }
    }

    /** Returns the renderer to use, running discovery if needed; emits STATE_ERROR and returns null if none. */
    private RendererDevice resolveTarget() throws IOException {
        RendererDevice dev = selected;
        if (dev != null) return dev;

        List<RendererDevice> list = knownRenderers;
        if (list.isEmpty() || preferredName != null && pick(list) == null) list = runDiscovery();

        List<RendererDevice> candidates = pickAll(list);
        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.isEmpty()) { emit(STATE_ERROR, noRendererMessage(), null); return null; }
        // Several equally good candidates: refuse to guess — a wrong guess would light up someone else's TV.
        emit(STATE_ERROR, "Several renderers found (" + describe(candidates)
                + "). Choose one with selectDirectRenderer().", null);
        return null;
    }

    private void pushAndPlay(RendererDevice dev, String url) throws IOException, UpnpFault {
        emit(STATE_CONNECTING, "Sending stream to " + dev.friendlyName, dev.friendlyName);

        String meta = buildDidl(url);
        try {
            soap(dev, "SetAVTransportURI", setUriArgs(url, meta));
        } catch (UpnpFault f) {
            // Some renderers reject DIDL-Lite they don't like (faults 402/714/…): retry bare.
            if (meta.isEmpty()) throw f;
            soap(dev, "SetAVTransportURI", setUriArgs(url, ""));
        }

        // Play straight away. A renderer that is still TRANSITIONING answers with a fault,
        // so retry briefly instead of failing a cast that would succeed 150 ms later.
        UpnpFault last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                soap(dev, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                return;
            } catch (UpnpFault f) {
                last = f;
                try { Thread.sleep(150); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw f; }
            }
        }
        throw last;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Step 1 — SSDP discovery over UDP multicast
    // ═════════════════════════════════════════════════════════════════════════════════════

    private List<RendererDevice> runDiscovery() throws IOException {
        emit(STATE_DISCOVERING, "Searching for DLNA renderers…", null);

        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        MulticastSocket socket = null;
        Map<String, SsdpReply> replies = new LinkedHashMap<>();
        try {
            // (1) Multicast lock — see lifecycle notes in the class header.
            if (wifi != null) {
                lock = wifi.createMulticastLock("LowLatencyCast-SSDP");
                lock.setReferenceCounted(false);   // one acquire()/release() pair, no counting surprises
                lock.acquire();
            }

            // (2) Ephemeral-port socket; replies are unicast to this port.
            socket = new MulticastSocket(0);
            socket.setTimeToLive(4);
            socket.setSoTimeout(250);              // short so the loop can re-send and check the deadline

            // (3) Publish for release(), then re-check: release() may have run before we published.
            activeSocket = socket;
            if (released.get()) throw new IOException("service released");

            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT);
            List<InetAddress> localAddrs = usableIpv4Interfaces();
            byte[] search = buildMSearch().getBytes(StandardCharsets.US_ASCII);
            byte[] buf = new byte[2048];

            // (4) Send + receive loop.
            long start = SystemClock.elapsedRealtime();
            long deadline = start + DISCOVERY_WINDOW_MS;
            long nextSend = start;
            int sends = 0;
            while (!released.get()) {
                long now = SystemClock.elapsedRealtime();
                if (now >= deadline) break;
                if (sends < SEARCH_SENDS && now >= nextSend) {
                    sendSearch(socket, search, group, localAddrs);
                    sends++;
                    nextSend = now + SEARCH_RESEND_MS;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException tick) {
                    continue;
                }
                SsdpReply r = SsdpReply.parse(p);
                if (r != null && !replies.containsKey(r.usn)) replies.put(r.usn, r);
            }
        } finally {
            // (5) Always runs.
            activeSocket = null;
            if (socket != null) socket.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
        if (released.get()) throw new IOException("service released");

        // Description fetch + XML parse. Sequential, still on the IO thread.
        List<RendererDevice> devices = new ArrayList<>();
        for (SsdpReply r : replies.values()) {
            try {
                RendererDevice d = fetchDevice(r);
                if (d != null) devices.add(d);
            } catch (Exception ignoredBadDevice) {
                // A broken/hostile device must not abort discovery of the good ones.
            }
        }
        knownRenderers = Collections.unmodifiableList(devices);
        return knownRenderers;
    }

    private static String buildMSearch() {
        return "M-SEARCH * HTTP/1.1\r\n"
             + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
             + "MAN: \"ssdp:discover\"\r\n"
             + "MX: " + SSDP_MX + "\r\n"
             + "ST: " + ST_MEDIA_RENDERER + "\r\n"
             + "USER-AGENT: Android/UPnP/1.1 TCCA/1.0\r\n"
             + "\r\n";
    }

    /**
     * Sends the search out of every usable interface. On phones with a hotspot and/or mobile
     * data active, the default multicast route is often NOT the Wi-Fi the dongle is on.
     * Replies still arrive on the single wildcard-bound socket.
     */
    private static void sendSearch(MulticastSocket socket, byte[] payload,
                                   InetSocketAddress group, List<InetAddress> localAddrs) {
        DatagramPacket packet = new DatagramPacket(payload, payload.length, group);
        if (localAddrs.isEmpty()) {
            try { socket.send(packet); } catch (IOException ignored) { }
            return;
        }
        for (InetAddress local : localAddrs) {
            try {
                socket.setInterface(local);
                socket.send(packet);
            } catch (IOException ignored) { /* one dead interface must not stop the others */ }
        }
    }

    private static List<InetAddress> usableIpv4Interfaces() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint() || !ni.supportsMulticast()) continue;
                // Skip cellular / VPN tunnels — the dongle is never on those.
                String name = ni.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("ppp") || name.startsWith("dummy")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** One parsed M-SEARCH response. */
    private static final class SsdpReply {
        final String usn, location;
        final InetAddress source;
        SsdpReply(String usn, String location, InetAddress source) {
            this.usn = usn; this.location = location; this.source = source;
        }

        static SsdpReply parse(DatagramPacket p) {
            String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            if (lines.length == 0 || !lines[0].toUpperCase(Locale.ROOT).startsWith("HTTP/1.1 200")) return null;
            String st = null, usn = null, location = null;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) continue;
                String k = lines[i].substring(0, c).trim().toUpperCase(Locale.ROOT);
                String v = lines[i].substring(c + 1).trim();
                if (k.equals("ST")) st = v;
                else if (k.equals("USN")) usn = v;
                else if (k.equals("LOCATION")) location = v;
            }
            if (location == null || st == null || !st.equalsIgnoreCase(ST_MEDIA_RENDERER)) return null;
            return new SsdpReply(usn != null ? usn : location, location, p.getAddress());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Step 2 — fetch + parse the device description XML for the AVTransport controlURL
    // ═════════════════════════════════════════════════════════════════════════════════════

    private RendererDevice fetchDevice(SsdpReply r) throws IOException, XmlPullParserException {
        URL location = new URL(r.location);
        // The LOCATION header is attacker-controlled input from an unauthenticated multicast reply.
        // Only follow it if it points back at the very host that sent the reply (no SSRF to
        // other LAN hosts / the internet).
        if (!"http".equalsIgnoreCase(location.getProtocol())
                || !location.getHost().equals(r.source.getHostAddress())) {
            throw new IOException("LOCATION does not match reply source");
        }

        byte[] xml = httpGet(location);

        // XmlPullParser (KXml) does not resolve external entities/DTDs, so no XXE exposure.
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), null);   // null → sniff encoding from the XML prolog

        String friendly = null, manufacturer = null, model = null, urlBase = null;
        boolean inService = false;
        String svcType = null, ctrl = null, foundType = null, foundCtrl = null;

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("service".equals(n)) { inService = true; svcType = null; ctrl = null; }
                else if ("friendlyName".equals(n) && friendly == null) friendly = p.nextText().trim();
                else if ("manufacturer".equals(n) && manufacturer == null) manufacturer = p.nextText().trim();
                else if ("modelName".equals(n) && model == null) model = p.nextText().trim();
                else if ("URLBase".equals(n) && urlBase == null) urlBase = p.nextText().trim();
                else if (inService && "serviceType".equals(n)) svcType = p.nextText().trim();
                else if (inService && "controlURL".equals(n)) ctrl = p.nextText().trim();
            } else if (ev == XmlPullParser.END_TAG && "service".equals(p.getName())) {
                if (foundCtrl == null && svcType != null && ctrl != null && svcType.startsWith(AVTRANSPORT_PREFIX)) {
                    foundType = svcType;
                    foundCtrl = ctrl;
                }
                inService = false;
            }
        }
        if (foundCtrl == null) return null;   // a MediaRenderer without AVTransport can't be driven

        // controlURL may be absolute or relative to <URLBase> (or, absent that, the LOCATION).
        URL base = new URL(urlBase != null && !urlBase.isEmpty() ? urlBase : r.location);
        URL control = new URL(base, foundCtrl);
        if (!"http".equalsIgnoreCase(control.getProtocol()) || !control.getHost().equals(location.getHost())) {
            throw new IOException("controlURL points at a different host");
        }
        return new RendererDevice(r.usn,
                friendly != null && !friendly.isEmpty() ? friendly : r.source.getHostAddress(),
                manufacturer == null ? "" : manufacturer,
                model == null ? "" : model,
                r.source.getHostAddress(), control.toString(), foundType);
    }

    private static byte[] httpGet(URL url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return readLimited(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
                if (out.size() > MAX_HTTP_BODY_BYTES) throw new IOException("response too large");
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Step 3 — UPnP SOAP actions
    // ═════════════════════════════════════════════════════════════════════════════════════

    /** UPnP-level failure (HTTP 500 + <UPnPError>). Distinct from IOException = "couldn't reach the device". */
    private static final class UpnpFault extends Exception {
        UpnpFault(String msg) { super(msg); }
    }

    private static final Pattern ERR_CODE = Pattern.compile("<errorCode>\\s*(\\d+)\\s*</errorCode>");
    private static final Pattern ERR_DESC = Pattern.compile("<errorDescription>([^<]*)</errorDescription>");

    private static String soap(RendererDevice dev, String action, String argsXml) throws IOException, UpnpFault {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + dev.serviceType + "\">"
                + argsXml
                + "</u:" + action + "></s:Body></s:Envelope>";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(dev.controlUrl).openConnection();
        try {
            c.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            // Must use the serviceType this device advertised, quoted.
            c.setRequestProperty("SOAPACTION", "\"" + dev.serviceType + "#" + action + "\"");
            c.setRequestProperty("Connection", "close");
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }

            int code = c.getResponseCode();
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = stream == null ? "" : new String(readLimited(stream), StandardCharsets.UTF_8);
            if (code / 100 != 2) {
                Matcher mc = ERR_CODE.matcher(resp), md = ERR_DESC.matcher(resp);
                throw new UpnpFault(action + " failed (HTTP " + code
                        + (mc.find() ? ", UPnP " + mc.group(1) : "")
                        + (md.find() ? ": " + md.group(1) : "") + ")");
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String setUriArgs(String url, String didl) {
        return "<InstanceID>0</InstanceID>"
             + "<CurrentURI>" + escapeXml(url) + "</CurrentURI>"
             + "<CurrentURIMetaData>" + escapeXml(didl) + "</CurrentURIMetaData>";  // DIDL travels escaped, as text
    }

    /** Minimal DIDL-Lite for http(s) streams; "" for other schemes (protocolInfo would be a guess). */
    private static String buildDidl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        String path = lower.contains("?") ? lower.substring(0, lower.indexOf('?')) : lower;
        String mime = path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                    : path.endsWith(".mp4")  ? "video/mp4"
                    : path.endsWith(".flv")  ? "video/x-flv"
                    : "video/mpeg";                       // MPEG-TS, the usual OBS/ffmpeg live container
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
             + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
             + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
             + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
             + "<dc:title>OBS Live</dc:title>"
             + "<upnp:class>object.item.videoItem</upnp:class>"
             + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + escapeXml(url) + "</res>"
             + "</item></DIDL-Lite>";
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════════════════

    /** Returns a human-readable problem, or null if the URL is acceptable. */
    static String validateStreamUrl(String url) {
        if (url.isEmpty()) return "Enter the OBS stream URL first";
        if (url.length() > 2048) return "Stream URL is too long";
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i)) || Character.isISOControl(url.charAt(i)))
                return "Stream URL must not contain spaces or control characters";
        }
        URI uri;
        try { uri = new URI(url); } catch (Exception e) { return "Stream URL is malformed"; }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = false;
        for (String s : ALLOWED_STREAM_SCHEMES) if (s.equals(scheme)) { ok = true; break; }
        if (!ok) return "Unsupported scheme '" + scheme + "' (use http, https, rtsp, rtmp, srt, udp or rtp)";
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return "Stream URL has no host";
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1"))
            return "The TV would resolve '" + host + "' to itself — use the OBS PC's LAN IP address";
        return null;
    }

    /** Preferred-name match first, then "anycast"-style hints, then everything (caller checks the count). */
    private List<RendererDevice> pickAll(List<RendererDevice> all) {
        String want = preferredName;
        if (want != null) {
            List<RendererDevice> m = filter(all, new String[] { want.toLowerCase(Locale.ROOT) });
            if (!m.isEmpty()) return m;
        }
        List<RendererDevice> hinted = filter(all, PREFERRED_NAME_HINTS);
        return hinted.isEmpty() ? all : hinted;
    }

    private RendererDevice pick(List<RendererDevice> all) {
        String want = preferredName;
        if (want == null) return null;
        List<RendererDevice> m = filter(all, new String[] { want.toLowerCase(Locale.ROOT) });
        return m.isEmpty() ? null : m.get(0);
    }

    private static List<RendererDevice> filter(List<RendererDevice> all, String[] fragments) {
        List<RendererDevice> out = new ArrayList<>();
        for (RendererDevice d : all) {
            String hay = (d.friendlyName + " " + d.manufacturer + " " + d.modelName + " " + d.usn)
                    .toLowerCase(Locale.ROOT);
            for (String f : fragments) if (hay.contains(f)) { out.add(d); break; }
        }
        return out;
    }

    private static String describe(List<RendererDevice> list) {
        StringBuilder sb = new StringBuilder();
        for (RendererDevice d : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.friendlyName);
        }
        return sb.toString();
    }

    private static String noRendererMessage() {
        return "No DLNA renderer found. Put the AnyCast dongle in DLNA mode and make sure the phone, "
             + "dongle and OBS PC are on the same Wi-Fi network.";
    }

    /** Queues work on the IO thread; false if already released. */
    private boolean submit(Runnable r) {
        if (released.get()) return false;
        try { io.execute(r); return true; }
        catch (RejectedExecutionException e) { return false; }   // lost a race with release()
    }

    private void emit(String state, String message, String deviceName) {
        if (released.get() && !STATE_ERROR.equals(state)) return;
        try { listener.onStatus(state, message, deviceName); }
        catch (RuntimeException ignored) { /* a misbehaving listener must not kill the IO thread */ }
    }
}
