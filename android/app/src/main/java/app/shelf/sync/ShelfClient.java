package app.shelf.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to a Shelf server over its HTTP API. Uploads are sent in chunks that
 * the server appends to a hidden part file, so an interrupted transfer resumes
 * where it stopped; the server checks size and SHA-256 before the file appears.
 */
public class ShelfClient {
    public static final int CHUNK = 8 * 1024 * 1024;

    public interface TokenListener { void onToken(String token); }

    public interface Progress {
        /** Bytes of the current file transferred so far. */
        void onBytes(long done, long total);
    }

    public interface Cancel { boolean cancelled(); }

    public static class ApiException extends IOException {
        public final int status;
        public ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    public static class Entry {
        public final String name;
        public final boolean dir;
        public final long size, mtime;
        Entry(String name, boolean dir, long size, long mtime) { this.name = name; this.dir = dir; this.size = size; this.mtime = mtime; }
    }

    public static class Tree {
        public boolean exists, writable;
        public final Map<String, long[]> files = new LinkedHashMap<>(); // path -> {size, mtime}
        public final List<String> dirs = new ArrayList<>();
    }

    public static class UploadResult {
        public String path, sha256;
        public long size;
        public boolean duplicate;
    }

    private final String base;
    private final String user, password;
    private volatile String token;
    private TokenListener tokenListener;

    public ShelfClient(String baseUrl, String user, String password, String token) {
        String b = baseUrl.trim();
        if (!b.matches("(?i)^https?://.*")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.user = user;
        this.password = password;
        this.token = token;
    }

    public String baseUrl() { return base; }
    public String token() { return token; }
    public void setTokenListener(TokenListener l) { tokenListener = l; }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** URL path for a server file, each segment encoded. */
    public String fileUrl(String path) {
        StringBuilder sb = new StringBuilder(base).append("/files");
        for (String seg : path.split("/")) if (!seg.isEmpty()) sb.append('/').append(enc(seg));
        return sb.toString();
    }

    // ---- low level -----------------------------------------------------------

    private HttpURLConnection open(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("X-Shelf", "1");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private JSONObject result(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        JSONObject j;
        try { j = body.isEmpty() ? new JSONObject() : new JSONObject(body); }
        catch (Exception e) {
            if (code >= 400) throw new ApiException(code, "HTTP " + code + " from the server");
            throw new IOException("The server sent an unexpected answer. Is this the address of a Shelf server?");
        }
        if (code >= 400) {
            ApiException ex = new ApiException(code, j.optString("error", "HTTP " + code));
            if (code == 409 && j.has("offset")) throw new OffsetException(j.optLong("offset"));
            throw ex;
        }
        return j;
    }

    static class OffsetException extends ApiException {
        final long offset;
        OffsetException(long offset) { super(409, "offset mismatch"); this.offset = offset; }
    }

    private interface Call { JSONObject run() throws IOException; }

    /** Runs a request, logging in again once if the token has expired. */
    private JSONObject authed(Call call) throws IOException {
        try {
            return call.run();
        } catch (ApiException e) {
            if (e.status != 401 || user == null || user.isEmpty()) throw e;
            login();
            return call.run();
        }
    }

    private JSONObject get(String pathAndQuery) throws IOException {
        return authed(() -> result(open("GET", base + pathAndQuery)));
    }

    private JSONObject postJson(String path, JSONObject body) throws IOException {
        return authed(() -> {
            HttpURLConnection c = open("POST", base + path);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            return result(c);
        });
    }

    // ---- API -----------------------------------------------------------------

    /** Checks the address and logs in. Returns whether the server is read-only. */
    public boolean connect() throws IOException {
        JSONObject me;
        try { me = result(open("GET", base + "/api/me")); }
        catch (java.net.UnknownHostException e) { throw new IOException("Cannot find the server " + e.getMessage()); }
        if (!"shelf".equals(me.optString("app")) || me.optInt("sync") < 1)
            throw new IOException("This server does not support sync. Update Shelf on the server to the latest version.");
        if (!me.optBoolean("authed")) login();
        return me.optBoolean("readonly");
    }

    public void login() throws IOException {
        JSONObject in = new JSONObject();
        try { in.put("user", user); in.put("password", password); } catch (Exception ignored) {}
        HttpURLConnection c = open("POST", base + "/api/login");
        c.setRequestProperty("Authorization", "");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = in.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        JSONObject j = result(c);
        token = j.optString("token", null);
        if (tokenListener != null) tokenListener.onToken(token);
    }

    /** One folder level, for the folder picker. */
    public List<Entry> list(String path) throws IOException {
        JSONObject j = get("/api/ls?p=" + enc(path));
        JSONArray a = j.optJSONArray("entries");
        List<Entry> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            out.add(new Entry(e.optString("n"), e.optBoolean("d"), e.optLong("s"), e.optLong("m")));
        }
        return out;
    }

    public Tree tree(String path) throws IOException {
        JSONObject j = get("/api/sync/tree?p=" + enc(path));
        Tree t = new Tree();
        t.exists = j.optBoolean("exists");
        t.writable = j.optBoolean("writable", true);
        JSONArray f = j.optJSONArray("files");
        if (f != null) for (int i = 0; i < f.length(); i++) {
            JSONObject e = f.optJSONObject(i);
            t.files.put(e.optString("p"), new long[]{e.optLong("s"), e.optLong("m")});
        }
        JSONArray d = j.optJSONArray("dirs");
        if (d != null) for (int i = 0; i < d.length(); i++) t.dirs.add(d.optString(i));
        return t;
    }

    public String hash(String path) throws IOException {
        return get("/api/sync/hash?p=" + enc(path)).optString("sha256");
    }

    public void mkdirs(String path) throws IOException {
        JSONObject in = new JSONObject();
        try { in.put("path", path); } catch (Exception ignored) {}
        postJson("/api/sync/mkdir", in);
    }

    public interface Source { InputStream open() throws IOException; }

    /**
     * Uploads one file into dir/name (name may contain sub-folders). In "keep"
     * mode a different file already at that name is kept and the upload gets a
     * free name; in "backup" mode the old file is moved to .shelf-versions.
     */
    public UploadResult upload(String dir, String name, long size, long mtime, String sha256, String mode,
                               Source src, Progress progress, Cancel cancel) throws IOException {
        String id = uploadId(dir + "/" + name, size, mtime, sha256);
        String q = "/api/sync/upload?p=" + enc(dir) + "&name=" + enc(name) + "&id=" + id;
        long offset = get(q).optLong("offset");
        if (offset > size) offset = 0;
        int failures = 0;
        while (true) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
            long len = Math.min(CHUNK, size - offset);
            boolean last = offset + len >= size;
            String url = base + q + "&offset=" + offset + (last
                    ? "&final=1&size=" + size + "&mtime=" + mtime + "&sha256=" + sha256 + "&mode=" + mode : "");
            try {
                final long start = offset;
                JSONObject j = authed(() -> sendChunk(url, src, start, len, size, progress));
                if (last) {
                    UploadResult r = new UploadResult();
                    r.path = j.optString("path");
                    r.sha256 = j.optString("sha256");
                    r.size = j.optLong("size");
                    r.duplicate = j.optBoolean("duplicate");
                    if (!sha256.equalsIgnoreCase(r.sha256)) throw new IOException("Server stored different content than was sent");
                    return r;
                }
                offset = j.optLong("offset", offset + len);
                failures = 0;
            } catch (OffsetException e) {
                offset = e.offset; // the server tells us where to continue
            } catch (ApiException e) {
                if (e.status < 500) throw e;
                if (++failures > 5) throw e;
                sleep(failures);
                offset = get(q).optLong("offset");
            } catch (IOException e) {
                if (cancel != null && cancel.cancelled()) throw e;
                if (++failures > 5) throw e;
                sleep(failures);
                offset = get(q).optLong("offset");
            }
        }
    }

