package com.qqai.component;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/** 组件探测：默认 TCP 连接，可选 HTTP GET。 */
final class Probes {

    private Probes() {
    }

    static ComponentModel.ProbeResult probe(ComponentModel.ComponentDescriptor d) {
        ComponentModel.ProbeSpec spec = d.getProbe();
        long start = System.currentTimeMillis();
        try {
            if (spec != null && "http".equalsIgnoreCase(spec.getType())) {
                String path = spec.getPath() == null || spec.getPath().isBlank() ? "/" : spec.getPath();
                String url = "http://127.0.0.1:" + spec.getPort() + path;
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(spec.getTimeoutMs());
                conn.setReadTimeout(spec.getTimeoutMs());
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                long cost = System.currentTimeMillis() - start;
                return new ComponentModel.ProbeResult(code >= 200 && code < 500, cost,
                        "HTTP " + code + " " + url);
            }
            int port = spec != null && spec.getPort() > 0 ? spec.getPort()
                    : (d.getPorts().isEmpty() ? 0 : d.getPorts().get(0));
            if (port <= 0) {
                return new ComponentModel.ProbeResult(false, 0, "未配置探测端口");
            }
            int timeout = spec != null && spec.getTimeoutMs() > 0 ? spec.getTimeoutMs() : 1000;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), timeout);
            }
            return new ComponentModel.ProbeResult(true, System.currentTimeMillis() - start, "TCP " + port + " 可连接");
        } catch (Exception e) {
            return new ComponentModel.ProbeResult(false, System.currentTimeMillis() - start,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
