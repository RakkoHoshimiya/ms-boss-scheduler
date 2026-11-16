#!/usr/bin/env bash
#
# 恢复脚本护栏的行为测试（不需要真实数据库）
#
# 用一个假的 docker 命令模拟容器与 psql 的返回，然后真的执行 deploy/restore.sh，
# 断言它在各种危险情况下**确实拒绝**，而不只是「源码里出现了某句提示」。
#
# 用法：bash deploy/tests/restore-guard-test.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESTORE="$ROOT/deploy/restore.sh"
# 注意：很多机器的 /tmp 是 noexec 挂载，脚本生成在这里会无法执行，
# 所以工作目录放在仓库根下（CI 的 checkout 目录也是可执行的）。
WORK="$(mktemp -d "$ROOT/.restore-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ---------------------------------------------------------------- 假 docker
mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
# 极简 docker 模拟：只回放测试需要的几种调用
args="$*"
printf '%s\n' "$args" >> "$FAKE_DOCKER_LOG"
case "$args" in
  *"sh -c true"*)
      exit 0 ;;
  *"pg_restore --list"*)
      # 目录里必须包含 restore.sh 会检查的那几张表
      for t in boss_run run_member player app_user; do
          echo "1; 0 0 TABLE DATA public $t owner"
      done
      exit 0 ;;
  *"pg_restore"*)
      exit 0 ;;
  *"from pg_namespace"*)
      # 模拟「这个库还承载着别的 schema」
      echo "${FAKE_OTHER_SCHEMA-other_app}"
      exit 0 ;;
  *"select 1 from pg_database"*)
      echo "${FAKE_DB_EXISTS-1}"
      exit 0 ;;
  *"pg_size_pretty"*)
      echo "1 MB"
      exit 0 ;;
  *"pg_dump"*)
      [[ "${FAKE_DB_EXISTS-1}" == '' ]] && exit 1
      exit 0 ;;
  *"select count(*)"*)
      [[ "${FAKE_COUNT_FAILURE:-0}" == 0 ]] || exit 3
      echo "${FAKE_ROW_COUNT-0}"
      exit 0 ;;
  "cp "*)
      dest="${@: -1}"
      [[ "$dest" == *:* ]] || printf 'rescue dump' > "$dest"
      exit 0 ;;
  *)
      exit 0 ;;
esac
STUB
chmod +x "$WORK/bin/docker"
export PATH="$WORK/bin:$PATH"

# ---------------------------------------------------------------- 假环境文件
cat > "$WORK/gmsr.env" <<'ENV'
GMSR_DB_PASSWORD=dummy
ENV
cat > "$WORK/root.env" <<'ENV'
PANEL_DB_ROOT_USER=postgres
PANEL_DB_ROOT_PASSWORD=dummy
ENV
export ENV_FILE="$WORK/gmsr.env"
export RESTORE_ROOT_ENV="$WORK/root.env"
export FAKE_DOCKER_LOG="$WORK/docker.log"
export TMPDIR="$WORK/tmp"
mkdir -p "$TMPDIR"
export REAL_MKTEMP
REAL_MKTEMP="$(command -v mktemp)"
cat > "$WORK/bin/mktemp" <<'STUB'
#!/usr/bin/env bash
if [[ "${FAKE_MIXED_CASE_TEMP:-0}" == 1 && "${@: -1}" == */gmsr-restore.XXXXXXXX ]]; then
    mixed="${@: -1}"
    mixed="${mixed%XXXXXXXX}MiXeD123"
    mkdir -m 700 "$mixed" || exit 1
    printf '%s\n' "$mixed"
else
    exec "$REAL_MKTEMP" "$@"
fi
STUB
chmod +x "$WORK/bin/mktemp"

# 使用真实脚本公开的环境覆盖，不再改写被测脚本。
cp "$RESTORE" "$WORK/restore.sh"
chmod +x "$WORK/restore.sh"

