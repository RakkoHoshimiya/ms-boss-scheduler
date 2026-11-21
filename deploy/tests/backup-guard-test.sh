#!/usr/bin/env bash
#
# 备份脚本护栏的行为测试（不需要真实数据库）
#
# 必需配置缺失或附件归档失败时，脚本必须失败且不发布不完整备份。
#
# 用法：bash deploy/tests/backup-guard-test.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP="$ROOT/deploy/backup.sh"
# /tmp 在不少机器上是 noexec，工作目录放在仓库根下
WORK="$(mktemp -d "$ROOT/.backup-test.XXXXXX")"
case "$WORK" in "$ROOT/.backup-test."*) ;; *) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT

mkdir -p "$WORK/bin"

# 假的 docker：pg_dump / pg_restore --list 都成功；cp 要真的把文件建出来，
# 否则脚本会在 du 那一步因为文件不存在而失败（失败原因就不是「缺配置」了）。
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  cp)
      dest="${@: -1}"
      mkdir -p "$(dirname "$dest")"
      printf 'fixture database archive %s\n' "${ATTACHMENTS_FAILURE:-0}" > "$dest"
      exit 0
      ;;
esac
exit 0
STUB
chmod +x "$WORK/bin/docker"
cat > "$WORK/bin/date" <<'STUB'
#!/usr/bin/env bash
if [[ "$1" == '+%Y%m%d-%H%M%S' ]]; then
    printf '20261122-040000\n'
else
    exec /usr/bin/date "$@"
fi
STUB
chmod +x "$WORK/bin/date"
# This fixture verifies missing configuration after a successful export, not the Linux
# backup mutex. Git Bash has no flock; leave Linux CI using the real executable.
if ! command -v flock > /dev/null; then
    printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/bin/flock"
    chmod +x "$WORK/bin/flock"
fi

printf 'GMSR_DB_PASSWORD=dummy\n' > "$WORK/env"
mkdir -p "$WORK/bk"

echo "=== 备份脚本护栏行为测试 ==="

set +e
PATH="$WORK/bin:$PATH" ENV_FILE="$WORK/env" BACKUP_DIR="$WORK/bk" \
    bash "$BACKUP" > "$WORK/out.log" 2>&1
rc=$?
set -e

if [[ $rc -eq 0 ]]; then
    echo "  ✗ 缺必需配置时 backup.sh 竟然成功了"
    cat "$WORK/out.log"
    exit 1
fi

if ! grep -q '缺少必需配置文件' "$WORK/out.log"; then
    echo "  ✗ 失败原因不是配置缺失，实际输出："
    cat "$WORK/out.log"
    exit 1
fi

echo "  ✓ 缺必需配置时按预期失败（退出码 $rc）"
grep '缺少必需配置文件' "$WORK/out.log" | sed 's/^/    /'

assert_unpublished() {
    local path
    for path in "$WORK/bk/"* "$WORK/bk/".[!.]*; do
        [[ ! -e "$path" || "$path" == "$WORK/bk/.gmsr-backup.lock" ]] || {
            echo "  ✗ 失败后仍留有备份文件或暂存目录：$path" >&2
            exit 1
        }
    done
}
assert_unpublished

# Point configuration reads and archives at the fixture instead of host paths.
mkdir -p "$WORK/opt/gmsr" "$WORK/opt/1panel/www/conf.d" "$WORK/etc/systemd/system" "$WORK/attachments" "$WORK/scripts"
cp "$WORK/env" "$WORK/opt/gmsr/gmsr.env"
touch "$WORK/opt/1panel/www/conf.d/gmsr.conf" "$WORK/etc/systemd/system/gmsr-backend.service"
export WORK
sed -e 's|"/\$f"|"$WORK/$f"|g' -e 's| -C / | -C "$WORK" |g' "$BACKUP" > "$WORK/scripts/backup.sh"
cat > "$WORK/scripts/backup-attachments.sh" <<'STUB'
#!/usr/bin/env bash
if [[ "${ATTACHMENTS_FAILURE:-0}" == 1 ]]; then
    echo 'fixture: attachment archive failed' >&2
    exit 17
fi
tar czf "$2" -C "$1" .
STUB
cat > "$WORK/bin/mv" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
destination="${@: -1}"
case "$destination" in
    "$WORK/bk/"gmsr-notes-*.tar.gz)
        if [[ "${PUBLISH_FAILURE:-0}" == 1 ]]; then
            echo 'fixture: publication failed after first archive' >&2
            exit 19
        fi
        ;;
    "$WORK/bk/"gmsr-*.dump)
        stamp="${destination##*/gmsr-}"
        stamp="${stamp%.dump}"
        test -s "$WORK/bk/gmsr-conf-$stamp.tar.gz"
        test -s "$WORK/bk/gmsr-notes-$stamp.tar.gz"
        ;;
esac
exec /usr/bin/mv "$@"
STUB
chmod +x "$WORK/bin/mv"

run_backup() {
    PATH="$WORK/bin:$PATH" ENV_FILE="$WORK/env" BACKUP_DIR="$WORK/bk" \
        GMSR_NOTES_DIRECTORY="$WORK/attachments" ATTACHMENTS_FAILURE="$1" \
        bash "$WORK/scripts/backup.sh" > "$WORK/out.log" 2>&1
}
if run_backup 1; then
    echo '  ✗ 附件归档失败后仍报告备份成功' >&2
    exit 1
fi
grep -q 'fixture: attachment archive failed' "$WORK/out.log" || { cat "$WORK/out.log"; exit 1; }
assert_unpublished

if PUBLISH_FAILURE=1 run_backup 0; then
    echo '  ✗ 部分归档发布失败后仍报告备份成功' >&2
    exit 1
fi
grep -q 'fixture: publication failed after first archive' "$WORK/out.log" || { cat "$WORK/out.log"; exit 1; }
assert_unpublished

run_backup 0 || { cat "$WORK/out.log"; exit 1; }
shopt -s nullglob
dumps=("$WORK/bk/"gmsr-*.dump)
[[ ${#dumps[@]} == 1 ]] || { echo '  ✗ 成功后未生成唯一的数据库备份' >&2; exit 1; }
stamp="${dumps[0]##*/gmsr-}"
stamp="${stamp%.dump}"
test -s "$WORK/bk/gmsr-conf-$stamp.tar.gz"
test -s "$WORK/bk/gmsr-notes-$stamp.tar.gz"
echo '  ✓ 附件失败时无残留，成功时生成同一时间戳的完整备份'

archives=("${dumps[0]}" "$WORK/bk/gmsr-conf-$stamp.tar.gz" "$WORK/bk/gmsr-notes-$stamp.tar.gz")
sha256sum "${archives[@]}" > "$WORK/complete-bundle.sha256"
printf '# changed fixture configuration\n' >> "$WORK/opt/gmsr/gmsr.env"
if run_backup 1; then
    echo '  ✗ 同一时间戳的失败备份报告成功' >&2
    exit 1
fi
sha256sum -c "$WORK/complete-bundle.sha256" > /dev/null
echo '  ✓ 后续失败的同一时间戳备份保留了已有完整备份'

echo "结果：全部通过"
