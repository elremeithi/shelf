package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newTestServer(t *testing.T) (*server, *http.ServeMux, string) {
	t.Helper()
	root := t.TempDir()
	s := &server{root: root, cache: t.TempDir(), flight: map[string]chan struct{}{}, secret: []byte("test-secret-test-secret-test-sec")}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/sync/tree", s.auth(s.handleSyncTree))
	mux.HandleFunc("GET /api/sync/hash", s.auth(s.handleSyncHash))
	mux.HandleFunc("GET /api/sync/upload", s.auth(s.handleSyncOffset))
	mux.HandleFunc("PUT /api/sync/upload", s.write(s.handleSyncChunk))
	mux.HandleFunc("POST /api/sync/mkdir", s.write(s.handleMkdirAll))
	return s, mux, root
}

func do(t *testing.T, mux *http.ServeMux, method, url string, body []byte) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, url, bytes.NewReader(body))
	req.Header.Set("X-Shelf", "1")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	var out map[string]any
	json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

// upload sends b in chunks of size n, the way the app does.
func upload(t *testing.T, mux *http.ServeMux, dir, name, id string, b []byte, n int, mode string) map[string]any {
	t.Helper()
	q := fmt.Sprintf("/api/sync/upload?p=%s&name=%s&id=%s", dir, name, id)
	for off := 0; ; off += n {
		end := min(off+n, len(b))
		url := fmt.Sprintf("%s&offset=%d", q, off)
		if end == len(b) {
			url += fmt.Sprintf("&final=1&size=%d&sha256=%s&mtime=1700000000000&mode=%s", len(b), sum(b), mode)
		}
		code, out := do(t, mux, "PUT", url, b[off:end])
		if code != 200 {
			t.Fatalf("chunk at %d: %d %v", off, code, out)
		}
		if end == len(b) {
			return out
		}
	}
}

func TestSyncUploadNeverLosesServerFiles(t *testing.T) {
	_, mux, root := newTestServer(t)
	os.MkdirAll(filepath.Join(root, "Phone"), 0o755)
	os.WriteFile(filepath.Join(root, "Phone", "a.txt"), []byte("server"), 0o644)

	// A clash in keep mode gets a new name; the server file stays.
	out := upload(t, mux, "/Phone", "a.txt", "keep000001", []byte("phone"), 2, "keep")
	if out["path"] != "/Phone/a (2).txt" {
		t.Fatalf("keep mode path = %v", out["path"])
	}
	// Backup mode replaces it, moving the old file into .shelf-versions.
	out = upload(t, mux, "/Phone", "a.txt", "back000001", []byte("phone v2"), 3, "backup")
	if out["path"] != "/Phone/a.txt" {
		t.Fatalf("backup mode path = %v", out["path"])
	}
	got, _ := os.ReadFile(filepath.Join(root, "Phone", "a.txt"))
	vers, _ := filepath.Glob(filepath.Join(root, "Phone", versionsDir, "a~*.txt"))
	if string(got) != "phone v2" || len(vers) != 1 {
		t.Fatalf("after backup: %q, versions %v", got, vers)
	}
	if old, _ := os.ReadFile(vers[0]); string(old) != "server" {
		t.Fatalf("version holds %q", old)
	}
	// Identical content is recognised and not stored twice.
	out = upload(t, mux, "/Phone", "a.txt", "dupe000001", []byte("phone v2"), 100, "keep")
	if out["duplicate"] != true {
		t.Fatalf("duplicate not detected: %v", out)
	}
	// The listing hides versions and part files.
	_, tree := do(t, mux, "GET", "/api/sync/tree?p=/Phone", nil)
	files := tree["files"].([]any)
	if len(files) != 2 {
		t.Fatalf("tree files = %v", files)
	}
}

func TestSyncUploadResumesAndChecksContent(t *testing.T) {
	_, mux, root := newTestServer(t)
	data := bytes.Repeat([]byte("0123456789"), 1000)
	q := "/api/sync/upload?p=/New/Deep&name=f.bin&id=resume0001"
	if code, _ := do(t, mux, "PUT", q+"&offset=0", data[:4000]); code != 200 {
		t.Fatal("first chunk failed")
	}
	// A chunk at the wrong offset is refused with the right one.
	code, out := do(t, mux, "PUT", q+"&offset=10", data[10:20])
	if code != http.StatusConflict || out["offset"].(float64) != 4000 {
		t.Fatalf("wrong offset: %d %v", code, out)
	}
	if _, out := do(t, mux, "GET", q, nil); out["offset"].(float64) != 4000 {
		t.Fatalf("resume offset = %v", out)
	}
	// A wrong checksum discards the file.
	code, _ = do(t, mux, "PUT", fmt.Sprintf("%s&offset=4000&final=1&size=%d&sha256=%s", q, len(data), strings.Repeat("0", 64)), data[4000:])
	if code != http.StatusUnprocessableEntity {
		t.Fatalf("bad checksum accepted: %d", code)
	}
	if _, err := os.Stat(filepath.Join(root, "New", "Deep", "f.bin")); err == nil {
		t.Fatal("file with bad checksum was kept")
	}
	out = upload(t, mux, "/New/Deep", "f.bin", "resume0002", data, 3000, "keep")
	if out["sha256"] != sum(data) {
		t.Fatalf("stored checksum %v", out["sha256"])
	}
	fi, _ := os.Stat(filepath.Join(root, "New", "Deep", "f.bin"))
	if fi.ModTime().UnixMilli() != 1700000000000 {
		t.Fatalf("mtime not kept: %v", fi.ModTime())
	}
}

func TestSyncTreeOfMissingFolder(t *testing.T) {
	_, mux, _ := newTestServer(t)
	_, out := do(t, mux, "GET", "/api/sync/tree?p=/Nope/Not/Here", nil)
	if out["exists"] != false {
		t.Fatalf("missing folder: %v", out)
	}
	if code, _ := do(t, mux, "POST", "/api/sync/mkdir", []byte(`{"path":"/Nope/Not/Here"}`)); code != 200 {
		t.Fatal("mkdir -p failed")
	}
	if _, out = do(t, mux, "GET", "/api/sync/tree?p=/Nope/Not/Here", nil); out["exists"] != true {
		t.Fatalf("after mkdir: %v", out)
	}
	if code, _ := do(t, mux, "GET", "/api/sync/tree?p=/../../etc", nil); code != 200 {
		// Cleaned to /etc inside the root: it simply does not exist there.
		t.Fatalf("traversal attempt: %d", code)
	}
}
