# A4 服务端

## 更新进度

后续每次提交同步追加对应记录，新记录在上。

### 2026-11-25 · 初始提交

- 服务端源码入库，包含游戏逻辑、网络层、协议、PVF 解析、数据持久化与工具程序。
- 修复孵化后宠物栏残留蛋图标：孵化成功后补发 NOTI 13 整栏刷新。
- 修复宠物改名被误拒：改用 `NameFilter.rejectReasonForCreature`，不再套用角色名的严格白名单 `etc/invalidname.etc`。
- 修复宠物台词被当成玩家发言：A4 下 CMD 132 作为主聊天框处理，宠物台词走 `onCreatureScript` 并回 NOTI 131。
- 修复领主房 Boss 死亡后的尸体白影：Boss 死亡时补发 NOTI 127 清场。
- 修复频道经验加成恒为 0：`channelBonusRate` 改为解析 `etc/channel_info.etc`。
- 修复装扮无法锁定：装扮锁定改用容器 3（装备栏）`slot=10+部位`，客户端才画出锁图标。
- 修复邮件领取复活币导致断线：`extract` 包 try/catch，非法物品不再冒泡。
- 修复右键开礼包无反应：补注册 A4 客户端右键礼包发送的 CMD 171。
- 修复结算界面显示错位：`toA4Bytes()` 按 A4 实际字段顺序重排。

## 项目说明

A4 服务端，用于承载 DNF A4 客户端的游戏逻辑。单进程运行，客户端通过外部登录服务取得令牌后接入。

## 运行方式

| 用途 | 文件 |
|---|---|
| 构建 | `build.cmd`（Maven，产物在 `target/`） |
| 启动 | `run.cmd` |
| Linux 部署模板 | `deploy/a4server.service` |
| GM 命令说明 | `GM命令.txt` |

数据库为单个 SQLite 文件 `data/s1a5.db`，无需单独部署数据库服务。端口、缺省密码等参数通过 `-Ds1a5.*` 传入，详见 `run.cmd` 内的说明。

## 目录结构

| 路径 | 内容 |
|---|---|
| `src/main/java/com/dylan/game/` | 游戏逻辑：副本、战斗、组队、公会、商店、邮件等 |
| `src/main/java/com/dylan/net/` | 网络层：登录入口、游戏服、会话管理 |
| `src/main/java/com/dylan/protocol/` | 协议封包与加密 |
| `src/main/java/com/dylan/pvf/` | PVF 脚本解析 |
| `src/main/java/com/dylan/store/` | 数据持久化 |
| `src/main/java/com/dylan/tools/` | 工具程序：建库、PVF 导出、协议检查等 |
| `src/main/resources/` | 日志配置、屏蔽词表 |
| `data/` | 运行时数据 |
