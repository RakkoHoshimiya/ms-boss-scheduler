# 安全响应头（正式对外环境）
# 放在 conf.d 但命名为 .inc，避免被 conf.d/*.conf 当成 server 配置加载；
# 通过 include 引入到本目录的 gmsr.conf 中（容器内路径 /usr/local/openresty/nginx/conf/conf.d/security-headers.inc）
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "geolocation=(), microphone=(), camera=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'" always;
