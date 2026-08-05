# 交接文档

本文写给接手本项目的下一个会话。目标是让你在不重读全部上游源码的前提下，知道现在到哪了、哪些结论已经被字节码证实、哪些坑已经踩过。

读完本文后，按顺序再读 `plan.md`（总体移植计划）和 `docs/platform-probes.md`（平台事实与 blocker）。`docs/source-migration-ledger.md` 是上游源码台账，需要排期时再查。

## 当前真实基线

- HEAD 共 18 个提交，相对 `origin/main` ahead 17 个。
- 工作树干净；最近提交信息为 `feat: 建立世界目录解析与原子写入存储层`。
- 不要把具体易变化的 HEAD hash 写入交接文档，提交信息可以记录。

---

## 1. 硬要求：必须积极使用子代理

**这不是建议，是硬要求。**

上游有 510 个 Java 文件、约 5.8 万行。如果主代理自己逐文件读源码，上下文会在两三个批次内耗尽。耗尽的表现不是报错，而是**静默的记忆虚构**：忘记既有决定、重复修已修过的 bug、把想象当成已落盘的事实。

本会话真实发生过一次。连续几轮里出现了"已完成属性层""已提交 6 个提交"这类叙述，但 `git log` 和磁盘上都不存在——那是上下文腐烂产生的虚构。发现方式是主代理重新跑了一次 `git log --oneline` 和 `git ls-files`，与叙述对不上。

所以工作方式固定为：

- **每个批次的实现工作派给 writer 子代理**，主代理不自己写大段代码。
- **实现完成后派给 reviewer 子代理**做交叉审核。
- **主代理只负责：切分批次、核实关键事实、决策取舍、提交。**
- 每个批次开始前，主代理先用少量精确命令（`git log`、`git ls-files`、`wc -l`、`javap`）确认当前真实状态，不依赖上一轮的记忆。

### 但子代理的报告不可全信

本会话中子代理报告出现过的具体问题：

- **测试数报错**：报告"81 个测试"，实际 101 个。
- **虚构不存在的字段**：报告称 `NativeProps.BG_IMAGE` 上游不存在，实际在上游第 154 行。
- **声称与上游一致但实际收窄了行为**：`readProgressFromNBT` 只 catch `IllegalArgumentException`，注释写"matching upstream"，而上游 catch 的是 `Exception`。
- **把上游缺陷当正确行为照搬**，也出现过反向情况：主动修了缺陷但没说明也没测试。

核实成本很低，收益很高。主代理必须独立核实的项：测试总数、存档字段字面量、任何"上游如此"的断言、任何 `net.minecraft` 成员的存在性。

### 验证修复是否真的生效

标准做法：**临时把修复改回缺陷状态，跑测试，确认恰好是那几个测试失败，然后恢复。**

没有这一步，你无法区分"测试锁住了行为"和"测试只是自证实现"。本会话用这招验证了四处修复：

- `UuidDatabase` 身份相等 → 反转后 6 个测试失败
- `removeQuest` null 守卫 → 反转后 1 个测试失败
- `PartyInstance` UUID 值相等 → 反转后 2 个测试失败
- `hostMigrate` 已有 OWNER 早退 → 删掉后 1 个测试失败

反转时注意：**不要用 `sed` 改多行 Java 代码**，本会话用 `sed` 反转 `QuestInstance` 时损坏了方法体（丢了一行、注释错位）。用 `edit` 工具做精确替换。

---

## 2. 子代理配置

两个 agent 已配置好，直接用。以下是配置文件里的实际值：

**`bq.bq-port-writer`** — 唯一写入者，`acceptanceRole: writer`
- 主模型：`tken/gpt-5.6-sol`（按用户指定）
- 回退：`mctop1/gpt-5.6-sol`, `geek2-gpt/gpt-5.6-sol`, `mctop1/gpt-5.5`

**`bq.bq-port-reviewer`** — 只读审核员，`acceptanceRole: read-only`
- 主模型：`geek2-claude/claude-opus-5`
- 回退：`tken/gpt-5.6-sol`, `mctop1/gpt-5.6-terra`, `mctop1/gpt-5.5`

两个 agent 都必须保持 `systemPromptMode: append`。原因见全局规则：agentrouter 类渠道校验客户端指纹，`replace` 模式会把 pi 基座提示词整体换掉，请求被判为非法客户端返回 `401 unauthorized client detected`。

### 为什么审核员没用 tken

