# 交接文档

本文写给接手本项目的下一个会话。目标是让你在不重读全部上游源码的前提下，知道现在到哪了、哪些结论已经被字节码证实、哪些坑已经踩过。

读完本文后，按顺序再读 `plan.md`（总体移植计划，**在仓库根目录，不在 `docs/` 下**——本文其余各处提到的 `plan.md` 同理，曾有会话按 `docs/plan.md` 查找未果而误判该文件不存在）和 `docs/platform-probes.md`（平台事实与 blocker）。阶段划分与验收标准以 `plan.md` 为准，本文只记执行状态；两者冲突时以 `plan.md` 为权威并回头修本文。`docs/source-migration-ledger.md` 是上游源码台账，需要排期时再查。

## 当前真实基线

- HEAD 共 30 个提交。
- 工作树干净；最近提交信息为 `docs: 记录 JSON 序列化层结论与上游类型标记约定`。
- `FishModLoader/` 是本地克隆的参考仓库，已进 `.gitignore`；构建实际用 Maven 坐标 `FishModLoader:3.4.2`，不引用该目录。
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
- access widener 的 `getWorldDirectory` 规则 → 移除后编译恰好在两处调用点报 protected 错误
- `appendLine` 的 LF 分帧守卫 → 移除后恰好 2 个分帧测试失败
- `list` 的 `malformed_`/`.DS_Store` 排除、`read` 的父目录检查、reader 返回 null 抛 `IOException`、`delete` 拒绝目录 → 四项一次性反转，恰好 4 个对应测试失败，1:1 归因

反转时注意：**不要用 `sed` 改多行 Java 代码**，本会话用 `sed` 反转 `QuestInstance` 时损坏了方法体（丢了一行、注释错位）。用 `edit` 工具做精确替换。反转后**必须先核实文件真实内容再恢复**：本会话有一次假设的恢复文本与实际不符，`edit` 直接报错；若当时用的是模糊匹配，就会把反转状态留在树里。

### 反转法证明不了的事

反转只证明"测试锁住了这个行为"，**不证明"这个行为是对的"**。这两件事本会话真的分开过。

`appendLine` 初版有个测试 `appendPreservesEveryExistingByteIncludingIncompleteTail`，断言崩溃残片 `incomplete` 后追加 `next` 得到 `incompletenext`。反转 APPEND 语义时它确实失败，看起来"锁住了行为"——但它锁住的行为本身是缺陷：`incompletenext` 是一条语法完整、无任何残缺标记的行，读侧无法把它和真实审计记录区分，等于允许伪造审计条目。

发现者是异源 reviewer，不是反转法。**两者不能互相替代**：反转法防"假测试"，交叉审核防"测试把缺陷固化成规范"。

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

最近一次构建与测试基线（提交 `8de696c`）：**34 个测试类、485 个测试**全绿（0 failure、0 error、0 skipped）。数字取自 `build/test-results/test/*.xml` 汇总，**不要用 Gradle 退出码或 `BUILD SUCCESSFUL` 当证据**——本项目已出现过全部 task `UP-TO-DATE` 的缓存命中，那种"成功"对当前代码状态零信息量；跑验证时加 `--rerun-tasks`。身份层基线见 §4.1，存储层基线见 §4.2 与 §4.2b，依赖运行时可用性见 §5.5。

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
- **`MiteWorldStorage` 的真实游戏集成仍未运行验证**。`MiteWorldStoragePathTest` 已覆盖 world-root/data-directory 分流、注入式 production adapter 和以 Minecraft 类型替身执行 mapped overload；真实 `MinecraftServer`/save handler 初始化及禁用分支仍未做端到端测试。
- **POSIX 权限未实测**。已改回上游的 `FileOutputStream` 建 tmp（走 umask）而非 `Files.createTempFile`（owner-only 0600），但结论来自 javadoc 推断，需 Linux smoke test。

### 4.2b `WorldStorage` 读侧与追加写（已完成）

提交 `feat: 补齐世界存储读侧枚举与追加写能力` 与 `fix: 追加写分帧隔离崩溃残片并补齐审计日志读侧`。这是为下一批次的追加审计做的前置。`core/storage/WorldDataStorage.java` 是纯 JVM 实现，`MiteWorldStorage` 只做委派。

新增接口：`exists`、`read`、`readLines`、`list`、`delete`、`appendLine`。七个入口全部经 `StoragePaths.resolveWithin`，逃逸与绝对路径各有一个覆盖全部入口的测试。

#### 交叉审核修掉的 Blocker：追加写会产生伪造审计记录

初版 `appendLine` 只做纯追加。崩溃残片 `incomplete` 后追加 `next` 得到 `incompletenext`——一条语法完整、读侧无法识别为残缺的行。对审计日志而言等于允许伪造条目。修法三件套，缺一不可：

1. **LF 分帧守卫**：已有非空文件末字节不是 LF 时先补一个 LF，残片因此独占一行。
2. **写循环**：`while (buffer.hasRemaining()) channel.write(buffer)`，不再假设单次 write 写完。
3. **truncate 回滚**：记录打开时 `channel.size()`，写入或 force 失败时截回，回滚失败用 `addSuppressed` 附加而不掩盖原异常。

**分帧守卫只保证隔离，不保证识别。** 残片独占一行后仍是一条"完整"的行，`readLines` 无法判断它是垃圾。所以下一批次的**审计记录格式必须自校验**，解析器必须拒绝不合法的行。这条已写进 `appendLine` 与 `WorldStorage` 的 javadoc，不要指望存储层替你识别。

#### 由主代理实测锁定的 NIO 边界（Windows / Java 17）

- `FileChannel.open(path, APPEND, READ)` 抛 `IllegalArgumentException`，两选项不能组合。所以末字节检查必须先开独立 READ channel 再开 APPEND channel。这中间有 check-to-append 竞态，只在同路径串行化前提下安全。
- APPEND channel 上 `position()` 与 `truncate(long)` 均可用（实测 4 字节截到 2 字节内容正确），所以回滚方案可行。
- `Files.newInputStream(目录)` 抛 `AccessDeniedException`（**不是** `NotDirectoryException`）；`Files.newInputStream(普通文件/子路径)` 抛 `NoSuchFileException`；`Files.list(普通文件)` 抛 `NotDirectoryException`；`deleteIfExists(空目录)` 返回 true 并真的删掉。

