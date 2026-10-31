#!/usr/bin/env bash
#
# 从备份恢复 GMSR 数据库
#
# 安全设计：**默认绝不覆盖生产库**。
#   - 恢复目标必须是新库名或显式 --force；
#   - 演练推荐恢复到独立库，验证完再删除。
#
# 用法：
#   ./restore.sh /root/backup/gmsr-20261031-215941.dump gmsr_drill
#       → 恢复到独立库 gmsr_drill（不存在会自动创建），演练用
#
#   ./restore.sh /root/backup/gmsr-xxx.dump gmsr --force
#       → 真正覆盖生产库（会先自动备份现有数据）
#
set -euo pipefail

DUMP="${1:-}"
TARGET_DB="${2:-}"
FORCE="${3:-}"

ENV_FILE="${ENV_FILE:-/opt/gmsr/gmsr.env}"
CONTAINER="${PG_CONTAINER:-1Panel-postgresql-2MLg}"
PROD_DB="${PROD_DB:-gmsr}"
ROOT_ENV="/opt/1panel/apps/postgresql/postgresql/.env"

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ -z "$DUMP" || -z "$TARGET_DB" ]] && usage
[[ ! -f "$DUMP" ]] && { echo "✗ 备份文件不存在：$DUMP" >&2; exit 1; }

if [[ "$TARGET_DB" == "$PROD_DB" && "$FORCE" != "--force" ]]; then
    cat >&2 <<EOF
✗ 拒绝覆盖生产库 "$PROD_DB"。

  演练请恢复到独立库：
      $0 $DUMP gmsr_drill

  确实要覆盖生产库（会先自动备份）：
      $0 $DUMP $PROD_DB --force
EOF
    exit 1
fi

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${GMSR_DB_PASSWORD:?gmsr.env 里没有 GMSR_DB_PASSWORD}"

# 建库需要超级用户
if [[ -f "$ROOT_ENV" ]]; then
    # shellcheck disable=SC1090
    set -a; . "$ROOT_ENV"; set +a
else
    echo "✗ 找不到 $ROOT_ENV（建库需要 PostgreSQL 超级用户）" >&2
    exit 1
fi
: "${PANEL_DB_ROOT_USER:?}" "${PANEL_DB_ROOT_PASSWORD:?}"

echo "[$(date '+%F %T')] 校验备份文件"
if ! docker exec "$CONTAINER" sh -c 'true' 2>/dev/null; then
    echo "✗ 无法访问容器 $CONTAINER" >&2
    exit 1
fi

# 覆盖生产库前先自救一份
if [[ "$TARGET_DB" == "$PROD_DB" ]]; then
    SELF="$(dirname "$DUMP")/pre-restore-$(date +%Y%m%d-%H%M%S).dump"
    echo "  先备份当前生产库到 $SELF"
    docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        pg_dump -U gmsr_app -d "$PROD_DB" -n gmsr --no-owner --no-acl -Fc -f /tmp/pre-restore.dump
    docker cp "$CONTAINER:/tmp/pre-restore.dump" "$SELF" > /dev/null
    docker exec "$CONTAINER" rm -f /tmp/pre-restore.dump
    echo "  ✓ 已保存 $SELF"
fi

echo "[$(date '+%F %T')] 重建目标库 $TARGET_DB"
docker exec -e PGPASSWORD="$PANEL_DB_ROOT_PASSWORD" "$CONTAINER" \
    psql -U "$PANEL_DB_ROOT_USER" -d postgres -q \
    -c "drop database if exists \"$TARGET_DB\"" \
    -c "create database \"$TARGET_DB\" owner gmsr_app"

echo "[$(date '+%F %T')] 恢复数据"
docker cp "$DUMP" "$CONTAINER:/tmp/restore.dump" > /dev/null
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    pg_restore -U gmsr_app -d "$TARGET_DB" --no-owner --no-acl /tmp/restore.dump
docker exec "$CONTAINER" rm -f /tmp/restore.dump

echo "[$(date '+%F %T')] ✓ 恢复完成，核对行数："
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    psql -U "$GMSR_DB_USER" -d "$TARGET_DB" -t -A -c "
      select '  boss_run   : ' || count(*) from gmsr.boss_run
      union all select '  run_member : ' || count(*) from gmsr.run_member
      union all select '  player     : ' || count(*) from gmsr.player
      union all select '  账号       : ' || count(*) from gmsr.app_user"

cat <<EOF

下一步：
  1) 演练：用应用指向该库验证可读写
       java -jar /opt/gmsr/app/gmsr-scheduler.jar --server.port=18082 \\
         --spring.datasource.url="jdbc:postgresql://127.0.0.1:54233/$TARGET_DB?currentSchema=gmsr"
     （Flyway 会自动把备份升级到最新结构）

  2) 验证完删除演练库
       docker exec -e PGPASSWORD=*** $CONTAINER psql -U $PANEL_DB_ROOT_USER -d postgres -c 'drop database $TARGET_DB'
EOF