用户指定优先 `tken/gpt-5.6-sol`，writer 已照此配置。审核员**故意**保留 Claude 作主模型、tken 放第一回退位，理由是本会话的实测证据：

前两轮用 GPT 审 GPT 的实现，都没发现 `PropertyTypeEnum` 大小写敏感导致玩家设置静默重置为默认值的问题。第三轮换 `geek2-claude/claude-opus-5` 后立刻发现了。这是同源盲区的直接证据，也是全局规则要求交叉审核换模型家族的原因。

如果你要统一用 tken，改 `~/.pi/agent/agents/bq.bq-port-reviewer.md` 的 `model` 字段即可，但要清楚代价是失去异源审核的价值。反过来，涉及高风险语义（存档格式、权限判定、并发）的批次，建议保持 Claude。

### reviewer 主模型断连时的处置

`WorldStorage` 批次连续两次派 reviewer 都在**运行到第 6 个 turn 时**报 `Connection error.`。查 `meta.json` 的 `attemptedModels` 只有 `geek2-claude/claude-opus-5` 一项——**回退链没有触发**。原因是 pi-subagents 的 fallback 只覆盖启动失败，中途断连不会切换模型。

所以配置里写了 `fallbackModels` 不等于中途断连有保护。处置方式是在调用时用 `model` 参数显式覆盖主模型：本批次改用 `S3AI/claude-opus-5` 后一次成功，产出完整审核报告。

`models.json` 里可用的 Claude 渠道（供覆盖时选择）：`S3AI`、`Gorouter`、`super`、`geek2-claude`、`geek2-claude-fest`、`agentrouterLinuxDo-claude`、`agentrouterGitHub-claude`。`baibei` 和 `anyrouter-claude` 见下方不可用清单。

### 并发约束

**同一时刻只能有一个子代理写入 `src/`。** reviewer 必须是只读。多个写入者并发操作同一工作目录会互相覆盖。

### 已知不可用渠道，不要派活

- `geek2-gpt-fest/*` — 403
- `baibei/claude-opus-4-7`、`baibei/claude-opus-4-8` — 403 blocked
- `anyrouter-claude/*` — 400，渠道要求启用 1m 上下文
- `anyrouter-gemini/gemini-2.5-pro` — 500
- `tken/gpt-5.6-terra` — bad_response_status_code

---

## 3. 当前状态

最近一次构建与测试基线：`./gradlew clean build --console=plain` 通过，23 个测试类、168 个测试全绿（0 failure、0 error、0 skipped）。身份层基线见 §4.1，存储层基线见 §4.2。

### 已完成

**阶段 0**：平台能力探针。专服生命周期、双向 custom payload 已验证；container GUI 是已知 blocker，见 `docs/platform-probes.md`。

**阶段 1**：工程骨架、元数据、包名。`com.example` 模板已清除，ManyLib 2.3.1 已作为必需依赖接入。

**阶段 2**：数据内核，分五个批次完成：

| 批次 | 内容 | 位置 |
|---|---|---|
| 1 | 枚举、UUID 编解码、注册表、数据库与查找策略 | `api/enums`, `api/util`, `api/registry`, `api/storage` |
| 2 | `ResourceKey` 逻辑 ID、属性容器层 | `api/util/ResourceKey`, `api/properties`, `storage/PropertyContainer` |
| 3 | quest graph 纯数据层、NBT 存储接口 | `api/questing`, `questing/QuestLine*`, `api/storage/INBT*` |
| 4 | quest 实体、任务/奖励存储、placeholder、registry | `questing/QuestInstance`, `questing/tasks`, `questing/rewards` |
| 5 | party 模型与角色迁移矩阵 | `api/questing/party`, `questing/party` |

### 阶段 2 剩余

只剩 `NameCache` 和 `QuestCache`，但**两者都卡在玩家身份上**，是阶段 3 的下游而非阶段 2 的收尾。不要在身份层建好前硬移植它们。

---

## 4. 下一步：阶段 3

按 plan.md §13 首轮实施顺序的第 5 步走。

### 4.1 身份服务（已完成）

`PlayerIdentityService` 批次已实现、提交并经过 S3AI/`claude-opus-5` 初审；问题修正后复审结论为 `No blocking issues`。实际文件如下：