printf 'not-a-real-dump' > "$WORK/dump.bin"

run() {
    # 返回 "退出码|输出"
    local out rc
    out="$("$WORK/restore.sh" "$@" 2>&1)"
    rc=$?
    echo "$rc|$out"
}

echo "=== 恢复脚本护栏行为测试 ==="

# 1) 目标库已存在、没给 --force → 必须拒绝
res="$(run "$WORK/dump.bin" gmsr)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" -ne 0 ]] && grep -q '已存在' <<< "$out"; then
    ok "已存在的库（无 --force）被拒绝"
else
    bad "已存在的库（无 --force）没有被拒绝（rc=$rc）"
fi

# 2) 目标库已存在、给 --force，但库里有别的 schema → 必须要求 --force-db
res="$(run "$WORK/dump.bin" gmsr --force)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" -ne 0 ]] && grep -q 'force-db' <<< "$out"; then
    ok "库内有其它 schema 时要求 --force-db"
else
    bad "库内有其它 schema 时没有拦住（rc=$rc）"
fi

# 3) 备份文件损坏 → 必须在碰数据库之前中止
printf '' > "$WORK/empty.dump"
res="$(run "$WORK/empty.dump" gmsr_drill_new)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" -ne 0 ]] && grep -q '空' <<< "$out"; then
    ok "空备份文件被拒绝"
else
    bad "空备份文件没有被拒绝（rc=$rc）"
fi

# 4) 目标库不存在 → 应当放行（走到恢复流程）
FAKE_DB_EXISTS="" res="$(FAKE_DB_EXISTS= run "$WORK/dump.bin" gmsr_drill_new)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" -eq 0 ]] && grep -q '恢复完成' <<< "$out"; then
    ok "新库正常走恢复流程"
else
    bad "新库恢复流程异常（rc=$rc）"
fi

# 5) 生产库已经不存在：不能尝试备份不存在的旧库，但仍须试恢复。
: > "$FAKE_DOCKER_LOG"
res="$(FAKE_DB_EXISTS= run "$WORK/dump.bin" gmsr)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" -eq 0 ]] && ! grep -q 'pg_dump' "$FAKE_DOCKER_LOG" && grep -q '试恢复成功：0' <<< "$out"; then
    ok "缺失的生产库可从合法空排班备份恢复，仍先试恢复"
else
    bad "生产库缺失时不能恢复，或跳过了试恢复（rc=$rc）"
fi

# 6) 已存在的生产库：合法零车次也能恢复，自救文件必须为私有权限。
: > "$FAKE_DOCKER_LOG"
res="$(FAKE_OTHER_SCHEMA= run "$WORK/dump.bin" gmsr --force)"
rc="${res%%|*}"; out="${res#*|}"
self=("$WORK"/pre-restore-*.dump)
private_mode=0
if [[ -f "${self[0]}" ]]; then
    case "$(uname -s)" in
        MINGW*|MSYS*) private_mode=1; echo '  注：NTFS 下不验证 POSIX mode，Linux CI 会验证600' ;;
        *) [[ "$(stat -c %a "${self[0]}")" == 600 ]] && private_mode=1 ;;
    esac
fi
if [[ "$rc" -eq 0 && "$private_mode" == 1 ]] && grep -q 'pg_dump' "$FAKE_DOCKER_LOG"; then
    ok "现有生产库的空排班备份可恢复，且整库自救备份为600"
else
    bad "现有生产库空排班恢复或自救备份保护异常（rc=$rc）"
fi

# 7) Reject SQL punctuation/oversized identifiers before any Docker or temporary files.
: > "$FAKE_DOCKER_LOG"
invalid_rejected=1
for target in "bad'; select 1; --" 'bad-name' "$(printf 'x%.0s' {1..64})"; do
    if "$WORK/restore.sh" "$WORK/dump.bin" "$target" --force > "$WORK/invalid.log" 2>&1; then invalid_rejected=0; fi