第 3 条导致两个真实缺陷已修：`read` 里的 `NotDirectoryException` catch 是死代码，而"路径中间段是普通文件"会被静默转成 `Optional.empty()`，把"存储布局损坏"报告成"数据不存在"；`delete` 会静默删空目录。

**Linux 上第 3 条的异常映射未实测。** `NoSuchFileException` 在 Linux 可能是 `FileSystemException: Not a directory`。部署到 Linux 前应复跑探针。

#### 与上游的有意偏离

上游 `JsonHelper.ReadFromFile` 用 `contains(".DS_Store")` 和 `contains("malformed_")` 在**读取阶段**跳过文件（注意是 contains，不是前缀匹配）。移植端没有对应的读侧跳过层，所以把这两个 contains 过滤合并进了 `list` 枚举边界。否则从 1.7.10 迁移来的世界里已有的 `malformed_<uuid>.json` 会被当成正常玩家进度去解析。

#### 已知未做

- **无父目录 fsync**。Java 无跨平台方案，Windows 尤其做不到。`appendLine` 新建文件后目录项持久性取决于文件系统；`AtomicFileStorage` 的 `Files.move` 同理。javadoc 已把承诺降级到与实际一致，不要再声称 forces to stable storage。
- **无短写/force/truncate 故障注入测试**。`WorldDataStorage` 直接开真实 `FileChannel`，没有可注入的 seam。加抽象会扩大批次范围，故只做了真实文件系统端到端覆盖。
- **同路径并发无内部锁**。两个类的 javadoc 都声明必须在服务端主线程调用或由调用方串行化。上游靠 `BQThreadedIO` 单线程队列做这件事，移植端取消了那层队列却没有替代物。若下一批次要从网络线程写审计，必须改成按路径加锁。
- `list` 的 `Files.isRegularFile` 会把 stat 失败当作非普通文件静默省略，上游则会交给读取阶段并至少记日志。已在 javadoc 登记。

### 4.2c 身份映射持久化与追加审计（已完成）

提交 `feat: 持久化身份映射并建立自校验追加审计`。这是 §4.3 第 2 项。

落地文件：`core/identity/` 下 `IdentityRecordCodec`、`IdentityRecordFields`、`IdentityRecordFormatException`、`FramedLines`、`IdentityAuditOperation`、`IdentityAuditRecord`、`IdentityAuditReport`、`IdentityRecordRejection`、`IdentityAuditLog`、`LegacyMappingStore`、`CorruptIdentityMappingException`、`PersistentPlayerIdentityService`；`core/storage/DirectoryWorldStorage`；`platform/fml/ServerIdentityContext`。`DeterministicPlayerIdentityService` 新增 `restoreMappings` 与 `requireDerivedIdentity`，`MiteWorldStorage` 改为委派 `DirectoryWorldStorage`，`CommonBootstrap` 接线 bind/retire。

**格式是纯文本自校验行，不用 JSON**，因此完全不碰 Gson 2.2.2 的限制。审计日志 `identity/IdentityAudit.log` 为 magic `BQIDAUDIT1` 加 10 个 `|` 分隔字段，末字段 CRC32；映射文件 `identity/LegacyIdentityMappings.txt` 为 `BQIDMAP1|<recordCount>|<crc32>` header 加逐条 `BQIDMAPREC1|...`，按 legacy UUID 排序以保证字节稳定。校验六道：字段数、magic、校验和、转义合法性、字段规范形式、重编码一致性。header 记录数校验是**整行删除唯一的检测手段**，逐行校验和查不出这种篡改。

基线：28 个测试类 **239** 个测试全绿。

#### 异源审核修掉的两个 Blocker

reviewer 用 Claude 家族，两条都是 writer（GPT 家族）自查没发现的，再次印证同源盲区。两条都由主代理独立核实后修掉并用反转法 1:1 归因。

**Bk1 审计序列号在「完整记录仅丢失末尾 LF」后被重用**。危险残片不是截断记录（那些必然栽在字段数检查上），而是校验和完全合法、只缺末尾 LF 的完整记录——正是 `appendLine` 分帧守卫存在的那个场景。后果链：重启时该行进 rejections、`highest` 跳过它 → 下次写入重用同一序号 → `appendLine` 补的 LF 恰好把残片终结成合法行 → 此后每次读取都接受崩溃期那行、**永久拒绝新写入的合法记录**。快照里有该映射，审计日志却永远不展示它。一次掉电即可触发，无需篡改。现 `initializeSequenceFromStorage` 一并遍历 `rejections()` 并用 `lenientSequence` 从被拒行解析序号。反转验证：恰好 1 个测试失败。

**Bk2 `restoreMappings` 不校验 identity UUID 与用户名的派生一致性**。`<bob 的派生 UUID> | "alice"` 可零拒绝加载，进度按 identity UUID 记到 bob 名下而所有界面显示 alice；自映射同样被接受。可达路径除手改文件外还有两条：后续 writer/迁移工具构造出不一致的字段对；**派生规则（namespace 或折叠规则）一旦变更**，旧文件静默保留旧 UUID 而 `resolveUsername` 返回新 UUID，同一玩家裂成两个身份——现在这种情况会显式报错而非静默，属破坏性存档格式变更，需显式迁移。现新增 `requireDerivedIdentity` 做跨字段校验。反转验证：恰好 2 个测试失败。

多对一映射**仍然接受**，因为 `mergeLegacy` 不调 `requireUnusedIdentity`，它是合法产物（已核实上游行为）。自映射也保留，理由是只在读路径拒绝会让 `mapLegacy` 接受的映射在重启后无法加载。

#### 同批修正的测试缺陷

三处测试把 `test_player` 的派生 UUID `536d10cf-c585-5a3e-9060-f818e26945f6` 当作 `alice` 的身份。主代理用 Python `uuid.uuid5` 以代码里的 namespace 独立验算：`test_player` → `536d10cf…`、`alice` → `defc59df-21a5-5a2d-b766-35e73bfb50ec`、`bob` → `e30601a1-c438-5ade-af4d-f7e0d601a30f`。这批 round-trip 测试**依赖 Bk2 的缺陷才通过，把缺陷固化成了规范**——修好 Bk2 后它们会变红。这是反转法查不出、只有异源审核能发现的那类问题，与 §1 记录的 `appendLine` 案例同源。`DeterministicPlayerIdentityServiceTest` 里配 `test_player` 的三处用法本来就对，未动。