- `src/main/java/com/github/postyizhan/betterquesting/platform/api/PlayerIdentity.java`
- `src/main/java/com/github/postyizhan/betterquesting/platform/api/PlayerIdentityResolution.java`
- `src/main/java/com/github/postyizhan/betterquesting/platform/api/PlayerIdentitySource.java`
- `src/main/java/com/github/postyizhan/betterquesting/platform/api/PlayerIdentityService.java`
- `src/main/java/com/github/postyizhan/betterquesting/platform/api/IdentityMappingConflictException.java`
- `src/main/java/com/github/postyizhan/betterquesting/core/identity/DeterministicPlayerIdentityService.java`
- `src/main/java/com/github/postyizhan/betterquesting/platform/fml/MitePlayerIdentityAdapter.java`
- `src/test/java/com/github/postyizhan/betterquesting/core/identity/DeterministicPlayerIdentityServiceTest.java`

已实现语义：规范化后的保守 ASCII 用户名使用固定 namespace 派生 RFC 4122 UUIDv5；旧档真实 UUID 默认隔离，不按用户名或派生 UUID 自动认领；管理员显式 `map`/`merge`/`replace` 才能建立或改变映射，冲突显式报错；`merge` 只能指向已有 legacy 映射。解析结果保留 legacy UUID、identity、source、decision 四字段值语义。MITE adapter 只调用已由 `javap` 验证的 `EntityPlayer.getEntityName()`。

验证：`./gradlew clean build --console=plain` 通过，22 个测试类、156 个测试全绿（0 failure、0 error、0 skipped）；RFC 4122 UUIDv5 向量另经 Python `uuid.uuid5` 核对；S3AI/Claude 复审为 `No blocking issues`。

关键身份契约：MITE 字节码显示 `NetLoginHandler` 只调用 `stripControlCodes`；`ServerConfigurationManager` 在线玩家比较使用 `equalsIgnoreCase`；`SaveHandler` 按原始名称拼接 `.dat`。因此 `Locale.ROOT` 小写折叠是 Windows 存档与在线身份契约，但不能声称它能让大小写敏感文件系统自动兼容两份仅大小写不同的历史存档。规则外运行时名称返回 `UNSUPPORTED_USERNAME` unresolved 结果；管理员 `map`/`merge`/`replace` 操作对规则外名称抛出 `IllegalArgumentException`，两类行为不能混淆。

保留决定：继续使用 `Locale.ROOT` 折叠；用户名下界保持 1 字符，不猜测 3 字符；`PlayerIdentityService` 继续放在 `platform/api`。这些放置与边界均来自 plan.md §4.2 的平台能力接口设计。null adapter 仍以 `<null>` 隔离并报告。

当前映射是**内存态**，重启不保留；WorldStorage 和迁移持久化完成前，禁止任何 identity-keyed progress 写入。`merge` 只能指向已有 legacy 映射，不能借此创建隐式目标。

#### 已实现的身份解析细节

- MITE `EntityPlayer` 上**没有** `GameProfile`，也没有 UUID 访问器。
- MITE 1.6.4 早于 Mojang 的 UUID 迁移，可验证身份**只有用户名**。
- 上游 `QuestingAPI.getQuestingUUID()` 依赖的类和方法在 MITE 上不存在。
- 派生 UUID 不是真实 Mojang UUID；玩家改名会形成新的逻辑身份，必须由管理员显式映射/合并。
- 导入上游 1.7.10 存档时，档里的真实 UUID 与派生 UUID 之间没有可计算映射，故默认隔离。

### 4.2 `WorldStorage`（已完成）

提交 `feat: 建立世界目录解析与原子写入存储层`。落地文件：

- `platform/api/WorldStorage.java` — 纯写侧边界接口
- `platform/fml/MiteWorldStorage.java` — 世界目录解析与禁用分支
- `core/storage/AtomicFileStorage.java` — tmp → sync → replace 事务
- `core/storage/StoragePaths.java` — 纯 JVM 路径边界守卫
- `src/test/.../core/storage/AtomicFileStorageTest.java` — 12 个测试
- `src/main/resources/betterquesting.accesswidener` — 新增 1 条规则

主代理独立 `javap` 复核得到的字节码事实（全部证实）：

