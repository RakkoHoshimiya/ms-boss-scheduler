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
      echo "other_app"
      exit 0 ;;
  *"select 1 from pg_database"*)
      echo "${FAKE_DB_EXISTS-1}"
      exit 0 ;;
  *"pg_size_pretty"*)
      echo "1 MB"
      exit 0 ;;
  *"pg_dump"*)
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

# restore.sh 里写的死路径是 /opt/1panel/...；测试时替换成临时文件
SED_EXPR="s#/opt/1panel/apps/postgresql/postgresql/.env#$WORK/root.env#"
sed "$SED_EXPR" "$RESTORE" > "$WORK/restore.sh"
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

echo "结果：$PASS 通过 / $FAIL 失败"
[[ $FAIL -eq 0 ]]
