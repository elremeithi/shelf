package main

// Endpoints for the Android app's folder sync.
//
// Uploads are resumable: a file is sent in chunks that are appended to a
// hidden part file next to its destination, so a dropped connection only
// costs the current chunk. When the last chunk arrives the server checks the
// size and SHA-256 the client announced, and only then moves the file into
// place. Nothing on the server is ever deleted or overwritten by a sync: a
// name clash either keeps both files, or moves the old one into a hidden
// .shelf-versions folder first.

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const versionsDir = ".shelf-versions"

var uploadID = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)

// handleSyncTree lists every file and folder below p, recursively.
func (s *server) handleSyncTree(w http.ResponseWriter, r *http.Request) {
	rel, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	type file struct {
		Path  string `json:"p"`
		Size  int64  `json:"s"`
		MTime int64  `json:"m"`
	}
	files, dirs := []file{}, []string{}
	fi, err := os.Stat(abs)
	if errors.Is(err, fs.ErrNotExist) {
		writeJSON(w, map[string]any{"path": rel, "exists": false, "files": files, "dirs": dirs})
		return
	}
	if err != nil {
		fsError(w, err)
		return
	}
	if !fi.IsDir() {
		httpError(w, http.StatusConflict, "that path is a file, not a folder")
		return
	}
	ctx := r.Context()
	err = filepath.WalkDir(abs, func(p string, d fs.DirEntry, err error) error {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err != nil || p == abs {
			return nil
		}
		if strings.HasPrefix(d.Name(), ".") && (!s.showHidden || d.Name() == versionsDir || strings.HasPrefix(d.Name(), ".shelf-")) {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		sub, _ := filepath.Rel(abs, p)
		sub = filepath.ToSlash(sub)
		info, err := os.Stat(p) // follow symlinks
		if err != nil {
			return nil
		}
		if info.IsDir() {
			dirs = append(dirs, sub)
		} else if info.Mode().IsRegular() {
			files = append(files, file{sub, info.Size(), info.ModTime().UnixMilli()})
		}
		return nil
	})
	if err != nil {
		return // client went away
	}
	writeJSON(w, map[string]any{"path": rel, "exists": true, "writable": !s.readonly && writable(abs), "files": files, "dirs": dirs})
}

// handleSyncHash returns the SHA-256 of one file.
func (s *server) handleSyncHash(w http.ResponseWriter, r *http.Request) {
	_, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	sum, size, err := hashFile(abs)
	if err != nil {
		fsError(w, err)
		return
	}
	fi, _ := os.Stat(abs)
	writeJSON(w, map[string]any{"sha256": sum, "s": size, "m": fi.ModTime().UnixMilli()})
}

func hashFile(p string) (string, int64, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	if fi, err := f.Stat(); err != nil || fi.IsDir() {
		return "", 0, fs.ErrNotExist
	}
	h := sha256.New()
	n, err := io.Copy(h, f)
	return hex.EncodeToString(h.Sum(nil)), n, err
}

// partTarget resolves the destination of a chunked upload and its part file.
func (s *server) partTarget(w http.ResponseWriter, r *http.Request) (rel, dst, part string, ok bool) {
	q := r.URL.Query()
	rel, dst, err := s.resolve(q.Get("p") + "/" + q.Get("name"))
	if err != nil || rel == "/" || path.Base(rel) == versionsDir {
		httpError(w, http.StatusBadRequest, "invalid upload path")
		return "", "", "", false
	}
	id := q.Get("id")
	if !uploadID.MatchString(id) {
		httpError(w, http.StatusBadRequest, "invalid upload id")
		return "", "", "", false
	}
	return rel, dst, filepath.Join(filepath.Dir(dst), ".shelf-part-"+id), true
}

// handleSyncOffset tells a client how much of an interrupted upload arrived.
func (s *server) handleSyncOffset(w http.ResponseWriter, r *http.Request) {
	_, _, part, ok := s.partTarget(w, r)
	if !ok {
		return
	}
	var n int64
	if fi, err := os.Stat(part); err == nil {
		n = fi.Size()
	}
	writeJSON(w, map[string]any{"offset": n})
}

// handleSyncChunk appends one chunk at the given offset. With final=1 it
// verifies the whole file and moves it into place.
func (s *server) handleSyncChunk(w http.ResponseWriter, r *http.Request) {
	rel, dst, part, ok := s.partTarget(w, r)
	if !ok {
		return
	}
	q := r.URL.Query()
	offset, err := strconv.ParseInt(q.Get("offset"), 10, 64)
	if err != nil || offset < 0 {
		httpError(w, http.StatusBadRequest, "invalid offset")
		return
	}
	if err := os.MkdirAll(filepath.Dir(part), 0o777); err != nil {
		fsError(w, err)
		return
	}
	f, err := os.OpenFile(part, os.O_WRONLY|os.O_CREATE, 0o666)
	if err != nil {
		fsError(w, err)
		return
	}
	fi, err := f.Stat()
	if err != nil {
		f.Close()
		fsError(w, err)
		return
	}
	if fi.Size() != offset {
		f.Close()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		fmt.Fprintf(w, `{"error":"offset mismatch","offset":%d}`, fi.Size())
		return
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		f.Close()
		fsError(w, err)
		return
	}
	_, err = io.Copy(f, r.Body)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		// Keep what arrived; the client resumes from the part file's size.
		httpError(w, http.StatusInternalServerError, "chunk interrupted: "+err.Error())
		return
	}
	if q.Get("final") != "1" {
		fi, _ := os.Stat(part)
		writeJSON(w, map[string]any{"offset": fi.Size()})
		return
	}

	// Last chunk: check the whole file before it becomes visible.
	wantSize, _ := strconv.ParseInt(q.Get("size"), 10, 64)
	sum, size, err := hashFile(part)
	if err != nil {
		fsError(w, err)
		return
	}
	if size != wantSize || (q.Get("sha256") != "" && !strings.EqualFold(q.Get("sha256"), sum)) {
		os.Remove(part)
		httpError(w, http.StatusUnprocessableEntity, "the uploaded file does not match its checksum; it was discarded, please retry")
		return
	}
	if ms, err := strconv.ParseInt(q.Get("mtime"), 10, 64); err == nil && ms > 0 {
		t := time.UnixMilli(ms)
		os.Chtimes(part, t, t)
	}

	final, duplicate, err := placeFile(part, dst, sum, q.Get("mode") == "backup")
	if err != nil {
		os.Remove(part)
		fsError(w, err)
		return
	}
	writeJSON(w, map[string]any{
		"path":      path.Join(path.Dir(rel), filepath.Base(final)),
		"size":      size,
		"sha256":    sum,
		"duplicate": duplicate,
	})
}