- Forge 的 `DimensionManager` 在 MITE 不可用。
- `MinecraftServer.worldServers` 是 public 数组。
- `World` 有 public `ISaveHandler getSaveHandler()`，无需开放其 protected `saveHandler` 字段。
- `ISaveHandler` 不声明 `getWorldDirectory()`；它声明的是 public `getWorldDirectoryName()` 和 `flush()`。
- 具体类 `SaveHandler` 有 protected `File getWorldDirectory()`。
- 不要用 `MinecraftServer.getFile()` 拼 `saves/<name>`——专服与集成服务器下语义不同。
- `SaveFormatOld.getSaveLoader` 返回 `SaveHandler`，`AnvilSaveConverter.getSaveLoader` 返回继承自它的 `AnvilSaveHandler`。所以 `instanceof SaveHandler` 在专服与集成服正常路径都成立，禁用分支是真防御分支。`SaveHandlerMP` 不继承 `SaveHandler`。

最终实现路径：`worldServers[0].getSaveHandler()` → `instanceof SaveHandler` 检查 → cast → access widener 开放的 `getWorldDirectory()`。规则原文，descriptor 已从当前 mapped JAR 核对：

```text
accessible method net/minecraft/SaveHandler getWorldDirectory ()Ljava/io/File;
```

运行时接线由 `fml.mod.json` 的 `"accessWidener"` 键提供。**该规则已用反转法验证**：移除后编译恰好在两处 `getWorldDirectory()` 调用点报 protected 访问错误。

#### 本批次交叉审核修正的两个 High

审核由 `S3AI/claude-opus-5` 完成（`geek2-claude` 连续两次中途断连，回退链不覆盖运行中断连）。两条都已修并被测试锁住：

**H1 隐式备份**：初版 `write()` 每次都无条件生成带毫秒时间戳的 `.bak`，永不覆盖也不清理。已核对上游：`JsonHelper.WriteToFile2` 正常保存路径**完全没有备份**，只有 tmp → readback 校验 → move；备份只出现在解析失败（固定名 `malformed_<name>.json`）与版本升级两处。按 dirty-player 模型，每玩家每 autosave 周期新增一个完整副本会直接耗尽磁盘。现在 `write()` 不备份，`backup()` 是显式独立 API。反转验证：加回隐式备份恰好 1 个测试失败。

**H2 `flush()` 转发 vanilla**：初版转发 `ISaveHandler.flush()`。字节码证实 `AnvilSaveHandler.flush()` = `ThreadedFileIOBase.waitForFinish()` + `RegionFileCache.clearRegionFileReferences()`，即 BQ 一次 flush 会阻塞 vanilla 区块 IO 线程并关闭全部 region 文件句柄——BQ 无权代 vanilla 做这个决定。`SaveHandler.flush()` 本身是空方法体（`0: return`）。现在 `flush()` 只保证 BQ 自身语义，`saveHandler` 字段已删除（顺带解除对 `MinecraftServer` 对象图的强引用）。

#### 已知未做，接线时必须处理

- **无 readback 校验**。上游 move 前会把 tmp 重新 `GSON.fromJson` 解析一遍，失败则放弃替换。当前只有 TODO 注释，钩子位置留给 serializer 批次决定。
- **`WorldStorage` 是纯写侧**。上游加载路径需要 read/exists/list/delete（`SaveLoadHandler` 要读 6 个文件并列 `QuestProgress/` 目录）。补读侧时会改接口，越晚接线越贵。
- **`MiteWorldStorage` 是世界生命周期绑定对象**，不能静态缓存。集成服务器每次进入世界都新建 server 与 save handler，缓存会让第二个世界继续写第一个世界的目录。且 `worldServers` 只在 `loadAllWorlds` 中赋值，在 world load 前调用 `resolve()` 会**永久固化为禁用**，无重试路径。
- **路径守卫只做词法 `normalize()`**，不解析符号链接。`betterquesting/` 下的符号链接可绕出目录。一旦 `relativePath` 来自客户端数据包，必须改成白名单或 `toRealPath()`。
- **`MiteWorldStorage` 无测试**，依赖 `MinecraftServer`。守卫逻辑已抽到 `StoragePaths` 纯函数并有覆盖，但禁用分支与目录解析仍只有人眼核对。
- **POSIX 权限未实测**。已改回上游的 `FileOutputStream` 建 tmp（走 umask）而非 `Files.createTempFile`（owner-only 0600），但结论来自 javadoc 推断，需 Linux smoke test。

### 4.3 存档/迁移剩余

第 1、4 项已完成（见 §4.2）。以下项目均未完成，不得在后续报告中声称已落地：