#### 已知未做

- **写序缺口**：审计 append 先于快照 save。审计失败回滚内存；快照失败但审计已成功时内存已应用、审计有记录、磁盘陈旧，抛 `UncheckedIOException`，调用方吞掉即内存/磁盘分歧。选这个方向是因为反序会让崩溃窗口产生「无审计记录的已应用变更」。下一次成功 mutation 会整份重写快照，分歧可自愈。
- **`ServerIdentityContext` 的纯 JVM 生命周期测试源码覆盖**：不同临时 world store、同 world 重启加载、`retire(A)` 后 `bind(B)` 的 clean rebind、matching-owner retirement、引用身份所有权、旧 owner 的 stale retire 与 bind 均不清除/替换新 binding、不同 owner 的损坏存储 bind 在加载前被拒且保留新 binding，以及同 owner 加载失败后保持 unbound。实际 `MinecraftServer` storage resolution、RIC server-started 是否确实在 `loadAllWorlds` 之后触发、world delete/unload 与 server stop callback 的运行顺序仍**未经 runtime smoke 验证**。
- **header-only 且 `count=0` 的文件与合法空快照字节完全相同**，从新世界复制一个过来即可静默清空全部映射并绕过 CRC。reviewer 建议启动时按 sequence 重放审计得净效果与快照对账——这也是目前审计日志唯一能发挥作用之处，否则它是只写的。
- CRC32 只防意外损坏与截断，**不防篡改**，对有文件写权限者无效。
- 无并发测试。靠 `synchronized` 加 javadoc 声明主线程；`WorldStorage` 仍无按路径内部锁。
- Linux 未测，异常映射可能与 Windows 不同。

### 4.2d JSON 序列化层（已完成）

提交 `feat: 建立 NBT 与 JSON 互转层并接入替换前 readback 校验`。这是 §4.3 第 3 项的序列化部分。

落地：`core/storage/json/` 下 `NbtJsonCodec`、`JsonDocuments`、`JsonDocumentStore`、`JsonSchemaFields`、`MalformedJsonDocumentException`、`NbtJsonDiagnostics`；`NbtNumbers` 从 `api/properties/basic/` 移到 `api/util/` 并公开（git 识别为重命名）；`NbtCompat` 新增 `sortedKeys`/`elements`；`AtomicFileStorage` 补 readback 钩子。基线 **31 个测试类 292 个测试**全绿。

#### 上游类型标记约定（存档兼容基准，证据 `NBTConverter.java:202-319`）

- `format=true`：compound 每个 key 编码为 `"<key>:<tagId>"`（`:272`），key 先经 `TreeSet` 排序（`:267`）；**list 不再是数组而是对象**，元素 key 为 `"<index>:<tagId>"`（`:221-225`）。
- **上游有两套写侧，格式基准取哪套要分清**：`JsonObject` 版（`:202/:262`，带 `:267` 的 TreeSet 排序）只被 `/bq_admin default` 导出命令（`QuestCommandDefaults.java:208/255/272/320/343`）和 `bq_standard` loot（`LootSaveLoad.java:47`）使用；**真实存档写侧走的是流式 `JsonWriter` 版**（`:139/:182`），`SaveLoadHandler.java:314/341/349/357/369` 写 QuestDatabase/Parties/NameCache/Lives 全部经它，而它在 `:185` 直接遍历 `func_150296_c()` **不排序**。故"TreeSet 排序是存档格式约定"是错的，排序只是导出命令的行为；我们两条路径都排序属 §4.2d 记录的有意偏离，因 JSON 成员顺序无语义、上游读侧 `:289` 按 `entrySet()` 遍历且 `setTag(key,…)` 与顺序无关。
- **上游真实存档的 compound 键序不只是"未排序"，而是不确定**（javap 核实，非推测）：`NBTTagCompound.tagMap` 声明类型为 `java.util.Map`，但两个构造器均 `new java/util/HashMap`，故 `func_150296_c()` = `tagMap.keySet()` 是 hash 序。推论有两条硬约束：(a) **多 key compound 不存在字节级上游基准**，任何断言精确序列化字符串的测试只在验证我方约定，不构成上游兼容性证据，不得如此宣称；(b) 差分/预言机测试比对 compound 必须按解析后结构比，比字符串会假失败。我方 TreeSet 排序因此不是引入分歧，而是把本就无契约的顺序收紧为确定值。
- **list 索引数字被读侧丢弃，顺序靠成员次序承载**（继承上游缺陷，非我方引入）：两侧读 list 都只解析 id 后缀、按 `entrySet()` 出现顺序 `appendTag`（上游 `:377-389`，我方 `NbtJsonCodec.java:352-365`），不看索引数字。写侧按自然循环序 `0,1,2…` 输出、Gson 保序，故自产文件正确；但 ≥10 元素的 list 一旦被外部工具按字典序重排 key（`"10:4"` 排到 `"1:4"` 与 `"2:4"` 之间），元素会**静默乱序且无任何报错**。改为按索引数字排序会在上游自己能读的文件上与上游分歧，故保持原样，风险由 `NbtJsonCodecTest` 的 `listIndicesPastNineKeepNaturalOrderNotLexicographicOrder` 与 `lexicographicallyReorderedListKeysSilentlyPermuteElements` 两例钉住。
- `format=false`：裸 key（`:274`）、普通数组（`:230-238`），类型信息丢失。
- 数值 tag（id 1..6）一律 `JsonPrimitive`（`:207-209`）；`NBTTagByteArray`/`NBTTagIntArray` **无论 format 都是普通数组**（`:240-255`），类型只能靠外层后缀恢复。
- null 或不识别类型返回空 `JsonObject`（`:203-205`、`:256-258`）。

**类型后缀必须按最后一个冒号切分**，上游 `:300` 用的就是 `key.lastIndexOf(':')`。不能改成首个冒号：属性 key 自身含冒号，`"betterquesting:editmode:1"` 必须切成 `betterquesting:editmode` 与 tagId `1`。上游 `:305-312` 的怪异分支（后缀解析失败时只在 `tags.hasKey(key)` 为真才 `continue`，否则以 `id=0` 落回裸 key）已复刻，以保证读上游存档行为一致。

