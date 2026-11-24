package app.shelf.sync;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Runs the sync engine against a real Shelf server:
 *   SHELF_TEST_URL=http://127.0.0.1:8097 SHELF_TEST_DATA=/path/served/by/shelf SHELF_TEST_USER=u SHELF_TEST_PASS=p
 * The phone side is a plain folder here; on the phone it is a storage-access-framework tree.
 */
public class SyncEngineTest {
    static final long OLD = System.currentTimeMillis() - 3_600_000;

    String url = System.getenv("SHELF_TEST_URL"), data = System.getenv("SHELF_TEST_DATA");
    ShelfClient client;
    File phone, server;
    MemState state = new MemState();
    List<String> log = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("set SHELF_TEST_URL and SHELF_TEST_DATA to run against a server", url != null && !url.isEmpty() && data != null);
        client = new ShelfClient(url, System.getenv("SHELF_TEST_USER"), System.getenv("SHELF_TEST_PASS"), null);
        client.connect();
        String run = "t" + System.nanoTime();
        phone = Files.createTempDirectory("phone").toFile();
        server = new File(data, run);
        remote = "/" + run + "/Backup/Phone"; // does not exist yet: the sync must create it
    }

    String remote;

    SyncEngine.Result sync(SyncEngine.Mode mode) { return sync(mode, () -> false); }

    SyncEngine.Result sync(SyncEngine.Mode mode, ShelfClient.Cancel cancel) {
        SyncEngine.Listener l = new SyncEngine.Listener() {
            public void onStatus(String t) { log.add(t); }
            public void onFile(String p, long d, long t) {}
        };
        SyncEngine.Result r = new SyncEngine(client, new DirStore(phone), state, l, cancel).run(1, remote, mode);
        System.out.println(mode + ": " + r.summary() + (r.errors.isEmpty() ? "" : " " + r.errors));
        return r;
    }

    File srv(String p) { return new File(server, "Backup/Phone/" + p); }

    // ---- move to server ------------------------------------------------------

    @Test
    public void moveUploadsVerifiesThenDeletesLocal() throws Exception {
        byte[] big = random(20_000_000); // several chunks
        put(phone, "IMG_1.jpg", "photo one".getBytes(), OLD);
        put(phone, "Videos/clip 1.mp4", big, OLD);
        put(phone, "same.txt", "identical".getBytes(), OLD);
        put(phone, "clash.txt", "phone version".getBytes(), OLD);
        put(phone, "ünïcødé name.txt", "u".getBytes(), OLD);
        put(phone, "recording.mp4", "still being written".getBytes(), System.currentTimeMillis());
        put(srv(""), "same.txt", "identical".getBytes(), OLD);
        put(srv(""), "clash.txt", "server version".getBytes(), OLD);
        put(srv(""), "server-only.txt", "keep me".getBytes(), OLD);

        SyncEngine.Result r = sync(SyncEngine.Mode.MOVE);
        assertNull(r.fatal);
        assertEquals(0, r.failed);
        assertEquals(4, r.uploaded);   // IMG_1, clip, clash (as a copy), unicode
        assertEquals(5, r.deletedLocal);
        assertEquals(1, r.skippedRecent);

        assertArrayEquals(big, read(srv("Videos/clip 1.mp4")));
        assertEquals("photo one", text(srv("IMG_1.jpg")));
        assertEquals("server version", text(srv("clash.txt")));   // the server file survives
        assertEquals("phone version", text(srv("clash (2).txt"))); // and the phone one is kept beside it
        assertEquals("keep me", text(srv("server-only.txt")));
        assertEquals("identical", text(srv("same.txt")));
        assertEquals(OLD / 1000, srv("IMG_1.jpg").lastModified() / 1000); // dates are kept
        assertEquals(List.of("recording.mp4"), names(phone));            // only the busy file is left
        assertFalse(new File(phone, "Videos").exists());                 // emptied folders are tidied
    }

    @Test
    public void interruptedUploadResumes() throws Exception {
        byte[] big = random(30_000_000);
        put(phone, "big.bin", big, OLD);
        AtomicBoolean stop = new AtomicBoolean();
        SyncEngine.Listener l = new SyncEngine.Listener() {
            public void onStatus(String t) {}
            public void onFile(String p, long done, long total) { if (done > 12_000_000) stop.set(true); }
        };
        SyncEngine.Result first = new SyncEngine(client, new DirStore(phone), state, l, stop::get).run(1, remote, SyncEngine.Mode.MOVE);
        assertEquals("Stopped", first.fatal);
        assertTrue(new File(phone, "big.bin").exists());
        assertFalse(srv("big.bin").exists());
        File[] parts = srv("").listFiles((d, n) -> n.startsWith(".shelf-part-"));
        assertEquals(1, parts.length);
        long kept = parts[0].length();
        assertTrue("part file holds the finished chunks: " + kept, kept >= ShelfClient.CHUNK);

        SyncEngine.Result second = sync(SyncEngine.Mode.MOVE);
        assertEquals(1, second.uploaded);
        assertArrayEquals(big, read(srv("big.bin")));
        assertFalse(new File(phone, "big.bin").exists());
        assertEquals(0, srv("").listFiles((d, n) -> n.startsWith(".shelf-part-")).length);
    }

    // ---- two-way -------------------------------------------------------------

    @Test
    public void twoWayNeverDeletesAndKeepsEveryVersion() throws Exception {
        put(phone, "phone.txt", "from phone".getBytes(), OLD);
        put(phone, "Album/both.jpg", "same bytes".getBytes(), OLD);
        put(phone, "differs.txt", "phone edit".getBytes(), OLD);
        put(srv(""), "Album/both.jpg", "same bytes".getBytes(), OLD + 5000);
        put(srv(""), "server.txt", "from server".getBytes(), OLD);
        put(srv(""), "differs.txt", "server edit".getBytes(), OLD);

        SyncEngine.Result r = sync(SyncEngine.Mode.TWO_WAY);
        assertNull(r.fatal);
        assertEquals(0, r.failed);
        assertEquals(1, r.conflicts);
        assertEquals("from phone", text(srv("phone.txt")));
        assertEquals("from server", text(new File(phone, "server.txt")));
        // Both versions of the conflicting file end up on both sides.
        assertEquals("server edit", text(srv("differs.txt")));
        assertEquals("phone edit", text(srv("differs (2).txt")));
        assertEquals("server edit", text(new File(phone, "differs.txt")));
        assertEquals("phone edit", text(new File(phone, "differs (2).txt")));

        SyncEngine.Result again = sync(SyncEngine.Mode.TWO_WAY);
        assertEquals("Up to date", again.summary());

        // A phone edit replaces the server file, and the old one goes to .shelf-versions.
        put(phone, "phone.txt", "edited on phone".getBytes(), OLD + 60_000);
        // A server edit comes down to the phone.
        put(srv(""), "server.txt", "edited on server".getBytes(), OLD + 60_000);
        r = sync(SyncEngine.Mode.TWO_WAY);
        assertEquals(1, r.uploaded);
        assertEquals(1, r.downloaded);
        assertEquals("edited on phone", text(srv("phone.txt")));
        File[] versions = srv(".shelf-versions").listFiles();
        assertEquals(1, versions.length);
        assertEquals("from phone", text(versions[0]));
        assertEquals("edited on server", text(new File(phone, "server.txt")));

        // Deleting on the phone never deletes on the server, and the file is not brought back.
        assertTrue(new File(phone, "phone.txt").delete());
        // Deleting on the server is not undone by re-uploading, and the phone keeps its copy.
        assertTrue(srv("server.txt").delete());
        r = sync(SyncEngine.Mode.TWO_WAY);
        assertEquals("Up to date", r.summary());
        assertEquals("edited on phone", text(srv("phone.txt")));
        assertFalse(new File(phone, "phone.txt").exists());
        assertEquals("edited on server", text(new File(phone, "server.txt")));
        assertFalse(srv("server.txt").exists());

        // New files after that still flow both ways.
        put(phone, "Album/new.jpg", "new photo".getBytes(), OLD + 120_000);
        put(srv(""), "Album/from web.png", "uploaded in the browser".getBytes(), OLD + 120_000);
        r = sync(SyncEngine.Mode.TWO_WAY);
        assertEquals(1, r.uploaded);
        assertEquals(1, r.downloaded);
        assertEquals("new photo", text(srv("Album/new.jpg")));
        assertEquals("uploaded in the browser", text(new File(phone, "Album/from web.png")));
    }

    @Test
    public void wrongPasswordIsReported() {
        ShelfClient bad = new ShelfClient(url, "u", "wrong-password", null);
        try { bad.connect(); fail("login should fail"); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("wrong")); }
    }

    // ---- test doubles ----------------------------------------------------------

    /** A plain folder standing in for the phone's storage. */
    static class DirStore implements LocalStore {
        final File root;
        DirStore(File root) { this.root = root; }

        public Map<String, LocalStore.Item> list() {
            Map<String, LocalStore.Item> out = new TreeMap<>();
            walk(root, "", out);
            return out;
        }

        void walk(File dir, String prefix, Map<String, LocalStore.Item> out) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File f : kids) {
                if (f.getName().startsWith(".")) continue;
                String p = prefix + f.getName();
                if (f.isDirectory()) walk(f, p + "/", out);
                else out.put(p, new LocalStore.Item(p, f.length(), f.lastModified(), f));
            }
        }

        public InputStream open(LocalStore.Item f) throws IOException { return new FileInputStream((File) f.handle); }

        File part(String p) {
            File f = new File(root, p);
            return new File(f.getParentFile(), "." + f.getName() + ".shelfpart");
        }

        public long partialSize(String p) { return part(p).length(); }

        public OutputStream openPartial(String p, boolean append) throws IOException {
            part(p).getParentFile().mkdirs();
            return new FileOutputStream(part(p), append);
        }

        public LocalStore.Item commitPartial(String p) throws IOException {
            File f = new File(root, p);
            Files.move(part(p).toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new LocalStore.Item(p, f.length(), f.lastModified(), f);
        }

        public void delete(LocalStore.Item f) throws IOException {
            if (!((File) f.handle).delete()) throw new IOException("cannot delete " + f.path);
        }

        public void removeEmptyFolders() { prune(root, true); }

        boolean prune(File dir, boolean isRoot) {
            File[] kids = dir.listFiles();
            boolean empty = true;
            if (kids != null) for (File k : kids) {
                if (k.isDirectory()) { if (!prune(k, false)) empty = false; }
                else empty = false;
            }
            return !isRoot && empty && dir.delete();
        }
    }

    static class MemState implements StateStore {
        final Map<Long, Map<String, Rec>> all = new HashMap<>();
        public Map<String, Rec> load(long id) { return new HashMap<>(all.getOrDefault(id, Map.of())); }
        public void save(long id, Map<String, Rec> recs) { all.put(id, new HashMap<>(recs)); }
    }

    static void put(File dir, String rel, byte[] b, long mtime) throws IOException {
        File f = new File(dir, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
        assertTrue(f.setLastModified(mtime));
    }

    static byte[] read(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    static String text(File f) throws IOException { return new String(read(f), StandardCharsets.UTF_8); }

    static List<String> names(File dir) {
        List<String> out = new ArrayList<>(new DirStore(dir).list().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }
}
