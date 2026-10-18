package com.apkunifier.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 带代理感知的 HTTP 下载工具。 */
public final class HttpDownloader {

    private static final int BUFFER_SIZE = 1 << 16;
    private static final String USER_AGENT = "apk-unifier/1.0 (+https://example.invalid)";

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    private HttpDownloader() {
    }

    /** 读取一次 HEAD 请求返回的 Location 头（用于解析 GitHub releases/latest 跳转地址）。 */
    public static String resolveRedirect(String url) throws IOException {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) openConnection(u, "HEAD");
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            if (code / 100 == 3) {
                return conn.getHeaderField("Location");
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /** 下载 url 到 target（先写临时文件再原子移动）。 */
    public static void download(String url, Path target, Progress progress) throws IOException {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) openConnection(u, "GET");
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            Files.createDirectories(target.getParent());
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 忽略清理失败
            }
        }
    }

    private static URLConnection openConnection(URL url, String method) throws IOException {
        Proxy proxy = resolveProxy(url.getHost());
        URLConnection connection = proxy == null ? url.openConnection() : url.openConnection(proxy);
        if (connection instanceof HttpURLConnection http) {
            http.setRequestMethod(method);
        }
        return connection;
    }

    /**
     * 依次从环境变量与系统属性中解析 HTTP 代理。
     * 沙箱/企业网络下通常通过 HTTP_PROXY / HTTPS_PROXY 提供出口。
     */
    private static Proxy resolveProxy(String host) {
        String noProxy = firstNonEmpty(System.getenv("NO_PROXY"), System.getenv("no_proxy"));
        if (noProxy != null && matchesNoProxy(host, noProxy)) {
            return Proxy.NO_PROXY;
        }
        String value = firstNonEmpty(
                System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"),
                System.getenv("ALL_PROXY"), System.getenv("all_proxy"));
        if (value == null) {
            String proxyHost = firstNonEmpty(System.getProperty("https.proxyHost"), System.getProperty("http.proxyHost"));
            if (proxyHost == null) {
                return null;
            }
            String portText = firstNonEmpty(System.getProperty("https.proxyPort"), System.getProperty("http.proxyPort"));
            int port = portText == null ? 8080 : Integer.parseInt(portText.trim());
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port));
        }
        try {
            String normalized = value.contains("://") ? value : "http://" + value;
            URI uri = URI.create(normalized);
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean matchesNoProxy(String host, String noProxy) {
        for (String part : noProxy.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String suffix = entry.startsWith(".") ? entry.substring(1) : entry;
            if (host.equalsIgnoreCase(suffix) || host.toLowerCase().endsWith("." + suffix.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
