#!/usr/bin/env bash
#
# 从备份恢复 GMSR 数据库
#
# 安全规则（刻意保守）：
#   1. 恢复前**先验证备份文件**（pg_restore --list），校验不过直接退出，不动任何库
#   2. 目标库**已存在**时，必须显式 --force；否则一律拒绝（不只是保护生产库）
#   3. 覆盖生产库时：
#        · 先备份当前生产库到 pre-restore-*.dump
#        · 先在临时库做一次**试恢复**并核对，成功后才动生产
#   4. 任何删除动作都会先打印将要删除的库名和大小
#
# 用法：
#   ./restore.sh <dump> <目标库> [--force]
#
#   演练（目标库不存在时不需要 --force）：
#       ./restore.sh /root/backup/gmsr-20261031-220612.dump gmsr_drill
#
#   演练（目标库已存在，重建）：
#       ./restore.sh /root/backup/gmsr-xxx.dump gmsr_drill --force
#
#   覆盖生产库：
#       ./restore.sh /root/backup/gmsr-xxx.dump gmsr --force
#
set -euo pipefail

DUMP="${1:-}"
TARGET_DB="${2:-}"
FLAG="${3:-}"

ENV_FILE="${ENV_FILE:-/opt/gmsr/gmsr.env}"
CONTAINER="${PG_CONTAINER:-1Panel-postgresql-2MLg}"
PROD_DB="${PROD_DB:-gmsr}"
ROOT_ENV="/opt/1panel/apps/postgresql/postgresql/.env"
DB_USER="gmsr_app"

die() { echo "✗ $*" >&2; exit 1; }

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ -z "$DUMP" || -z "$TARGET_DB" ]] && usage
[[ "$FLAG" != "" && "$FLAG" != "--force" ]] && die "无法识别的参数：$FLAG（只支持 --force）"
FORCE=0
[[ "$FLAG" == "--force" ]] && FORCE=1

# ---------------------------------------------------------------- 依赖与连接
[[ -f "$ENV_FILE" ]] || die "找不到 $ENV_FILE"
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${GMSR_DB_PASSWORD:?gmsr.env 里没有 GMSR_DB_PASSWORD}"

[[ -f "$ROOT_ENV" ]] || die "找不到 $ROOT_ENV（建库/删库需要 PostgreSQL 超级用户）"
# shellcheck disable=SC1090
set -a; . "$ROOT_ENV"; set +a
: "${PANEL_DB_ROOT_USER:?}" "${PANEL_DB_ROOT_PASSWORD:?}"

docker exec "$CONTAINER" sh -c 'true' 2>/dev/null || die "无法访问容器 $CONTAINER"

psql_super() {
    docker exec -e PGPASSWORD="$PANEL_DB_ROOT_PASSWORD" "$CONTAINER" \
        psql -U "$PANEL_DB_ROOT_USER" -d postgres -qtAX -c "$1"
}

# ---------------------------------------------------------------- 1) 验证备份文件
echo "[$(date '+%F %T')] ① 验证备份文件"
[[ -f "$DUMP" ]] || die "备份文件不存在：$DUMP"
[[ -s "$DUMP" ]] || die "备份文件是空的：$DUMP"
SIZE="$(du -h "$DUMP" | cut -f1)"
SHA="$(sha256sum "$DUMP" | cut -d' ' -f1)"

docker cp "$DUMP" "$CONTAINER:/tmp/restore-src.dump" > /dev/null
trap 'docker exec "$CONTAINER" rm -f /tmp/restore-src.dump /tmp/restore-trial.dump 2>/dev/null || true' EXIT

# 2) 目录可读性（格式是否合法）
docker exec "$CONTAINER" pg_restore --list /tmp/restore-src.dump > /tmp/restore-toc.txt 2>/dev/null \
    || die "备份文件无法解析（pg_restore --list 失败），已中止，未改动任何数据库"
# 3) 内容检查：必须包含业务表
for t in boss_run run_member player app_user; do
    grep -q "TABLE DATA .* $t " /tmp/restore-toc.txt \
        || die "备份文件里没有表 $t 的数据，看起来不是本项目的备份，已中止"
done
rm -f /tmp/restore-toc.txt
echo "  大小 $SIZE  sha256 ${SHA:0:16}…  目录校验通过"

# ---------------------------------------------------------------- 4) 目标库检查
echo "[$(date '+%F %T')] ② 检查目标库 $TARGET_DB"
EXISTS="$(psql_super "select 1 from pg_database where datname = '$TARGET_DB'")"

