# Koneko Warden Guard

## 本mod使用了ai进行代码编写

## 功能

- 对野生监守者手持幽匿块右键，不再把野生监守者本体变成保镖，而是让玩家获得“监守者信任”。
- 获得信任后，默认 `G`：召唤 / 收回一个监守者替身实体。
- 默认 `V`：命令监守者对玩家准星内的有效目标进行音波攻击。
- 新增可摆放的 `监守者神像`，支持东西南北朝向。
- 用幽匿块右键监守者神像会维护神像并刷新信任期限。
- 信任期限约为 `72` 个游戏日，作为接近铜块完全生锈时间的稳定近似值。
- 神像会随随机刻逐渐生锈；用幽匿块维护会把锈蚀状态重置。
- 信任过期后，已召唤的监守者替身会被自动收回，玩家不能再召唤，直到重新获得信任或维护神像。
- 驯服监守者以“替身”模式紧贴悬浮在玩家背后，距离小于一个玩家身位；有目标时快速飞向目标并近战攻击。
- 玩家攻击目标后，监守者会攻击该目标。
- 玩家或监守者受到攻击后，监守者会反击有效攻击者。
- 监守者主动攻击附近敌对怪物，包括未驯服的野生监守者。
- 监守者替身不会攻击其他玩家、玩家宠物、本 Mod 的其他保镖，也会把 Koneko March 的从军/军马视为友方。
- 监守者替身带保护标签并拒绝实体伤害；玩家、友方召唤物或敌对生物都不能真正伤害它，但敌对生物攻击它时仍会触发反击。
- 监守者替身与野生监守者可以互相战斗。
- 玩家拥有监守者信任后，附近野生监守者不会主动把该玩家作为目标；该玩家主动攻击野生监守者后，野生监守者会还击。
- ModMenu 配置界面：控制“受到攻击自动召唤监守者”。该设置会由客户端用 `/wardenguard config auto_summon true|false` 同步到服务器，服务器按玩家分别保存并执行。

## 监守者神像合成

```text
 E 
SCS
DDD
```

- `E`：回响碎片
- `S`：幽匿块
- `C`：幽匿催发体
- `D`：深板岩砖

## 命令

- `/wardenguard toggle`
- `/wardenguard summon`
- `/wardenguard recall`
- `/wardenguard sonic`
- `/wardenguard status`
- `/wardenguard config auto_summon true|false`

## 构建

需要 JDK 21。

```bash
gradle build
```

如果你希望使用 Gradle Wrapper，可在本机已有 Gradle 的情况下运行：

```bash
gradle wrapper
./gradlew build
```

## 依赖

- Minecraft `1.21.11`
- Fabric Loader `0.18.1+`
- Fabric API `0.141.1+1.21.11`
- Mod Menu `17.0.0+` 可选，仅用于配置界面。

## 实现说明

- 飞行模式使用原版监守者实体的 `NoGravity` 与服务端速度控制实现，不是自定义模型动画。
- 信任状态、信任到期时间与自动召唤状态使用玩家命令标签保存，不需要持续扫描世界实体。
- 被召唤的监守者仍是原版 Warden 实体，但带有本 Mod 的命令标签和目标过滤 Mixin。

## 与[ Koneko March ](https://github.com/KonekoBelown/Koneko-March-Fabric)联动用命令标签

被召唤的监守者会持续携带这些命令标签，供 Koneko March 或其他 Koneko 系列 Mod 判断为“友方保护实体”：

- `koneko_friendly_entity`
- `koneko_no_friendly_attack`
- `koneko_invulnerable_guard`
- `koneko_warden_guard_entity`
- `kwg_guard_warden`
- `kwg_owner_<玩家UUID>`

本 Mod 同时会把 Koneko March 已有标签视为友方：

- `konekomarch_march_entity`
- `konekomarch_march_ai`
- `konekomarch_march_mount`

如果要让 Koneko March 主动“意识到”监守者不能打，需要在 Koneko March 的目标筛选/伤害筛选里额外排除 `koneko_no_friendly_attack` 或 `koneko_warden_guard_entity`。即使旧版 Koneko March 尚未更新，本 Mod 的伤害事件也会拦截对监守者替身的实际伤害。

## 键位分类

监守者快捷键使用独立键位分类 ID，避免和 Koneko March 同时安装时重复注册崩溃；显示名称仍翻译为 “Koneko 行军”：

- `Identifier.of("koneko", "warden_guard")`
- 语言键：`category.koneko.warden_guard` / `key.category.koneko.warden_guard`

实际按键项：

- `key.koneko.warden_guard.toggle`
- `key.koneko.warden_guard.sonic`

## 1.2.1 trust/idol rewrite

- Taming item changed from echo shard to sculk block.
- Taming now grants player trust instead of converting the wild Warden into the guard.
- Added the Warden Idol block and recipe.
- Trust expires after an approximate full-copper-oxidation window unless refreshed with sculk on the idol.
- Retained the v7 owner-to-guard UUID cache and periodic scans to avoid TPS drops after repeated summon/recall cycles.
