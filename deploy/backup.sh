#!/usr/bin/env bash
#
# 自动备份 GMSR（数据库 + 配置 + 应用 jar）
#
# 用法：
#   ./backup.sh                       # 备份到 /root/backup，保留 14 天
#   BACKUP_DIR=/mnt/nas ./backup.sh   # 备份到另一块盘（推荐，见 RESTORE.md）
#
# 设计要点：
#   * 数据库导出后立刻用 pg_restore --list 校验，坏文件不会被当成好备份
#   * 配置包区分「必需文件」和「可选文件」：必需文件缺失直接失败，
#     不会出现「打了个不完整的包却报成功」
#   * 配置包创建时即 umask 077（内含 gmsr.env 的密码和密钥）
#   * 数据库与配置包都按 KEEP_DAYS 清理
#
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/gmsr/gmsr.env}"
BACKUP_DIR="${BACKUP_DIR:-/root/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
CONTAINER="${PG_CONTAINER:-1Panel-postgresql-2MLg}"
DB_NAME="${DB_NAME:-gmsr}"
DB_SCHEMA="${DB_SCHEMA:-gmsr}"
DB_USER="${DB_USER:-gmsr_app}"

die() { echo "✗ $*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || die "找不到 $ENV_FILE"
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${GMSR_DB_PASSWORD:?gmsr.env 里没有 GMSR_DB_PASSWORD}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/gmsr-$STAMP.dump"
TMP_IN_CONTAINER="/tmp/gmsr-backup-$STAMP.dump"

cleanup() { docker exec "$CONTAINER" rm -f "$TMP_IN_CONTAINER" 2>/dev/null || true; }
trap cleanup EXIT

echo "[$(date '+%F %T')] 开始备份 $DB_NAME.$DB_SCHEMA"

# ---------------------------------------------------------------- 1) 数据库
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    pg_dump -U "$DB_USER" -d "$DB_NAME" -n "$DB_SCHEMA" \
            --no-owner --no-acl -Fc -f "$TMP_IN_CONTAINER"

# 导出后立刻校验：读不出目录就说明文件是坏的
docker exec "$CONTAINER" pg_restore --list "$TMP_IN_CONTAINER" > /dev/null 2>&1 \
    || die "数据库备份校验失败（pg_restore --list 读不出来）"

docker cp "$CONTAINER:$TMP_IN_CONTAINER" "$OUT" > /dev/null
echo "  ✓ 数据库 $OUT ($(du -h "$OUT" | cut -f1))  校验通过"

# ---------------------------------------------------------------- 2) 配置
CONF="$BACKUP_DIR/gmsr-conf-$STAMP.tar.gz"

# 必需：缺任何一个都说明这台机器的部署不完整，备份应失败并报警
REQUIRED=(
    opt/gmsr/gmsr.env
    opt/1panel/www/conf.d/gmsr.conf
    etc/systemd/system/gmsr-backend.service
)
# 可选：没有也能恢复（例如不用语音时没有 livekit 配置）
OPTIONAL=(
    opt/1panel/www/conf.d/livekit-rtc.inc
    opt/gmsr/bin/backup.sh
    opt/gmsr/bin/restore.sh
    etc/systemd/system/gmsr-backup.service
    etc/systemd/system/gmsr-backup.timer
)

MISSING=()
for f in "${REQUIRED[@]}"; do
    [[ -f "/$f" ]] || MISSING+=("$f")
done
if [[ ${#MISSING[@]} -gt 0 ]]; then
    die "缺少必需配置文件，配置备份中止：${MISSING[*]}"
fi

INCLUDE=("${REQUIRED[@]}")
for f in "${OPTIONAL[@]}"; do
    [[ -f "/$f" ]] && INCLUDE+=("$f")
done

# 配置包里有密钥：创建时就限权，而不是事后 chmod
( umask 077; tar czf "$CONF" -C / "${INCLUDE[@]}" )

# 逐个确认必需文件真的在包里（tar 可能因为权限等原因静默跳过）
for f in "${REQUIRED[@]}"; do
    tar tzf "$CONF" "$f" > /dev/null 2>&1 \
        || die "配置包缺少 $f，备份不可信（已中止）"
done
echo "  ✓ 配置 $CONF ($(du -h "$CONF" | cut -f1))  含 ${#INCLUDE[@]} 个文件，必需项齐全"

# ---------------------------------------------------------------- 3) 应用 jar
if [[ -f /opt/gmsr/app/gmsr-scheduler.jar ]]; then
    cp /opt/gmsr/app/gmsr-scheduler.jar "$BACKUP_DIR/gmsr-scheduler-latest.jar"
    echo "  ✓ 应用 jar 已更新（覆盖式）"
else
    echo "  ⚠ 未找到应用 jar，跳过（不影响数据库与配置备份）" >&2
fi

# ---------------------------------------------------------------- 4) 清理过期
DELETED=0
while IFS= read -r old; do
    rm -f "$old" && DELETED=$((DELETED + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 \( -name 'gmsr-*.dump' -o -name 'gmsr-conf-*.tar.gz' \) \
             -type f -mtime "+$KEEP_DAYS")
[[ $DELETED -gt 0 ]] && echo "  清理了 $DELETED 个超过 ${KEEP_DAYS} 天的备份"

echo "  当前备份目录占用：$(du -sh "$BACKUP_DIR" | cut -f1)（数据库与配置包均保留 ${KEEP_DAYS} 天）"
