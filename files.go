package main

import (
	"archive/zip"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"syscall"
)

type entry struct {
	Name  string `json:"n"`
	Path  string `json:"p,omitempty"` // only set for search results
	Dir   bool   `json:"d,omitempty"`
	Size  int64  `json:"s"`
	MTime int64  `json:"m"`
	Kind  string `json:"k,omitempty"` // i=image v=video a=audio
}

var kinds = map[string]string{}

// Types the browser needs spelled out; Go's built-in table is small and the
// container has no /etc/mime.types.
var mimes = map[string]string{
	"mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime", "webm": "video/webm",
	"mkv": "video/x-matroska", "avi": "video/x-msvideo", "ogv": "video/ogg", "mpg": "video/mpeg",
	"mpeg": "video/mpeg", "wmv": "video/x-ms-wmv", "flv": "video/x-flv", "3gp": "video/3gpp",
	"mp3": "audio/mpeg", "m4a": "audio/mp4", "flac": "audio/flac", "ogg": "audio/ogg",
	"opus": "audio/ogg", "wav": "audio/wav", "aac": "audio/aac", "oga": "audio/ogg", "m4b": "audio/mp4",
	"m2ts": "video/mp2t", "mts": "video/mp2t",
	"heic": "image/heic", "heif": "image/heif", "tif": "image/tiff", "tiff": "image/tiff",
	"ico": "image/x-icon", "bmp": "image/bmp",
	"txt": "text/plain; charset=utf-8", "md": "text/plain; charset=utf-8", "log": "text/plain; charset=utf-8",
}

func init() {
	for k, exts := range map[string]string{
		"i": "jpg jpeg png gif webp avif bmp svg ico heic heif tif tiff",
		"v": "mp4 m4v mov webm mkv avi ogv mpg mpeg wmv flv 3gp ts m2ts mts vob divx asf rm rmvb f4v mxf ogm",
		"a": "mp3 m4a flac ogg opus wav aac wma aiff aif ape mka ac3 dts wv m4b oga",
	} {
		for _, e := range strings.Fields(exts) {
			kinds[e] = k
		}
	}
}

func extOf(name string) string {
	return strings.ToLower(strings.TrimPrefix(path.Ext(name), "."))
}

func kindOf(name string) string { return kinds[extOf(name)] }

func (s *server) hidden(name string) bool {
	return !s.showHidden && strings.HasPrefix(name, ".")
}

func mkEntry(name string, fi fs.FileInfo) entry {
	e := entry{Name: name, Dir: fi.IsDir(), MTime: fi.ModTime().UnixMilli()}
	if !e.Dir {
		e.Size = fi.Size()
		e.Kind = kindOf(name)
	}
	return e
}

func (s *server) handleList(w http.ResponseWriter, r *http.Request) {
	rel, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	des, err := os.ReadDir(abs)
	if err != nil {
		fsError(w, err)
		return
	}
	out := make([]entry, 0, len(des))
	for _, de := range des {
		if s.hidden(de.Name()) {
			continue
		}
		var fi fs.FileInfo
		if de.Type()&fs.ModeSymlink != 0 {
			fi, err = os.Stat(filepath.Join(abs, de.Name()))
		} else {
			fi, err = de.Info()
		}
		if err != nil {
			continue
		}
		out = append(out, mkEntry(de.Name(), fi))
	}
	writeJSON(w, map[string]any{"path": rel, "entries": out, "writable": !s.readonly && writable(abs)})
}

func (s *server) handleSearch(w http.ResponseWriter, r *http.Request) {
	rel, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	words := strings.Fields(strings.ToLower(r.URL.Query().Get("q")))
	out := []entry{}
	if len(words) == 0 {
		writeJSON(w, map[string]any{"entries": out})
		return
	}
	const limit = 500
	more := false
	ctx := r.Context()
	filepath.WalkDir(abs, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if ctx.Err() != nil {
			return fs.SkipAll
		}
		if p == abs {
			return nil
		}
		if s.hidden(d.Name()) {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		low := strings.ToLower(d.Name())
		for _, w := range words {
			if !strings.Contains(low, w) {
				return nil
			}
		}
		if len(out) >= limit {
			more = true
			return fs.SkipAll
		}
		fi, err := d.Info()
		if err != nil {
			return nil
		}
		sub, _ := filepath.Rel(abs, p)
		e := mkEntry(d.Name(), fi)
		e.Path = path.Join(rel, filepath.ToSlash(sub))
		out = append(out, e)
		return nil
	})
	writeJSON(w, map[string]any{"entries": out, "more": more})
}

