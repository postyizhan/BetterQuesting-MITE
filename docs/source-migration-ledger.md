# 上游源码迁移台账

本台账覆盖 `BetterQuesting-master/src/main/java`，用于阶段 2 排序和复查，不表示任何目录已经完成移植。分类依据是目录职责和静态 import 检索；“纯领域候选”仅表示值得进一步隔离验证，不等于已经证明平台无关。

## 基线与复查方法

上游共有 510 个 Java 文件，四个顶级模块分别为：

| 顶级模块 | 文件数 | 主要职责 |
| --- | ---: | --- |
| `betterquesting` | 365 | 核心 API、数据库、网络、命令、客户端与方块/物品 |
| `bq_standard` | 127 | 标准任务/奖励、GUI、导入器及外部集成 |
| `drethic` | 11 | Quest Book 附属内容 |
| `lokko12` | 7 | Command Blocks for Better Questing 附属内容 |

可在仓库根目录复查：

```sh
find BetterQuesting-master/src/main/java -type f -name '*.java' | wc -l
for d in BetterQuesting-master/src/main/java/*; do printf '%s ' "$(basename "$d")"; find "$d" -type f -name '*.java' | wc -l; done
find BetterQuesting-master/src/main/java -type f -name '*.java' -print0 | xargs -0 grep -lE '^import (net\.minecraft|net\.minecraftforge)\.' | wc -l
find BetterQuesting-master/src/main/java -type f -name '*.java' -print0 | xargs -0 grep -lE '^import net\.minecraftforge\.' | wc -l
```

当前检索结果：366 个文件显式 import Minecraft 或 Forge，79 个文件显式 import Forge。没有命中不代表无平台耦合，尚需检查继承关系、同包类型和方法签名。

## `betterquesting`（365）

| 目录 | 文件数 | 初步分类 | 迁移判断/证据 |
| --- | ---: | --- | --- |
| `api` | 70 | 混合：纯领域候选、Minecraft/Forge 依赖、客户端 | `enums`、`misc/ICallback` 和部分 property 接口可优先审查；`client` 明确为客户端，placeholders、quest/task/reward、NBT 与 ItemStack 类型需要映射或边界适配。 |
| `api2` | 153 | 混合：纯领域候选、Minecraft 依赖、客户端 | `registry` 与 `storage` 中部分通用容器是候选；`client` 占大头且应推迟。`api2/storage` 中 11 个无直接 Minecraft/Forge import 的类可作为首批逐类验证集合。 |
| `questing` | 12 | 领域核心但有 Minecraft/NBT 依赖 | 数据库、任务/奖励注册和队伍模型是目标领域，但不能按原样认定为纯 Java；需先抽离 NBT、玩家和事件边界。 |
| `storage` | 4 | 领域存储 + Minecraft 依赖 | 生命周期、NBT、玩家身份相关，等待持久化边界设计后迁移。 |
| `network` | 21 | Forge 依赖/协议 | 原实现基于 Forge 网络设施；只可作为协议语义参考，不能直接移植。 |
| `commands` | 20 | Minecraft/Forge 依赖 | 命令发送者、玩家和服务端状态耦合，推迟到平台命令层。 |
| `blocks`, `items` | 7 | Minecraft 依赖 | 注册、方块实体、容器和物品行为，非阶段 2 首批。 |
| `client` | 65 | 客户端 | GUI、渲染、主题、编辑器与工具箱；必须保持在客户端边界。 |
| `core`, `handlers` | 10 | Forge 生命周期/平台胶水 | `@Mod`、代理、事件、GUI 与存档 handler 用于提炼行为，不直接复用。 |
| `misc` | 3 | 混合 | 文件/搜索辅助类仍引用 BQ 或 Minecraft 类型，逐类复核。 |

## `bq_standard`（127）

| 目录 | 文件数 | 初步分类 | 迁移判断/证据 |
| --- | ---: | --- | --- |
| `tasks` | 31 | 领域语义 + Minecraft/Forge 依赖 | 标准任务规则是后续重点，但物品、实体、世界、事件和 NBT 耦合明显；先定义端口再迁移实现。 |
| `rewards` | 15 | 领域语义 + Minecraft 依赖 | 命令、物品、经验、计分板和战利品均接触游戏状态。 |
| `network` | 7 | Forge 依赖/协议 | 与核心网络一样只提取消息语义。 |
| `client` | 30 | 客户端 | 标准任务/奖励编辑和显示 GUI，后置。 |
| `handlers`, `core`, `commands`, `items` | 12 | Forge/Minecraft 平台层 | 事件、配置、注册和代理均需重写为 FML/RIC 接入。 |
| `importers` | 21 | 外部集成 | HQM 格式与类型耦合，待核心数据模型稳定后再评估。 |
| `integration` | 5 | 外部集成 | 静态 import 命中 CodeChicken/NEI 与 Vending Machine API，不纳入核心首批。 |

外部集成证据可用以下命令复查：

```sh
grep -RhoE '^import [^;]+;' BetterQuesting-master/src/main/java | grep -E 'codechicken|vending|hqm|hardcorequesting' | sort -u
```

## `drethic`（11）与 `lokko12`（7）

| 模块 | 初步分类 | 迁移判断 |
| --- | --- | --- |
| `drethic/questbook` | Minecraft/Forge 附属模块 | 包含物品、合成、配置、事件和代理；不是核心领域迁移前置条件。 |
| `lokko12/CB4BQ` | Minecraft/Forge 附属模块 | 包含方块和代理；由独立的 `LICENSE.cb-for-bq` / `LICENSE.command-blocks` 文本覆盖，功能迁移后置。 |

## 阶段 2 首批候选

首批只做逐类依赖验证和最小领域骨架，不把候选状态升级为“已验证纯平台无关”：

1. `betterquesting/api/enums`（4 个枚举）：核对序列化名称和行为，不引入 Minecraft 类型。
2. `betterquesting/api2/registry`（4 个接口/实现）：检查其对 `ResourceLocation`、NBT 和上游具体工厂的间接依赖后再决定是否迁移。
3. `betterquesting/api2/storage` 中静态检索未直接 import Minecraft/Forge 的 11 个数据库/lookup 类：逐类检查泛型边界和同包依赖，建立可测试的 UUID 数据库候选集。
4. `betterquesting/api/properties` 的接口与 primitive property 类型：仅在 NBT/ItemStack 依赖可通过适配边界隔离后纳入。

阶段 2 的验证门槛应包括：目标类依赖图复核、无 `net.minecraft`/`net.minecraftforge`/`cpw.mods.fml` import 的编译边界，以及针对标识、查找和序列化行为的单元测试。客户端 GUI、Forge 网络 handler、外部集成与附属方块不属于首批范围。