#### 平台差异

上游 `:267` 用 `func_150296_c()` 取 key 集合，**MITE 的 `NBTTagCompound` 没有任何 key-set 方法**，只有返回裸 `Collection` 的 `getTags()`（`tagMap.values()` 活视图）。MITE 上枚举 key 的唯一路径是遍历 `getTags()` 再对每个 `NBTBase` 调 `getName()`（已 javap 核实为 `public final String`）。上游 `:409`、`:518-531` 反射访问 `NBTTagList.tagList`，MITE 有 public `tagCount()`/`tagAt(int)` 可直接用（javap 核实 `tagList` 为 private `java.util.List`，构造器 `new ArrayList`；无参构造器置 id=9、name=`""`；`setTag` 先调 `NBTBase.setName(key)` 再 `Map.put`）。

**javap 探针只能打重映射后的 loom 中间 jar**，路径：

```
~/.gradle/caches/fml-loom/minecraftMaven/net/minecraft/minecraft-merged-empty-intermediate/
  1.6.4-MITE-loom.mappings.1_6_4_MITE.layered+hash.1614893260-v2/
  minecraft-merged-empty-intermediate-…-v2.jar
```

打原始 `1.6.4-MITE.jar` 或 `minecraft-merged.jar` 会得到空输出：原始 jar 是**混淆**的（7208 个 `a.class`/`aa.class` 形态的条目），按名字 grep 或 javap 全部零命中，而**零命中不构成"MITE 没有该能力"的证据**——这个坑已踩过两次（`NBTTagCompound`、`fluid`）。

#### `fallbackTagId` 不复刻数组扫描是**安全**的（上游该扫描是死代码）

上游 `:474-508` 的 `fallbackTagID` 看似对 `JsonArray` 扫元素定类型（全落 byte → `7`，全落 int → `11`，否则 `9`），但 **`:510` 的 `tagID = 9;` 位于循环闭合（`:508`）之后、`isJsonArray()` 分支之内，无条件执行**，把扫描结果全部覆盖。故上游所有 `JsonArray` 一律解析为 tagID `9`，那 30 行扫描是死代码。我方 `NbtJsonCodec.fallbackTagId` 直接 `return TAG_LIST` 与之逐位等价，属安全偏离（省掉死代码），非行为分歧。

**不要"修"它。** 复刻扫描会让我方在上游产出 `NBTTagList` 的地方产出 `NBTTagByteArray`/`NBTTagIntArray`，反而制造真分歧。曾有一轮误判此处为"静默丢数据的不安全偏离"（commit `5f9026a`），错因是只读 `:454-470` 与尾部片段、把 `:510` 当成空数组分支；`NbtJsonCodecOracleDiffTest` 的差分已证明两侧此路径一致。

可达性（顺带核实，结论仍有效）：上游全仓 **23 个调用点（11 个 `JSONtoNBT_*` + 12 个 `NBTtoJSON_*`）一律传 `format=true`**，无任何 `format=false` 调用者，故 plain 方言在上游是死代码——这解释了 `:167-171` 缺 `endArray()` 为何能潜伏至今。`fallbackTagId` 在 `format=true` 下仍可达（经 `:305-312` 后缀解析失败落回 `id=0`，即手改档）。

#### `format` / `build` 上游语义（已由主代理独立核实）

- **`format`** = `"3.1.0"`，定义在 `BetterQuesting.java:57`。只写在 `QuestCommandDefaults.java:207`、`:342`、`SaveLoadHandler.java:307`，**全仓无读取点**——上游没有任何 format 版本分支，它纯粹是标记。移植端原样保留。
- **`build`** 写在 `SaveLoadHandler.java:309`、读在 `:197`，是上游**唯一**的版本分支：`equalsIgnoreCase` 不匹配时把五个数据库复制进 `backup/<storedVersion>/`。因为只有 `saveConfig` 写它，默认任务包只有 `format` 没有 `build`，所以**缺失 `build` 必须视为需要升级**。
- **`mitePortFormat`** 新增，值 `"1"`，仅在移植端布局变更时递增；缺失即表示文件由上游写出。

#### Gson 2.2.2 的数值陷阱（新发现）

`LazilyParsedNumber.intValue()`/`longValue()` 回退到 **`BigInteger`** 而非 `BigDecimal`。因此解析出的 `"count:3": 1.5` 或 `"count:4": 1E3` 会**抛异常**而非截断，codec 把该成员降级为空字符串 tag、**值丢失**。上游 `instanceNumber` 同样失败，故未"修"（修了会让移植端写出的文件与上游分歧）。推论：`1E3` 不含小数点，`fallbackTagId` 会判成 long 然后丢值，**手工编辑这些文件时不能用指数记法**。

#### 已知未做

- **生产接线状态**：`JsonDocumentStore`/`JsonSchemaFields` 已由 B4.1-B4.6 接入各数据库生命周期；严格 completion-only 的旧 `QuestProgress.json` copy migration 也已接入生产启动路径并永久保留原件，见 §4.3。带 task progress 或无法证明无损的数据仍会 fail closed，完整 task-bearing 迁移语义尚未实现。`stamp(root, build)` 的 build 串仍靠参数传入，因为还没有解析 mod 版本的途径（上游用 `Loader.instance().activeModContainer().getVersion()`，需要 FML 侧 supplier）。
- **从未用真实上游 1.7.10 世界写出的 `QuestDatabase.json` 验证过**。往返只在自产文档上证明，格式规则逐条对着上游行号复刻。这是唯一能闭合兼容性声明的检查，缺 1.7.10 世界做不了。
- `format=false` 下 byte[] 与 int[] 真正不可区分，都降级为 long list。
- quarantine 是 copy 而非 move（与上游一致），重复失败会覆盖上一份隔离副本；且用 `writeAtomically` 复制会整文件进内存。
- `NbtJsonDiagnostics.IGNORE` 是默认，异常在游戏内目前静默，需平台接线传入 logger。
- `mitePortFormat` 无迁移分支，`readMitePortFormat` 生产未用。

#### 有意偏离上游

