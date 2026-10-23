#!/bin/sh
set -eu
umask 077
mkdir -p /backups
while true; do
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  target="/backups/munin-$stamp.dump"
  if pg_dump --format=custom --no-owner --file="$target.tmp" && pg_restore --list "$target.tmp" >/dev/null; then
    mv "$target.tmp" "$target"
    find /backups -name 'munin-*.dump' -mtime "+${BACKUP_RETENTION_DAYS:-7}" -delete
    echo "backup complete: $stamp"
    sleep 86400
  else
    rm -f "$target.tmp"
    echo 'backup failed; retry in 5 minutes' >&2
    sleep 300
  fi
done