1. ~~完成 `WorldStorage` 世界目录解析及所需 access widener 或 invoker/accessor。~~ 已完成。
2. 持久化身份映射并建立追加审计；迁移报告必须记录身份来源和管理员决定，这是 plan.md 阶段 3 第 7 条的硬要求。
3. 保留原 `format`、`build`、任务 ID、属性 key 和 UUID 表示，另增 `mitePortFormat` 表示移植端 schema。
4. ~~实现原子文件写入与带时间戳备份。~~ 已完成，但备份改为显式调用而非每次写入隐式生成（对齐上游，见 §4.2 H1）。
5. 建立 JSON/NBT golden fixture：空库、典型任务线、旧单文件进度、分玩家进度、缺失物品/实体/维度、损坏/截断/超大文件。
6. 实现 `LegacyQuestImporter`；旧 `QuestProgress.json` 转换为逐玩家文件后必须保留原件。
7. 周期 autosave、world save、server stop 均触发 flush；server stop 必须等待待处理 I/O 完成。
8. 目标平台缺失物品、实体、维度等内容时生成 placeholder 和迁移报告，不得静默替换成其他内容。
9. 最后接回 `NameCache`/`QuestCache`；`IQuestSettings.canUserEdit` 随 `NameCache` 后续处理，不能遗漏或让网络/GUI 假设权限检查已存在。

`QuestLoot.json` 在阶段 3 **只做识别、备份和不透明保留**；语义解析等阶段 7 的 LootRegistry。

### 4.4 下一会话首轮动作

1. 先核对 `git status`、`git log` 和当前测试数。
2. 再读取 `plan.md` 阶段 3 与 `docs/platform-probes.md`。
3. 按 §4.3 优先级派 writer 实现下一个批次，即第 2 项**持久化身份映射与追加审计**。这一项会第一次真正接线 `WorldStorage`，所以必须同时处理 §4.2 列出的生命周期约束（不得静态缓存、不得在 world load 前 `resolve()`）和读侧接口缺口。
4. 派 reviewer 复审该批次。**reviewer 主模型直接指定 `S3AI/claude-opus-5`**，不要用配置文件里的 `geek2-claude/claude-opus-5`（本会话连续两次中途断连）。
5. 主代理核实相关 `javap` owner/字段/descriptor 与测试结果，用反转法验证关键修复，再提交。

### 4.5 已知残余风险

身份映射尚未持久化；尚无追加审计；`MitePlayerIdentityAdapter` 尚未用真实 `EntityPlayer` 做测试；container GUI blocker 仍存在。

`WorldStorage` 新增的残余风险见 §4.2「已知未做」：无 readback 校验、纯写侧接口、生命周期绑定未接线、符号链接可绕过词法守卫、`MiteWorldStorage` 无测试、POSIX 权限未实测。

---

## 5. 平台关键事实

完整证据链对 §5.1 与 §5.4 成立，证据在 `docs/platform-probes.md`。§5.2/§5.3 的结论由当前代码注释、测试及本项目字节码核实记录锁定，但尚未整理进 `docs/platform-probes.md`。这里是最容易踩的四条。

### 5.1 `ResourceLocation` 不能当逻辑 ID（blocker）

MITE 的 `ResourceLocation` 构造器会把实例登记进静态待校验队列，集成服务器每 20 tick 校验一次资源文件是否存在，缺失时在客户端 HUD 上持续渲染红字 `Resource not found: xxx`。专服不跑这个校验。

上游把 `ResourceLocation` 当纯逻辑标识用（属性 key、factory ID、注册表 key），照搬会让每个逻辑 ID 都变成一条虚假报错——仅 `NativeProps` 就有 53 个。

**领域层统一用 `api/util/ResourceKey`。** 它保持 `domain:path` 文本形式与 MITE 的 domain 解析规则（按首个 `:` 切分、`indexOf > 1` 时取前缀、domain 小写化、缺省 `minecraft`），所以存档与 factory ID 的文本表示与上游一致。只有客户端真正加载纹理/声音等实际资源文件时才转成 `ResourceLocation`，并在该处显式决定是否参与校验。

注意一处继承自 MITE 的不对称：`parse()` 会小写化 domain，双参构造器不会。所以 `new ResourceKey("BetterQuesting","name")` 与 `ResourceKey.parse("BetterQuesting:name")` 不相等。已有测试锁定。

### 5.2 MITE NBT 差异统一走 `api/util/NbtCompat`

不要每处重写兼容代码。已有四个 helper：

