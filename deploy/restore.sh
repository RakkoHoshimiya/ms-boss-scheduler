#!/usr/bin/env bash
#
# 从备份恢复 GMSR 数据库
#
# 安全规则（刻意保守）：
#   1. 恢复前**先验证备份文件**（pg_restore --list + 必需表），不过就退出，不动任何数据库
#   2. 目标库**已存在**时，必须显式 --force；否则一律拒绝（不只是保护生产库）
#   3. 覆盖生产库时：
#        · 先做**整库**自救备份（不只是 gmsr schema）
#        · 若库中还有别的 schema，必须再加 --force-db，否则拒绝
#          （重建数据库会连带删掉那些 schema，而普通备份救不回来）
#        · 先在临时库试恢复并核对，成功后才动生产
#   4. 任何删除动作都会先打印将要删除的库名和大小
#
# 用法：
#   ./restore.sh <dump> <目标库> [--force] [--force-db]
#
#   演练（目标库不存在时不需要 --force）：
#       ./restore.sh /root/backup/gmsr-20261031-220612.dump gmsr_drill
#
#   演练（目标库已存在，重建）：
#       ./restore.sh /root/backup/gmsr-xxx.dump gmsr_drill --force
#
#   覆盖生产库：
#       ./restore.sh /root/backup/gmsr-xxx.dump gmsr --force
#       （若该库还承载其它 schema，需要再加 --force-db）
#
set -euo pipefail
umask 077

DUMP="${1:-}"
TARGET_DB="${2:-}"
shift 2 2>/dev/null || true

ENV_FILE="${ENV_FILE:-/opt/gmsr/gmsr.env}"
CONTAINER="${PG_CONTAINER:-1Panel-postgresql-2MLg}"
PROD_DB="${PROD_DB:-gmsr}"
DB_SCHEMA="${DB_SCHEMA:-gmsr}"
ROOT_ENV="${RESTORE_ROOT_ENV:-/opt/1panel/apps/postgresql/postgresql/.env}"
DB_USER="gmsr_app"

FORCE=0
FORCE_DB=0
for a in "$@"; do
    case "$a" in
        --force) FORCE=1 ;;
        --force-db) FORCE_DB=1 ;;
        *) echo "✗ 无法识别的参数：$a（只支持 --force / --force-db）" >&2; exit 1 ;;
    esac
done

die() { echo "✗ $*" >&2; exit 1; }

usage() {
    sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ -z "$DUMP" || -z "$TARGET_DB" ]] && usage

