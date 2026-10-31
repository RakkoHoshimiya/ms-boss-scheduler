# 备份与恢复

> 本文记录一次**真实恢复演练**的结果，以及日常备份/恢复的操作步骤。
> 演练日期：2026-10-31

## 备份什么

| 内容 | 文件 | 说明 |
| --- | --- | --- |
| 数据库 | `gmsr-YYYYmmdd-HHMMSS.dump` | `pg_dump -Fc`（custom 格式），含结构 + 数据 |
| 配置 | `gmsr-conf-YYYYmmdd-HHMMSS.tar.gz` | `gmsr.env`（含密钥，权限 600）、nginx 站点配置、systemd 单元、运维脚本 |
| 应用 | `gmsr-scheduler-latest.jar` | 覆盖式保留一份，省一次构建 |

**只备份数据库是不够的** —— 演练时发现，光有数据、没有 `gmsr.env`（数据库/Redis 密码、JWT secret）和 nginx 配置，服务起不来。

## 自动备份

`deploy/backup.sh` 由 systemd timer 每天 04:00 执行：

```bash
systemctl list-timers gmsr-backup.timer     # 看下次执行时间
systemctl start gmsr-backup.service         # 立刻跑一次
journalctl -u gmsr-backup.service -n 20      # 看上次结果
```

保留最近 **14 天**，过期自动清理（只删 `gmsr-*.dump` / `gmsr-conf-*.tar.gz`，不会误删别的文件）。

环境变量可覆盖：

```bash
BACKUP_DIR=/mnt/nas/backup KEEP_DAYS=30 /opt/gmsr/bin/backup.sh
```

备份流程内置**完整性校验**：导出后立刻 `pg_restore --list`，读不出目录就报错退出 —— 避免"看起来有备份、其实是个坏文件"。

## 恢复演练（2026-10-31 实际做过）

| 步骤 | 结果 |
| --- | --- |
| 取最新备份 `pre-v3-20261031-215941.dump`（34K） | ✓ |
| 建独立库 `gmsr_drill`（超级用户建库，**不碰生产**） | ✓ |
| `pg_restore` 恢复 | **267 ms** |
| 逐表行数比对（8 张表） | 完全一致 |
| 内容指纹比对（`boss_run` / `run_member` / `player` / `app_user` / `boss` / `character` 的 md5） | 完全一致 |
| 起一个应用实例指向恢复库（端口 18082） | 启动成功 |
| Flyway 自动把恢复库从 v2 升到 v3 | ✓ `Successfully applied 1 migration` |
| 用备份里的管理员密码登录 | ✓ |
| 读数据（`/api/runs`） | 50 趟 / 135 席位 / 21 群友 / 40 角色，与生产一致 |
| 写数据（建车次 → 加人 → 删车次） | 200 / 200 / 204，约束正常 |
| 清理演练库 | ✓ 生产库未受影响（50 趟） |

**结论：备份可用，恢复流程可用，旧的备份能被 Flyway 自动升级到最新结构。**

### 演练暴露的问题（已修）

| 问题 | 修复 |
| --- | --- |
| **没有任何定时备份**（全靠手工） | 加 `gmsr-backup.timer`，每天 04:00 + `Persistent=true`（停机后开机补跑） |
| 备份只含数据库，缺配置 | `backup.sh` 同时打包 `gmsr.env` / nginx / systemd |
| 备份没有完整性校验 | 导出后立即 `pg_restore --list` 校验，失败即报错 |
| 恢复没有防误操作 | `restore.sh` 默认拒绝覆盖生产库，需显式 `--force`，且会先自救备份 |

## 恢复步骤

### 演练 / 验证备份是否可用（推荐）

```bash
# 1) 恢复到独立库（自动建库，不碰生产）
/opt/gmsr/bin/restore.sh /root/backup/gmsr-20261031-220612.dump gmsr_drill

# 2) 起一个实例验证（Flyway 会自动升级到最新结构）
java -jar /opt/gmsr/app/gmsr-scheduler.jar --server.port=18082 \
  --spring.datasource.url="jdbc:postgresql://127.0.0.1:54233/gmsr_drill?currentSchema=gmsr"

# 3) 验证完删掉
docker exec -e PGPASSWORD=*** 1Panel-postgresql-2MLg \
  psql -U postgres -d postgres -c 'drop database gmsr_drill'
```

### 真正的灾难恢复（生产库损坏）

```bash
# 1) 停服务，避免写入
systemctl stop gmsr-backend

# 2) 恢复（脚本会先自动备份当前库到 pre-restore-*.dump，再覆盖）
/opt/gmsr/bin/restore.sh /root/backup/gmsr-<最新>.dump gmsr --force

# 3) 起服务（Flyway 自动补齐结构差异）
systemctl start gmsr-backend
curl -s localhost:18080/api/health

# 4) 若是整机重建，还要还原配置
tar xzf /root/backup/gmsr-conf-<最新>.tar.gz -C /
systemctl daemon-reload
```

### 恢复后必查

- [ ] `/api/health` 200
- [ ] `boss_run` / `run_member` 行数与备份前一致
- [ ] 页面能正常打开、能看到车次
- [ ] 用管理员登录一次
- [ ] `flyway_schema_history` 版本是最新
- [ ] 备份定时器仍在运行（`systemctl list-timers gmsr-backup.timer`）

## 已知限制

1. **备份和生产库在同一块磁盘上**（`/root/backup`）。磁盘整体损坏就一起没了。
   建议把 `BACKUP_DIR` 指到另一块盘，或用 1Panel 的云存储备份做异地副本。
2. **没有异地副本**，也没有演练过"整机重建"（只演练了"库恢复 + 起实例"）。
3. 备份窗口是每天一次 → **RPO 最长 24 小时**。对排班系统可接受，但重要操作前后建议手动 `backup.sh` 一次。
4. 恢复耗时随数据量增长，当前（135 席位 / 34K 备份）约 0.3 秒，不具参考性。