// placeFile moves a verified part file to dst without losing anything that
// is already there. If dst holds identical content the part is dropped. In
// backup mode an existing different file is moved to .shelf-versions and
// replaced; otherwise the new file gets a free "name (2)" style name.
func placeFile(part, dst, sum string, backup bool) (final string, duplicate bool, err error) {
	if fi, err := os.Stat(dst); err == nil {
		if fi.IsDir() {
			return placeFile(part, uniquePath(dst), sum, false)
		}
		if old, _, err := hashFile(dst); err == nil && old == sum {
			os.Chtimes(dst, fi.ModTime(), mustStat(part).ModTime())
			os.Remove(part)
			return dst, true, nil
		}
		if backup {
			vdir := filepath.Join(filepath.Dir(dst), versionsDir)
			if err := os.MkdirAll(vdir, 0o777); err != nil {
				return "", false, err
			}
			ext := filepath.Ext(dst)
			stem := strings.TrimSuffix(filepath.Base(dst), ext)
			keep := uniquePath(filepath.Join(vdir, stem+"~"+fi.ModTime().Format("2006-01-02_150405")+ext))
			if err := os.Rename(dst, keep); err != nil {
				return "", false, err
			}
		} else {
			dst = uniquePath(dst)
		}
	}
	if err := os.Rename(part, dst); err != nil {
		return "", false, err
	}
	return dst, false, nil
}

func mustStat(p string) fs.FileInfo {
	fi, err := os.Stat(p)
	if err != nil {
		return zeroInfo{}
	}
	return fi
}

type zeroInfo struct{}

func (zeroInfo) Name() string       { return "" }
func (zeroInfo) Size() int64        { return 0 }
func (zeroInfo) Mode() fs.FileMode  { return 0 }
func (zeroInfo) ModTime() time.Time { return time.Now() }
func (zeroInfo) IsDir() bool        { return false }
func (zeroInfo) Sys() any           { return nil }

// handleMkdirAll creates a folder and any missing parents.
func (s *server) handleMkdirAll(w http.ResponseWriter, r *http.Request) {
	var in struct{ Path string }
	if !readJSON(w, r, &in) {
		return
	}
	rel, abs, err := s.resolve(in.Path)
	if err != nil {
		fsError(w, err)
		return
	}
	if err := os.MkdirAll(abs, 0o777); err != nil {
		fsError(w, err)
		return
	}
	writeJSON(w, map[string]any{"path": rel})
}
