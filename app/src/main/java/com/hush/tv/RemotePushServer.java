package com.hush.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny LAN-only HTTP receiver for APK uploads.
 *
 * The phone browser posts the raw APK body to /upload?name=foo.apk. Keeping the
 * protocol this small avoids pulling in a web server or multipart parser.
 */
final class RemotePushServer {

    interface Listener {
        void onReady(String url);

        void onProgress(int percent);

        void onApkReceived(File apk);

        void onError(String message);
    }

    private static final String TAG = "HushPush";
    private static final int PREFERRED_PORT = 8765;
    private static final long MAX_UPLOAD_BYTES = 300L * 1024L * 1024L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object uploadLock = new Object();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private volatile boolean running;
    private String url;

    RemotePushServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (running) {
            postReady();
            return;
        }
        executor = Executors.newFixedThreadPool(3);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(PREFERRED_PORT), 8);
            } catch (IOException preferredPortBusy) {
                serverSocket.close();
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(0), 8);
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot bind push server", e);
            postError("无法启动接收服务，请检查网络");
            return;
        }

        running = true;
        cleanupOldUploads();
        String ip = findLocalIpv4();
        url = ip == null ? null : "http://" + ip + ":" + serverSocket.getLocalPort();
        postReady();

        acceptThread = new Thread(this::acceptLoop, "HushPushServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        url = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(5 * 60 * 1000);
                ExecutorService current = executor;
                if (current != null) {
                    current.execute(() -> handle(socket));
                } else {
                    closeQuietly(socket);
                }
            } catch (SocketException e) {
                if (running) {
                    Log.w(TAG, "Push socket stopped", e);
                }
                break;
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Push accept failed", e);
                    postError("接收服务异常，请重试");
                }
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket ignored = socket) {
            InputStream input = socket.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] request = requestLine.split(" ");
            if (request.length < 2) {
                sendText(socket.getOutputStream(), 400, "Bad Request");
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = readLine(input)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US),
                            header.substring(colon + 1).trim());
                }
            }

            String method = request[0];
            String path = request[1];
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendHtml(socket.getOutputStream(), uploadPage());
                return;
            }
            if ("GET".equals(method) && path.startsWith("/favicon")) {
                sendText(socket.getOutputStream(), 204, "");
                return;
            }
            if ("POST".equals(method) && path.startsWith("/upload")) {
                handleUpload(socket.getOutputStream(), input, headers, path);
                return;
            }
            sendText(socket.getOutputStream(), 404, "Not Found");
        } catch (IOException e) {
            Log.w(TAG, "Push request failed", e);
        }
    }

    private void handleUpload(OutputStream output, InputStream input, Map<String, String> headers,
                              String path) throws IOException {
        synchronized (uploadLock) {
            String lengthHeader = headers.get("content-length");
            long contentLength;
            try {
                contentLength = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
            } catch (NumberFormatException e) {
                contentLength = -1;
            }
            if (contentLength <= 0) {
                sendText(output, 411, "缺少文件长度");
                return;
            }
            if (contentLength > MAX_UPLOAD_BYTES) {
                sendText(output, 413, "APK 太大（上限 300 MB）");
                return;
            }

            String fileName = queryParam(path, "name");
            if (fileName == null || !fileName.toLowerCase(Locale.US).endsWith(".apk")) {
                sendText(output, 400, "只支持 APK 文件");
                return;
            }

            File dir = new File(context.getCacheDir(), "push");
            if (!dir.exists() && !dir.mkdirs()) {
                sendText(output, 500, "无法创建缓存目录");
                return;
            }
            File target = new File(dir, "upload-" + System.currentTimeMillis() + ".apk");
            FileOutputStream fileOutput = new FileOutputStream(target);
            long remaining = contentLength;
            long total = contentLength;
            int lastPercent = -1;
            byte[] buffer = new byte[64 * 1024];
            try {
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new IOException("upload ended early");
                    }
                    fileOutput.write(buffer, 0, read);
                    remaining -= read;
                    int percent = (int) ((total - remaining) * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        postProgress(percent);
                    }
                }
                fileOutput.flush();
            } catch (IOException e) {
                closeQuietly(fileOutput);
                target.delete();
                sendText(output, 400, "上传中断，请重试");
                return;
            } finally {
                closeQuietly(fileOutput);
            }

            if (!isApk(target)) {
                target.delete();
                sendText(output, 400, "不是有效的 APK 文件");
                return;
            }

            sendText(output, 200, "APK 已收到，正在发送安装请求。请在电视上确认安装。");
            postReceived(target);
        }
    }

    private void cleanupOldUploads() {
        File dir = new File(context.getCacheDir(), "push");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private boolean isApk(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private String queryParam(String path, String key) {
        int query = path.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String part : path.substring(query + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && key.equals(part.substring(0, eq))) {
                try {
                    return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String uploadPage() {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>HUSH TV 远程推送</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#08090b;color:#f2eee5;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center}"
                + "main{width:min(520px,92vw);padding:32px}h1{font-size:30px;margin:8px 0 8px;font-weight:500}"
                + ".brand{color:#e7a56a;letter-spacing:.18em;font-size:12px}.sub{color:#8b919a;font-size:14px;line-height:1.6}"
                + "input[type=file]{display:block;width:100%;margin:28px 0 18px;padding:16px;border:1px solid #2a2d33;"
                + "border-radius:14px;background:#121418;color:#c9c5bc;font-size:15px}"
                + "button{width:100%;padding:16px;border:0;border-radius:14px;background:#f1ede3;color:#0a0b0d;"
                + "font-size:17px;font-weight:600}button:disabled{opacity:.35}"
                + "#status{min-height:24px;margin:16px 0;color:#c9c5bc;font-size:14px;line-height:1.5}"
                + ".bar{height:3px;background:#24272d;border-radius:3px;overflow:hidden}.bar i{display:block;height:100%;width:0;"
                + "background:#e7a56a;transition:width .2s}.hint{margin-top:22px;color:#5e646d;font-size:12px;line-height:1.7}"
                + "</style><body><main><div class=\"brand\">HUSH / 留白</div><h1>远程推送</h1>"
                + "<p class=\"sub\">选择 APK 上传，电视收到后会自动请求安装。</p>"
                + "<input type=\"file\" id=\"file\" accept=\".apk,application/vnd.android.package-archive\">"
                + "<button id=\"send\" disabled>上传并安装</button><p id=\"status\"></p>"
                + "<div class=\"bar\"><i id=\"bar\"></i></div>"
                + "<p class=\"hint\">手机和电视需连接同一个 Wi-Fi。上传期间不要关闭电视上的远程推送页面。</p>"
                + "</main><script>"
                + "var f=document.getElementById('file'),b=document.getElementById('send'),s=document.getElementById('status'),bar=document.getElementById('bar');"
                + "f.onchange=function(){b.disabled=!f.files.length};"
                + "b.onclick=function(){var file=f.files[0];if(!file)return;if(!/\\.apk$/i.test(file.name)){s.textContent='请选择 APK 文件';return;}"
                + "b.disabled=true;s.textContent='正在上传…';var x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(file.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable){var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';s.textContent='正在上传 '+p+'%';}};"
                + "x.onload=function(){s.textContent=x.responseText||'已收到';bar.style.width='100%';};"
                + "x.onerror=function(){s.textContent='网络错误，请重试';b.disabled=false;};x.send(file);};"
                + "</script></body></html>";
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                output.write(value);
            }
            if (output.size() > 8192) {
                throw new IOException("header too long");
            }
        }
        if (value == -1 && output.size() == 0) {
            return null;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sendHtml(OutputStream output, String html) throws IOException {
        send(output, 200, "OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(OutputStream output, int code, String text) throws IOException {
        send(output, code, code == 200 ? "OK" : "Error", "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(OutputStream output, int code, String reason, String type, byte[] body)
            throws IOException {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private String findLocalIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (address.isSiteLocalAddress()) {
                        return host;
                    }
                    if (fallback == null) {
                        fallback = host;
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Cannot inspect network interfaces", e);
        }
        return fallback;
    }

    private void postReady() {
        main.post(() -> listener.onReady(url));
    }

    private void postProgress(int percent) {
        main.post(() -> listener.onProgress(percent));
    }

    private void postReceived(File file) {
        main.post(() -> listener.onApkReceived(file));
    }

    private void postError(String message) {
        main.post(() -> listener.onError(message));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