streaming 路径也排序 key（上游 streaming 走 1.7.10 `HashMap` keySet 无序，而 `SaveLoadHandler.java:314` 用的正是 streaming 路径，故上游实际产出是 hash 序；JSON 成员顺序无语义，上游仍可读）；plain 模式的 list 补上 `endArray()`（上游 `:166-171` 调了 `beginArray()` 却从不 `endArray()`，会让其后每个 tag 错位，因唯一 streaming 调用方传 `format=true` 而潜伏）；`fallbackTagId` 不复刻上游 `:474-510` 的数组扫描（扫完在 `:510` 无条件覆盖为 `9`，不可观测）；非 object 文档拒绝而非静默返回空（上游对空文件返回 null 并让调用方走默认值，会让截断的数据库在下次保存时被空库替换）；null NBT 载荷降级为空值（MITE 单参构造器留 null，1.7.10 不可达）。

### 4.2f B3 golden fixture 套件（已完成）

本批次只新增测试与资源，不接线生产数据库，也不实现 `LegacyQuestImporter`、网络、GUI 或 Fluid Task。资源总数为 **33**：`fixtures/database/` 13 个（4 个空数据库、典型任务库/任务线与 parties、旧单文件和分玩家进度、缺失 item/entity/dimension/fluid placeholder 各 1）；`fixtures/malformed/` 18 个；`fixtures/lenient/` 2 个。超大输入不提交多 MB 文件：测试生成 250,000 元素（约 1.6 MB）数组并验证往返，另以 20,000 层嵌套记录 Gson 2.2.2 的拒绝/栈溢出边界；当前生产 codec 没有字节大小上限，因此这不是“超限文件被生产代码拒绝”的兼容性声明。

字段证据来自仓库内 `BetterQuesting-master` 1.7.10 源码：`SaveLoadHandler` 的文件/根键和流式 `NBTConverter` 路径；`QuestDatabase`、`QuestLineDatabase`、`QuestInstance`、`TaskStorage` 的 ID/进度形状；`TaskRetrieval` + `BigItemStack.writeToNBT` 的 `id`/`Count`/`Damage`/`OreDict`/`tag`；`TaskHunt` 的 `target`/`targetNBT`；`TaskLocation` 的 `dimension`/坐标；`TaskFluid` 与 `JsonHelper.FluidStackToJson` 的 `FluidName`/`Amount`/可选 `Tag` 及 `ignoreNBT`/`consume`/`groupDetect`/`autoConsume`。缺失内容测试只验证 codec/oracle 的不透明保留，不实例化目标平台对象、不静默替换。

`DatabaseFixtureTest` 对 13 个文件逐一做 JSON→NBT、NBT→JSON oracle 差分和往返；compound 按结构比较而非字符串成员顺序，list 按自然索引顺序比较。所有 fixture 都是按源码字段手工构造，仓库没有可运行的真实 1.7.10 世界导出物；因此明确为**未由真实运行产物验证**，不能声称字节级上游兼容。真实上游 `QuestDatabase.json` 世界写出仍是未闭合风险。

### 4.3 存档/迁移剩余

第 1、4 项已完成（见 §4.2）；读侧与追加写前置已完成（见 §4.2b）。以下项目均未完成，不得在后续报告中声称已落地：

