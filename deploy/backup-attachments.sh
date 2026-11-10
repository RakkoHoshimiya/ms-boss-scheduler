#!/usr/bin/env bash
# Archive the private attachment tree; output must be outside the source tree.
set -euo pipefail
umask 077
[[ $# == 2 ]] || { echo 'Usage: backup-attachments.sh DIRECTORY ARCHIVE' >&2; exit 1; }
source_dir="$(realpath -e -- "$1")"
archive="$(realpath -m -- "$2")"
[[ -d "$source_dir" ]] || { echo 'Attachment directory is missing' >&2; exit 1; }
case "$archive" in "$source_dir"|"$source_dir"/*) echo 'Archive must be outside attachment directory' >&2; exit 1;; esac
trap 'rm -f -- "$archive.tmp"' EXIT
tar czf "$archive.tmp" -C "$source_dir" .
tar tzf "$archive.tmp" > /dev/null
mv -- "$archive.tmp" "$archive"
echo 'Attachment archive verified'
