// Shelf: a small, fast web file manager with a media gallery.
package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed web
var webFS embed.FS

type server struct {
	root       string
	cache      string
	user, pass string
	readonly   bool
	showHidden bool
	secret     []byte

	sem     chan struct{} // limits concurrent thumbnail jobs
	tc      *transcoder
	flightM sync.Mutex
	flight  map[string]chan struct{}

	index  []byte
	assets map[string][]byte
}

const sessionTTL = 30 * 24 * time.Hour

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envBool(k string) bool {
	v := strings.ToLower(os.Getenv(k))
	return v == "1" || v == "true" || v == "yes"
}

func main() {
	s := &server{
		root:       env("SHELF_ROOT", "/data"),
		cache:      env("SHELF_CACHE", "/cache"),
		user:       os.Getenv("SHELF_USER"),
		pass:       os.Getenv("SHELF_PASSWORD"),
		readonly:   envBool("SHELF_READONLY"),
		showHidden: envBool("SHELF_SHOW_HIDDEN"),
		sem:        make(chan struct{}, max(2, runtime.NumCPU())),
		flight:     map[string]chan struct{}{},
	}
	addr := env("SHELF_ADDR", ":8080")

	root, err := filepath.Abs(s.root)
	if err != nil {
		log.Fatal(err)
	}
	s.root = root
	if fi, err := os.Stat(s.root); err != nil || !fi.IsDir() {
		log.Fatalf("SHELF_ROOT %q is not a directory", s.root)
	}
	if err := os.MkdirAll(s.cache, 0o755); err != nil {
		log.Fatalf("cannot create cache dir: %v", err)
	}
	s.loadSecret()
	s.tc = newTranscoder(s.cache)
	s.loadAssets()

	if s.user == "" || s.pass == "" {
		s.user, s.pass = "", ""
		log.Print("WARNING: SHELF_USER / SHELF_PASSWORD not set, running without login")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.handleIndex)
	mux.HandleFunc("GET /_/{name}", s.handleAsset)
	mux.HandleFunc("GET /api/me", s.handleMe)
	mux.HandleFunc("POST /api/login", s.handleLogin)
	mux.HandleFunc("POST /api/logout", s.handleLogout)

	mux.HandleFunc("GET /api/ls", s.auth(s.handleList))
	mux.HandleFunc("GET /api/search", s.auth(s.handleSearch))
	mux.HandleFunc("GET /api/thumb", s.auth(s.handleThumb))
	mux.HandleFunc("GET /api/media/info", s.auth(s.handleMediaInfo))
	mux.HandleFunc("GET /api/hls/index.m3u8", s.auth(s.handleHLSIndex))
	mux.HandleFunc("GET /api/hls/seg", s.auth(s.handleHLSSegment))
	mux.HandleFunc("GET /api/zip", s.auth(s.handleZip))
	mux.HandleFunc("GET /files/", s.auth(s.handleFile))

	mux.HandleFunc("PUT /api/upload", s.write(s.handleUpload))
	mux.HandleFunc("POST /api/mkdir", s.write(s.handleMkdir))
	mux.HandleFunc("POST /api/rename", s.write(s.handleRename))
	mux.HandleFunc("POST /api/transfer", s.write(s.handleTransfer))
	mux.HandleFunc("POST /api/delete", s.write(s.handleDelete))

	// Folder sync for the Android app.
	mux.HandleFunc("GET /api/sync/tree", s.auth(s.handleSyncTree))
	mux.HandleFunc("GET /api/sync/hash", s.auth(s.handleSyncHash))
	mux.HandleFunc("GET /api/sync/upload", s.auth(s.handleSyncOffset))
	mux.HandleFunc("PUT /api/sync/upload", s.write(s.handleSyncChunk))
	mux.HandleFunc("POST /api/sync/mkdir", s.write(s.handleMkdirAll))

	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 15 * time.Second,
	}
	// Stop encoders (some may be paused) before exiting.
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		s.tc.stopAll()
		os.Exit(0)
	}()
	if out, err := exec.Command("ffmpeg", "-hide_banner", "-encoders").Output(); err != nil {
		log.Print("WARNING: ffmpeg not found, video thumbnails and transcoding are unavailable")
	} else if !strings.Contains(string(out), "libx264") {
		log.Print("WARNING: this ffmpeg has no libx264 encoder, video transcoding is unavailable")
	}
	if !s.readonly && !writable(s.root) {
		log.Printf("NOTE: user %d, group %d cannot write to the top folder %s; uploads only work in subfolders it can write to", os.Getuid(), os.Getgid(), s.root)
	}
	log.Printf("shelf serving %s on %s (cache %s, readonly=%v)", s.root, addr, s.cache, s.readonly)
	log.Fatal(srv.ListenAndServe())
}

