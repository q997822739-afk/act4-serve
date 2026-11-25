# A4 服务端

## 更新进度

后续每次提交同步追加对应记录，新记录在上。

### 2026-11-25 · 源码入库

- 服务端源码入库，包含游戏逻辑、网络层、协议、PVF 解析、数据持久化与工具程序。

### 2026-11-24 · 组队与状态同步修复

- 修复窗口聊天重复：聊天内容重复上屏。
- 修复黑钻商城点击就送：购买流程未校验扣费。
- 修复吃经验书还扣经验：使用经验书时经验值反而减少。
- 修复进死亡之塔网络中断：进入死亡之塔时连接被断开。
- 修复通关经验与实际获取不符：结算显示的数值与实际入账不一致。
- 修复解散队伍没反应：解散队伍的操作无效。
- 修复邀请入公会后博肯处无选项：邀请入会成功但公会显示升级，博肯处没有对应选项。
- 修复任务给的金币为 0：见 2026-11-23 任务金币条。
- 修复城镇里队友血蓝不同步：城镇内队伍成员的血量、魔法值未同步。
- 修复不能队伍发言、不能喇叭发言：队伍频道与喇叭频道发言无效。
- 修复装备栏满后装备跑到消耗品栏：背包满时装备被放入消耗品栏。
- 修复快捷键生效后显示旧键位：改键生效后界面仍显示改动前的键位。
- 修复组队不能委任队长：无法把队长委任给其他队员。
- 修复人物名字变红：角色名显示为红色。
- 修复疲劳药每日限用无效：PVF 已写每日限用 3 个，但服务端未生效。
- 修复创建队伍无反应：创建队伍后界面无反馈，且无法设置队伍名。
- 修复队友不在附近也能进入：队友距离过远仍可进入副本。
- 修复踢人后无法解散队伍、卡死无法进图：把队员踢出后队伍状态残留。
- 修复队友副本回城后队伍无法退出：队员回城后队伍无法解散，其他队员看到该成员在线但卡住不动。
- 修复组队状态下把队友移出公会后血量异常：移出公会后队伍成员血量数值错误。
- 修复虚弱状态进选图界面瞬间补满状态：在选图界面被补满血，白嫖摆脱虚弱。
- 修复离线人员名字未高亮：不在线的角色名未按离线样式显示。
- 修复虚弱时选图可补满血省金币：虚弱状态下选择地图会补满生命，绕过金币消耗。
- 修复黑钻翻牌：黑钻翻牌奖励结算异常。

### 2026-11-23 · 协议与界面修复

- 修复孵化后仍显示蛋图标：`CreatureHandler` 孵化成功后只发 NOTI 105 + 单格 NOTI 14，没有整栏刷新；改为补发 NOTI 13，宠物栏一次性重发。
- 修复宠物改名被误拒：`InventoryService` 原本直接调用角色名过滤器 `NameFilter.rejectReason`，套用 `etc/invalidname.etc` 严格白名单，英文和 `~` 等字符被拒且不扣改名卡；改用 `rejectReasonForCreature`，宠物用独立规则。
- 修复宠物台词被当成玩家发言：`ChatHandler` 在 A4 下把所有 CMD 132 拦截进 `ChatService.onA4Chat`，宠物台词没走 `onCreatureScript` + NOTI 131；改为按包类型分流，宠物台词回 NOTI 131。
- 修复领主房尸体白影：`CombatService` 在 `bossDying`（CMD 127）发完 NOTI 127 `confirmAndClear` 后，Boss 死亡（CMD 42）再发 NOTI 38 时未补发 NOTI 127 清场；已在 Boss 死亡时补发。
- 修复频道经验加成恒为 0：`ClearExpBonus.channelBonusRate` 原本写死 `return 0.0`，未解析 `etc/channel_info.etc`；改为按频道表计算。
- 修复结算界面显示错位：`Noti35ClearRewardBody.toA4Bytes()` 原按 ACT5 顺序排列，多塞了 `expItemBonus` / `pcRoomBonus`，导致黑钻等加成显示到错行；已按 A4 实际字段顺序重排。
- 修复装扮无法锁定：`ItemLockService.SPACES` 缺少装扮栏，装扮无法锁定与重登持久化；A4 客户端把身上穿戴的装扮当成装备栏第 10..18 格，改用容器 3（装备栏）`slot=10+部位` 才画得出锁图标。
- 修复邮件领取复活币断线：`MailHandler.extract` 原本未包 try/catch，邮件误发虚拟占位复活币 id=1（实物为 id=42）时抛 `IllegalArgumentException` 直接冒泡导致断线；已包异常处理。
- 修复右键开礼包无反应：影响海之勇者宝箱、黑钻礼盒等所有 `[booster]`。`BoosterHandler` 只注册了 CMD 170，漏了 A4 客户端右键礼包实际发送的 CMD 171；已补注册。
- 修复任务金币为 0：`QuestService` 查的是 ACT5 才有的 `quest/questparameter.etc`，A4 PVF 里没有该文件，回退到 `QuestParameterScript.empty()` 导致金币恒为 0。A4 应取任务 `[clear reward item]` 的 `itemId=0`。**待实机验证。**
- 修复 SP 显示为 0：`ExperienceService` 原本只按 ACT5 规则在升级时发 SP，未实现 A4 的随经验值实时增长 SP。**待实机验证。**


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