func (s *server) handleFile(w http.ResponseWriter, r *http.Request) {
	rel, abs, err := s.resolve(strings.TrimPrefix(r.URL.Path, "/files"))
	if err != nil {
		fsError(w, err)
		return
	}
	f, err := os.Open(abs)
	if err != nil {
		fsError(w, err)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.IsDir() {
		httpError(w, http.StatusNotFound, "not found")
		return
	}
	name := path.Base(rel)
	ct := mimes[extOf(name)]
	if ct == "" {
		ct = mime.TypeByExtension(path.Ext(name))
	}
	if ct == "" {
		ct = "application/octet-stream"
	}
	h := w.Header()
	h.Set("Content-Type", ct)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cache-Control", "private, no-cache")
	// Uploaded HTML or SVG must never run script with this site's session.
	inert := strings.HasPrefix(ct, "video/") || strings.HasPrefix(ct, "audio/") || ct == "application/pdf" ||
		(strings.HasPrefix(ct, "image/") && !strings.Contains(ct, "svg"))
	if !inert {
		h.Set("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
	}
	if r.URL.Query().Has("dl") {
		h.Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	}
	http.ServeContent(w, r, name, fi.ModTime(), f)
}

func (s *server) handleZip(w http.ResponseWriter, r *http.Request) {
	paths := r.URL.Query()["p"]
	if len(paths) == 0 {
		httpError(w, http.StatusBadRequest, "nothing selected")
		return
	}
	type item struct{ rel, abs string }
	var items []item
	for _, p := range paths {
		rel, abs, err := s.resolve(p)
		if err != nil {
			fsError(w, err)
			return
		}
		if _, err := os.Stat(abs); err != nil {
			fsError(w, err)
			return
		}
		items = append(items, item{rel, abs})
	}
	name := "files"
	if len(items) == 1 && items[0].rel != "/" {
		name = path.Base(items[0].rel)
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name + ".zip"}))
	zw := zip.NewWriter(w)
	defer zw.Close()
	ctx := r.Context()
	for _, it := range items {
		base := filepath.Dir(it.abs)
		if it.rel == "/" {
			base = it.abs
		}
		filepath.WalkDir(it.abs, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if ctx.Err() != nil {
				return fs.SkipAll
			}
			if p != it.abs && s.hidden(d.Name()) {
				if d.IsDir() {
					return fs.SkipDir
				}
				return nil
			}
			fi, err := os.Stat(p) // follow symlinks
			if err != nil {
				return nil
			}
			zn, _ := filepath.Rel(base, p)
			zn = filepath.ToSlash(zn)
			if zn == "." {
				return nil
			}
			if fi.IsDir() {
				zw.CreateHeader(&zip.FileHeader{Name: zn + "/", Modified: fi.ModTime()})
				return nil
			}
			if !fi.Mode().IsRegular() {
				return nil
			}
			// Media is already compressed; storing keeps the download fast.
			fw, err := zw.CreateHeader(&zip.FileHeader{Name: zn, Modified: fi.ModTime(), Method: zip.Store})
			if err != nil {
				return fs.SkipAll
			}
			f, err := os.Open(p)
			if err != nil {
				return nil
			}
			_, err = io.Copy(fw, f)
			f.Close()
			if err != nil {
				return fs.SkipAll
			}
			return nil
		})
	}
}

// ---- writes --------------------------------------------------------------

// uniquePath returns p, or "name (2).ext" style variants if p is taken.
func uniquePath(p string) string {
	if _, err := os.Lstat(p); errors.Is(err, fs.ErrNotExist) {
		return p
	}
	ext := filepath.Ext(p)
	stem := strings.TrimSuffix(p, ext)
	for i := 2; ; i++ {
		c := fmt.Sprintf("%s (%d)%s", stem, i, ext)
		if _, err := os.Lstat(c); errors.Is(err, fs.ErrNotExist) {
			return c
		}
	}
}

func validName(n string) bool {
	return n != "" && n != "." && n != ".." && !strings.ContainsAny(n, "/\\\x00")
}

func (s *server) handleUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	// "name" may carry sub-directories when a whole folder is dropped.
	rel, abs, err := s.resolve(q.Get("p") + "/" + q.Get("name"))
	if err != nil || rel == "/" {
		httpError(w, http.StatusBadRequest, "invalid upload path")
		return
	}
	dir := filepath.Dir(abs)
	if err := os.MkdirAll(dir, 0o777); err != nil {
		fsError(w, err)
		return
	}
	rnd := make([]byte, 6)
	rand.Read(rnd)
	tmp := filepath.Join(dir, ".shelf-upload-"+hex.EncodeToString(rnd))
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o666)
	if err != nil {
		fsError(w, err)
		return
	}
	_, err = io.Copy(f, r.Body)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp)
		httpError(w, http.StatusInternalServerError, "upload failed: "+err.Error())
		return
	}
	dst := abs
	if !q.Has("overwrite") {
		dst = uniquePath(abs)
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		fsError(w, err)
		return
	}
	writeJSON(w, map[string]any{"name": filepath.Base(dst)})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(v); err != nil {
		httpError(w, http.StatusBadRequest, "bad request")
		return false
	}
	return true
}

