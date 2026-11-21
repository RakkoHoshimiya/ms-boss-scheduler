#!/bin/bash
# 一键重新部署: 后端 jar + 前端静态文件
# 用法: ./deploy.sh [all|backend|frontend]
set -euo pipefail

MODE="${1:-all}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$HERE/.." && pwd)"
# 部署私钥：SSH_KEY 环境变量优先，其次项目内 .deploy/，
# 再次项目上级目录的 .deploy/（旧目录布局），最后 ~/.ssh/gmsr_deploy
if [[ -z "${SSH_KEY:-}" ]]; then
  if [ -f "$PROJECT_DIR/.deploy/id_ed25519" ]; then
    SSH_KEY="$PROJECT_DIR/.deploy/id_ed25519"
  elif [ -f "$PROJECT_DIR/../.deploy/id_ed25519" ]; then
    SSH_KEY="$PROJECT_DIR/../.deploy/id_ed25519"
  else
    SSH_KEY="$HOME/.ssh/gmsr_deploy"
  fi
fi
if [[ -z "${KNOWN_HOSTS:-}" ]]; then
  if [[ -f "$PROJECT_DIR/.deploy/known_hosts" ]]; then KNOWN_HOSTS="$PROJECT_DIR/.deploy/known_hosts"
  elif [[ -f "$PROJECT_DIR/../.deploy/known_hosts" ]]; then KNOWN_HOSTS="$PROJECT_DIR/../.deploy/known_hosts"
  else KNOWN_HOSTS="$HOME/.ssh/known_hosts"; fi
fi
[[ -f "$KNOWN_HOSTS" ]] || { echo '缺少已核验的 SSH known_hosts 文件；请先核对服务器指纹，并用 KNOWN_HOSTS=/path/to/known_hosts 指定文件。' >&2; exit 1; }
SERVER="${SERVER:-root@<YOUR_SERVER_IP>}"
SSH_PORT="${SSH_PORT:-2222}"

SSH=(ssh -i "$SSH_KEY" -p "$SSH_PORT" -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$KNOWN_HOSTS" -o ConnectTimeout=20)
SCP=(scp -i "$SSH_KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$KNOWN_HOSTS" -r)

deploy_backend() {
  echo "==> 同步后端源码到服务器"
  "${SSH[@]}" "$SERVER" 'mkdir -p /root/gmsr-build'
  "${SCP[@]}" "$PROJECT_DIR/backend" "$SERVER:/root/gmsr-build/"
  echo "==> 服务器上编译打包"
  "${SSH[@]}" "$SERVER" 'cd /root/gmsr-build/backend && mvn -B -q package -DskipTests -Dfile.encoding=UTF-8'
  echo "==> 替换 jar 并重启服务"
  "${SSH[@]}" "$SERVER" bash -s <<'BACKEND'
set -eEuo pipefail
umask 077
live=/opt/gmsr/app/gmsr-scheduler.jar
previous=/opt/gmsr/app/.rollback/gmsr-scheduler-previous.jar
had_previous=0
install -d -m 700 /opt/gmsr/app/.rollback
if [[ -f "$live" ]]; then
  install -m 600 "$live" "$previous.next"
  mv -f -- "$previous.next" "$previous"
  had_previous=1
fi
wait_ready() {
  local health
  for i in $(seq 1 30); do
    sleep 2
    if health=$(curl -fsS -m 3 http://127.0.0.1:18080/api/health) && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$health"; then return 0; fi
  done
  echo '后端健康检查超时，请查看 journalctl -u gmsr-backend' >&2
  return 1
}
rollback() {
  local failure=$?
  trap - ERR
  if [[ "$had_previous" == 1 ]]; then
    if install -o gmsr -g gmsr -m 644 "$previous" "$live.next" && mv -f -- "$live.next" "$live" && systemctl restart gmsr-backend && wait_ready; then
      echo '部署失败，已恢复上一版本并确认后端健康' >&2
    else
      echo '部署失败且自动回滚未恢复健康，请立即检查服务；旧 JAR 保留在 .rollback 目录' >&2
    fi
  else
    echo '首次部署失败，没有旧版本可回滚' >&2
  fi
  exit "$failure"
}
trap rollback ERR
install -o gmsr -g gmsr -m 644 /root/gmsr-build/backend/target/gmsr-scheduler.jar "$live.next"
mv -f -- "$live.next" "$live"
systemctl restart gmsr-backend
wait_ready
trap - ERR
echo '后端已就绪'
BACKEND
}

deploy_frontend() {
  echo "==> 本地构建前端"
  cd "$PROJECT_DIR/frontend"
  export npm_config_cache="${npm_config_cache:-$PROJECT_DIR/.cache/npm}"
  npm ci --no-audit --no-fund
  npm run build
  echo "==> 上传静态文件到 /opt/1panel/www/gmsr"
  local stage
  stage=$("${SSH[@]}" "$SERVER" 'umask 077; mkdir -p /opt/1panel/www; mktemp -d /opt/1panel/www/.gmsr-deploy.XXXXXXXX')
  [[ "$stage" =~ ^/opt/1panel/www/\.gmsr-deploy\.[a-zA-Z0-9]+$ ]] || { echo '无效的远端发布目录' >&2; return 1; }
  "${SCP[@]}" "$PROJECT_DIR/frontend/dist/." "$SERVER:$stage/"
  "${SSH[@]}" "$SERVER" bash -s -- "$stage" <<'REMOTE'
set -euo pipefail
stage="$1"
[[ "$stage" =~ ^/opt/1panel/www/\.gmsr-deploy\.[a-zA-Z0-9]+$ ]] || exit 1
web=/opt/1panel/www/gmsr
trap 'rm -rf -- "$stage"' EXIT
test -s "$stage/index.html"
test -d "$stage/assets"
docker exec 1Panel-openresty-xjMh nginx -t
mkdir -p "$web"
# Install every referenced asset before switching the entry point. Keep older hashed
# bundles for open tabs and independent certificate helpers absent from this build.
while IFS= read -r -d '' file; do
  relative="${file#"$stage/"}"
  [[ "$relative" == index.html ]] && continue
  install -D -m 644 "$file" "$web/$relative"
done < <(find "$stage" -type f -print0)
next="$web/.index-$(basename "$stage").html"
install -m 644 "$stage/index.html" "$next"
mv -f -- "$next" "$web/index.html"
docker exec 1Panel-openresty-xjMh nginx -s reload
REMOTE
}

case "$MODE" in
  backend) deploy_backend ;;
  frontend) deploy_frontend ;;
  all) deploy_backend; deploy_frontend ;;
  *) echo "用法: $0 [all|backend|frontend]"; exit 1 ;;
esac

echo "==> 完成: http://<YOUR_SERVER_IP>:8080/  https://<YOUR_SERVER_IP>:4433/"