done
if DB_SCHEMA="bad;drop" "$WORK/restore.sh" "$WORK/dump.bin" gmsr_drill > "$WORK/invalid.log" 2>&1; then invalid_rejected=0; fi
if PROD_DB="bad'" "$WORK/restore.sh" "$WORK/dump.bin" gmsr_drill > "$WORK/invalid.log" 2>&1; then invalid_rejected=0; fi
if [[ "$invalid_rejected" == 1 && ! -s "$FAKE_DOCKER_LOG" ]] && grep -q '标识符' "$WORK/invalid.log"; then
    ok "非法数据库/schema标识符在任何Docker操作前拒绝"
else
    bad "非法标识符未被安全拒绝"
fi

# 8) A deterministic mixed-case mktemp suffix must not create one DB name and connect
# to a different one. PostgreSQL would fold the unquoted CREATE name to lowercase.
: > "$FAKE_DOCKER_LOG"
res="$(FAKE_MIXED_CASE_TEMP=1 FAKE_DB_EXISTS= run "$WORK/dump.bin" gmsr)"
rc="${res%%|*}"; out="${res#*|}"
if [[ "$rc" == 0 ]] && grep -q 'create database gmsr_restore_check_mixed123' "$FAKE_DOCKER_LOG" \
        && grep -q -- '-d gmsr_restore_check_mixed123' "$FAKE_DOCKER_LOG" \
        && ! grep -q 'gmsr_restore_check_MiXeD123' "$FAKE_DOCKER_LOG"; then
    ok "大小写随机后缀的试恢复库名称在创建和连接时保持一致"
else
    bad "混合大小写后缀导致试恢复库名称不一致（rc=$rc）"
fi

# 9) Two independent drills must never share a container input dump or a host TOC.
: > "$FAKE_DOCKER_LOG"
FAKE_DB_EXISTS= "$WORK/restore.sh" "$WORK/dump.bin" drill_one > "$WORK/one.log" 2>&1 & first=$!
FAKE_DB_EXISTS= "$WORK/restore.sh" "$WORK/dump.bin" drill_two > "$WORK/two.log" 2>&1 & second=$!
first_rc=0; second_rc=0
wait "$first" || first_rc=$?
wait "$second" || second_rc=$?
source_count="$(awk '/^cp .*gmsr-restore-.*-src.dump/ {print $NF}' "$FAKE_DOCKER_LOG" | sort -u | wc -l)"
if [[ "$first_rc" == 0 && "$second_rc" == 0 && "$source_count" -eq 2 ]] \
        && ! grep -q '/tmp/restore-src.dump\|/tmp/pre-restore.dump' "$FAKE_DOCKER_LOG" \
        && [[ -z "$(find "$TMPDIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    ok "并发演练使用独立输入及私有目录，并在退出后清理"
else
    bad "并发恢复临时文件未隔离或未清理（$first_rc/$second_rc, sources=$source_count）"
fi

# 10) A count-query failure after successful trial import leaves production untouched
# and still drops this invocation's private-data trial database through the EXIT trap.
: > "$FAKE_DOCKER_LOG"
res="$(FAKE_COUNT_FAILURE=1 FAKE_DB_EXISTS= run "$WORK/dump.bin" gmsr)"
rc="${res%%|*}"
if [[ "$rc" -ne 0 ]] \
        && grep -q 'create database gmsr_restore_check_' "$FAKE_DOCKER_LOG" \
        && grep -q 'drop database if exists gmsr_restore_check_' "$FAKE_DOCKER_LOG" \
        && ! grep -q 'drop database if exists "gmsr"' "$FAKE_DOCKER_LOG" \
        && [[ -z "$(find "$TMPDIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    ok "试恢复后统计查询失败仍清理临时库，且未删除生产库"
else
    bad "试恢复后查询失败未清理临时库或误动生产库（rc=$rc）"
fi

echo "结果：$PASS 通过 / $FAIL 失败"
[[ $FAIL -eq 0 ]]