| helper | 替代的 Forge API |
|---|---|
| `isNumeric(nbt, key)` | `hasKey(key, 99)` |
| `getTagId(nbt, key)` | `func_150299_b(key)`，key 缺失返回 0 |
| `getListOrEmpty(nbt, key)` | typed `getTagList(key, type)` |
| `getCompoundAt(list, i)` | `getCompoundTagAt(i)` |

`getListOrEmpty` 不只是去重：**MITE 的 `getTagList` 遇到类型不匹配会抛 `ClassCastException` 崩游戏**，上游返回空列表。这个 shim 把行为拉回上游。

已由字节码确认的 tag ID，与 Forge `Constants.NBT` 完全一致：BYTE_ARRAY=7、STRING=8、LIST=9、COMPOUND=10、INT_ARRAY=11。

另两条字节码结论：

- `NBTTagCompound.setTag(key, tag)` 会先调用 `tag.setName(key)` 覆盖 tag 名，所以构造 tag 时传空串占位是安全的。
- `getCompoundTag(key)` 对缺失 key 返回**游离的**新 compound（未插入 map），所以 `setProperty` 末尾必须显式 `setTag` 回写。
- `getTags()` 返回 `tagMap.values()` 的**活视图**，遍历中删除必须先快照名字。

### 5.3 数值窄化必须按源 tag 类型分支

上游 1.7.10 的 `NBTPrimitive` 访问器语义不统一：**浮点 tag 转整型用 `MathHelper.floor`（向下取整），整型 tag 用强转加掩码（向零截断）**。所以 `-3.7` 读成 int 是 `-4` 而不是 `-3`。

第一版实现统一经 `Double` 中转，同时搞错了负小数取整方向和大 long 精度。现在 `api/properties/basic/NbtNumbers` 按源 tag id 分支复刻。**新增数值属性类型时必须按类型分别处理，不能套用统一规则。** 特别注意 `NBTTagFloat` 的 long 访问器用直接强转，而 `NBTTagDouble` 用 floor——两者不同。

### 5.4 `ChatAllowedCharacters` 字段名陷阱

后续做文件名安全化时必读，已写入 `docs/platform-probes.md`。

MITE 的 `ChatAllowedCharacters.allowedCharacters` 是 **`String`**，内容来自客户端资源 `/font.txt`。字符数组字段叫 `allowedCharactersArray`，而它的内容其实是 **15 个禁用字符**（字段名有误导性）：`/`、`\n`、`\r`、`\t`、`\0`、`\f`、`` ` ``、`?`、`*`、`\`、`<`、`>`、`|`、`"`、`:`。

上游 `JsonHelper.makeFileNameSafe` 遍历的是 1.7.10 中 `char[]` 类型的 `allowedCharacters`，对应 MITE 的 `allowedCharactersArray`，**不是**同名 String 字段。照搬会同时犯两个错：把客户端 `/font.txt` 资源依赖引进服务端，以及用完全错误的过滤集。该禁用集也不含 Windows 保留设备名，`StoragePaths` 另行拒绝了 `CON`/`NUL`/`AUX`/`PRN`/`COM1-9`/`LPT1-9`。

---

## 6. 有意偏离上游的地方

每处都在代码注释里标了原因并有测试锁定。**同步上游时不要无意改回去。**

