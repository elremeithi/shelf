package app.shelf.sync;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Syncs one phone folder with one server folder.
 *
 * MOVE: every phone file is uploaded, the server confirms it stored exactly
 * those bytes (SHA-256), and only then is the phone copy deleted. Files that
 * are already on the server with identical content are just deleted locally.
 *
 * TWO_WAY: new and changed files go both ways. Nothing is ever deleted by a
 * sync, on either side: a file deleted on one side is left alone on the other.
 * The server never loses a version either: when a phone edit replaces a server
 * file, the old one is moved to the folder's hidden .shelf-versions, and when
 * both sides changed the same file, both versions are kept.
 */
public class SyncEngine {
    public enum Mode { MOVE, TWO_WAY }

    /** Files changed this recently may still be being written (a video recording, a download). */
    public static final long SETTLE_MS = 60_000;
    static final long MTIME_SLACK = 2_000;

    public interface Listener {
        void onStatus(String text);
        void onFile(String path, long done, long total);
    }

    public static class Result {
        public int uploaded, downloaded, deletedLocal, conflicts, failed, skippedRecent;
        public long bytes;
        public final List<String> errors = new ArrayList<>();
        public String fatal;

        public String summary() {
            if (fatal != null) return fatal;
            List<String> p = new ArrayList<>();
            if (uploaded > 0) p.add(uploaded + " uploaded");
            if (downloaded > 0) p.add(downloaded + " downloaded");
            if (deletedLocal > 0) p.add(deletedLocal + " removed from phone");
            if (conflicts > 0) p.add(conflicts + " conflicts kept as copies");
            if (failed > 0) p.add(failed + " failed");
            if (skippedRecent > 0) p.add(skippedRecent + " still being written, next time");
            return p.isEmpty() ? "Up to date" : String.join(", ", p);
        }
    }

    private final ShelfClient client;
    private final LocalStore local;
    private final StateStore state;
    private final Listener listener;
    private final ShelfClient.Cancel cancel;
    private final long now;
    private Result res;

    public SyncEngine(ShelfClient client, LocalStore local, StateStore state, Listener listener, ShelfClient.Cancel cancel) {
        this(client, local, state, listener, cancel, System.currentTimeMillis());
    }

    SyncEngine(ShelfClient client, LocalStore local, StateStore state, Listener listener, ShelfClient.Cancel cancel, long now) {
        this.client = client;
        this.local = local;
        this.state = state;
        this.listener = listener;
        this.cancel = cancel;
        this.now = now;
    }

    public Result run(long pairId, String remoteRoot, Mode mode) {
        res = new Result();
        String root = normalize(remoteRoot);
        try {
            status("Checking the server folder");
            ShelfClient.Tree tree = client.tree(root);
            if (!tree.exists) {
                status("Creating " + root + " on the server");
                client.mkdirs(root);
                tree = client.tree(root);
            }
            if (mode == Mode.MOVE) move(root, tree);
            else {
                int conflicts = twoWay(pairId, root, tree);
                // Conflict copies were created on the server; one more pass brings them to the phone.
                if (conflicts > 0 && !cancelled()) twoWay(pairId, root, client.tree(root));
            }
        } catch (IOException e) {
            res.fatal = cancelled() ? "Stopped" : message(e);
        }
        return res;
    }

    // ---- move to server ------------------------------------------------------

    private void move(String root, ShelfClient.Tree tree) throws IOException {
        if (!tree.writable) throw new IOException("Shelf cannot write to " + root + " on the server");
        status("Reading the phone folder");
        Map<String, LocalStore.Item> files = local.list();
        int i = 0, streak = 0;
        for (String p : new TreeSet<>(files.keySet())) {
            if (cancelled()) throw new IOException("Cancelled");
            LocalStore.Item f = files.get(p);
            i++;
            if (now - f.mtime < SETTLE_MS) { res.skippedRecent++; continue; }
            status("Moving " + i + " of " + files.size() + ": " + p);
            try {
                String sha = ShelfClient.sha256(local.open(f), cancel);
                long[] r = tree.files.get(p);
                boolean onServer = r != null && r[0] == f.size && sha.equals(client.hash(join(root, p)));
                if (!onServer) {
                    ShelfClient.UploadResult u = upload(root, p, f, sha, "keep");
                    if (!sha.equalsIgnoreCase(u.sha256)) throw new IOException("checksum mismatch");
                    res.uploaded++;
                    res.bytes += f.size;
                }
                // Only now, with the server's copy confirmed, does the phone copy go.
                local.delete(f);
                res.deletedLocal++;
                streak = 0;
            } catch (IOException e) {
                if (failed(p, e, ++streak)) throw e;
            }
        }
        local.removeEmptyFolders();
    }

    // ---- two-way -------------------------------------------------------------

