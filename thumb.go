package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	_ "golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

const (
	thumbSize   = 512  // grid thumbnails
	previewSize = 2560 // full-screen stand-in for formats browsers cannot show
	maxPixels   = 150e6
)

// handleThumb serves a cached JPEG rendition of an image or video frame.
// The cache key includes size and mtime, so a changed file gets a new thumb.
func (s *server) handleThumb(w http.ResponseWriter, r *http.Request) {
	rel, abs, err := s.resolve(r.URL.Query().Get("p"))
	if err != nil {
		fsError(w, err)
		return
	}
	fi, err := os.Stat(abs)
	if err != nil || fi.IsDir() {
		httpError(w, http.StatusNotFound, "not found")
		return
	}
	kind := kindOf(rel)
	if kind != "i" && kind != "v" {
		httpError(w, http.StatusNotFound, "no thumbnail for this type")
		return
	}
	size, tag := thumbSize, "t"
	if r.URL.Query().Get("s") == "l" {
		size, tag = previewSize, "l"
	}
	sum := sha256.Sum256(fmt.Appendf(nil, "%s|%d|%d|%s|v2", rel, fi.Size(), fi.ModTime().UnixNano(), tag))
	key := hex.EncodeToString(sum[:16])
	cp := filepath.Join(s.cache, key[:2], key+".jpg")

	if s.serveThumb(w, r, cp) {
		return
	}

	// Collapse concurrent requests for the same thumbnail into one job.
	s.flightM.Lock()
	if ch, busy := s.flight[key]; busy {
		s.flightM.Unlock()
		select {
		case <-ch:
		case <-r.Context().Done():
			return
		}
		if !s.serveThumb(w, r, cp) {
			httpError(w, http.StatusServiceUnavailable, "thumbnail not ready")
		}
		return
	}
	ch := make(chan struct{})
	s.flight[key] = ch
	s.flightM.Unlock()
	defer func() {
		s.flightM.Lock()
		delete(s.flight, key)
		s.flightM.Unlock()
		close(ch)
	}()

	// Wait for a worker slot, but give up if the client scrolled away.
	select {
	case s.sem <- struct{}{}:
	case <-r.Context().Done():
		return
	}
	data, err := makeThumb(abs, kind, size)
	<-s.sem

	os.MkdirAll(filepath.Dir(cp), 0o755)
	if err != nil {
		data = nil // an empty file remembers the failure so we do not retry forever
	}
	tmp := cp + ".tmp"
	if werr := os.WriteFile(tmp, data, 0o644); werr == nil {
		os.Rename(tmp, cp)
	}
	if err != nil {
		httpError(w, http.StatusUnsupportedMediaType, "cannot create thumbnail")
		return
	}
	writeThumb(w, r, bytes.NewReader(data), time.Now())
}

func (s *server) serveThumb(w http.ResponseWriter, r *http.Request, cp string) bool {
	f, err := os.Open(cp)
	if err != nil {
		return false
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return false
	}
	if fi.Size() == 0 {
		httpError(w, http.StatusUnsupportedMediaType, "cannot create thumbnail")
		return true
	}
	writeThumb(w, r, f, fi.ModTime())
	return true
}

func writeThumb(w http.ResponseWriter, r *http.Request, rs io.ReadSeeker, mod time.Time) {
	w.Header().Set("Content-Type", "image/jpeg")
	// The URL carries the file's mtime, so the response never goes stale.
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	http.ServeContent(w, r, "", mod, rs)
}

func makeThumb(abs, kind string, size int) ([]byte, error) {
	if kind == "i" {
		// Big JPEGs: ffmpeg can decode at a fraction of the resolution, which
		// is several times faster than a full decode in Go.
		if b, err := fastJPEGThumb(abs, size); err == nil {
			return b, nil
		}
		if b, err := nativeThumb(abs, size); err == nil {
			return b, nil
		}
		return ffmpegThumb(abs, size, nil, "")
	}
	// Skip a few seconds in to avoid black intro frames; short clips fall back to 0.
	if b, err := ffmpegThumb(abs, size, []string{"-ss", "3"}, ""); err == nil {
		return b, nil
	}
	return ffmpegThumb(abs, size, nil, "")
}

var orientFilter = map[int]string{
	2: "hflip,", 3: "hflip,vflip,", 4: "vflip,",
	5: "transpose=0,", 6: "transpose=1,", 7: "transpose=3,", 8: "transpose=2,",
}

func fastJPEGThumb(abs string, size int) ([]byte, error) {
	f, err := os.Open(abs)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	cfg, format, err := image.DecodeConfig(bufio.NewReader(f))
	if err != nil || format != "jpeg" || cfg.Width*cfg.Height < 3e6 {
		return nil, errors.New("not a large jpeg")
	}
	f.Seek(0, io.SeekStart)
	orient := jpegOrientation(bufio.NewReader(f))
	lowres := 0
	for lowres < 3 && max(cfg.Width, cfg.Height)>>(lowres+1) >= 2*size {
		lowres++
	}
	// We rotate ourselves so the result is the same on every ffmpeg version.
	return ffmpegThumb(abs, size, []string{"-noautorotate", "-lowres", fmt.Sprint(lowres)}, orientFilter[orient])
}