// ---- static assets -------------------------------------------------------

func (s *server) loadAssets() {
	s.assets = map[string][]byte{}
	sub, _ := fs.Sub(webFS, "web")
	h := sha256.New()
	for _, n := range []string{"app.js", "style.css", "hls.min.js"} {
		b, err := fs.ReadFile(sub, n)
		if err != nil {
			log.Fatal(err)
		}
		s.assets[n] = b
		h.Write(b)
	}
	ver := hex.EncodeToString(h.Sum(nil))[:12]
	idx, err := fs.ReadFile(sub, "index.html")
	if err != nil {
		log.Fatal(err)
	}
	s.index = []byte(strings.ReplaceAll(string(idx), "{{v}}", ver))
}

func (s *server) handleIndex(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Referrer-Policy", "same-origin")
	w.Write(s.index)
}

func (s *server) handleAsset(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	b, ok := s.assets[name]
	if !ok {
		http.NotFound(w, r)
		return
	}
	ct := "text/css; charset=utf-8"
	if strings.HasSuffix(name, ".js") {
		ct = "text/javascript; charset=utf-8"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Write(b)
}

// ---- auth ----------------------------------------------------------------

func (s *server) loadSecret() {
	p := filepath.Join(s.cache, ".secret")
	if b, err := os.ReadFile(p); err == nil && len(b) == 32 {
		s.secret = b
		return
	}
	s.secret = make([]byte, 32)
	if _, err := rand.Read(s.secret); err != nil {
		log.Fatal(err)
	}
	// Best effort: without it, logins simply do not survive a restart.
	_ = os.WriteFile(p, s.secret, 0o600)
}

func (s *server) sign(exp string) string {
	m := hmac.New(sha256.New, s.secret)
	m.Write([]byte(s.user + "\x00" + s.pass + "\x00" + exp))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func (s *server) authed(r *http.Request) bool {
	if s.user == "" {
		return true
	}
	val := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(a, "Bearer ") {
		val = strings.TrimPrefix(a, "Bearer ")
	} else if c, err := r.Cookie("shelf"); err == nil {
		val = c.Value
	} else {
		return false
	}
	exp, sig, ok := strings.Cut(val, ".")
	if !ok {
		return false
	}
	t, err := strconv.ParseInt(exp, 10, 64)
	if err != nil || time.Now().Unix() > t {
		return false
	}
	return hmac.Equal([]byte(sig), []byte(s.sign(exp)))
}

func (s *server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.authed(r) {
			httpError(w, http.StatusUnauthorized, "login required")
			return
		}
		h(w, r)
	}
}

// write wraps handlers that change files. The custom header cannot be sent
// cross-origin without a CORS preflight, which blocks request forgery.
func (s *server) write(h http.HandlerFunc) http.HandlerFunc {
	return s.auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Shelf") == "" {
			httpError(w, http.StatusForbidden, "missing X-Shelf header")
			return
		}
		if s.readonly {
			httpError(w, http.StatusForbidden, "this server is read-only")
			return
		}
		h(w, r)
	})
}

