package main

// On-the-fly transcoding to HLS (H.264 + AAC in MPEG-TS segments).
//
// The playlist is written up front from the file's duration, so the player
// knows the full length and can seek anywhere. Segments are only encoded
// when asked for: a request for a segment that does not exist yet starts
// (or re-aims) ffmpeg at that position.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	segSeconds   = 4
	maxAhead     = 15 // pause the encoder when this many segments ahead of the player
	maxEncoders  = 2  // simultaneous ffmpeg transcodes
	encoderIdle  = 90 * time.Second
	segmentsIdle = 15 * time.Minute
)

// Video presets: target height (0 = keep source size) and x264 quality.
var qualities = map[string]struct {
	height int
	crf    int
}{
	"src":  {0, 18},
	"1080": {1080, 21},
	"720":  {720, 22},
	"480":  {480, 23},
}

type mediaInfo struct {
	Duration float64 `json:"duration"`
	Video    string  `json:"video,omitempty"` // codec name
	Audio    string  `json:"audio,omitempty"`
	Width    int     `json:"width,omitempty"`
	Height   int     `json:"height,omitempty"`
	PixFmt   string  `json:"pixfmt,omitempty"`
}

type transcoder struct {
	dir string

	mu     sync.Mutex
	jobs   map[string]*hlsJob
	probes map[string]*mediaInfo
}

type hlsJob struct {
	key, dir, abs string
	quality       string
	audioOnly     bool
	segments      int

	mu      sync.Mutex
	cmd     *exec.Cmd
	done    chan struct{} // closed when cmd has exited
	start   int           // first segment of the current run
	next    int           // next segment the current run will finish
	paused  bool
	lastReq int
	lastUse time.Time
}

func newTranscoder(cache string) *transcoder {
	t := &transcoder{dir: filepath.Join(cache, "hls"), jobs: map[string]*hlsJob{}, probes: map[string]*mediaInfo{}}
	os.RemoveAll(t.dir) // segments from a previous run are never reused
	go t.janitor()
	return t
}

// ---- probing -------------------------------------------------------------

