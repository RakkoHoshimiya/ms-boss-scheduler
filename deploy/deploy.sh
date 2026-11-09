#!/bin/bash
# 一键重新部署: 后端 jar + 前端静态文件
# 用法: ./deploy.sh [all|backend|frontend]
set -euo pipefail

MODE="${1:-all}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$HERE/.." && pwd)"
# 部署私钥：优先用 ~/.ssh/gmsr_deploy，其次用项目上级目录 .deploy/id_ed25519
if [ -f "$HOME/.ssh/gmsr_deploy" ]; then
  SSH_KEY="$HOME/.ssh/gmsr_deploy"
else
  SSH_KEY="$PROJECT_DIR/../.deploy/id_ed25519"
fi
KNOWN_HOSTS="$PROJECT_DIR/../.deploy/known_hosts"
SERVER="root@<YOUR_SERVER_IP>"
SSH_PORT=2222

SSH="ssh -i $SSH_KEY -p $SSH_PORT -o StrictHostKeyChecking=no -o UserKnownHostsFile=$KNOWN_HOSTS -o ConnectTimeout=20"
SCP="scp -i $SSH_KEY -P $SSH_PORT -o StrictHostKeyChecking=no -o UserKnownHostsFile=$KNOWN_HOSTS -r"

deploy_backend() {
  echo "==> 同步后端源码到服务器"
  $SSH "$SERVER" 'mkdir -p /root/gmsr-build'
  $SCP "$PROJECT_DIR/backend" "$SERVER:/root/gmsr-build/"
  echo "==> 服务器上编译打包"
  $SSH "$SERVER" 'cd /root/gmsr-build/backend && mvn -B -q package -DskipTests -Dfile.encoding=UTF-8'
  echo "==> 替换 jar 并重启服务"
  $SSH "$SERVER" '
    set -e
    cp /root/gmsr-build/backend/target/gmsr-scheduler.jar /opt/gmsr/app/gmsr-scheduler.jar
    chown gmsr:gmsr /opt/gmsr/app/gmsr-scheduler.jar
    systemctl restart gmsr-backend
    for i in $(seq 1 30); do
      sleep 2
      if curl -s -m 3 http://127.0.0.1:18080/api/health >/dev/null; then
        echo "后端已就绪"; curl -s http://127.0.0.1:18080/api/health; echo
        exit 0
      fi
    done
    echo "后端启动超时，请查看 /opt/gmsr/logs/app.log"; exit 1
  '
}

deploy_frontend() {
  echo "==> 本地构建前端"
  cd "$PROJECT_DIR/frontend"
  export npm_config_cache="$PROJECT_DIR/../.tools/npm-cache"
  npm install --no-audit --no-fund
  npm run build
  echo "==> 上传静态文件到 /opt/1panel/www/gmsr"
  $SSH "$SERVER" 'rm -rf /opt/1panel/www/gmsr/* && mkdir -p /opt/1panel/www/gmsr'
  $SCP "$PROJECT_DIR/frontend/dist/." "$SERVER:/opt/1panel/www/gmsr/"
  echo "==> 重载 OpenResty"
  $SSH "$SERVER" 'docker exec 1Panel-openresty-xjMh nginx -t && docker exec 1Panel-openresty-xjMh nginx -s reload'
}

case "$MODE" in
  backend) deploy_backend ;;
  frontend) deploy_frontend ;;
  all) deploy_backend; deploy_frontend ;;
  *) echo "用法: $0 [all|backend|frontend]"; exit 1 ;;
esac

echo "==> 完成: http://<YOUR_SERVER_IP>:8080/  https://<YOUR_SERVER_IP>:4433/"