# These values enter SQL identifiers and catalog string comparisons below. Restrict the
# maintenance tool to plain lowercase PostgreSQL names before touching files or Docker.
validate_identifiers() {
    local name value
    for name in TARGET_DB PROD_DB DB_SCHEMA DB_USER; do
        value="${!name}"
        [[ "$value" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] \
            || die "$name 必须是最长 63 字符的小写 PostgreSQL 标识符（字母、数字、下划线，不能以数字开头）"
    done
}
validate_identifiers

# ---------------------------------------------------------------- 依赖与连接
[[ -f "$ENV_FILE" ]] || die "找不到 $ENV_FILE"
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${GMSR_DB_PASSWORD:?gmsr.env 里没有 GMSR_DB_PASSWORD}"

[[ -f "$ROOT_ENV" ]] || die "找不到 $ROOT_ENV（建库/删库需要 PostgreSQL 超级用户）"
# shellcheck disable=SC1090
set -a; . "$ROOT_ENV"; set +a
: "${PANEL_DB_ROOT_USER:?}" "${PANEL_DB_ROOT_PASSWORD:?}"
validate_identifiers

docker exec "$CONTAINER" sh -c 'true' 2>/dev/null || die "无法访问容器 $CONTAINER"

psql_super() {
    docker exec -e PGPASSWORD="$PANEL_DB_ROOT_PASSWORD" "$CONTAINER" \
        psql -U "$PANEL_DB_ROOT_USER" -d postgres -qtAX -c "$1"
}

# 连到指定库执行（用于检查其它 schema）
psql_in_db() {
    docker exec -e PGPASSWORD="$PANEL_DB_ROOT_PASSWORD" "$CONTAINER" \
        psql -U "$PANEL_DB_ROOT_USER" -d "$1" -qtAX -c "$2"
}

# ---------------------------------------------------------------- 1) 验证备份文件
echo "[$(date '+%F %T')] ① 验证备份文件"
[[ -f "$DUMP" ]] || die "备份文件不存在：$DUMP"
[[ -s "$DUMP" ]] || die "备份文件是空的：$DUMP"
SIZE="$(du -h "$DUMP" | cut -f1)"
SHA="$(sha256sum "$DUMP" | cut -d' ' -f1)"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gmsr-restore.XXXXXXXX")"
case "$WORK_DIR" in "${TMPDIR:-/tmp}/gmsr-restore."*) ;; *) die '无效的恢复临时目录';; esac
RUN_ID="${WORK_DIR##*.}"
SOURCE_DUMP="/tmp/gmsr-restore-$RUN_ID-src.dump"
RESCUE_DUMP="/tmp/gmsr-restore-$RUN_ID-rescue.dump"
TRIAL=""
TRIAL_CREATED=0
cleanup() {
    # A failed verification query must not leave a private-data drill database behind.
    # Only drop a name this invocation successfully created, never a preexisting DB.
    if [[ "$TRIAL_CREATED" == 1 ]]; then
        psql_super "drop database if exists $TRIAL" > /dev/null 2>&1 || true
    fi
    docker exec "$CONTAINER" rm -f "$SOURCE_DUMP" "$RESCUE_DUMP" 2>/dev/null || true
    rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT
docker cp "$DUMP" "$CONTAINER:$SOURCE_DUMP" > /dev/null

docker exec "$CONTAINER" pg_restore --list "$SOURCE_DUMP" > "$WORK_DIR/toc.txt" 2>/dev/null \
    || die "备份文件无法解析（pg_restore --list 失败），已中止，未改动任何数据库"
for t in boss_run run_member player app_user; do
    grep -q "TABLE DATA .* $t " "$WORK_DIR/toc.txt" \
        || die "备份文件里没有表 $t 的数据，看起来不是本项目的备份，已中止"
done
rm -f "$WORK_DIR/toc.txt"
echo "  大小 $SIZE  sha256 ${SHA:0:16}…  目录校验通过"

# ---------------------------------------------------------------- 2) 目标库检查
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

    # 库里如果还有别的 schema，重建会把它们一起删掉 —— 必须显式确认
    OTHER="$(psql_in_db "$TARGET_DB" \
        "select coalesce(string_agg(nspname, ', ' order by nspname), '')
           from pg_namespace
          where nspname not in ('public','information_schema','pg_catalog','pg_toast')
            and nspname not like 'pg_temp%'
            and nspname not like 'pg_toast_temp%'
            and nspname <> '$DB_SCHEMA'")"
    if [[ -n "$OTHER" && $FORCE_DB -ne 1 ]]; then
        cat >&2 <<EOF
✗ 目标库 "$TARGET_DB" 里还有其它 schema：$OTHER

  恢复脚本会**删除并重建整个数据库**，这些 schema 会一起消失，
  而本项目的备份只包含 $DB_SCHEMA，救不回它们。

  如果这些 schema 确实可以丢弃，请显式确认：
      $0 $DUMP $TARGET_DB --force --force-db

  如果它们还要保留，请不要用整库恢复 —— 改为把备份恢复到一个新库，
  再从新库里把 $DB_SCHEMA 的数据迁过来。
EOF
        exit 1
    fi
    echo "  ⚠ 将删除并重建已存在的库 $TARGET_DB（$DBSIZE）"
    [[ -n "$OTHER" ]] && echo "  ⚠ 库内其它 schema 也会被删除：$OTHER（已用 --force-db 确认）"
fi

# ---------------------------------------------------------------- 3) 覆盖生产库前的额外保护
if [[ "$TARGET_DB" == "$PROD_DB" && -n "$EXISTS" ]]; then
    echo "[$(date '+%F %T')] ③ 覆盖生产库：先做**整库**自救备份"
    SELF="$(dirname "$DUMP")/pre-restore-$(date +%Y%m%d-%H%M%S)-$RUN_ID.dump"
    # 注意：这里**不加 -n**，备份整个数据库（含其它 schema），
    # 否则下面的 drop database 会删掉自救备份救不回来的东西。
    docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        pg_dump -U "$DB_USER" -d "$PROD_DB" --no-owner --no-acl -Fc -f "$RESCUE_DUMP"
    docker exec "$CONTAINER" pg_restore --list "$RESCUE_DUMP" > /dev/null 2>&1 \
        || die "自救备份校验失败，已中止（生产库未改动）"
    docker cp "$CONTAINER:$RESCUE_DUMP" "$SELF" > /dev/null
    chmod 600 "$SELF"
    docker exec "$CONTAINER" rm -f "$RESCUE_DUMP"
    echo "  ✓ 整库备份（含所有 schema）已保存到 $SELF"

fi
if [[ "$TARGET_DB" == "$PROD_DB" ]]; then
    echo "[$(date '+%F %T')] ④ 试恢复到临时库（确认备份真的能用，再动生产）"
    # Unquoted PostgreSQL identifiers fold to lowercase; use exactly that name when
    # connecting later, even when mktemp produced a mixed-case random suffix.
    TRIAL="gmsr_restore_check_${RUN_ID,,}"
    psql_super "create database $TRIAL owner $DB_USER" > /dev/null
    TRIAL_CREATED=1
    if ! docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
            pg_restore -U "$DB_USER" -d "$TRIAL" --no-owner --no-acl "$SOURCE_DUMP"; then
        die "试恢复失败，已中止（生产库未改动）"
    fi
    TR_BOSS="$(docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        psql -U "$DB_USER" -d "$TRIAL" -qtAX -c "select count(*) from $DB_SCHEMA.boss_run")"
    TR_MEM="$(docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
        psql -U "$DB_USER" -d "$TRIAL" -qtAX -c "select count(*) from $DB_SCHEMA.run_member")"
    psql_super "drop database if exists $TRIAL" > /dev/null
    TRIAL_CREATED=0
    [[ "$TR_BOSS" =~ ^[0-9]+$ && "$TR_MEM" =~ ^[0-9]+$ ]] || die "试恢复后无法核对业务表，已中止（生产库未改动）"
    echo "  ✓ 试恢复成功：$TR_BOSS 趟车次 / $TR_MEM 个席位（临时库已删除）"