1. ~~完成 `WorldStorage` 世界目录解析及所需 access widener 或 invoker/accessor。~~ 已完成。
2. ~~持久化身份映射并建立追加审计；迁移报告必须记录身份来源和管理员决定。~~ 已完成，见 §4.2c。
3. 序列化层与字段语义已完成，见 §4.2d；剩余部分是把 codec 接到各数据库的读写路径（批次 B4）。
4. ~~实现原子文件写入与带时间戳备份。~~ 已完成，但备份改为显式调用而非每次写入隐式生成（对齐上游，见 §4.2 H1）。替换前的 readback 校验已于 §4.2d 补齐。
5. ~~建立 JSON/NBT golden fixture：空库、典型任务线、旧单文件进度、分玩家进度、缺失物品/实体/维度/**流体**、损坏/截断/超大文件。~~ B3 已完成，见 §4.2f：13 个 database fixture、18 个 malformed、2 个 lenient；超大输入为生成式压力样本，当前生产 codec 无字节大小上限。

流体一类不可跳过，已核实：上游存在 `bq_standard/tasks/TaskFluid.java`、`api/placeholders/FluidPlaceholder.java`、`api/questing/tasks/IFluidTask.java`，共 17 个文件引用 `FluidStack`/`FluidRegistry`，所以**真实上游存档里可能带流体任务数据**。序列化形状（`TaskFluid.java:70-81`）：`requiredFluids` 是 compound 列表，每项由 Forge `FluidStack.writeToNBT` 写出（`FluidName` 字符串 + `Amount` int + 可选 `Tag` compound），同层还有 `ignoreNBT`/`consume`/`groupDetect`/`autoConsume` 四个 boolean；进度侧 `data` 是 TAG_INT 列表（`:124`）。`FluidStack` 是 Forge 类型而 MITE 无 Forge，故阶段 3 对流体只能**不透明保留 + placeholder + 迁移报告**（对应 `plan.md` 工作项 9），不得静默替换成其他物品，语义解析留待后续阶段。

**探针方法警告**：不要用 `unzip -l 1.6.4-MITE.jar | grep -i fluid` 之类按名字搜 jar 来判断平台是否支持某概念。该 jar 是**混淆**的（条目形如 `a.class`/`aa.class`/`aaa.class`，7208 个文件），任何可读类名都搜不到，零命中不构成"不存在"的证据。同样的坑先前在 `NBTTagCompound` 上踩过一次，见 §5.2 与记忆条目。
6. ~~实现 `LegacyQuestImporter`；旧 `QuestProgress.json` 转换为逐玩家文件后必须保留原件。~~ 已完成；实现为 `LegacyQuestProgressImporter` 的 completion-only copy migration，原件永不删除，协议和限制见下文。
7. 周期 autosave、world save、server stop 均触发 flush；server stop 必须等待待处理 I/O 完成。
8. 目标平台缺失物品、实体、维度等内容时生成 placeholder 和迁移报告，不得静默替换成其他内容。
9. 最后接回 `NameCache`/`QuestCache`；`IQuestSettings.canUserEdit` 随 `NameCache` 后续处理，不能遗漏或让网络/GUI 假设权限检查已存在。

`QuestLoot.json` 在阶段 3 **只做识别、备份/损坏证据留存和不透明保留**；语义解析仍属于阶段 7 的 LootRegistry。B4.6 的存储威胁模型与本 mod 其余基于路径的 world persistence 一致：宿主进程和本地用户受信任。操作开始时已经存在的意外 symlink/reparse point、非常规 source、恶意文件 bytes、截断、超限、深度攻击、普通 I/O 失败、命名碰撞和部分写入属于范围内；恶意本地参与者在操作期间并发替换父目录、junction 或预留文件不属于范围内。实现不会也不得声称父路径遍历 race-free。

#### `QuestProgress.json` completion-only copy migration（已完成）

迁移入口在 progress load 之前运行，但 quest database 必须已经加载；现有启动顺序仍是 `loadQuestDatabases` 后 `loadQuestProgress`。world save 与 server stop 仍先落 progress 再落 database，未改变两处顺序约束。legacy UUID 原样保留，不做用户名、在线身份或 identity mapping 推断。

识别先从 canonical BetterQuesting data root 直接捕获最多 8 MiB + 1 byte 的 source，要求 root/source/既有 marker/backup/output directory/output 都是 non-symlink、non-reparse 的预期类型，并在操作开始时验证 canonical containment。捕获前后复验 source/root identity。bytes 使用 REPORT 模式严格 UTF-8 decode，再由 strict `JsonReader` 做最大 128 层的结构解析；comments、单引号、unquoted token、trailing comma、重复 raw member、typed-key conflict、非连续 list index、非 canonical number，以及 string value/member name 中的 unpaired UTF-16 surrogate 都在 NBT 转换前拒绝；合法 surrogate pair 保留。legacy 根必须没有 canonical `mitePortFormat`，且只能有 `questProgress:9`；每条 quest 只能有一个 `questID:3` 或完整 `questIDHigh:4` + `questIDLow:4`、必需的 `completed:9` 和 `tasks:9`。current/future canonical `mitePortFormat:8` 保持 `BLOCKED`，不进入 legacy 分支。

自动转换只接受 tasks 为空且每条 quest 至少有一个 canonical UUID completion owner 的记录。重复 quest ID、同 quest 重复 completion UUID、case-variant UUID、dual quest ID、unknown quest、越界/非 integral 数值均拒绝。root/quest 层任何 opaque 字段以及 empty completion quest 都无法证明归属，故 `BLOCKED`；不会把它们复制给所有玩家。completion 内的 owned opaque 字段只有在其完整 typed NBT 形状可无损转换时才保留；byte/short/int/long、string、compound、formatted list、byte[] 和 int[] 有逐值范围检查，float/double 因不能证明十进制 JSON 到 binary NBT 的精确性而拒绝。已验证的 legacy numeric quest ID 会先用当前 `QuestDatabase` 已知映射解析，再只写 `questIDHigh:4`/`questIDLow:4`，生成文件不残留冲突的 `questID:3`。初次迁移最多生成 4096 个 player files、总计 64 MiB，marker 最多 1 MiB；每个 planned player document 先在 8 MiB bounded buffer 中完整序列化，等于 8 MiB 可发布，多 1 byte 即在创建 backup/output/marker 前 fail closed。

固定 artifact 位于同一 data root：原件 `QuestProgress.json` 永久保留；exact-byte backup 为 `QuestProgress.json.legacy-migration.bak`；immutable prepared marker 为 `QuestProgress.legacy-migration.prepared`；current complete marker 为 `QuestProgress.legacy-migration.complete`；live split outputs 位于 `QuestProgress/<canonical-uuid>.json`。prepared marker 记录 source/backup digest 和初始 split plan，写入 temp 后 file fsync，再要求同文件系统 `ATOMIC_MOVE` 发布，最后在 provider 支持时 parent-directory fsync；不支持 atomic marker move 时迁移失败，不做 non-atomic fallback。backup 和每个 output 以 `CREATE_NEW` 写入、file fsync、parent-directory fsync；complete marker 最后用同样协议发布。这里仅声称 marker publication 使用 provider 明确支持的 atomic move，不声称多文件事务本身原子。

startup 只有在 source 可重新严格识别、prepared marker 与 source 重建的初始 plan 完全一致、backup 与 source digest 一致、complete marker 与**当前每个 live output** 的 path/size/SHA-256 完全一致时才忽略保留的 source。complete 缺 prepared、marker/source/backup/output 缺失或不匹配、unexpected output、symlink/reparse/non-regular path 都 fail closed。仅有 valid prepared marker 的 partial transaction 会在 startup 恢复：exact artifact 复用，缺失或 digest 不符的 transaction-owned partial artifact 从仍验证通过的 source 重建，然后发布 complete。ordinary failure 尝试逆序清理；第一次 cleanup delete/sync 失败即停止删除更早的 recovery dependencies，suppressed exception 与 prepared marker/backup/source/尚存 outputs 一起保留。若 complete marker 的 rollback delete 失败，则不再删除它依赖的 outputs/backup；restart 要么完整验证该 complete state，要么确定性 fail closed。若断电只留下尚未发布的 prepared temp，无法证明该 collision 属于本次事务，startup 有意 `BLOCKED`，不自动删除。

迁移完成后的正常 player save/delete 在无 dirty window 时复验 source/prepared/backup/current complete 与全部现有 output digest。写前先 bounded-serialize 并记录本 lifecycle 准备发布的 player digest；写后刷新会 canonical-parse 旧 complete marker，把每个 non-dirty live output 与旧 manifest 比较，并要求每个 dirty output 精确等于本 lifecycle 已知 save digest 或已知 delete。refresh 失败后的 retry 仍执行同一比较，故不能顺带认可其他玩家的外部修改、删除或新增；随后才 sync `QuestProgress/`，并以 file fsync + required atomic replace + root-directory sync 刷新 current complete marker。因此后续 restart 仍逐 output 验 digest。若进程在 live file 已变更而 complete marker 尚未刷新时终止，startup 有意 fail closed，不把无法区分的合法更新与篡改自动归类；只有同一仍存活 lifecycle 持有上述 known dirty state，才能安全 retry。原件和 exact backup 始终存在，故该窗口不丢 legacy source，但 lifecycle 消失后可能需要管理员确认。

运行证据仅覆盖 macOS + Java 17 default provider。directory fsync 在 Windows 分支跳过，在其他 provider 只忽略明确的 `UnsupportedOperationException`；Windows/junction 行为未做运行测试。trusted-local-host 模型与 QuestLoot 相同：操作期间恶意本地并发替换路径不在范围内，也不声称 race-free containment。SHA-256 marker 是完整性/状态一致性校验而非针对受信任本地管理员的认证，未使用 secret 或 MAC。

#### `QuestLoot.json` opaque recognition（已完成）

生产路径使用 Windows/macOS 默认 Java provider 均提供的普通 NIO API：立即把非符号链接 world root 解析为 canonical root，确认固定的直接子项 `QuestLoot.json` 仍受该 root 包含，以 `NOFOLLOW_LINKS` 读取属性并只接受 regular file。source channel 优先以 `READ + NOFOLLOW_LINKS` 打开；provider 不支持该 open option 时才退回 `READ`，并在打开前后按上述 trusted-local-host 模型复验 source/root identity。B4.6 的这套实际路径实现只在 macOS/Java 17 上做过运行测试；Windows 分支只使用可移植 API 并保留 provider fallback，但本轮未在 Windows 上运行，不能把旧的 Windows 追加写探针当成这条路径的运行证据。每次 lifecycle/start 只捕获一次 immutable bytes，最多读取 8 MiB + 1 byte；严格 UTF-8 decode 后，深度上限 128 的保守预检在进入 Gson 2.2.2 前识别双/单引号、三种 lenient comment。token 边界逐项对齐 Gson 2.2.2：`nextNonWhitespace` 只跳过 ASCII tab/LF/CR/space，unquoted literal 另把 form feed 当 delimiter；U+2003 等非 ASCII whitespace 仍属于 unquoted token，因此其后的 quote/backslash 会在 Gson 递归前按歧义 token 拒绝。正确引用字符串内的普通 Unicode 仍有效。

缺失报告 `ABSENT`。上游 `groups:9` legacy envelope、当前 `mitePortFormat:8="1"` 和 future canonical positive integer 版本均报告 opaque `BLOCKED`，但仅在从捕获 bytes 生成 exact-byte `*.recognized.bak` 后返回。畸形、非 object、类型错误、词法歧义、非法 UTF-8 或深度超限仅在 exact-byte `*.corrupt.evidence` 成功后报告 `QUARANTINED`。两类副本都在 source 旁以 `CREATE_NEW` 直接预留最终名称并通过同一 channel 写入捕获 bytes，随后 file fsync，并在 provider 支持时 parent fsync；不再把 temp atomic-move 到已存在的 reservation，因此兼容 Windows 默认 provider。既有 source/backup/evidence 不被覆盖，碰撞使用数字 suffix，普通写入或 sync 失败会清理本次不完整目标，清理失败作为 suppressed exception 保留。这不是 atomic publication：最终路径从 `CREATE_NEW` 起可见，成功返回前其他观察者可能读到增长中的文件；进程崩溃或断电可能留下不完整目标，阶段 3 不做 power-loss recovery，后续重试会把残留视为碰撞并使用数字 suffix。副本失败时 lifecycle fail closed 且不缓存成功；同一 lifecycle 的后续 start 会在无成功缓存时重试，成功后只缓存/绑定一次。超过 8 MiB 报告 `OVERSIZED`，source 原样保留且不生成副本；阶段 3 没有任何 QuestLoot serialization/writeback 路径。重复同 owner start 返回已完成分析和同一副本，world delete/server stop 会清理绑定，跨 world rebind 会关闭旧 lifecycle。

### 4.4 下一会话首轮动作

1. 先核对 `git status`、`git log` 和当前测试数。
2. 再读取 `plan.md` 阶段 3 与 `docs/platform-probes.md`。
3. ~~派 writer 做 **golden fixture 套件**（批次 B3，§4.3 第 5 项）。~~ B3 已在 §4.2f 完成并通过定向测试；仍未闭合的是“真实上游 1.7.10 世界写出的 `QuestDatabase.json`”运行产物验证，当前 fixture 均已明确标注为**未由真实运行产物验证**。注意 §8：领域层直接暴露 `net.minecraft.NBT*`，故 fixture 测试必须带 Minecraft classpath 跑，不能当纯 JVM 单元测试。

   之后是 B4：把 codec 接到 `QuestDatabase`/`QuestLineDatabase`/`PartyManager`/`QuestSettings` 的读写路径。reviewer 曾建议建立单一 `PropertyNbtCodec` 适配层（§8），可在 B4 一并考虑。
4. 派 reviewer 复审该批次。**reviewer 主模型直接指定 `S3AI/claude-opus-5`**，不要用配置文件里的 `geek2-claude/claude-opus-5`（本会话连续两次中途断连）。
5. 主代理核实相关 `javap` owner/字段/descriptor 与测试结果，用反转法验证关键修复，再提交。

### 4.5 已知残余风险

身份映射已持久化、追加审计已有调用方（§4.2c），但该批次自身的残余风险见 §4.2c「已知未做」——其中**写序缺口**与 **header-only 文件可静默清空全部映射**两条最值得下一批次关注。

仍然存在的：`MitePlayerIdentityAdapter` 尚未用真实 `EntityPlayer` 做测试；`ServerIdentityContext` 有纯 JVM 生命周期测试源码，但实际 `MinecraftServer` storage resolution、RIC server-started 排序和生命周期 callback 仍未经 runtime smoke 验证；container GUI blocker 仍存在。

`WorldStorage` 的残余风险分两处：§4.2「已知未做」（无 readback 校验、生命周期绑定未接线、符号链接可绕过词法守卫、真实 Minecraft server/save handler 集成未运行验证、POSIX 权限未实测）与 §4.2b「已知未做」（无父目录 fsync、无故障注入测试、同路径并发无内部锁、Linux 异常映射未实测）。`MiteWorldStoragePathTest` 现已覆盖纯路径分流、production adapter 与 mapped overload 类型替身；§4.2 的「纯写侧接口」一条已由 §4.2b 解决。

---

## 5. 平台关键事实

完整证据链对 §5.1、§5.4 与 §5.5 成立，证据在 `docs/platform-probes.md`。§5.2/§5.3 的结论由当前代码注释、测试及本项目字节码核实记录锁定，但尚未整理进 `docs/platform-probes.md`。这里是最容易踩的五条。

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

### 5.4b custom payload 长度边界与包处理线程（阶段 4 必读）

完整证据在 `docs/platform-probes.md` 的「Custom payload 长度边界与包处理线程」。三条结论：

1. **`Packet250CustomPayload` 收发侧比较符不对称。** 构造器 `if_icmple 32767`（允许等于），`readPacketData` `if_icmpge 32767`（拒绝等于）。恰好 32767 字节能发出去，但对端跳过 `readFully`、`data` 留 null，**静默丢整个包体**。可用上限是 **32766**。`length` 由有符号 `readShort()` 读取，失败分支不消费声明字节数，所以接收侧必须先判 `data == null` 显式拒绝，否则声明 `length` 为 0/负数/≥32767 即可触发 NPE。
2. **服务端包处理本就在主线程**（`MinecraftServer` tick → `NetworkListenThread.networkTick()` → `NetServerHandler.networkTick()` → `processReadPackets()` → `handleCustomPayload` → RIC mixin → `Packet.apply`）。上游的 `scheduleServerTask` 是为 netty 网络线程准备的，MITE 上没有这个问题；`MinecraftServer` 也无 `callable`/`FutureTask`/queue 成员、不实现 `IThreadListener`。**不要新建服务端主线程队列。** 仍需确认客户端侧排空点与 RIC 是否自带线程切换。
3. **上游分片无 transfer ID。** `PacketAssembly.java:35` 的 `bufSize = 20480`，容器只有 `size`/`index`/`end`/`data` 四字段；服务端按玩家单槽、客户端仅一个全局缓冲，登录连发 8 类包时交错即静默串包。

### 5.5 编译期成功不等于运行时可用（已踩一次）

**这是一整类风险，不是单个 bug。** 完整证据在 `docs/platform-probes.md` 的「依赖运行时可用性」。

本会话发现阶段 2 已提交的 `NaiveLookupLogic` 在用 fastutil 的 `Int2ObjectOpenHashMap`，而 fastutil **完全不在运行时 classpath 上**：不在游戏 jar、不在 launcher libraries、不在 FishModLoader/RustedIronCore/ManyLib，我们的产物 jar 里也是 0 个类。这会在首次 quest 批量查找时 `NoClassDefFoundError`。已改为 JDK `HashMap`。

为什么能溜进来：`build.gradle` 用 `implementation` 声明了 fastutil，编译通过、测试全绿（测试跑在完整 Gradle classpath 上），但玩家侧根本没有这个库。**测试绿不能证明运行时可用。**

为什么不能靠打包解决：本项目只有 `jar` 任务、**没有 `remapJar`**，而 Fabric 的 jar-in-jar 嵌套发生在 remapJar 阶段，所以 Loom 的 `include` 配置在当前构建下不生效。ModdedMITE maven 上的 `FastUtil:8.5.12` 也只有空壳 POM、没有 jar。

同一次排查还发现 Gson 是**版本**错配而非缺失：编译期解析到 2.11.0，运行期只有 2.2.2（`JsonObject.members` 字段类型为 `internal.StringMap` 锁定，且 launcher libraries 声明 `gson:2.2.2`）。已把 `build.gradle` 固定为 2.2.2，让编译器直接拦住不存在的 API。**阶段 3 序列化批次必须先读 `platform-probes.md` 的 2.2.2 可用 API 清单**，上游 1.7.10 用到的 `JsonParser.parseString`、`JsonArray.remove/isEmpty`、`JsonObject.keySet/size` 等一概不能照搬。

已核实的全部外部包提供者（新增依赖前照此格式先核实）：

| 外部包 | 运行时提供者 | `fml.mod.json` 已声明 |
|---|---|---|
| `huix.glacier.*`、`moddedmite.rustedironcore.*` | RustedIronCore | 是 |
| `net.fabricmc.api`、`net.xiaoyu233.fml`、`org.apache.logging.log4j`、`org.spongepowered.asm.mixin` | FishModLoader | 是 |
| `com.google.gson` | 游戏 jar 内嵌 2.2.2 | 平台提供 |
| `net.minecraft.*` | 游戏 jar | — |

注意 `org.apache.logging.log4j` 由 FishModLoader 提供，**不是** MC 1.6.4 自带（vanilla 1.6.4 用的是 `ILogAgent`，log4j2 是 1.7.x 才进 Minecraft 的）。

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
| `core/BetterQuestingConstants.java` | 探针 channel 用 3 参 `ResourceLocation(..., false)` 显式跳过资源校验 | 2 参构造器字节码 `iconst_1` 证明默认 `verify=true`，会把实例登记进 `resources_to_verify`；集成服务器每 20 tick 校验，而 channel 不是真实资源文件，故单人模式 HUD 会持续渲染红字。文本值不变，wire 兼容性不受影响。见 §5.1。 |
| `core/storage/json/NbtJsonCodec.java` | streaming 路径也排序 key；plain 模式 list 补 `endArray()`；`fallbackTagId` 不复刻数组扫描（**安全**：上游该扫描被 `:510` 无条件覆盖，是死代码，勿"修"，见 §4.2d 同名小节）；null 载荷降级为空值 | 详见 §4.2d「有意偏离上游」。其中 `endArray()` 是修上游真缺陷（`NBTConverter.java:166-171` 会让其后每个 tag 错位，因唯一 streaming 调用方传 `format=true` 而潜伏）。 |
| `core/storage/json/JsonDocuments.java` | 非 object 文档抛异常拒绝，不静默返回空 | 上游 `fromJson` 对空文件返回 null 并让调用方走默认值，会让截断的数据库在下次保存时被空库替换。 |
| `core/identity/` 全体 | 映射快照与审计日志用纯文本自校验行，非 JSON | 上游没有身份映射与审计的对应物（1.7.10 有可验证的 Mojang UUID），故这是新增而非偏离。选纯文本是为规避 Gson 2.2.2 的 API 缺失面，见 §4.2c。 |
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
