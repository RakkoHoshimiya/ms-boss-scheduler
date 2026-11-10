#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "$ROOT/.tools/note-backup-test.XXXXXX")"
case "$WORK" in "$ROOT/.tools/note-backup-test."*) ;; *) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/source/2026/11" "$WORK/restore"
printf 'image fixture' > "$WORK/source/2026/11/image with spaces.png"
bash "$ROOT/deploy/backup-attachments.sh" "$WORK/source" "$WORK/notes.tar.gz"
tar xzf "$WORK/notes.tar.gz" -C "$WORK/restore"
cmp "$WORK/source/2026/11/image with spaces.png" "$WORK/restore/2026/11/image with spaces.png"
if bash "$ROOT/deploy/backup-attachments.sh" "$WORK/source" "$WORK/source/recursive.tar.gz"; then echo 'Unsafe archive location was accepted' >&2; exit 1; fi
mkdir "$WORK/empty"
bash "$ROOT/deploy/backup-attachments.sh" "$WORK/empty" "$WORK/empty.tar.gz"
echo 'ATTACHMENT_BACKUP_PASS: nested names, restore round-trip, empty directory, recursion guard'
