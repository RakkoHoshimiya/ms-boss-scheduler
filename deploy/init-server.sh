#!/bin/bash
# 服务器初始化脚本（首次部署时需要，已在 <YOUR_SERVER_IP> 执行过）
# 作用：准备 JDK25 + Maven、PostgreSQL 库与账号、运行目录、systemd 服务、OpenResty 站点
set -euo pipefail

PG_CONTAINER=1Panel-postgresql-2MLg
APP_USER=gmsr
APP_DIR=/opt/gmsr
WEB_DIR=/opt/1panel/www/gmsr
OR_CONF=/opt/1panel/www/conf.d/gmsr.conf

echo "==> 1. 安装 OpenJDK 25 与 Maven"
if ! command -v java >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y && apt-get install -y openjdk-25-jdk-headless curl unzip
fi
if ! command -v mvn >/dev/null; then
  cd /root
  curl -sL -o maven.tar.gz https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz
  tar xzf maven.tar.gz && ln -sf /root/apache-maven-3.9.16/bin/mvn /usr/local/bin/mvn
fi

echo "==> 2. 创建 PostgreSQL 数据库与账号"
docker exec "$PG_CONTAINER" psql -U user_JectHy -d postgres -tc "SELECT 1 FROM pg_roles WHERE rolname='gmsr_app'" \
  | grep -q 1 || docker exec "$PG_CONTAINER" psql -U user_JectHy -d postgres \
      -c "CREATE ROLE gmsr_app LOGIN PASSWORD 'CHANGE_ME_DB_PASSWORD';"
docker exec "$PG_CONTAINER" psql -U user_JectHy -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='gmsr'" \
  | grep -q 1 || docker exec "$PG_CONTAINER" psql -U user_JectHy -d postgres \
      -c "CREATE DATABASE gmsr OWNER gmsr_app ENCODING 'UTF8' TEMPLATE template0;"
docker exec "$PG_CONTAINER" psql -U user_JectHy -d gmsr \
  -c "CREATE SCHEMA IF NOT EXISTS gmsr AUTHORIZATION gmsr_app;"
docker exec "$PG_CONTAINER" psql -U user_JectHy -d gmsr \
  -c "ALTER ROLE gmsr_app SET search_path = gmsr, public;"

echo "==> 3. 创建运行账号与目录"
id "$APP_USER" >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_DIR/app" "$APP_DIR/logs"

echo "==> 4. 安装 systemd 服务与 OpenResty 站点配置"
install -m 644 "$(dirname "$0")/gmsr-backend.service" /etc/systemd/system/gmsr-backend.service
mkdir -p "$WEB_DIR"
install -m 644 "$(dirname "$0")/gmsr.conf" "$OR_CONF"
systemctl daemon-reload
echo "提示: 请先创建 $APP_DIR/gmsr.env（参考 gmsr.env.example），再执行 systemctl enable --now gmsr-backend"