    private JSONObject sendChunk(String url, Source src, long offset, long len, long total, Progress progress) throws IOException {
        HttpURLConnection c = open("PUT", url);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(len);
        try (InputStream in = src.open(); OutputStream out = c.getOutputStream()) {
            skipFully(in, offset);
            byte[] buf = new byte[65536];
            long left = len, done = offset;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("The file got shorter while it was being uploaded");
                out.write(buf, 0, n);
                left -= n;
                done += n;
                if (progress != null) progress.onBytes(done, total);
            }
        }
        return result(c);
    }

    static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (buf == null) buf = new byte[65536];
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r < 0) throw new IOException("Unexpected end of file");
                s = r;
            }
            n -= s;
        }
    }

    /** Downloads a server file starting at offset into out (resume with Range). */
    public void download(String path, long offset, long size, OutputStream out, Progress progress, Cancel cancel) throws IOException {
        int failures = 0;
        long done = offset;
        while (true) {
            try {
                HttpURLConnection c = open("GET", fileUrl(path));
                if (done > 0) c.setRequestProperty("Range", "bytes=" + done + "-");
                int code = c.getResponseCode();
                if (code == 401 && user != null && !user.isEmpty()) { login(); continue; }
                if (code == 416 && done >= size) return;
                if (code >= 400) {
                    String msg = "HTTP " + code;
                    try { msg = new JSONObject(readAll(c.getErrorStream())).optString("error", msg); } catch (Exception ignored) {}
                    throw new ApiException(code, "download failed: " + msg);
                }
                if (done > 0 && code != 206) throw new IOException("The server ignored the resume request");
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        if (progress != null) progress.onBytes(done, size);
                    }
                }
                if (done != size) throw new IOException("Download ended early");
                return;
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                if ((cancel != null && cancel.cancelled()) || ++failures > 5) throw e;
                sleep(failures);
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    private static void sleep(int attempt) {
        try { Thread.sleep(Math.min(30000, 1000L << attempt)); } catch (InterruptedException ignored) {}
    }

    /** A stable id, so an upload resumes after the app or phone restarts. */
    static String uploadId(String path, long size, long mtime, String sha) {
        return sha256Hex((path + "|" + size + "|" + mtime + "|" + sha).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String sha256Hex(byte[] b) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String sha256(InputStream in, Cancel cancel) throws IOException {
        try (InputStream is = in) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