func (s *server) handleMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{
		"app":      "shelf",
		"sync":     1,
		"authed":   s.authed(r),
		"login":    s.user != "",
		"readonly": s.readonly,
	})
}

func (s *server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var in struct{ User, Password string }
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in); err != nil {
		httpError(w, http.StatusBadRequest, "bad request")
		return
	}
	u := sha256.Sum256([]byte(in.User))
	p := sha256.Sum256([]byte(in.Password))
	wu := sha256.Sum256([]byte(s.user))
	wp := sha256.Sum256([]byte(s.pass))
	ok := subtle.ConstantTimeCompare(u[:], wu[:])&subtle.ConstantTimeCompare(p[:], wp[:]) == 1
	if s.user == "" || !ok {
		time.Sleep(700 * time.Millisecond) // slow down guessing
		httpError(w, http.StatusUnauthorized, "wrong username or password")
		return
	}
	exp := strconv.FormatInt(time.Now().Add(sessionTTL).Unix(), 10)
	token := exp + "." + s.sign(exp)
	http.SetCookie(w, &http.Cookie{
		Name:     "shelf",
		Value:    token,
		Path:     "/",
		MaxAge:   int(sessionTTL.Seconds()),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https",
	})
	// Apps use the same signed value as a bearer token.
	writeJSON(w, map[string]any{"ok": true, "token": token, "expires": exp})
}

func (s *server) handleLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: "shelf", Value: "", Path: "/", MaxAge: -1, HttpOnly: true})
	writeJSON(w, map[string]any{"ok": true})
}

// ---- helpers -------------------------------------------------------------

var errHidden = errors.New("not found")

// resolve maps a client path to a cleaned slash path ("/a/b") and the
// absolute location on disk. Cleaning against "/" keeps it inside the root.
func (s *server) resolve(p string) (rel, abs string, err error) {
	if strings.ContainsRune(p, 0) {
		return "", "", errors.New("invalid path")
	}
	rel = path.Clean("/" + strings.ReplaceAll(p, "\\", "/"))
	if !s.showHidden {
		for _, seg := range strings.Split(rel, "/") {
			if strings.HasPrefix(seg, ".") {
				return "", "", errHidden
			}
		}
	}
	return rel, filepath.Join(s.root, filepath.FromSlash(rel)), nil
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// permissionHint explains a permission error in terms a server owner can act on.
func permissionHint() string {
	return fmt.Sprintf("permission denied: Shelf runs as user %d, group %d (PUID/PGID), which cannot write to this folder. "+
		"Give that user write access to the folder on the server, or set PUID/PGID to the folder's owner.", os.Getuid(), os.Getgid())
}

// writable reports whether the server process may create files in dir.
func writable(dir string) bool { return syscall.Access(dir, 2 /* W_OK */) == nil }

// fsError reports a filesystem error without leaking absolute paths.
func fsError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, errHidden):
		httpError(w, http.StatusNotFound, "not found")
	case errors.Is(err, fs.ErrPermission):
		httpError(w, http.StatusForbidden, permissionHint())
	case errors.Is(err, syscall.EROFS):
		httpError(w, http.StatusForbidden, "this folder is mounted read-only; give the container path Read/Write access")
	case errors.Is(err, syscall.ENOSPC), errors.Is(err, syscall.EDQUOT):
		httpError(w, http.StatusInsufficientStorage, "the disk is full")
	case errors.Is(err, fs.ErrExist):
		httpError(w, http.StatusConflict, "already exists")
	default:
		var pe *fs.PathError
		if errors.As(err, &pe) {
			httpError(w, http.StatusInternalServerError, pe.Op+": "+pe.Err.Error())
			return
		}
		var le *os.LinkError
		if errors.As(err, &le) {
			httpError(w, http.StatusInternalServerError, le.Op+": "+le.Err.Error())
			return
		}
		httpError(w, http.StatusBadRequest, err.Error())
	}
}
