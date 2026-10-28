package com.hush.tv;

import android.content.Context;
import android.os.Environment;
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
 * A small LAN-only HTTP receiver for phone/computer uploads.
 *
 * The browser posts the raw file body to /upload?name=foo.ext; keeping the
 * protocol this small avoids a multipart parser or a bundled web server.
 */
final class RemotePushServer {

    interface Listener {
        void onReady(String url, String directory);

        void onProgress(int percent);

        void onFileReceived(File file);

        void onError(String message);
    }

    private static final String TAG = "HushPush";
    private static final int PREFERRED_PORT = 8765;
    private static final long MAX_UPLOAD_BYTES = 300L * 1024L * 1024L;

    private final Context context;
    private final Listener listener;
    private final File uploadDir;
    private final boolean wallpaperMode;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object uploadLock = new Object();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private volatile boolean running;
    private String url;

    RemotePushServer(Context context, Listener listener) {
        this(context, listener, getPushDir(context), false);
    }

    RemotePushServer(Context context, Listener listener, File uploadDir, boolean wallpaperMode) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.uploadDir = uploadDir;
        this.wallpaperMode = wallpaperMode;
    }

    static File getPushDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            base = new File(context.getFilesDir(), "downloads");
        }
        File dir = new File(base, "HUSH");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static File getWallpaperDir(Context context) {
        File dir = new File(getPushDir(context), "wallpapers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
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
                sendText(output, 413, "文件太大（上限 300 MB）");
                return;
            }

            String requestedName = queryParam(path, "name");
            if (requestedName == null || requestedName.trim().isEmpty()) {
                sendText(output, 400, "缺少文件名");
                return;
            }
            String safeName = sanitizeName(requestedName);
            boolean apk = safeName.toLowerCase(Locale.US).endsWith(".apk");
            File target = uniqueFile(uploadDir, safeName);
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

            if (apk && !isApk(target)) {
                target.delete();
                sendText(output, 400, "不是有效的 APK 文件");
                return;
            }

            String result = "已收到：" + target.getName() + "\n"
                    + "保存到：" + target.getAbsolutePath() + "\n"
                    + (apk ? "正在请求安装，请在电视上确认。" : "文件已保存到电视。");
            sendText(output, 200, result);
            postFile(target);
        }
    }

    private File uniqueFile(File dir, String name) {
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(context.getFilesDir(), "HUSH");
            dir.mkdirs();
        }
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        while (target.exists()) {
            target = new File(dir, base + "-" + index + ext);
            index++;
        }
        return target;
    }

    private String sanitizeName(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 32) {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String cleaned = builder.toString().trim();
        if (cleaned.isEmpty()) {
            cleaned = "file";
        }
        if (cleaned.length() > 80) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 12 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 80 - ext.length()) + ext;
        }
        return cleaned;
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
        String dir = uploadDir.getAbsolutePath();
        if (wallpaperMode) {
            return wallpaperPage(dir);
        }
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>HUSH TV 远程推送</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#08090b;color:#f2eee5;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center}"
                + "main{width:min(560px,92vw);padding:32px}h1{font-size:30px;margin:8px 0;font-weight:500}"
                + ".brand{color:#e7a56a;letter-spacing:.18em;font-size:12px}.sub{color:#8b919a;font-size:14px;line-height:1.6}"
                + "input[type=file]{display:block;width:100%;margin:24px 0 16px;padding:16px;border:1px solid #2a2d33;"
                + "border-radius:14px;background:#121418;color:#c9c5bc;font-size:15px}"
                + "button{width:100%;padding:16px;border:0;border-radius:14px;background:#f1ede3;color:#0a0b0d;"
                + "font-size:17px;font-weight:600}button:disabled{opacity:.35}"
                + "#status{min-height:24px;margin:16px 0;color:#c9c5bc;font-size:14px;line-height:1.55;white-space:pre-wrap}"
                + ".bar{height:3px;background:#24272d;border-radius:3px;overflow:hidden}.bar i{display:block;height:100%;width:0;"
                + "background:#e7a56a;transition:width .2s}.hint{margin-top:20px;color:#5e646d;font-size:12px;line-height:1.7}"
                + ".path{color:#9a8f80;word-break:break-all}"
                + "</style><body><main><div class=\"brand\">HUSH / 留白</div><h1>远程推送</h1>"
                + "<p class=\"sub\">选择文件上传。APK 会自动拉起电视安装，其它文件保存到电视。</p>"
                + "<input type=\"file\" id=\"file\"><button id=\"send\" disabled>上传文件</button>"
                + "<p id=\"status\"></p><div class=\"bar\"><i id=\"bar\"></i></div>"
                + "<p class=\"hint\">保存到电视：<span class=\"path\">" + escapeHtml(dir) + "</span></p>"
                + "<p class=\"hint\">手机和电视需连接同一个 Wi-Fi。上传期间不要关闭电视上的远程推送页面。</p>"
                + "</main><script>"
                + "var f=document.getElementById('file'),b=document.getElementById('send'),s=document.getElementById('status'),bar=document.getElementById('bar');"
                + "f.onchange=function(){b.disabled=!f.files.length};"
                + "b.onclick=function(){var file=f.files[0];if(!file)return;"
                + "b.disabled=true;s.textContent='正在上传…';var x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(file.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable){var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';s.textContent='正在上传 '+p+'%';}};"
                + "x.onload=function(){s.textContent=x.responseText||'已收到';bar.style.width='100%';b.disabled=false;};"
                + "x.onerror=function(){s.textContent='网络错误，请重试';b.disabled=false;};x.send(file);};"
                + "</script></body></html>";
    }

    private String wallpaperPage(String dir) {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>HUSH TV 壁纸推送</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#08090b;color:#f2eee5;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center}"
                + "main{width:min(560px,92vw);padding:32px}h1{font-size:30px;margin:8px 0;font-weight:500}"
                + ".brand{color:#e7a56a;letter-spacing:.18em;font-size:12px}.sub{color:#8b919a;font-size:14px;line-height:1.6}"
                + "input[type=file]{display:block;width:100%;margin:24px 0 16px;padding:16px;border:1px solid #2a2d33;"
                + "border-radius:14px;background:#121418;color:#c9c5bc;font-size:15px}"
                + "button{width:100%;padding:16px;border:0;border-radius:14px;background:#f1ede3;color:#0a0b0d;"
                + "font-size:17px;font-weight:600}button:disabled{opacity:.35}"
                + "#status{min-height:24px;margin:16px 0;color:#c9c5bc;font-size:14px;line-height:1.55;white-space:pre-wrap}"
                + ".bar{height:3px;background:#24272d;border-radius:3px;overflow:hidden}.bar i{display:block;height:100%;width:0;"
                + "background:#e7a56a;transition:width .2s}.hint{margin-top:20px;color:#5e646d;font-size:12px;line-height:1.7}"
                + ".path{color:#9a8f80;word-break:break-all}"
                + "</style><body><main><div class=\"brand\">HUSH / 留白</div><h1>壁纸推送</h1>"
                + "<p class=\"sub\">选择一张图片上传。上传成功后电视会立即把它设为壁纸，并加入壁纸历史。</p>"
                + "<input type=\"file\" id=\"file\" accept=\"image/*\"><button id=\"send\" disabled>上传图片</button>"
                + "<p id=\"status\"></p><div class=\"bar\"><i id=\"bar\"></i></div>"
                + "<p class=\"hint\">保存到电视：<span class=\"path\">" + escapeHtml(dir) + "</span></p>"
                + "<p class=\"hint\">手机和电视需连接同一个 Wi-Fi。上传期间不要关闭电视上的壁纸设置弹窗。</p>"
                + "</main><script>"
                + "var f=document.getElementById('file'),b=document.getElementById('send'),s=document.getElementById('status'),bar=document.getElementById('bar');"
                + "f.onchange=function(){b.disabled=!f.files.length};"
                + "b.onclick=function(){var file=f.files[0];if(!file)return;"
                + "b.disabled=true;s.textContent='正在上传…';var x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(file.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable){var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';s.textContent='正在上传 '+p+'%';}};"
                + "x.onload=function(){s.textContent=x.responseText||'已收到';bar.style.width='100%';b.disabled=false;};"
                + "x.onerror=function(){s.textContent='网络错误，请重试';b.disabled=false;};x.send(file);};"
                + "</script></body></html>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        String dir = uploadDir.getAbsolutePath();
        main.post(() -> listener.onReady(url, dir));
    }

    private void postProgress(int percent) {
        main.post(() -> listener.onProgress(percent));
    }

    private void postFile(File file) {
        main.post(() -> listener.onFileReceived(file));
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
