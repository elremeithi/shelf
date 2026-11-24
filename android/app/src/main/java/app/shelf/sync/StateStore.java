package app.shelf.sync;

import java.util.Map;

/** What both sides looked like after the last sync, per file. */
public interface StateStore {
    final class Rec {
        public long localSize, localMtime, remoteSize, remoteMtime;
        public Rec(long ls, long lm, long rs, long rm) { localSize = ls; localMtime = lm; remoteSize = rs; remoteMtime = rm; }
    }

    Map<String, Rec> load(long pairId);

    /** Replaces the stored state of the pair with these records. */
    void save(long pairId, Map<String, Rec> recs);
}