func (t *transcoder) probe(abs string, fi os.FileInfo) (*mediaInfo, error) {
	key := fmt.Sprintf("%s|%d|%d", abs, fi.Size(), fi.ModTime().UnixNano())
	t.mu.Lock()
	if mi := t.probes[key]; mi != nil {
		t.mu.Unlock()
		return mi, nil
	}
	t.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe",
		"-print_format", "json", "-show_format", "-show_streams", "file:"+abs).Output()
	if err != nil {
		return nil, err
	}
	var raw struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
		Streams []struct {
			CodecType   string `json:"codec_type"`
			CodecName   string `json:"codec_name"`
			Width       int    `json:"width"`
			Height      int    `json:"height"`
			PixFmt      string `json:"pix_fmt"`
			Disposition struct {
				AttachedPic int `json:"attached_pic"`
			} `json:"disposition"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return nil, err
	}
	mi := &mediaInfo{}
	mi.Duration, _ = strconv.ParseFloat(raw.Format.Duration, 64)
	for _, s := range raw.Streams {
		switch {
		case s.CodecType == "video" && s.Disposition.AttachedPic == 0 && mi.Video == "":
			mi.Video, mi.Width, mi.Height, mi.PixFmt = s.CodecName, s.Width, s.Height, s.PixFmt
		case s.CodecType == "audio" && mi.Audio == "":
			mi.Audio = s.CodecName
		}
	}
	t.mu.Lock()
	if len(t.probes) > 1000 {
		clear(t.probes)
	}
	t.probes[key] = mi
	t.mu.Unlock()
	return mi, nil
}

// mediaFile resolves and validates the "p" parameter of a media request.
func (s *server) mediaFile(w http.ResponseWriter, r *http.Request) (rel, abs string, fi os.FileInfo, mi *mediaInfo, ok bool) {
	rel, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	fi, err = os.Stat(abs)
	if err != nil || fi.IsDir() {
		httpError(w, http.StatusNotFound, "not found")
		return
	}
	if k := kindOf(rel); k != "v" && k != "a" {
		httpError(w, http.StatusUnsupportedMediaType, "not a media file")
		return
	}
	mi, err = s.tc.probe(abs, fi)
	if err != nil || (mi.Video == "" && mi.Audio == "") {
		httpError(w, http.StatusUnsupportedMediaType, "cannot read this media file")
		return
	}
	return rel, abs, fi, mi, true
}

func (s *server) handleMediaInfo(w http.ResponseWriter, r *http.Request) {
	if _, _, _, mi, ok := s.mediaFile(w, r); ok {
		writeJSON(w, mi)
	}
}

// ---- HLS endpoints -------------------------------------------------------

func (s *server) hlsJobFor(w http.ResponseWriter, r *http.Request) *hlsJob {
	rel, abs, fi, mi, ok := s.mediaFile(w, r)
	if !ok {
		return nil
	}
	if mi.Duration <= 0 {
		httpError(w, http.StatusUnsupportedMediaType, "unknown duration, cannot transcode")
		return nil
	}
	q := r.URL.Query().Get("q")
	if _, known := qualities[q]; !known {
		q = "src"
	}
	audioOnly := mi.Video == "" || kindOf(rel) == "a"
	if audioOnly {
		q = "src"
	}
	sum := sha256.Sum256(fmt.Appendf(nil, "%s|%d|%d|%s", rel, fi.Size(), fi.ModTime().UnixNano(), q))
	key := hex.EncodeToString(sum[:12])

	t := s.tc
	t.mu.Lock()
	defer t.mu.Unlock()
	j := t.jobs[key]
	if j == nil {
		n := int(mi.Duration / segSeconds)
		if mi.Duration-float64(n*segSeconds) > 0.05 {
			n++
		}
		j = &hlsJob{key: key, dir: filepath.Join(t.dir, key), abs: abs, quality: q, audioOnly: audioOnly,
			segments: max(1, n), lastUse: time.Now()}
		j.buildPlaylist(mi.Duration)
		t.jobs[key] = j
	}
	return j
}

// The playlist template of each job; "%s" stands for the segment URL prefix.
var playlists sync.Map // job key -> string

func (j *hlsJob) buildPlaylist(total float64) {
	var b strings.Builder
	fmt.Fprintf(&b, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n", segSeconds)
	for i := 0; i < j.segments; i++ {
		d := float64(segSeconds)
		if i == j.segments-1 {
			d = total - float64(i*segSeconds)
		}
		fmt.Fprintf(&b, "#EXTINF:%.3f,\n%%s&n=%d\n", d, i)
	}
	b.WriteString("#EXT-X-ENDLIST\n")
	playlists.Store(j.key, b.String())
}

func (s *server) handleHLSIndex(w http.ResponseWriter, r *http.Request) {
	j := s.hlsJobFor(w, r)
	if j == nil {
		return
	}
	tpl, _ := playlists.Load(j.key)
	q := r.URL.Query()
	base := "seg?p=" + urlEscape(q.Get("p")) + "&q=" + j.quality + "&v=" + urlEscape(q.Get("v"))
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	fmt.Fprint(w, strings.ReplaceAll(tpl.(string), "%s", base))
}

func urlEscape(v string) string { return url.QueryEscape(v) }

func (s *server) handleHLSSegment(w http.ResponseWriter, r *http.Request) {
	j := s.hlsJobFor(w, r)
	if j == nil {
		return
	}
	n, err := strconv.Atoi(r.URL.Query().Get("n"))
	if err != nil || n < 0 || n >= j.segments {
		httpError(w, http.StatusNotFound, "no such segment")
		return
	}
	file := j.segPath(n)
	deadline := time.Now().Add(90 * time.Second)
	exits := 0
	for {
		done, err := s.tc.ensure(j, n)
		if err != nil {
			httpError(w, http.StatusInternalServerError, "transcoder failed to start")
			return
		}
		if fileExists(file) {
			break
		}
		select {
		case <-r.Context().Done():
			return
		case <-done:
			// The encoder stopped: it finished, another request re-aimed it,
			// or the file is shorter than its header claims. Retry a few
			// times before giving up on this segment.
			if exits++; exits > 3 && !fileExists(file) {
				httpError(w, http.StatusNotFound, "segment not available")
				return
			}
		case <-time.After(60 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			httpError(w, http.StatusGatewayTimeout, "transcoding too slow")
			return
		}
	}
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, file)
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// ---- encoder control -----------------------------------------------------

func (j *hlsJob) segPath(n int) string { return filepath.Join(j.dir, strconv.Itoa(n)+".ts") }

func (j *hlsJob) running() bool {
	if j.cmd == nil {
		return false
	}
	select {
	case <-j.done:
		return false
	default:
		return true
	}
}

// advance moves next past every segment the current run has completed.
func (j *hlsJob) advance() {
	for j.next < j.segments && fileExists(j.segPath(j.next)) {
		j.next++
	}
}

func (j *hlsJob) stop() {
	if j.cmd == nil {
		return
	}
	if j.running() {
		j.cmd.Process.Kill()
		<-j.done
	}
	j.cmd, j.paused = nil, false
}

func (j *hlsJob) setPaused(p bool) {
	if !j.running() || j.paused == p {
		return
	}
	sig := syscall.SIGCONT
	if p {
		sig = syscall.SIGSTOP
	}
	if j.cmd.Process.Signal(sig) == nil {
		j.paused = p
	}
}

// ensure makes sure an encoder is working towards segment n and returns a
// channel that closes when that encoder exits.
func (t *transcoder) ensure(j *hlsJob, n int) (<-chan struct{}, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.lastReq, j.lastUse = n, time.Now()
	if fileExists(j.segPath(n)) {
		return j.done, nil
	}
	j.advance()
	// Reuse the running encoder only if it will reach n soon.
	if j.running() && n >= j.start && n <= j.next+3 {
		j.setPaused(false)
		return j.done, nil
	}
	j.stop()
	t.makeRoom(j)
	if err := j.startAt(n); err != nil {
		return nil, err
	}
	return j.done, nil
}

// makeRoom stops the least recently used other encoder when too many run.
func (t *transcoder) makeRoom(keep *hlsJob) {
	t.mu.Lock()
	var others []*hlsJob
	for _, o := range t.jobs {
		if o != keep {
			others = append(others, o)
		}
	}
	t.mu.Unlock()
	var busy []*hlsJob
	for _, o := range others {
		o.mu.Lock()
		if o.running() {
			busy = append(busy, o)
		}
		o.mu.Unlock()
	}
	for len(busy) >= maxEncoders {
		oldest := 0
		for i, o := range busy {
			if o.lastUse.Before(busy[oldest].lastUse) {
				oldest = i
			}
		}
		o := busy[oldest]
		o.mu.Lock()
		o.stop()
		o.mu.Unlock()
		busy = append(busy[:oldest], busy[oldest+1:]...)
	}
}

func (j *hlsJob) startAt(n int) error {
	if err := os.MkdirAll(j.dir, 0o755); err != nil {
		return err
	}
	at := n * segSeconds
	args := []string{"-v", "error", "-nostdin", "-protocol_whitelist", "file,pipe"}
	if at > 0 {
		args = append(args, "-ss", strconv.Itoa(at))
	}
	args = append(args, "-i", "file:"+j.abs, "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1")
	if j.audioOnly {
		args = append(args, "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", "256k", "-ac", "2")
	} else {
		q := qualities[j.quality]
		// Even dimensions and 8-bit 4:2:0 are what every H.264 decoder accepts.
		scale := "scale=trunc(iw/2)*2:trunc(ih/2)*2"
		if q.height > 0 {
			scale = fmt.Sprintf("scale=-2:'min(%d,trunc(ih/2)*2)'", q.height)
		}
		args = append(args,
			"-map", "0:v:0", "-map", "0:a:0?",
			"-c:v", "libx264", "-preset", "veryfast", "-crf", strconv.Itoa(q.crf),
			"-vf", scale+",format=yuv420p",
			// A keyframe exactly at every segment boundary, wherever we start.
			"-force_key_frames", fmt.Sprintf("expr:gte(t,n_forced*%d)", segSeconds),
			"-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
		)
	}
	args = append(args,
		// Shift timestamps to the seek position so segments from different
		// runs sit on one continuous timeline.
		"-output_ts_offset", strconv.Itoa(at), "-max_muxing_queue_size", "4096",
		"-f", "hls", "-hls_time", strconv.Itoa(segSeconds), "-hls_list_size", "0",
		"-hls_segment_type", "mpegts", "-hls_flags", "temp_file",
		"-start_number", strconv.Itoa(n),
		"-hls_segment_filename", filepath.Join(j.dir, "%d.ts"),
		filepath.Join(j.dir, "ffmpeg.m3u8"),
	)
	cmd := exec.Command("ffmpeg", args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	done := make(chan struct{})
	j.cmd, j.done, j.start, j.next, j.paused = cmd, done, n, n, false
	go func() {
		err := cmd.Wait()
		if err != nil && cmd.ProcessState != nil && cmd.ProcessState.ExitCode() > 0 {
			log.Printf("transcode %s: %v: %s", filepath.Base(j.abs), err, lastLine(stderr.String()))
		}
		close(done)
	}()
	return nil
}

func (t *transcoder) stopAll() {
	t.mu.Lock()
	defer t.mu.Unlock()
	for _, j := range t.jobs {
		j.mu.Lock()
		j.stop()
		j.mu.Unlock()
	}
	os.RemoveAll(t.dir)
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return lines[len(lines)-1]
}

// janitor throttles encoders that are far ahead of their player, stops idle
// ones, and deletes segments nobody has asked for in a while.
func (t *transcoder) janitor() {
	for range time.Tick(time.Second) {
		t.mu.Lock()
		jobs := make([]*hlsJob, 0, len(t.jobs))
		for _, j := range t.jobs {
			jobs = append(jobs, j)
		}
		t.mu.Unlock()
		for _, j := range jobs {
			j.mu.Lock()
			idle := time.Since(j.lastUse)
			if j.running() {
				j.advance()
				if idle > encoderIdle {
					j.stop()
				} else {
					j.setPaused(j.next-j.lastReq > maxAhead)
				}
			}
			expired := idle > segmentsIdle
			if expired {
				j.stop()
				os.RemoveAll(j.dir)
			}
			j.mu.Unlock()
			if expired {
				t.mu.Lock()
				delete(t.jobs, j.key)
				t.mu.Unlock()
				playlists.Delete(j.key)
			}
		}
	}
}
