package app.shelf.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A phone folder the user picked with the system folder picker. Works through
 * the storage access framework, so the app needs no broad storage permission
 * and only ever touches the folders it was given.
 */
public class SafStore implements LocalStore {
    private static final String PART = ".shelfpart";
    private static final String[] COLS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private final ContentResolver cr;
    private final Uri tree;
    private final String rootId;
    private final Map<String, String> dirs = new HashMap<>();   // relative dir path ("" = root) -> document id
    private final Map<String, String> files = new HashMap<>();  // relative file path -> document id
    private final Map<String, String> parts = new HashMap<>();  // relative file path -> partial download id
    private final Map<String, Long> partSizes = new HashMap<>();

    public SafStore(Context ctx, Uri tree) {
        this.cr = ctx.getContentResolver();
        this.tree = tree;
        this.rootId = DocumentsContract.getTreeDocumentId(tree);
    }

    private Uri doc(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

    @Override
    public Map<String, Item> list() throws IOException {
        dirs.clear(); files.clear(); parts.clear(); partSizes.clear();
        Map<String, Item> out = new TreeMap<>();
        dirs.put("", rootId);
        List<String[]> queue = new ArrayList<>();
        queue.add(new String[]{"", rootId});
        while (!queue.isEmpty()) {
            String[] d = queue.remove(queue.size() - 1);
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, d[1]);
            try (Cursor c = cr.query(kids, COLS, null, null, null)) {
                if (c == null) {
                    if (d[0].isEmpty()) throw new IOException("The phone folder is no longer available. Pick it again.");
                    continue;
                }
                while (c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (name == null) continue;
                    String rel = d[0].isEmpty() ? name : d[0] + "/" + name;
                    if (name.startsWith(".")) {
                        // Our own unfinished downloads are hidden ".name.shelfpart" files.
                        if (name.endsWith(PART) && !Document.MIME_TYPE_DIR.equals(mime)) {
                            String target = (d[0].isEmpty() ? "" : d[0] + "/") + name.substring(1, name.length() - PART.length());
                            parts.put(target, id);
                            partSizes.put(target, c.isNull(3) ? 0 : c.getLong(3));
                        }
                        continue; // hidden files (".trashed-…", ".thumbnails") are never synced
                    }
                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        dirs.put(rel, id);
                        queue.add(new String[]{rel, id});
                    } else {
                        files.put(rel, id);
                        out.put(rel, new Item(rel, c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4), id));
                    }
                }
            } catch (SecurityException e) {
                throw new IOException("Shelf lost access to the phone folder. Pick it again in the sync settings.");
            }
        }
        return out;
    }

    @Override
    public InputStream open(Item f) throws IOException {
        InputStream in = cr.openInputStream(doc((String) f.handle));
        if (in == null) throw new FileNotFoundException(f.path);
        return in;
    }

    private String ensureDir(String rel) throws IOException {
        String id = dirs.get(rel);
        if (id != null) return id;
        int slash = rel.lastIndexOf('/');
        String parent = ensureDir(slash < 0 ? "" : rel.substring(0, slash));
        Uri made = DocumentsContract.createDocument(cr, doc(parent), Document.MIME_TYPE_DIR, rel.substring(slash + 1));
        if (made == null) throw new IOException("Cannot create folder " + rel + " on the phone");
        id = DocumentsContract.getDocumentId(made);
        dirs.put(rel, id);
        return id;
    }

    private static String parentOf(String rel) { int i = rel.lastIndexOf('/'); return i < 0 ? "" : rel.substring(0, i); }

    private static String nameOf(String rel) { return rel.substring(rel.lastIndexOf('/') + 1); }

    @Override
    public long partialSize(String path) {
        Long n = partSizes.get(path);
        return n == null ? 0 : n;
    }

    @Override
    public OutputStream openPartial(String path, boolean append) throws IOException {
        String id = parts.get(path);
        if (id == null || !append) {
            if (id != null) DocumentsContract.deleteDocument(cr, doc(id));
            Uri made = DocumentsContract.createDocument(cr, doc(ensureDir(parentOf(path))), "application/octet-stream", "." + nameOf(path) + PART);
            if (made == null) throw new IOException("Cannot write to the phone folder");
            id = DocumentsContract.getDocumentId(made);
            parts.put(path, id);
            append = false;
        }
        OutputStream out;
        try { out = cr.openOutputStream(doc(id), append ? "wa" : "wt"); }
        catch (IllegalArgumentException | UnsupportedOperationException e) {
            // Providers without append support: start over.
            out = cr.openOutputStream(doc(id), "w");
        }
        if (out == null) throw new IOException("Cannot write to the phone folder");
        return out;
    }

    @Override
    public Item commitPartial(String path) throws IOException {
        String id = parts.remove(path);
        partSizes.remove(path);
        if (id == null) throw new IOException("Download of " + path + " was lost");
        String old = files.get(path);
        if (old != null) DocumentsContract.deleteDocument(cr, doc(old));
        Uri renamed = DocumentsContract.renameDocument(cr, doc(id), nameOf(path));
        String newId = renamed == null ? id : DocumentsContract.getDocumentId(renamed);
        files.put(path, newId);
        try (Cursor c = cr.query(doc(newId), COLS, null, null, null)) {
            if (c != null && c.moveToFirst()) return new Item(path, c.getLong(3), c.getLong(4), newId);
        }
        throw new IOException("Cannot read back " + path);
    }

    @Override
    public void delete(Item f) throws IOException {
        if (!DocumentsContract.deleteDocument(cr, doc((String) f.handle))) throw new IOException("Cannot delete " + f.path + " on the phone");
        files.remove(f.path);
    }

    @Override
    public void removeEmptyFolders() throws IOException {
        list();
        // Deepest first, so a parent becomes empty after its children go.
        List<String> paths = new ArrayList<>(dirs.keySet());
        paths.sort((a, b) -> b.length() - a.length());
        for (String rel : paths) {
            if (rel.isEmpty()) continue;
            try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirs.get(rel)), new String[]{Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (c != null && c.getCount() == 0) DocumentsContract.deleteDocument(cr, doc(dirs.get(rel)));
            } catch (Exception ignored) {
                // leave the folder
            }
        }
    }

    /** A readable name for a picked folder, like "Internal storage › DCIM/Camera". */
    public static String describe(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String vol = id.substring(0, colon), path = id.substring(colon + 1);
        String where = vol.equals("primary") ? "Phone" : "SD card";
        return path.isEmpty() ? where : where + " › " + path;
    }
}
