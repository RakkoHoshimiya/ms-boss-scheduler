#!/usr/bin/env bash
#
# 自动备份 GMSR 数据库（PostgreSQL，custom 格式 + 完整性校验）
#
# 用法：
#   ./backup.sh                  # 备份到 /root/backup，保留 14 天
#   BACKUP_DIR=/mnt/nas ./backup.sh
#
# 依赖：宿主机有 docker，能 exec 进 1Panel 的 postgres 容器。
# 连接信息从 /opt/gmsr/gmsr.env 读取（GMSR_DB_PASSWORD 等），不写死在脚本里。
#
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/gmsr/gmsr.env}"
BACKUP_DIR="${BACKUP_DIR:-/root/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
CONTAINER="${PG_CONTAINER:-1Panel-postgresql-2MLg}"
DB_NAME="${DB_NAME:-gmsr}"
DB_SCHEMA="${DB_SCHEMA:-gmsr}"
DB_USER="${DB_USER:-gmsr_app}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "✗ 找不到 $ENV_FILE" >&2
    exit 1
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${GMSR_DB_PASSWORD:?gmsr.env 里没有 GMSR_DB_PASSWORD}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/gmsr-$STAMP.dump"
TMP_IN_CONTAINER="/tmp/gmsr-backup-$STAMP.dump"

cleanup() {
    docker exec "$CONTAINER" rm -f "$TMP_IN_CONTAINER" 2>/dev/null || true
}
trap cleanup EXIT

echo "[$(date '+%F %T')] 开始备份 $DB_NAME.$DB_SCHEMA"

# 1) 在容器内导出（-Fc 便于 pg_restore 选择性恢复；--no-owner 便于换环境恢复）
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    pg_dump -U "$DB_USER" -d "$DB_NAME" -n "$DB_SCHEMA" \
            --no-owner --no-acl -Fc -f "$TMP_IN_CONTAINER"

# 2) 立刻做完整性校验：能读出目录结构才算有效备份
if ! docker exec "$CONTAINER" pg_restore --list "$TMP_IN_CONTAINER" > /dev/null 2>&1; then
    echo "✗ 备份文件校验失败（pg_restore --list 读不出来）" >&2
    exit 1
fi

# 3) 拷回宿主机
docker cp "$CONTAINER:$TMP_IN_CONTAINER" "$OUT" > /dev/null
SIZE="$(du -h "$OUT" | cut -f1)"
echo "[$(date '+%F %T')] ✓ 备份完成 $OUT ($SIZE)"

# 4) 清理过期备份（只删自己的命名格式，避免误删别的文件）
DELETED=0
while IFS= read -r old; do
    rm -f "$old" && DELETED=$((DELETED + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -name 'gmsr-*.dump' -type f -mtime "+$KEEP_DAYS")
[[ $DELETED -gt 0 ]] && echo "  清理了 $DELETED 个超过 ${KEEP_DAYS} 天的备份"

# 5) 备份配置（只靠数据库恢复不起来：还需要环境变量、站点配置、systemd 单元）
CONF="$BACKUP_DIR/gmsr-conf-$STAMP.tar.gz"
tar czf "$CONF" -C / \
    opt/gmsr/gmsr.env \
    opt/gmsr/bin \
    opt/1panel/www/conf.d/gmsr.conf \
    opt/1panel/www/conf.d/livekit-rtc.inc \
    etc/systemd/system/gmsr-backend.service \
    etc/systemd/system/gmsr-backup.service \
    etc/systemd/system/gmsr-backup.timer \
    2>/dev/null || true
if [[ -s "$CONF" ]]; then
    echo "  ✓ 配置备份 $CONF ($(du -h "$CONF" | cut -f1))"
    chmod 600 "$CONF"   # 里面含 gmsr.env 的密钥
else
    echo "  ⚠ 配置备份失败（不影响数据库备份）" >&2
fi

# jar 单独存一份、覆盖式更新：从源码可以重建，但留着能省一次构建
if [[ -f /opt/gmsr/app/gmsr-scheduler.jar ]]; then
    cp /opt/gmsr/app/gmsr-scheduler.jar "$BACKUP_DIR/gmsr-scheduler-latest.jar"
    echo "  ✓ 应用 jar 已更新（覆盖式）"
fi

# 6) 提示总量
echo "  当前备份目录占用：$(du -sh "$BACKUP_DIR" | cut -f1)（保留最近 ${KEEP_DAYS} 天）"