| 位置 | 偏离 | 原因 |
|---|---|---|
| `api/storage/UuidDatabase.java` | 覆写 `equals`/`hashCode` 为对象身份 | 本移植继承 `AbstractMap` 复用视图代码，会带来结构相等；而子类（如 `QuestLine`）本身作为 value 存进另一个 `UuidDatabase`，该类强制 value 唯一，结构相等会让两个空 quest line 被判重复而拒绝插入。上游 `UuidDatabase` 委派 `HashBiMap` 字段、不继承 `AbstractMap`，天然是身份相等。 |
| `api/storage/ArrayCacheLookupLogic.java` | `bulkLookup` 同时检查下界 | 上游只检查上界，小于 offset 的 key 会抛 `ArrayIndexOutOfBoundsException`，而 `SimpleDatabase.getValue` 对非法 ID 返回 null，两者不一致。 |
| `questing/QuestLineDatabase.java` | `removeQuest` 加 null 守卫；`readFromNBT` 不把 null 写入 `lineOrder` | 上游无条件解引用会 NPE，而 null 是被支持的 map 状态。 |
| `questing/QuestInstance.java` | `readFromNBT` 开头 `prereqTypes.clear()` | 上游不清，重复读入同一实例会留下陈旧类型，把存档语义为 NORMAL 的前置报告成 HIDDEN/IMPLICIT，并写回存档。 |
| `questing/party/PartyInstance.java` | `setStatus`/`hostMigrate` 用 `equals` 比 UUID；改角色映射时显式快照迭代 | 上游用 `!=` 引用比较，只在 UUID 都来自同一 map key 时才碰巧有效；一旦传入从 NBT 或网络包解析出的等值不同实例就误判。 |
| `api/questing/IQuest.java` | `RequirementType` 去掉 `PresetIcon`，`getTranslationKey()` 返回原始 key | 图标属客户端阶段 5。ordinal 与 byte id 保持不变以维持存档格式。上游 lang key 的拼写错误 `visbility` 也保留。 |
| `core/storage/AtomicFileStorage.java` | 写入**同步**完成，`writeAndSync` 额外做 `getFD().sync()` | 上游走 `BQThreadedIO.DISK_IO`（4 线程池）异步写且不 fsync。移植端先同步以保证 server stop 不丢数据。若后续为主线程性能引入异步队列，`WorldStorage.flush()` 必须 join 该队列，否则 stop 会静默丢数据。 |
| `core/storage/AtomicFileStorage.java` | 备份仅在显式调用 `backup()` 时产生，名为带 UTC 时间戳的 `<file>.<ts>.bak` | 上游正常保存路径（`JsonHelper.WriteToFile2`）完全不备份，只在解析失败时写固定名 `malformed_<name>.json`、版本升级时写 `backup/<ver>/`。移植端保持正常写入不备份，以免每玩家每 autosave 周期堆积无上限副本；但备份名改为时间戳，避免覆盖历史证据。 |
| `core/storage/StoragePaths.java` | 非法路径抛 `IOException` 拒绝，不做字符替换 | 上游 `JsonHelper.makeFileNameSafe` 把非法字符替换成 `_`，静默改名。存储边界宁可拒绝也不静默改写玩家进度文件名。另额外拒绝 Windows 保留设备名，上游禁用集不含这些，见 §5.4。 |

### 关于 `UuidDatabase` 的 `Map` 契约不对称

审核员曾提 High 说这破坏了 `Map` 契约，建议改成组合。**已核实并驳回，不要重新纠结：**

上游 `IUuidDatabase extends BiMap<UUID,T>`（→ `Map`），且上游 `UuidDatabase` 与 `QuestLine` 都不覆写 `equals`。所以 `hashMap.equals(db)` 为真、反向为假这个不对称**是从上游继承的，不是移植引入的**。建议的改法会让 `QuestLine` 仍然不对称，等于把问题搬家。

真实的下游风险是阶段 3 的变更检测和阶段 4 的集合键，到那时再具体处理。

---

## 7. 推迟的成员

分布在 7 个文件：`api/properties/NativeProps`、`api/questing/IQuest`、`api/questing/rewards/IReward`、`api/questing/tasks/ITask`、`api/storage/IQuestSettings`、`questing/QuestLine`、`questing/party/PartyManager`。

注意标注文本**不统一**——`deferred`、`TODO stages 6/7`、`TODO: Add ... after`、`TODO: Restore ... when` 都用过。要找全推迟点，用宽一点的模式：

```sh
grep -rln -i "eferred\|TODO" src/main/java
```

统一标注文本是个值得顺手做的小改进。

**推迟的接口成员是直接删签名并标注所属阶段，不是留返回假值的占位实现。** 否则会误导后续代码以为权限检查已存在。

| 推迟项 | 依赖 | 目标阶段 |
|---|---|---|
| `IQuest.getState`/`update`/`detect`/`canSubmit`/`isUnlocked`/`isUnlockable`/`canClaim`/`canClaimBasically`/`claimReward` | `EntityPlayer`, `QuestCache`, `ParticipantInfo`, `PartyManager` | 6/7 |
| `ITask.detect` | `ParticipantInfo` | 6 |
| `ITask.getTaskGui`/`getTaskEditor`、`IReward.getRewardGui`/`getRewardEditor` | 客户端 GUI | 5 |
| `IReward.canClaim`/`claimReward` | 玩家身份、参与者信息 | 7 |
| `IQuestSettings.canUserEdit` | 玩家身份、OP 权限、`NameCache`；随 `NameCache` 后续处理，不得遗漏 | 3/7 |
| `PartyManager.SyncPartyQuests` × 3 + `SyncPlayerContainer` | 玩家、`QuestCache`、`NameCache` | 7 |
| `PartyInvitations` | 阶段 4 网络处理器 | 4 |
| `PropertyTypeItemStack`、`NativeProps.ICON`/`CONFETTI_ICON` | `BigItemStack` | 后续 |

