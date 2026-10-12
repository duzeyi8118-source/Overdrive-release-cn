package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared proxy detection utility for the local SOCKS/HTTP proxies: Tailscale
 * (8539), WireGuard (8541) and sing-box (8119).
 *
 * The BYD head unit may route internet through a sing-box proxy on port 8119.
 * The WireGuard listener (wgproxy) sends only destinations inside the peers'
 * AllowedIPs through the tunnel and dials everything else directly or via
 * sing-box, so the proxy route chain stays correct when it is selected.
 * This helper probes the proxy availability and provides socket factories
 * for both HTTP clients (OkHttp) and MQTT clients (Paho).
 *
 * Probe result is cached for 60 seconds to avoid excessive socket probes.
 * Cache is invalidated on connection failures so the next attempt re-probes.
 */
public class ProxyHelper {

    private static final String TAG = "ProxyHelper";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PROXY_HOST = "127.0.0.1";
    private static final int PROXY_PORT = 8119;
    private static final int TAILSCALE_PROXY_PORT = 8539;
    private static final int WIREGUARD_PROXY_PORT = 8541;
    private static final String PROXY_ENABLED_FILE = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/.tailscale/proxy_enabled");
    private static final String WIREGUARD_PROXY_ENABLED_FILE = com.overdrive.app.wireguard.WireGuardPaths.PROXY_FLAG;
    // Loopback TCP connect budget. 200ms was too tight: a cold/loaded sing-box (or a
    // probe issued while the proxy is still binding) could miss, and a SINGLE miss
    // poisoned a whole minute (see the asymmetric cache below) → every map search /
    // routing / tile fetch went DIRECT and timed out on in-car mobile data even though
    // the proxy was actually up. 500ms is still trivial against a localhost listener
    // (a refused connection returns immediately; only a genuinely slow accept waits).
    private static final int PROBE_TIMEOUT_MS = 500;
    // ASYMMETRIC cache (root-cause fix for "couldn't search routes while sing-box on"):
    //   • a POSITIVE probe caches for CACHE_DURATION_MS — the proxy rarely vanishes
    //     mid-drive, so we don't re-probe on every request.
    //   • a NEGATIVE probe caches for only NEG_CACHE_DURATION_MS, so a TRANSIENT miss
    //     self-heals within seconds instead of forcing NO_PROXY (→ direct → timeout)
    //     for a full minute. connectFailed()/invalidateCache() still force an immediate
    //     re-probe on a real failure; this just bounds the blast radius of a probe that
    //     missed while the proxy was fine.
    private static final long CACHE_DURATION_MS = 60_000;     // positive result: 60s
    private static final long NEG_CACHE_DURATION_MS = 2_000;  // negative result: 2s

    private static volatile boolean proxyChecked = false;
    private static volatile boolean proxyAvailable = false;
    private static volatile boolean singboxAvailable = false;
    private static volatile boolean tailscaleAvailable = false;
    private static volatile boolean wireguardAvailable = false;
    private static volatile int proxyPort = PROXY_PORT;
    private static volatile long lastProbeTime = 0;

    /**
     * Guards the process-global {@code socksProxyHost}/{@code socksProxyPort} JVM
     * properties across the window where they matter: from the set/clear until the
     * connection's underlying socket has been created (Paho reads them at socket
     * creation, not afterwards). Two connect paths touch or depend on them:
     *  - WS/WSS + proxy (MqttPublisherService): must SET them (Paho bug #573 bypasses
     *    the SocketFactory for WebSockets) and needs them to survive until its socket
     *    is created.
     *  - Any DIRECT (no-proxy) connect using a default socket factory: must CLEAR them
     *    (leftover props would misroute the direct socket through a dead proxy) and
     *    needs them to stay cleared until its socket is created.
     * Without a shared lock, two connections connecting concurrently on their own
     * scheduler threads can interleave set/clear mid-window (ProxyHelper's negative
     * cache and invalidateCache() let them disagree about proxy state). Hold this lock
     * from the property mutation through the blocking connect() call. Factory-proxied
     * connects (explicit {@code new Socket(proxy)}) ignore the props entirely and must
     * NOT take this lock — connects are serialized only when the props are in play.
     */
    public static final Object SOCKS_PROPS_LOCK = new Object();