if [[ -n "$EXISTS" ]]; then
    DBSIZE="$(psql_super "select pg_size_pretty(pg_database_size('$TARGET_DB'))")"
    if [[ $FORCE -ne 1 ]]; then
        cat >&2 <<EOF
✗ 目标库 "$TARGET_DB" 已存在（$DBSIZE），恢复会**删除并重建**它。

  确认要覆盖，请加 --force：
      $0 $DUMP $TARGET_DB --force

  如果你只是想验证备份，请换一个不存在的库名，例如：
      $0 $DUMP gmsr_drill_$(date +%Y%m%d)
EOF
        exit 1
    fi
    echo "  ⚠ 将删除并重建已存在的库 $TARGET_DB（$DBSIZE）"
fi

# ---------------------------------------------------------------- 5) 覆盖生产库前的额外保护
if [[ "$TARGET_DB" == "$PROD_DB" ]]; then
    echo "[$(date '+%F %T')] ③ 覆盖生产库：先自救备份当前数据"
    SELF="$(dirname "$DUMP")/pre-restore-$(date +%Y%m%d-%H%M%S).dump"
    docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        pg_dump -U "$DB_USER" -d "$PROD_DB" -n gmsr --no-owner --no-acl -Fc -f /tmp/pre-restore.dump
    docker cp "$CONTAINER:/tmp/pre-restore.dump" "$SELF" > /dev/null
    docker exec "$CONTAINER" rm -f /tmp/pre-restore.dump
    echo "  ✓ 当前生产库已备份到 $SELF"

    echo "[$(date '+%F %T')] ④ 试恢复到临时库（确认备份真的能用，再动生产）"
    TRIAL="gmsr_restore_check_$(date +%s)"
    psql_super "create database $TRIAL owner $DB_USER" > /dev/null
    if ! docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
            pg_restore -U "$DB_USER" -d "$TRIAL" --no-owner --no-acl /tmp/restore-src.dump; then
        psql_super "drop database if exists $TRIAL" > /dev/null || true
        die "试恢复失败，已中止（生产库未改动）"
    fi
    TR_BOSS="$(docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        psql -U "$DB_USER" -d "$TRIAL" -qtAX -c "select count(*) from gmsr.boss_run")"
    TR_MEM="$(docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        psql -U "$DB_USER" -d "$TRIAL" -qtAX -c "select count(*) from gmsr.run_member")"
    psql_super "drop database if exists $TRIAL" > /dev/null
    [[ "${TR_BOSS:-0}" -gt 0 ]] || die "试恢复后 boss_run 为空，已中止（生产库未改动）"
    echo "  ✓ 试恢复成功：$TR_BOSS 趟车次 / $TR_MEM 个席位（临时库已删除）"
else
    echo "[$(date '+%F %T')] ③ 目标不是生产库，跳过自救备份与试恢复"
fi

# ---------------------------------------------------------------- 6) 重建并恢复
echo "[$(date '+%F %T')] ⑤ 重建目标库 $TARGET_DB"
psql_super "drop database if exists \"$TARGET_DB\"" > /dev/null
psql_super "create database \"$TARGET_DB\" owner $DB_USER" > /dev/null

echo "[$(date '+%F %T')] ⑥ 恢复数据"
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    pg_restore -U "$DB_USER" -d "$TARGET_DB" --no-owner --no-acl /tmp/restore-src.dump
docker exec "$CONTAINER" rm -f /tmp/restore-src.dump

# ---------------------------------------------------------------- 7) 结果核对
echo "[$(date '+%F %T')] ✓ 恢复完成"
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    psql -U "$DB_USER" -d "$TARGET_DB" -qtAX -c "
      select '  boss_run   : ' || count(*) from gmsr.boss_run
      union all select '  run_member : ' || count(*) from gmsr.run_member
      union all select '  player     : ' || count(*) from gmsr.player
      union all select '  账号       : ' || count(*) from gmsr.app_user"

cat <<EOF

下一步：
  验证模式（演练库）：起一个实例指向它，确认能读写
      java -jar /opt/gmsr/app/gmsr-scheduler.jar --server.port=18082 \\
        --spring.datasource.url="jdbc:postgresql://127.0.0.1:54233/$TARGET_DB?currentSchema=gmsr"
    Flyway 会自动把备份升级到最新结构。

  生产模式：直接 systemctl start gmsr-backend 即可。

  演练完删除演练库：
      docker exec -e PGPASSWORD=*** $CONTAINER \\
        psql -U $PANEL_DB_ROOT_USER -d postgres -c 'drop database $TARGET_DB'
EOF