// ---- pure Go path: JPEG, PNG, GIF, WebP, BMP ------------------------------

func nativeThumb(abs string, size int) ([]byte, error) {
	f, err := os.Open(abs)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	cfg, format, err := image.DecodeConfig(bufio.NewReader(f))
	if err != nil {
		return nil, err
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || float64(cfg.Width)*float64(cfg.Height) > maxPixels {
		return nil, errors.New("image too large")
	}
	orient := 1
	if format == "jpeg" {
		f.Seek(0, io.SeekStart)
		orient = jpegOrientation(bufio.NewReader(f))
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	src, _, err := image.Decode(bufio.NewReaderSize(f, 1<<16))
	if err != nil {
		return nil, err
	}
	sb := src.Bounds()
	w, h := sb.Dx(), sb.Dy()
	if w > size || h > size {
		if w >= h {
			w, h = size, max(1, h*size/w)
		} else {
			w, h = max(1, w*size/h), size
		}
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src) // flatten transparency
	// Halve with a cheap filter until close, then finish with a good one.
	for sb.Dx() >= 4*w && sb.Dy() >= 4*h {
		half := image.NewRGBA(image.Rect(0, 0, sb.Dx()/2, sb.Dy()/2))
		draw.ApproxBiLinear.Scale(half, half.Bounds(), src, sb, draw.Src, nil)
		src, sb = half, half.Bounds()
	}
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, sb, draw.Over, nil)

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, applyOrientation(dst, orient), &jpeg.Options{Quality: 82}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// jpegOrientation returns the EXIF orientation (1-8), or 1 if absent.
func jpegOrientation(r *bufio.Reader) int {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:2]); err != nil || b[0] != 0xFF || b[1] != 0xD8 {
		return 1
	}
	for {
		if _, err := io.ReadFull(r, b[:4]); err != nil || b[0] != 0xFF {
			return 1
		}
		marker, n := b[1], int(binary.BigEndian.Uint16(b[2:4]))-2
		if n < 0 || marker == 0xDA || marker == 0xD9 {
			return 1
		}
		if marker != 0xE1 {
			if _, err := r.Discard(n); err != nil {
				return 1
			}
			continue
		}
		d := make([]byte, n)
		if _, err := io.ReadFull(r, d); err != nil {
			return 1
		}
		if len(d) < 14 || string(d[:6]) != "Exif\x00\x00" {
			continue // e.g. an XMP segment
		}
		t := d[6:]
		var bo binary.ByteOrder = binary.BigEndian
		if string(t[:2]) == "II" {
			bo = binary.LittleEndian
		}
		off := int(bo.Uint32(t[4:8]))
		if off+2 > len(t) {
			return 1
		}
		count := int(bo.Uint16(t[off:]))
		for i := 0; i < count; i++ {
			e := off + 2 + i*12
			if e+12 > len(t) {
				return 1
			}
			if bo.Uint16(t[e:]) == 0x0112 {
				if o := int(bo.Uint16(t[e+8:])); o >= 1 && o <= 8 {
					return o
				}
				return 1
			}
		}
		return 1
	}
}

func applyOrientation(src *image.RGBA, o int) image.Image {
	if o <= 1 || o > 8 {
		return src
	}
	w, h := src.Bounds().Dx(), src.Bounds().Dy()
	dw, dh := w, h
	if o >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var dx, dy int
			switch o {
			case 2:
				dx, dy = w-1-x, y
			case 3:
				dx, dy = w-1-x, h-1-y
			case 4:
				dx, dy = x, h-1-y
			case 5:
				dx, dy = y, x
			case 6:
				dx, dy = h-1-y, x
			case 7:
				dx, dy = h-1-y, w-1-x
			case 8:
				dx, dy = y, w-1-x
			}
			dst.SetRGBA(dx, dy, src.RGBAAt(x, y))
		}
	}
	return dst
}

// ---- ffmpeg path: video frames and every other image format ---------------

func ffmpegThumb(abs string, size int, inOpts []string, preFilter string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	args := []string{"-v", "error", "-nostdin", "-protocol_whitelist", "file,pipe"}
	args = append(args, inOpts...)
	args = append(args,
		"-i", "file:"+abs,
		"-map", "0:v:0", "-an", "-sn", "-dn",
		"-frames:v", "1",
		"-vf", preFilter+fmt.Sprintf("scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease:flags=bicubic", size, size),
		"-pix_fmt", "yuvj420p", "-q:v", "4",
		"-f", "image2pipe", "-c:v", "mjpeg", "pipe:1",
	)
	out, err := exec.CommandContext(ctx, "ffmpeg", args...).Output()
	if err != nil {
		return nil, err
	}
	if len(out) < 100 {
		return nil, errors.New("no frame decoded")
	}
	return out, nil
}