    private ProxyHelper() {} // Utility class

    private static void ensureProbed() {
        long now = System.currentTimeMillis();
        if (proxyChecked) {
            long ttl = proxyAvailable ? CACHE_DURATION_MS : NEG_CACHE_DURATION_MS;
            if ((now - lastProbeTime) < ttl) {
                return;
            }
        }
        isProxyAvailable();
    }

    /**
     * Check if any local proxy is available. Result is cached — a POSITIVE result
     * for {@link #CACHE_DURATION_MS}, a NEGATIVE result for only
     * {@link #NEG_CACHE_DURATION_MS}.
     */
    public static boolean isProxyAvailable() {
        long now = System.currentTimeMillis();
        if (proxyChecked) {
            long ttl = proxyAvailable ? CACHE_DURATION_MS : NEG_CACHE_DURATION_MS;
            if ((now - lastProbeTime) < ttl) {
                return proxyAvailable;
            }
        }

        proxyChecked = true;
        lastProbeTime = now;

        // Independently probe all three proxy listeners
        singboxAvailable = probePort(PROXY_PORT);
        tailscaleAvailable = probePort(TAILSCALE_PROXY_PORT);
        wireguardAvailable = probePort(WIREGUARD_PROXY_PORT);
        proxyAvailable = singboxAvailable || tailscaleAvailable || wireguardAvailable;

        // Prefer sing-box (8119) for general egress.
        // For intranet/tunnels, prefer WireGuard (8541) over Tailscale (8539)
        // because WireGuard bridges EasyTier and local NAS LAN.
        if (singboxAvailable) {
            proxyPort = PROXY_PORT;
            logger.info("Proxy probe: sing-box available on port " + PROXY_PORT);
        } else if (wireguardAvailable) {
            proxyPort = WIREGUARD_PROXY_PORT;
            logger.info("Proxy probe: WireGuard proxy available on port " + WIREGUARD_PROXY_PORT);
        } else if (tailscaleAvailable) {
            proxyPort = TAILSCALE_PROXY_PORT;
            logger.info("Proxy probe: Tailscale proxy available on port " + TAILSCALE_PROXY_PORT);
        } else {
            proxyPort = PROXY_PORT;
        }

        return proxyAvailable;
    }

