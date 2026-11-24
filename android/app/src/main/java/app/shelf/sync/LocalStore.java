package app.shelf.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** The phone side of a sync pair: a folder tree, seen through relative paths like "2026/IMG_1.jpg". */
public interface LocalStore {
    final class Item {
        public final String path;
        public final long size, mtime;
        public final Object handle;
        public Item(String path, long size, long mtime, Object handle) { this.path = path; this.size = size; this.mtime = mtime; this.handle = handle; }
    }

    /** Every file below the root, skipping hidden names. */
    Map<String, Item> list() throws IOException;

    InputStream open(Item f) throws IOException;

    /** Size of a half-finished download of path, or 0. */
    long partialSize(String path) throws IOException;

    /** Output for the download of path, appending to an existing partial download. */
    OutputStream openPartial(String path, boolean append) throws IOException;

    /** Replaces path with the finished download and returns the new file. */
    Item commitPartial(String path) throws IOException;

    void delete(Item f) throws IOException;

    /** Removes folders left empty below the root (not the root itself). */
    void removeEmptyFolders() throws IOException;
}