    private int twoWay(long pairId, String root, ShelfClient.Tree tree) throws IOException {
        status("Reading the phone folder");
        Map<String, LocalStore.Item> files = local.list();
        Map<String, StateStore.Rec> prev = state.load(pairId);
        Map<String, StateStore.Rec> next = new HashMap<>(prev);
        TreeSet<String> all = new TreeSet<>(files.keySet());
        all.addAll(tree.files.keySet());
        all.addAll(prev.keySet());
        int i = 0, streak = 0, conflicts = 0, sinceSave = 0;
        for (String p : all) {
            if (cancelled()) break;
            i++;
            LocalStore.Item l = files.get(p);
            long[] r = tree.files.get(p);
            StateStore.Rec was = prev.get(p);
            try {
                // A file changed in the last minute may still be being written. Files this
                // app wrote itself (matching the last sync) are not waited for.
                boolean known = was != null && l != null && same(l.size, l.mtime, was.localSize, was.localMtime);
                if (l != null && !known && now - l.mtime < SETTLE_MS) { res.skippedRecent++; continue; }
                if (l != null && r != null) {
                    boolean lc = was == null || !same(l.size, l.mtime, was.localSize, was.localMtime);
                    boolean rc = was == null || !same(r[0], r[1], was.remoteSize, was.remoteMtime);
                    if (!lc && !rc) continue;
                    if (was == null || (lc && rc)) {
                        // New on both sides, or changed on both: equal content is fine.
                        if (l.size == r[0] && (near(l.mtime, r[1]) || sameContent(root, p, l))) {
                            next.put(p, rec(l, r[0], r[1]));
                        } else {
                            conflict(root, p, l, r, next);
                            conflicts++;
                        }
                    } else if (lc) {
                        status(i + " of " + all.size() + ": uploading " + p);
                        if (!tree.writable) continue;
                        upload(root, p, l, null, "backup");
                        res.uploaded++;
                        res.bytes += l.size;
                        next.put(p, rec(l, l.size, l.mtime));
                    } else {
                        status(i + " of " + all.size() + ": downloading " + p);
                        next.put(p, rec(download(root, p, r), r[0], r[1]));
                    }
                } else if (l != null) {
                    // On the phone only. If it was synced before, the server copy was deleted there: leave it.
                    if (was != null && same(l.size, l.mtime, was.localSize, was.localMtime)) continue;
                    if (!tree.writable) continue;
                    status(i + " of " + all.size() + ": uploading " + p);
                    ShelfClient.UploadResult u = upload(root, p, l, null, "keep");
                    res.uploaded++;
                    res.bytes += l.size;
                    if (u.path.equals(join(root, p))) next.put(p, rec(l, l.size, l.mtime));
                } else if (r != null) {
                    // On the server only. If it was synced before, it was deleted on the phone: leave it.
                    if (was != null && same(r[0], r[1], was.remoteSize, was.remoteMtime)) continue;
                    status(i + " of " + all.size() + ": downloading " + p);
                    next.put(p, rec(download(root, p, r), r[0], r[1]));
                } else {
                    next.remove(p); // gone on both sides
                }
                streak = 0;
                if (++sinceSave >= 25) { state.save(pairId, next); sinceSave = 0; }
            } catch (IOException e) {
                if (failed(p, e, ++streak)) { state.save(pairId, next); throw e; }
            }
        }
        state.save(pairId, next);
        return conflicts;
    }

    /** Both sides changed: keep the phone version on the server under a new name, then take the server's. */
    private void conflict(String root, String p, LocalStore.Item l, long[] r, Map<String, StateStore.Rec> next) throws IOException {
        status("Both sides changed " + p + ", keeping both");
        ShelfClient.UploadResult u = upload(root, p, l, null, "keep");
        res.uploaded++;
        res.bytes += l.size;
        res.conflicts++;
        if (!u.duplicate) next.put(p, rec(download(root, p, r), r[0], r[1]));
    }

    private boolean sameContent(String root, String p, LocalStore.Item l) throws IOException {
        status("Comparing " + p);
        return ShelfClient.sha256(local.open(l), cancel).equals(client.hash(join(root, p)));
    }

    // ---- transfers -----------------------------------------------------------

    private ShelfClient.UploadResult upload(String root, String p, LocalStore.Item f, String sha, String mode) throws IOException {
        if (sha == null) sha = ShelfClient.sha256(local.open(f), cancel);
        int slash = p.lastIndexOf('/');
        String dir = slash < 0 ? root : join(root, p.substring(0, slash));
        String name = slash < 0 ? p : p.substring(slash + 1);
        final String path = p;
        return client.upload(dir, name, f.size, f.mtime, sha, mode, () -> local.open(f),
                (done, total) -> listener.onFile(path, done, total), cancel);
    }

    private LocalStore.Item download(String root, String p, long[] r) throws IOException {
        long have = local.partialSize(p);
        if (have > r[0]) have = 0;
        try (OutputStream out = local.openPartial(p, have > 0)) {
            final String path = p;
            client.download(join(root, p), have, r[0], out, (done, total) -> listener.onFile(path, done, total), cancel);
        }
        LocalStore.Item f = local.commitPartial(p);
        res.downloaded++;
        res.bytes += r[0];
        return f;
    }

    // ---- helpers -------------------------------------------------------------

    /** Records a failure. Returns true when the run should stop (the connection looks gone). */
    private boolean failed(String p, IOException e, int streak) {
        if (cancelled()) return true;
        res.failed++;
        if (res.errors.size() < 20) res.errors.add(p + ": " + message(e));
        boolean network = !(e instanceof ShelfClient.ApiException);
        return network && streak >= 3;
    }

    static String message(IOException e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException)
            return "Cannot reach the server (" + (m == null ? e.getClass().getSimpleName() : m) + ")";
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static StateStore.Rec rec(LocalStore.Item l, long rs, long rm) { return new StateStore.Rec(l.size, l.mtime, rs, rm); }

    static boolean near(long a, long b) { return Math.abs(a - b) <= MTIME_SLACK; }

    static boolean same(long size, long mtime, long size2, long mtime2) { return size == size2 && near(mtime, mtime2); }

    static String normalize(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new IllegalArgumentException("invalid server folder");
            sb.append('/').append(seg);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    static String join(String root, String p) { return root.equals("/") ? "/" + p : root + "/" + p; }

    private boolean cancelled() { return cancel != null && cancel.cancelled(); }

    private void status(String s) { listener.onStatus(s); }
}