    /** Loopback TCP probe of a single port on a FRESH socket (see caller for why per-port). */
    public static boolean probePort(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(PROXY_HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the default active proxy port number.
     * Used by MqttPublisherService to set JVM-level socksProxyPort for WebSocket connections.
     */
    public static int getProxyPort() {
        return proxyPort;
    }

    /**
     * Get the best proxy port for the given destination host / IP.
     * Directs Tailscale IPs (100.x) to Tailscale (8539), intranet (10.x, 192.168.x) to WireGuard (8541),
     * and public hosts to sing-box (8119).
     */
    public static int getProxyPort(String host) {
        return getProxyPortForHost(host);
    }

    public static int getProxyPortForHost(String host) {
        ensureProbed();
        if (host != null && !host.isEmpty()) {
            if (isTailscaleHost(host)) {
                if (tailscaleAvailable) return TAILSCALE_PROXY_PORT;
            } else if (isIntranetHost(host)) {
                if (wireguardAvailable) return WIREGUARD_PROXY_PORT;
                if (tailscaleAvailable) return TAILSCALE_PROXY_PORT;
            }
        }
        if (singboxAvailable) return PROXY_PORT;
        if (wireguardAvailable) return WIREGUARD_PROXY_PORT;
        if (tailscaleAvailable) return TAILSCALE_PROXY_PORT;
        return proxyPort;
    }

    public static boolean isProxyAvailable(String host) {
        return isProxyAvailableForHost(host);
    }

    public static boolean isProxyAvailableForHost(String host) {
        ensureProbed();
        if (host == null || host.isEmpty()) {
            return proxyAvailable;
        }
        if (isTailscaleHost(host)) {
            return tailscaleAvailable || wireguardAvailable;
        }
        if (isIntranetHost(host)) {
            return wireguardAvailable || tailscaleAvailable;
        }
        // Public host requires an outbound egress proxy like sing-box
        return singboxAvailable;
    }

    public static boolean isTailscaleHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith(".ts.net")) return true;
        if (h.startsWith("100.")) {
            String[] parts = h.split("\\.");
            if (parts.length == 4) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 64 && second <= 127;
                } catch (NumberFormatException ignored) {}
            }
            return true;
        }
        return false;
    }

    public static boolean isIntranetHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true;
        if (h.endsWith(".local") || h.endsWith(".lan")) return true;
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    /**
     * The Tailscale SOCKS port we expect when the proxy is enabled. Unlike {@link #getProxyPort()}
     * (which stays at the sing-box default until a successful probe updates it), this is stable, so
     * callers can name the right port in "proxy warming up" diagnostics.
     */
    public static int getTailscaleProxyPort() {
        return TAILSCALE_PROXY_PORT;
    }

    /**
     * The SOCKS port we expect when the user enabled a proxy: Tailscale if its flag is on, else
     * WireGuard if its flag is on, else the Tailscale port. Stable (flag-based, not probe-based),
     * so "proxy warming up" diagnostics and the fail-closed route name the right endpoint.
     */
    public static int getExpectedProxyPort() {
        if (isFlagEnabled(PROXY_ENABLED_FILE)) return TAILSCALE_PROXY_PORT;
        if (isFlagEnabled(WIREGUARD_PROXY_ENABLED_FILE)) return WIREGUARD_PROXY_PORT;
        return TAILSCALE_PROXY_PORT;
    }

    /**
     * Invalidate the proxy cache.
     * Call this on connection failures so the next attempt re-probes.
     */
    public static void invalidateCache() {
        proxyAvailable = false;
        singboxAvailable = false;
        tailscaleAvailable = false;
        wireguardAvailable = false;
        proxyChecked = false;
    }

    /**
     * Whether the user has ENABLED the Tailscale SOCKS proxy (persisted flag written by the
     * Daemons screen to {@code .tailscale/proxy_enabled}) or the WireGuard tunnel (flag written
     * by the launcher to {@code .wireguard/proxy_enabled}). When true, outbound traffic is
     * expected to go through the proxy ONLY, so callers must not fall back to a direct dial
     * that cannot reach a proxy-only (e.g. subnet-routed LAN) broker off Wi-Fi.
     */
    public static boolean isProxyExpected() {
        return isFlagEnabled(PROXY_ENABLED_FILE) || isFlagEnabled(WIREGUARD_PROXY_ENABLED_FILE);
    }

    private static boolean isFlagEnabled(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return isProxyEnabledValue(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse a persisted {@code proxy_enabled} value: true iff "true" (any case) or "1", trimmed. */
    static boolean isProxyEnabledValue(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * Get a Java Proxy object for HTTP clients (OkHttp).
     * Returns Proxy.NO_PROXY if proxy is not available.
     *
     * Tailscale (8539) is a tailnet-only SOCKS5 proxy without public exit-node routing.
     * It rejects public internet domains with SOCKS error 0x01 (general SOCKS server failure).
     * Therefore, general HTTP requests fall back to direct connection (Proxy.NO_PROXY) unless
     * a true outbound proxy like sing-box (8119) is available.
     */
    public static Proxy getHttpProxy() {
        return getHttpProxy(null);
    }

    public static Proxy getHttpProxy(String host) {
        ensureProbed();
        if (host != null && !host.isEmpty()) {
            if (isTailscaleHost(host)) {
                if (tailscaleAvailable) {
                    return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(PROXY_HOST, TAILSCALE_PROXY_PORT));
                }
            } else if (isIntranetHost(host)) {
                if (wireguardAvailable) {
                    return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(PROXY_HOST, WIREGUARD_PROXY_PORT));
                } else if (tailscaleAvailable) {
                    return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(PROXY_HOST, TAILSCALE_PROXY_PORT));
                }
            }
        }
        if (singboxAvailable) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        }
        if (host == null && proxyAvailable) {
            Proxy.Type type = (proxyPort == TAILSCALE_PROXY_PORT || proxyPort == WIREGUARD_PROXY_PORT)
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            return new Proxy(type, new InetSocketAddress(PROXY_HOST, proxyPort));
        }
        return Proxy.NO_PROXY;
    }

    /**
     * Whether a public outbound proxy (sing-box on 8119) is available.
     * Returns false if only Tailscale or WireGuard (internal tunnel proxies) is running.
     */
    public static boolean isPublicProxyAvailable() {
        ensureProbed();
        return singboxAvailable;
    }

    /**
     * Proxy route CHAIN for clients whose destinations are PUBLIC internet
     * (BYD Cloud, GitHub release metadata): the selected local proxy first,
     * then a DIRECT fallback — or just DIRECT when no proxy is up.
     *
     * <p>Why a chain: {@link #isProxyAvailable()} is a blind loopback TCP
     * probe. It cannot distinguish a general-egress proxy (sing-box, or
     * Tailscale fronting an exit node) from the tailnet-only Tailscale SOCKS
     * listener that the Daemons screen exposes for private MQTT brokers —
     * and it prefers the Tailscale port. A tailnet-only listener stays
     * healthy under the probe while every public destination sent through it
     * fails, so a single frozen proxy wedges the caller for as long as the
     * listener is up ({@link #invalidateCache()} just re-selects it). OkHttp
     * consumers install the chain via a per-call ProxySelector: proxy-first
     * behavior is preserved where the proxy IS the egress (sing-box /
     * exit-node cars), and everything else falls through to direct within
     * the same call. Do NOT use this for privacy-sensitive traffic — that's
     * {@link #getFailClosedHttpProxy()}'s job, which intentionally has no
     * direct fallback.
     */
    public static java.util.List<Proxy> proxyRouteChain(Proxy selected) {
        if (selected == null || Proxy.NO_PROXY.equals(selected)) {
            return java.util.Collections.singletonList(Proxy.NO_PROXY);
        }
        java.util.List<Proxy> chain = new java.util.ArrayList<>(2);
        chain.add(selected);
        chain.add(Proxy.NO_PROXY);
        return chain;
    }

    /**
     * Ready-made per-call OkHttp {@link java.net.ProxySelector} for simple
     * PUBLIC-internet clients (weather, analytics ping, RoadSense sync, map
     * routing/tiles): re-evaluates {@link #getHttpProxy()} on every request
     * and returns {@link #proxyRouteChain}, so the proxy is picked up when it
     * appears and a tailnet-only listener can't wedge the feature. Clients
     * with bespoke logging or resolution cadence (BYD cloud transport,
     * AppUpdater, Telegram daemon) keep their own selectors built on
     * {@link #proxyRouteChain}.
     */
    public static java.net.ProxySelector chainProxySelector() {
        return CHAIN_PROXY_SELECTOR;
    }

    private static final java.net.ProxySelector CHAIN_PROXY_SELECTOR =
            new java.net.ProxySelector() {
                @Override
                public java.util.List<Proxy> select(java.net.URI uri) {
                    return proxyRouteChain(getHttpProxy());
                }

                @Override
                public void connectFailed(
                        java.net.URI uri,
                        SocketAddress address,
                        java.io.IOException failure) {
                    // Only proxy legs are reported here (OkHttp never calls
                    // connectFailed for DIRECT routes) — re-probe next call.
                    invalidateCache();
                }
            };

    /**
     * HTTP proxy selection for privacy-sensitive traffic. When the user has
     * enabled proxy-only routing but the listener is still unavailable, return
     * the expected local SOCKS endpoint so clients fail closed instead of
     * silently dialing the destination directly.
     */
    public static Proxy getFailClosedHttpProxy() {
        Proxy selected = getHttpProxy();
        if (!Proxy.NO_PROXY.equals(selected) || !isProxyExpected()) {
            return selected;
        }
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(PROXY_HOST, getExpectedProxyPort()));
    }

    /**
     * Get a SocketFactory that routes through the sing-box SOCKS proxy.
     * Used by Paho MQTT client via MqttConnectOptions.setSocketFactory().
     *
     * If proxy is not available, returns the default SocketFactory (direct connection).
     */
    public static SocketFactory getMqttSocketFactory() {
        return getMqttSocketFactory(null);
    }

    public static SocketFactory getMqttSocketFactory(String host) {
        if (isProxyAvailableForHost(host)) {
            int port = getProxyPortForHost(host);
            return new ProxiedSocketFactory(port);
        }
        return SocketFactory.getDefault();
    }

    /**
     * Get an SSLSocketFactory that routes through the appropriate SOCKS proxy.
     *
     * This is the hard part: we create a raw SOCKS-proxied TCP socket first,
     * then overlay TLS on top of it using SSLSocketFactory.createSocket(Socket, host, port, autoClose).
     * This lets Paho see an SSLSocketFactory (so it performs the TLS handshake) while the
     * underlying transport is routed through the proxy.
     *
     * @param trustAll if true, accepts any server certificate (for self-signed / Home Assistant)
     */
    public static SSLSocketFactory getProxiedSslSocketFactory(boolean trustAll) {
        return getProxiedSslSocketFactory(null, trustAll);
    }

    public static SSLSocketFactory getProxiedSslSocketFactory(String host, boolean trustAll) {
        try {
            SSLSocketFactory baseSslFactory = trustAll
                    ? getTrustAllSslFactory()
                    : (SSLSocketFactory) SSLSocketFactory.getDefault();

            if (isProxyAvailableForHost(host)) {
                int port = getProxyPortForHost(host);
                return new ProxiedSslSocketFactory(baseSslFactory, port);
            }
            return baseSslFactory;
        } catch (Exception e) {
            logger.error("Failed to create proxied SSL factory: " + e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    /**
     * Get an SSLSocketFactory that trusts ALL certificates.
     *
     * WARNING: This disables certificate validation entirely. Only use for local
     * brokers with self-signed certs (Home Assistant, dev Mosquitto instances).
     * Never use against public brokers where MITM is a real risk.
     */
    public static SSLSocketFactory getTrustAllSslFactory() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };

        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        sc.init(null, trustAllCerts, new SecureRandom());
        return sc.getSocketFactory();
    }

    /**
     * SocketFactory that creates sockets routed through the sing-box SOCKS proxy.
     * Paho MQTT uses SocketFactory to create its TCP connections, so this is the
     * cleanest way to proxy MQTT traffic without modifying Paho internals.
     */
    static class ProxiedSocketFactory extends SocketFactory {

        private final int configuredPort;
        private final Proxy proxy;

        ProxiedSocketFactory() {
            this(proxyPort);
        }

        ProxiedSocketFactory(int port) {
            this.configuredPort = port;
            this.proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(PROXY_HOST, port));
        }

        private Proxy resolveProxyForHost(String host) {
            if (host != null && !host.isEmpty()) {
                int resolved = getProxyPortForHost(host);
                if (resolved != this.configuredPort) {
                    return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(PROXY_HOST, resolved));
                }
            }
            return this.proxy;
        }

        @Override
        public Socket createSocket() {
            return new Socket(proxy);
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket socket = new Socket(resolveProxyForHost(host));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            Socket socket = new Socket(resolveProxyForHost(host));
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket socket = new Socket(resolveProxyForHost(host != null ? host.getHostAddress() : null));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port,
                                   java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            Socket socket = new Socket(resolveProxyForHost(address != null ? address.getHostAddress() : null));
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }

    /**
     * SSLSocketFactory that creates TLS sockets routed through the appropriate SOCKS proxy.
     *
     * Strategy: create a raw SOCKS-proxied TCP socket, then layer TLS on top using
     * SSLSocketFactory.createSocket(Socket, host, port, autoClose). This gives Paho
     * a proper SSLSocket for the TLS handshake while the underlying bytes flow through the proxy.
     */
    static class ProxiedSslSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory sslFactory;
        private final int configuredPort;
        private final Proxy proxy;

        ProxiedSslSocketFactory(SSLSocketFactory sslFactory) {
            this(sslFactory, proxyPort);
        }

        ProxiedSslSocketFactory(SSLSocketFactory sslFactory, int port) {
            this.sslFactory = sslFactory;
            this.configuredPort = port;
            this.proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(PROXY_HOST, port));
        }

        private Proxy resolveProxyForHost(String host) {
            if (host != null && !host.isEmpty()) {
                int resolved = getProxyPortForHost(host);
                if (resolved != this.configuredPort) {
                    return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(PROXY_HOST, resolved));
                }
            }
            return this.proxy;
        }

        /**
         * No-arg createSocket() — Paho's SSLNetworkModule calls this first, then
         * either connects it and upgrades via createSocket(Socket, host, port, true),
         * or (in some versions) casts the result directly to SSLSocket.
         *
         * We return a DeferredSslSocket: a plain proxied Socket wrapped in a
         * delegating SSLSocket shell. This satisfies the cast to SSLSocket
         * immediately. When Paho calls connect(), the underlying SOCKS tunnel
         * is established. When Paho calls startHandshake(), we upgrade the
         * tunnel to real TLS on the fly.
         */
        @Override
        public Socket createSocket() throws java.io.IOException {
            return new DeferredSslSocket(new Socket(proxy), sslFactory);
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            // 1. Create raw TCP socket through SOCKS proxy
            Socket tunnel = new Socket(resolveProxyForHost(host));
            tunnel.connect(new InetSocketAddress(host, port));
            // 2. Layer TLS on top of the connected tunnel
            return sslFactory.createSocket(tunnel, host, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            Socket tunnel = new Socket(resolveProxyForHost(host));
            tunnel.bind(new InetSocketAddress(localHost, localPort));
            tunnel.connect(new InetSocketAddress(host, port));
            return sslFactory.createSocket(tunnel, host, port, true);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket tunnel = new Socket(resolveProxyForHost(host != null ? host.getHostAddress() : null));
            tunnel.connect(new InetSocketAddress(host, port));
            return sslFactory.createSocket(tunnel, host.getHostName(), port, true);
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port,
                                   java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            Socket tunnel = new Socket(resolveProxyForHost(address != null ? address.getHostAddress() : null));
            tunnel.bind(new InetSocketAddress(localAddress, localPort));
            tunnel.connect(new InetSocketAddress(address, port));
            return sslFactory.createSocket(tunnel, address.getHostName(), port, true);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            // Paho may call this variant directly — layer TLS on the provided socket.
            // If 's' is our DeferredSslSocket, unwrap to the real underlying socket first
            // so we don't nest SSLSocket inside SSLSocket.
            Socket raw = (s instanceof DeferredSslSocket) ? ((DeferredSslSocket) s).getInnerSocket() : s;
            return sslFactory.createSocket(raw, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return sslFactory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return sslFactory.getSupportedCipherSuites();
        }
    }

    /**
     * A thin SSLSocket wrapper around a plain (SOCKS-proxied) Socket.
     *
     * Paho's SSLNetworkModule (via TCPNetworkModule.start()) does:
     *   1. factory.createSocket()                    → stored in 'socket' field
     *   2. socket.connect(sockaddr, timeout)          → TCP connect
     *   3. socket.setSoTimeout(1000)
     *   4. ((SSLSocket) socket).setEnabledCipherSuites(...)  ← before handshake!
     *   5. ((SSLSocket) socket).getSSLParameters()           ← before handshake!
     *   6. ((SSLSocket) socket).setSSLParameters(...)        ← before handshake!
     *   7. ((SSLSocket) socket).startHandshake()
     *
     * Steps 4-6 happen BEFORE startHandshake(), so we must buffer any
     * configuration (cipher suites, SSL parameters, protocols) and replay
     * them onto the real SSLSocket when it's created in startHandshake().
     *
     * DeferredSslSocket solves this by:
     *   - Extending SSLSocket so all casts succeed
     *   - Delegating connect/setSoTimeout to the inner proxied socket
     *   - Buffering SSL config (ciphers, protocols, parameters) pre-handshake
     *   - On startHandshake(), creating the real SSLSocket via
     *     sslFactory.createSocket(innerSocket, host, port, true),
     *     replaying buffered config, then performing the handshake
     */
    static class DeferredSslSocket extends SSLSocket {

        private final Socket innerSocket;
        private final SSLSocketFactory sslFactory;
        private SSLSocket realSsl;
        private String peerHost;
        private int peerPort;

        // Buffered SSL configuration — applied to realSsl in startHandshake()
        private String[] pendingEnabledCiphers;
        private String[] pendingEnabledProtocols;
        private javax.net.ssl.SSLParameters pendingSSLParameters;
        private Boolean pendingUseClientMode;
        private Boolean pendingNeedClientAuth;
        private Boolean pendingWantClientAuth;
        private Boolean pendingEnableSessionCreation;
        private final java.util.List<HandshakeCompletedListener> pendingListeners =
                new java.util.ArrayList<>();

        DeferredSslSocket(Socket innerSocket, SSLSocketFactory sslFactory) {
            this.innerSocket = innerSocket;
            this.sslFactory = sslFactory;
        }

        /** Expose the raw inner socket for unwrapping in createSocket(Socket,...) */
        Socket getInnerSocket() {
            return innerSocket;
        }

        // --- Connect: delegate to inner socket, remember peer for TLS upgrade ---

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            capturePeer(endpoint);
            innerSocket.connect(endpoint);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            capturePeer(endpoint);
            innerSocket.connect(endpoint, timeout);
        }

        private void capturePeer(SocketAddress endpoint) {
            if (endpoint instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) endpoint;
                peerHost = inet.getHostString();
                peerPort = inet.getPort();
            }
        }

        // --- TLS upgrade on startHandshake ---

        @Override
        public void startHandshake() throws IOException {
            if (realSsl != null) {
                realSsl.startHandshake();
                return;
            }
            if (peerHost == null) {
                peerHost = innerSocket.getInetAddress() != null
                        ? innerSocket.getInetAddress().getHostName() : "unknown";
                peerPort = innerSocket.getPort();
            }
            realSsl = (SSLSocket) sslFactory.createSocket(innerSocket, peerHost, peerPort, true);

            // Replay buffered configuration onto the real SSLSocket
            if (pendingEnabledCiphers != null) realSsl.setEnabledCipherSuites(pendingEnabledCiphers);
            if (pendingEnabledProtocols != null) realSsl.setEnabledProtocols(pendingEnabledProtocols);
            if (pendingSSLParameters != null) realSsl.setSSLParameters(pendingSSLParameters);
            if (pendingUseClientMode != null) realSsl.setUseClientMode(pendingUseClientMode);
            if (pendingNeedClientAuth != null) realSsl.setNeedClientAuth(pendingNeedClientAuth);
            if (pendingWantClientAuth != null) realSsl.setWantClientAuth(pendingWantClientAuth);
            if (pendingEnableSessionCreation != null) realSsl.setEnableSessionCreation(pendingEnableSessionCreation);
            for (HandshakeCompletedListener l : pendingListeners) {
                realSsl.addHandshakeCompletedListener(l);
            }

            realSsl.startHandshake();
        }

        private SSLSocket ensureSsl() throws IOException {
            if (realSsl == null) startHandshake();
            return realSsl;
        }

        // --- I/O: delegate to real SSLSocket after handshake ---

        @Override
        public InputStream getInputStream() throws IOException {
            return ensureSsl().getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return ensureSsl().getOutputStream();
        }

        // --- SSLSocket methods: buffer pre-handshake, delegate post-handshake ---

        @Override
        public SSLSession getSession() {
            if (realSsl != null) return realSsl.getSession();
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (realSsl != null) {
                realSsl.addHandshakeCompletedListener(listener);
            } else {
                pendingListeners.add(listener);
            }
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (realSsl != null) {
                realSsl.removeHandshakeCompletedListener(listener);
            } else {
                pendingListeners.remove(listener);
            }
        }

        @Override
        public String[] getSupportedCipherSuites() {
            if (realSsl != null) return realSsl.getSupportedCipherSuites();
            // Can't know without a real SSLSocket; return empty — Paho doesn't call this pre-handshake
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            if (realSsl != null) return realSsl.getEnabledCipherSuites();
            if (pendingEnabledCiphers != null) return pendingEnabledCiphers;
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            if (realSsl != null) {
                realSsl.setEnabledCipherSuites(suites);
            } else {
                pendingEnabledCiphers = suites;
            }
        }

        @Override
        public String[] getSupportedProtocols() {
            if (realSsl != null) return realSsl.getSupportedProtocols();
            return new String[0];
        }

        @Override
        public String[] getEnabledProtocols() {
            if (realSsl != null) return realSsl.getEnabledProtocols();
            if (pendingEnabledProtocols != null) return pendingEnabledProtocols;
            return new String[0];
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            if (realSsl != null) {
                realSsl.setEnabledProtocols(protocols);
            } else {
                pendingEnabledProtocols = protocols;
            }
        }

        @Override
        public javax.net.ssl.SSLParameters getSSLParameters() {
            if (realSsl != null) return realSsl.getSSLParameters();
            // Pre-handshake: return a fresh SSLParameters that Paho can configure.
            // Paho calls getSSLParameters(), mutates it, then calls setSSLParameters().
            // We'll capture the final state in setSSLParameters().
            if (pendingSSLParameters != null) return pendingSSLParameters;
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public void setSSLParameters(javax.net.ssl.SSLParameters params) {
            if (realSsl != null) {
                realSsl.setSSLParameters(params);
            } else {
                pendingSSLParameters = params;
            }
        }

        @Override
        public boolean getUseClientMode() {
            if (realSsl != null) return realSsl.getUseClientMode();
            return pendingUseClientMode != null ? pendingUseClientMode : true;
        }

        @Override
        public void setUseClientMode(boolean mode) {
            if (realSsl != null) {
                realSsl.setUseClientMode(mode);
            } else {
                pendingUseClientMode = mode;
            }
        }

        @Override
        public boolean getNeedClientAuth() {
            if (realSsl != null) return realSsl.getNeedClientAuth();
            return pendingNeedClientAuth != null ? pendingNeedClientAuth : false;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            if (realSsl != null) {
                realSsl.setNeedClientAuth(need);
            } else {
                pendingNeedClientAuth = need;
            }
        }

        @Override
        public boolean getWantClientAuth() {
            if (realSsl != null) return realSsl.getWantClientAuth();
            return pendingWantClientAuth != null ? pendingWantClientAuth : false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
            if (realSsl != null) {
                realSsl.setWantClientAuth(want);
            } else {
                pendingWantClientAuth = want;
            }
        }

        @Override
        public boolean getEnableSessionCreation() {
            if (realSsl != null) return realSsl.getEnableSessionCreation();
            return pendingEnableSessionCreation != null ? pendingEnableSessionCreation : true;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            if (realSsl != null) {
                realSsl.setEnableSessionCreation(flag);
            } else {
                pendingEnableSessionCreation = flag;
            }
        }

        // --- Socket state delegation ---

        @Override
        public boolean isConnected() {
            return innerSocket.isConnected();
        }

        @Override
        public boolean isClosed() {
            return innerSocket.isClosed();
        }

        @Override
        public boolean isBound() {
            return innerSocket.isBound();
        }

        @Override
        public void close() throws IOException {
            if (realSsl != null) {
                realSsl.close(); // closes inner socket too (autoClose=true)
            } else {
                innerSocket.close();
            }
        }

        @Override
        public void setSoTimeout(int timeout) throws java.net.SocketException {
            // Pre-handshake: set on inner socket (Paho calls this before startHandshake)
            // Post-handshake: set on both (realSsl wraps innerSocket, but timeout is on the underlying)
            innerSocket.setSoTimeout(timeout);
            if (realSsl != null) {
                try { realSsl.setSoTimeout(timeout); } catch (java.net.SocketException ignored) {}
            }
        }

        @Override
        public int getSoTimeout() throws java.net.SocketException {
            return innerSocket.getSoTimeout();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws java.net.SocketException {
            innerSocket.setTcpNoDelay(on);
        }

        @Override
        public java.net.InetAddress getInetAddress() {
            return innerSocket.getInetAddress();
        }

        @Override
        public int getPort() {
            return innerSocket.getPort();
        }

        @Override
        public int getLocalPort() {
            return innerSocket.getLocalPort();
        }
    }
}
