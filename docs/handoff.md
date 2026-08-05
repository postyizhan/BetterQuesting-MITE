# 交接文档

本文写给接手本项目的下一个会话。目标是让你在不重读全部上游源码的前提下，知道现在到哪了、哪些结论已经被字节码证实、哪些坑已经踩过。

读完本文后，按顺序再读 `plan.md`（总体移植计划）和 `docs/platform-probes.md`（平台事实与 blocker）。`docs/source-migration-ledger.md` 是上游源码台账，需要排期时再查。

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

两个 agent 已配置好，直接用：

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

14 个提交，工作树干净，`./gradlew clean build` 通过，127 个测试全绿（21 个测试类，77 个生产 Java 文件）。

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

### 4.1 `PlayerIdentityService`（最高优先级，也是最大风险）

**先读 plan.md §5 阶段 3 第 7 条的硬要求**：优先使用可验证的 MITE/GameProfile/服务端持久身份。若只能取得用户名或离线派生 UUID，**不得自动声称可识别改名玩家**；歧义数据应隔离并要求管理员通过映射表确认，迁移报告记录来源和决定。

本会话已开始探查但未完成，已确认的事实：

- MITE `EntityPlayer` 上**没有** `GameProfile`，也没有 UUID 访问器。
- MITE 1.6.4 早于 Mojang 的 UUID 迁移，可验证身份**只有用户名**。
- 上游 `QuestingAPI.getQuestingUUID()` 依赖的类和方法在 MITE 上不存在。

这个探查结论需要下一个会话**独立复核**（用 `javap` 核对 mapped jar），因为它决定整个身份层的设计。

推论（同样需要复核后再据以实现）：

- 派生 UUID 只能是从用户名确定性推导，不是真实 Mojang UUID。
- 改名玩家的进度无法自动关联——用户名变则派生 UUID 变。
- 导入上游 1.7.10 存档时，档里的真实 UUID 与我们的派生 UUID **之间没有可计算的映射**。这些进度必须隔离，由管理员通过显式映射表确认，不能按名字凑对。

### 4.2 `WorldStorage`

已由字节码确认的路径（记录在 `docs/platform-probes.md`）：

- Forge 的 `DimensionManager` 在 MITE 不可用。
- `MinecraftServer.worldServers` 是 public 数组。
- `World.saveHandler` 是 protected，需要一条 access widener 或 accessor mixin 才能取到。
- `ISaveHandler.getWorldDirectory()` 返回世界根目录，接口上还有 `flush()`。
- 不要用 `MinecraftServer.getFile()` 拼 `saves/<name>`——专服与集成服务器下语义不同。

结论路径：`worldServers[0].saveHandler.getWorldDirectory()`。

### 4.3 存档层其余工作

- 原子写入：临时文件 → 关闭/同步 → 替换；覆盖旧档前建带时间戳备份。
- 注意 `ATOMIC_MOVE` 在 Windows 跨卷时可能不可用，需要回退路径。
- golden fixture：空库、典型任务线、旧单文件进度、分玩家进度、缺失物品/实体/维度、损坏/截断/超大文件。
- `QuestLoot.json` 阶段 3 **只做识别、备份和不透明保留**；语义解析等阶段 7 的 LootRegistry。

---

## 5. 平台关键事实

完整证据链在 `docs/platform-probes.md`。这里是最容易踩的两条。

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
| `IQuestSettings.canUserEdit` | 玩家身份、OP 权限、`NameCache` | 3/7 |
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
