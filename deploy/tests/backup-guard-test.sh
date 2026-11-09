#!/usr/bin/env bash
#
# 备份脚本护栏的行为测试（不需要真实数据库）
#
# 断言：必需配置文件缺失时，backup.sh 必须**失败**并且明确说是缺配置，
# 而不是打一个不完整的包就报成功。
#
# 用法：bash deploy/tests/backup-guard-test.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP="$ROOT/deploy/backup.sh"
# /tmp 在不少机器上是 noexec，工作目录放在仓库根下
WORK="$(mktemp -d "$ROOT/.backup-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"

# 假的 docker：pg_dump / pg_restore --list 都成功；cp 要真的把文件建出来，
# 否则脚本会在 du 那一步因为文件不存在而失败（失败原因就不是「缺配置」了）。
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  cp)
      dest="${@: -1}"
      mkdir -p "$(dirname "$dest")"
      : > "$dest"
      exit 0
      ;;
esac
exit 0
STUB
chmod +x "$WORK/bin/docker"

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

# 顺带确认：数据库备份那一步是成功的（说明失败确实来自配置检查）
if grep -q '数据库.*校验通过' "$WORK/out.log"; then
    echo "  ✓ 数据库备份本身是通过校验的"
else
    echo "  ✗ 数据库备份阶段就失败了，测试前提不成立"
    cat "$WORK/out.log"
    exit 1
fi

echo "结果：全部通过"
