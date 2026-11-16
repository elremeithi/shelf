#!/bin/sh
# Starts Shelf as PUID:PGID (Unraid uses 99:100) with the requested UMASK.
set -e
umask "${UMASK:-022}"

if [ "$(id -u)" = "0" ]; then
	PUID="${PUID:-1000}"
	PGID="${PGID:-1000}"
	# A bind-mounted cache folder is created by Docker as root; hand it over.
	mkdir -p "$SHELF_CACHE"
	if [ "$(stat -c %u:%g "$SHELF_CACHE")" != "$PUID:$PGID" ]; then
		chown -R "$PUID:$PGID" "$SHELF_CACHE"
	fi
	exec su-exec "$PUID:$PGID" shelf "$@"
fi

# Started with --user: already unprivileged, nothing to switch.
exec shelf "$@"
