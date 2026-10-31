# ===== LiveKit 信令反代（WebSocket）=====
#
# 这个文件被 deploy/gmsr.conf 里的 HTTPS server 块 include：
#   include /usr/local/openresty/nginx/conf/conf.d/livekit-rtc.inc;
#
# 为什么不直接写在 gmsr.conf 里：语音是可选功能，
# 单独一个文件便于「不用语音时整段不 include」而不必改主配置。
#
# 部署位置（以 1Panel 为例）：
#   宿主机：/opt/1panel/www/conf.d/livekit-rtc.inc
#   容器内：/usr/local/openresty/nginx/conf/conf.d/livekit-rtc.inc
# 1Panel 会把 conf.d 目录整体挂进容器，所以放在宿主机对应目录即可。
#
# 不用语音时怎么办：
#   保留这个文件也不会有问题（只是 /lk/ 访问时反代到一个没监听的端口，返回 502）；
#   想彻底关掉，把 gmsr.conf 里那行 include 注释掉，并删掉这个文件。
#
# 对外地址：wss://<host>:4433/lk/rtc?access_token=...
# 尾部斜杠 + proxy_pass 到根，会把 /lk 前缀剥掉再转发：
#   /lk/rtc  →  LiveKit 的 /rtc   （LiveKit 客户端的真实请求路径）
# 复用 4433 的证书，群友只需信任一次私有 CA。
location /lk/ {
    proxy_pass http://127.0.0.1:7880/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # 语音是长连接，不能缓冲、不能短超时
    proxy_buffering off;
    proxy_read_timeout 86400s;
    proxy_send_timeout 86400s;
}