### 两条不能照搬的

**`PartyManager.SyncPartyQuests`**：上游用裸 `new Thread(...)` 在后台线程直接改任务完成状态，违反 plan.md §4.3 的主线程约束。必须改为服务端主线程调度或 EventBridge 派发。已写进 `PartyManager` 类注释。

**`IQuestSettings.canUserEdit`**：后续网络层和 GUI 层**不能假设这个权限检查已经存在**。

`NativeProps` 里 `betterquesting:icon`、`betterquesting:confetti_icon`、`betterquesting:frame` 三个存档 key 已在注释和 `NativePropsTest` 的常量清单中保留，`BigItemStack` 落地后按此恢复。

---

## 8. 已知架构债

领域层直接暴露 `net.minecraft.NBT*`。这符合 plan.md 阶段 2 的验收标准（只禁 Forge/FML/客户端类），但代价会在后续显现：

**阶段 3**：golden fixture 测试必须带 Minecraft classpath 跑，不能作为纯 JVM 单元测试独立运行。旧存档解析还要处理 JSON↔NBT 转换。

**阶段 4**：网络 codec 必须自己实现 NBT 的深度、总字节数、字符串长度、集合项数上限。**不能把任意客户端 NBT 直接交给 `readFromNBT`**，那会绕过大小限制和服务端字段授权。

审核员建议在阶段 3 建立单一 `PropertyNbtCodec` 适配层、阶段 4 建立受限 NBT 解码器和字段白名单。这是方向性建议，尚未实施。

另一条线程契约（已写进 `IPropertyContainer` 注释）：`writeToNBT` 返回的对象经过深拷贝，可以安全交给其他线程编码；`readFromNBT` 修改共享状态，必须在服务端主线程调用。

---

## 9. 环境注意事项

**Windows + Git Bash**：

- 用 `;` 分隔命令比 `&&` 稳定，长命令容易被截断，拆开执行。
- **不要用 `2>nul` 这类 Windows 重定向**。会在工作目录生成名为 `nul` 的文件，而 `nul` 是保留设备名，`rm` 和 `del` 都会 PermissionError。清理方式：`cmd //c "del \\\\?\\<绝对路径>\\nul"`。本会话审核员踩过。
- 源码是 LF，Git 提示 `LF will be replaced by CRLF` 是正常的。
- `.gitignore` 本身是 CRLF，用 `edit` 改它要注意行尾匹配，必要时用 append。

**Gradle**：默认吞掉测试的 stdout。要看测试内部输出就写文件再读，不要指望 `--info`。

**仓库约定**：

- 上游参考源码 `BetterQuesting-master/`、`ManyLib-main/` **已提交**（README 有链接引用）。
- `retroEMI-MITE-master/`、`logs/`、`.pi-subagents/` **不提交**，已在 `.gitignore`。
- 提交信息：`type: 中文描述`，英文半角冒号后一个空格，不加 AI 署名。

---

## 10. 一次操作失误的记录

会话中途 `git status` 显示 `plan.md` 被删除，我执行了 `git checkout -- plan.md` 恢复它。如果当时用户正在编辑该文件，这条命令可能覆盖了未保存的改动。

事后确认 `plan.md` 里用户添加的 EMI 集成优先级内容完整，并已随 `7a86bfc` 提交。但如果后续发现 plan.md 有内容缺失，从这里查起。

**教训**：工作树出现意料之外的变化时，先 `git diff` 看清楚或先备份，不要直接 `checkout` 恢复。文件"消失"也可能是用户或其他进程正在操作。

---

## 11. EMI 集成

用户已在 `plan.md` 把 EMI 集成标为高优先级，替代原 NEI 集成，参考代码在 `retroEMI-MITE-master/`（不提交）。

但这属于阶段 10 可选兼容模块，**不要提前动**。plan.md §3.2 和 §6 阶段 10 的约束仍然有效：独立 feature flag、运行时模组存在性检查、核心代码无静态引用、缺失不阻止数据库加载和服务器启动。