else
    echo "[$(date '+%F %T')] ③ 目标不是生产库，跳过自救备份与试恢复"
fi

# ---------------------------------------------------------------- 4) 重建并恢复
echo "[$(date '+%F %T')] ⑤ 重建目标库 $TARGET_DB"
psql_super "drop database if exists \"$TARGET_DB\"" > /dev/null
psql_super "create database \"$TARGET_DB\" owner $DB_USER" > /dev/null

echo "[$(date '+%F %T')] ⑥ 恢复数据"
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    pg_restore -U "$DB_USER" -d "$TARGET_DB" --no-owner --no-acl "$SOURCE_DUMP"
docker exec "$CONTAINER" rm -f "$SOURCE_DUMP"

# ---------------------------------------------------------------- 5) 结果核对
echo "[$(date '+%F %T')] ✓ 恢复完成"
docker exec -e PGPASSWORD="$GMSR_DB_PASSWORD" "$CONTAINER" \
    psql -U "$DB_USER" -d "$TARGET_DB" -qtAX -c "
      select '  boss_run   : ' || count(*) from $DB_SCHEMA.boss_run
      union all select '  run_member : ' || count(*) from $DB_SCHEMA.run_member
      union all select '  player     : ' || count(*) from $DB_SCHEMA.player
      union all select '  账号       : ' || count(*) from $DB_SCHEMA.app_user"

cat <<EOF

下一步：
  验证模式（演练库）：起一个实例指向它，确认能读写
      java -jar /opt/gmsr/app/gmsr-scheduler.jar --server.port=18082 \\
        --spring.datasource.url="jdbc:postgresql://127.0.0.1:54233/$TARGET_DB?currentSchema=$DB_SCHEMA"
    Flyway 会自动把备份升级到最新结构。

  生产模式：直接 systemctl start gmsr-backend 即可。

  演练完删除演练库：
      docker exec -e PGPASSWORD=*** $CONTAINER \\
        psql -U $PANEL_DB_ROOT_USER -d postgres -c 'drop database $TARGET_DB'
EOF
