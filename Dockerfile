FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /shelf .

FROM alpine:3.22
# ffmpeg makes the video thumbnails (and HEIC/TIFF previews).
RUN apk add --no-cache ffmpeg su-exec && mkdir -p /data /cache
COPY --from=build /shelf /usr/local/bin/shelf
COPY entrypoint.sh /entrypoint.sh
ENV SHELF_ROOT=/data SHELF_CACHE=/cache SHELF_ADDR=:8080 PUID=1000 PGID=1000 UMASK=022
VOLUME /cache
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -q --spider http://127.0.0.1:8080/api/me || exit 1
ENTRYPOINT ["/entrypoint.sh"]