func (s *server) handleMkdir(w http.ResponseWriter, r *http.Request) {
	var in struct{ Path, Name string }
	if !readJSON(w, r, &in) {
		return
	}
	if !validName(in.Name) {
		httpError(w, http.StatusBadRequest, "invalid name")
		return
	}
	_, abs, err := s.resolve(in.Path + "/" + in.Name)
	if err != nil {
		httpError(w, http.StatusBadRequest, "invalid name")
		return
	}
	if err := os.Mkdir(abs, 0o777); err != nil {
		fsError(w, err)
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func (s *server) handleRename(w http.ResponseWriter, r *http.Request) {
	var in struct{ Path, Name string }
	if !readJSON(w, r, &in) {
		return
	}
	rel, abs, err := s.resolve(in.Path)
	if err != nil || rel == "/" || !validName(in.Name) {
		httpError(w, http.StatusBadRequest, "invalid name")
		return
	}
	_, dst, err := s.resolve(path.Dir(rel) + "/" + in.Name)
	if err != nil {
		httpError(w, http.StatusBadRequest, "invalid name")
		return
	}
	if _, err := os.Lstat(dst); err == nil && !sameFile(abs, dst) {
		httpError(w, http.StatusConflict, "a file with that name already exists")
		return
	}
	if err := os.Rename(abs, dst); err != nil {
		fsError(w, err)
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func sameFile(a, b string) bool {
	fa, err1 := os.Stat(a)
	fb, err2 := os.Stat(b)
	return err1 == nil && err2 == nil && os.SameFile(fa, fb)
}

// handleTransfer moves or copies a set of paths into a directory.
func (s *server) handleTransfer(w http.ResponseWriter, r *http.Request) {
	var in struct {
		From []string
		To   string
		Copy bool
	}
	if !readJSON(w, r, &in) {
		return
	}
	_, toAbs, err := s.resolve(in.To)
	if err != nil {
		fsError(w, err)
		return
	}
	if fi, err := os.Stat(toAbs); err != nil || !fi.IsDir() {
		httpError(w, http.StatusBadRequest, "destination is not a folder")
		return
	}
	for _, p := range in.From {
		rel, abs, err := s.resolve(p)
		if err != nil || rel == "/" {
			httpError(w, http.StatusBadRequest, "invalid source")
			return
		}
		if toAbs == abs || strings.HasPrefix(toAbs+string(filepath.Separator), abs+string(filepath.Separator)) {
			httpError(w, http.StatusBadRequest, "cannot put a folder inside itself")
			return
		}
		if !in.Copy && filepath.Dir(abs) == toAbs {
			continue // already there
		}
		dst := uniquePath(filepath.Join(toAbs, filepath.Base(abs)))
		if in.Copy {
			err = copyTree(abs, dst)
		} else {
			err = os.Rename(abs, dst)
			if errors.Is(err, syscall.EXDEV) { // across mounts: copy, then remove
				if err = copyTree(abs, dst); err == nil {
					err = os.RemoveAll(abs)
				}
			}
		}
		if err != nil {
			fsError(w, err)
			return
		}
	}
	writeJSON(w, map[string]any{"ok": true})
}

func copyTree(src, dst string) error {
	fi, err := os.Stat(src)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return copyFile(src, dst, fi)
	}
	if err := os.Mkdir(dst, 0o777); err != nil {
		return err
	}
	des, err := os.ReadDir(src)
	if err != nil {
		return err
	}
	for _, de := range des {
		if err := copyTree(filepath.Join(src, de.Name()), filepath.Join(dst, de.Name())); err != nil {
			return err
		}
	}
	return os.Chtimes(dst, fi.ModTime(), fi.ModTime())
}

func copyFile(src, dst string, fi fs.FileInfo) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o666)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(dst)
		return err
	}
	return os.Chtimes(dst, fi.ModTime(), fi.ModTime())
}

func (s *server) handleDelete(w http.ResponseWriter, r *http.Request) {
	var in struct{ Paths []string }
	if !readJSON(w, r, &in) {
		return
	}
	for _, p := range in.Paths {
		rel, abs, err := s.resolve(p)
		if err != nil || rel == "/" {
			httpError(w, http.StatusBadRequest, "invalid path")
			return
		}
		if err := os.RemoveAll(abs); err != nil {
			fsError(w, err)
			return
		}
	}
	writeJSON(w, map[string]any{"ok": true})
}
