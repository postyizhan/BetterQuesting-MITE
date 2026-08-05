# BetterQuesting 1.7.10 → MITE 1.6.4 Fish Mod Loader 移植计划

## 1. 目标

将 `BetterQuesting-master/` 中面向 Minecraft 1.7.10 Forge 的 BetterQuesting 移植到当前根目录的 Minecraft `1.6.4-MITE`、Fish Mod Loader `3.4.2` 工程中。

移植后的约束如下：

- 所有生产 Java 代码统一使用包根：`com.github.postyizhan.betterquesting`。
- 主模组 ID 与主资源域保持 `betterquesting`；同时保留 `bq_standard` 等上游逻辑命名空间。Java 包名变化不得破坏资源、factory、网络和任务数据中的稳定 ID。
- 不携带 Forge 1.7.10、FML 1.7.10 或 SimpleImpl 运行时依赖。
- 服务端对任务完成、编辑权限、队伍操作、物品提交和奖励领取保持权威。
- 尽量兼容上游 `FORMAT 3.1.0` 的任务数据库及玩家进度数据；无法无损转换的内容必须备份并生成迁移报告。
- 客户端、单人集成服务器和专用服务器均须可运行。
- `ManyLib` 固定为必须安装的独立运行时依赖，最低版本锁定为 `2.3.1`；统一复用其配置、热键、基础客户端 GUI、输入和通用工具能力，但不以它重写 BetterQuesting 的领域 GUI、网络或服务端状态机。

## 2. 已确认的工程基线

### 2.1 源模组规模

`BetterQuesting-master/src/main/java` 约有 510 个 Java 文件、58,000 余行，包含四个原 Forge 模块：

| 原顶级包 | 规模与职责 | 目标包 |
| --- | --- | --- |
| `betterquesting.*` | 核心 API、数据库、网络、存档、GUI、命令、方块与物品 | `com.github.postyizhan.betterquesting.*` |
| `bq_standard.*` | 标准任务、奖励、战利品、导入器和外部集成 | `com.github.postyizhan.betterquesting.standard.*` |
| `drethic.questbook.*` | 任务书物品与配方 | `com.github.postyizhan.betterquesting.compat.questbook.*` |
| `lokko12.CB4BQ.*` | 命令方块扩展 | `com.github.postyizhan.betterquesting.compat.commandblock.*` |

其中 GUI 框架和客户端界面约占三万行，是最大且最容易拖慢首个可运行版本的部分。移植必须按可运行的纵向切片推进，不能先整体复制源码再依靠编译错误逐个修补。

### 2.2 目标平台

根工程当前使用：

- Minecraft：`1.6.4-MITE`
- Fish Mod Loader：`3.4.2`
- RustedIronCore：`1.5.0`
- `fml-loom`：`0.1`
- Java：17
- Loader 入口：Fabric 风格 `ModInitializer`
- 内容注册入口：`IGameRegistry`
- 事件：RustedIronCore `Handlers` 与即将废弃的 Fish Guava EventBus 并存
- 网络：RustedIronCore `Packet` / `PacketReader`，底层为 `Packet250CustomPayload`
- Access Transformer 替代：Access Widener
- ASM/Coremod 替代：Mixin

模板仅证明了初始化、基础注册和少量事件。以下能力在实现前仍必须通过目标 jar、映射或运行探针确认：专服停止回调、世界目录、容器打开、TileEntity 同步、服务端 tick、玩家身份、方块交互/破坏、合成/熔炼、资源重载、声音、积分板和流体。

### 2.3 ManyLib 结论

`ManyLib-main/` 与目标平台版本一致，适合：

- 客户端 JSON 配置和配置页；
- 组合热键；
- 简单搜索框、滚动条、文本框等通用控件；
- 客户端 tick、world-load 和 HUD overlay。

它不适合替代：

- BetterQuesting 的任务数据库和进度存档；
- 网络协议与服务端状态；
- `IScene` / canvas / panel / transform / theme / designer 体系；
- 服务端生命周期和事件桥。

ManyLib 的部分鼠标、world-last、tooltip-last 分发目前未接通，并自带多个 required Mixin。因此本项目将它作为**必须安装的独立 mod 依赖**，优先复用配置、热键、基础屏幕/控件、客户端 tick、world-load、HUD overlay、JSON 和通用 GUI 工具；不得把其源码直接混入 BetterQuesting 主 source set，也不得依赖其尚未接通的事件接口。实施阶段 1 必须先验证 `2.3.1` 的可解析 Maven 坐标、专服加载行为和 LGPL-3.0 分发要求。若当前发布版无法在专服加载，应先修复或推动 ManyLib 提供 side-safe 版本，而不是在 BetterQuesting 中恢复可选依赖分支。

## 3. 范围与非目标

### 3.1 核心完成范围

正式移植版本应最终包含：

- 任务、任务线、属性、队伍、生命、名称缓存和玩家进度；
- 标准任务与奖励；
- 任务查看、搜索、书签、队伍及编辑界面；
- 服务端存档、默认任务包和旧数据迁移；
- 多人同步与服务端权限校验；
- 管理员、用户及客户端命令；
- Guide Book、Extra Life、Submit Station、Observation Station、Loot Chest；
- 通知、主题、资源和本地化；
- 专用服务器支持。

### 3.2 延后或条件启用范围

以下功能不能阻塞核心版本，只有目标生态存在可验证等价能力时才启用：

- Forge Fluid 任务和流体提交；
- OreDictionary 语义；
- **EMI 集成（高优先级）**：替代原 NEI 集成，使用 retroEMI-MITE-master
- Vending Machine、DuraDisplay、GTNHLib 集成；
- HQM 导入；
- CB4BQ 命令方块扩展；
- 不存在于 MITE 的实体、维度、物品或流体兼容。

### 3.3 明确非目标

- 不兼容原 1.7.10 Forge 插件的 Java ABI。
- 不保留旧顶级 Java 包作为永久兼容层。
- 不把 Forge API 伪造进主工程来掩盖平台差异。
- 不把客户端提供的任务完成、UUID、奖励数量或权限判断视为可信数据。
- 不在首个里程碑内全量完成 designer、NBT 编辑器和所有外部集成。

## 4. 总体架构

### 4.1 建议包结构

```text
com.github.postyizhan.betterquesting
├─ api/                       对外 API、接口、枚举和稳定逻辑 ID
├─ core/                      模组常量、公共 bootstrap、注册中心
├─ questing/                  任务、任务线、队伍、任务/奖励状态机
├─ storage/                   数据库、存档、迁移、备份
├─ network/                   业务包、codec、分片、验证
├─ standard/                  标准任务、奖励、loot
├─ command/                   用户和管理命令
├─ content/                   物品、方块、TileEntity、容器
├─ platform/
│  ├─ api/                    平台能力接口
│  └─ fml/                    Fish/RIC/MITE 实现
├─ client/                    GUI、主题、渲染、热键、通知
└─ compat/                    可选集成，禁止被核心静态引用
```

### 4.2 平台能力接口

开始大规模迁移前建立以下边界：

- `PlatformLifecycle`：common/client/server 启动、world save、server stop；
- `PlayerIdentityService`：用户名、稳定 UUID、离线服迁移；
- `WorldStorage`：世界根目录、原子写入、备份和 flush；
- `PacketTransport`：方向、发送、接收、线程切换；
- `EventBridge`：tick、登录、死亡、重生、交互、击杀、合成等；
- `ContentRegistrar`：物品、方块、实体、TileEntity、配方和稳定 ID；
- `ContainerBridge`：服务端 container、window ID、客户端 GUI；
- `PermissionService`：管理员等级和命令来源；
- `ResourceBridge`：资源域、语言、纹理、声音和 reload；
- `FluidCapability`、`OreMatchCapability`：存在则实现，不存在则显式报告禁用。

领域层不得直接依赖 Fish、RIC、Mixin、客户端类或 Forge 类。

### 4.3 客户端与服务端隔离

- 公共入口不得在常量池中引用 `net.minecraft.client.*`。
- 客户端入口单独初始化 GUI、热键、主题、资源、渲染和 S2C handler。
- 服务端负责数据库、任务判定、进度、队伍、权限、奖励和持久化。
- 原本混合两侧职责的 `handlers/EventHandler.java` 拆为 common、server、client 三组事件桥。
- 后台线程只能进行文件 I/O 或不可变数据编码；世界、库存、玩家和数据库修改必须回到服务端主线程。

## 5. Forge → Fish/MITE 替换矩阵

| Forge 1.7.10 能力 | 目标方案 | 实施要求 |
| --- | --- | --- |
| `@Mod`、pre/init/postInit | `fml.mod.json`、`ModInitializer`、RIC initialization | 不假设阶段一一对应 |
| `@SidedProxy`、`@SideOnly` | common/client/server bootstrap 与物理隔离 | 用专服 smoke test 验证 |
| Forge/FML event bus | RIC `Handlers` 优先，Fish event 次之，缺口用窄 Mixin | 业务类不直接订阅 loader event |
| `GameRegistry` | `IGameRegistry`、`MinecraftRegistry`、经验证的注册事件 | 固定逻辑 ID，检查数值 ID 冲突 |
| `SimpleNetworkWrapper` | RIC custom payload + 自有 codec/协议 | 每类业务包定义方向、上限和权限 |
| `IGuiHandler`、`openGui` | 自有 C2S/S2C 打开协议 + container/window 同步 | 先实现最小探针再移植提交站 |
| Forge `Configuration` | ManyLib `ConfigManager` / `SimpleConfigs`；服务端权威配置另建同步层 | 客户端设置由 ManyLib 统一承载，权威设置不得只存客户端 |
| `ClientRegistry.registerKeyBinding` | ManyLib `ConfigHotkey` / `KeybindMulti` | 只在客户端 bootstrap 注册 |
| `WorldEvent.Save` | 经验证的 save hook + 周期 autosave + stop flush | 不使用 client world-load 代替服务端生命周期 |
| extended entity properties | UUID 键控的服务端状态/cache | 登录、退出、克隆、重生显式处理 |
| `OreDictionary` | MITE 等价能力或精确匹配降级 | 数据载入时报告语义损失 |
| Forge Fluid API | 目标流体能力适配或 feature flag 禁用 | 禁止伪造成功状态 |
| Access Transformer | Access Widener | 每项开放附真实 descriptor 证据 |
| Coremod/ASM | 最小 Mixin | 目标方法和 descriptor 必须验证 mapped jar |
| Forge 自定义事件 | 项目内 observer/handler | 需要时保留取消和优先级语义 |
| client/server command handler | Fish/RIC command register event | 修改性命令统一做服务端权限检查 |

## 6. 分阶段实施

## 阶段 0：冻结基线与能力探针

### 工作

1. 记录上游源码版本、`FORMAT 3.1.0`、许可证和四个模块的功能清单。
2. 建立源码迁移台账，逐目录标记：纯领域、Minecraft 依赖、Forge 依赖、客户端、外部集成。
3. 通过本地依赖 jar、mapped Minecraft jar 和最小运行代码验证：
   - common/client/dedicated server 入口；
   - server start、world save、server stop；
   - client/server tick；
   - 双向 custom payload；
   - command registration；
   - item/block/TileEntity/entity/recipe 注册；
   - player login/logout/death/respawn；
   - GUI 与 container 打开；
   - 世界目录和 NBT API；
   - crafting/smelting/break/interact/kill 事件；
   - resource、sound、scoreboard、UUID、inventory 和 fluid 能力。
4. 对缺口按“RIC / Fish legacy event / Mixin / 不可用”定案。

### 验收

- 最小 mod 可启动客户端和专用服务器。
- 每项关键能力都有真实 API 或真实缺口证据。
- 未验证 API 不进入后续实现假设。

## 阶段 1：工程骨架、元数据和包名

### 预计修改

- `build.gradle`
- `gradle.properties`
- `settings.gradle`
- `src/main/resources/fml.mod.json`
- `src/main/resources/betterquesting.mixins.json`
- `src/main/resources/betterquesting.accesswidener`
- 删除 `src/main/java/com/example/` 和 `modid.*` 模板文件

### 工作

1. 设置：
   - `maven_group=com.github.postyizhan`
   - `archives_base_name=BetterQuesting-MITE`
   - mod id/resource domain=`betterquesting`
2. 建立 `BetterQuestingMod`、client/server/registry 入口。
3. 将模组名称、作者、描述、图标、许可证和依赖写入 `fml.mod.json`。
4. 建立平台接口和空实现骨架。
5. 启用 dedicated-server run 或等价 smoke-test 方式。
6. 以已验证的固定 Maven 坐标加入 ManyLib `2.3.1` 编译和运行依赖，并在 `fml.mod.json` 声明 `many-lib >=2.3.1` 为必需依赖；禁止使用动态版本。
7. 保留 BetterQuesting MIT 许可证和仓库附带的 QuestBook、Command Blocks 许可证；发布说明中列出 ManyLib，并满足 LGPL-3.0 的许可证文本、版权通知和用户可替换独立库要求。

### 验收

- `./gradlew clean build` 通过。
- JAR 内没有 `com.example`、`modid` 或错误的模板元数据。
- 所有新生产包均以 `com.github.postyizhan.betterquesting` 开头。
- 客户端和专服均能加载空骨架。

## 阶段 2：平台无关数据内核

### 源范围

- `betterquesting/api/properties/`
- `betterquesting/api2/registry/`
- `betterquesting/api2/storage/`
- `betterquesting/api/questing/`
- `betterquesting/questing/`
- `betterquesting/storage/PropertyContainer.java`

### 工作

1. 移植属性容器、数据库、注册表、任务/奖励接口、任务线和队伍模型。
2. 保留原稳定资源 ID，例如 `betterquesting:*`、`bq_standard:*`；Java 包重命名不能改变数据 ID。
3. 解开 `QuestInstance` 对 `bq_standard.RewardChoice`、QuestBook 配置等实现类的反向依赖，改为策略或接口。
4. 用 JDK 集合替换不必要的 Netty internal collection 等依赖。
5. 把玩家、物品、维度、积分板等 Minecraft 类型隔离为可序列化引用或平台 facade。
6. 建立单元测试和上游 fixture。

### 验收

- 领域层无 `cpw.mods.fml.*`、`net.minecraftforge.*` 和 `net.minecraft.client.*` import。
- 可独立创建任务、前置关系、队伍、任务进度并完成序列化往返。
- AND/OR、可见性、重复任务、自动领取、global/share 等关键状态机与上游一致。

## 阶段 3：存档、身份和迁移

### 兼容文件

```text
<world>/betterquesting/QuestDatabase.json
<world>/betterquesting/QuestProgress.json
<world>/betterquesting/QuestProgress/<uuid>.json
<world>/betterquesting/QuestingParties.json
<world>/betterquesting/LifeDatabase.json
<world>/betterquesting/NameCache.json
<world>/QuestLoot.json
```

### 工作

1. 实现稳定的世界目录解析，不沿用仅适合原 Forge 的 integrated/dedicated 路径拼接。
2. 保留原 `format`、`build`、任务 ID、属性 key 和 UUID 表示；另增 `mitePortFormat` 表示移植端 schema。
3. 为原 JSON/NBT 数据建立 golden fixture：
   - 空数据库；
   - 典型任务线；
   - 旧单文件进度；
   - 分玩家进度；
   - 缺失物品/实体/维度/流体；
   - 损坏、截断和超大文件。
4. 阶段 3 对 `QuestLoot.json` 只做识别、备份和不透明保留；其语义解析与 round-trip 等 LootRegistry/奖励在阶段 7 落地后再启用。
5. 写入采用临时文件、关闭/同步、替换；覆盖旧格式前创建带时间戳备份。
6. 旧 `QuestProgress.json` 转换为逐玩家文件后保留原件。
7. 建立 `PlayerIdentityService`，优先使用可验证的 MITE/GameProfile/服务端持久身份。若只能取得用户名或离线派生 UUID，不得自动声称可识别改名玩家；歧义数据应隔离并要求管理员通过映射表确认，迁移报告记录来源和决定。
8. 周期 autosave、world save、server stop 均可触发 flush；stop 必须等待待处理 I/O。
9. 遇到目标平台不存在的内容时生成 placeholder 和报告，不静默替换成其他物品。

### 验收

- fixture 往返后语义等价，数值和列表类型不漂移。
- 转换失败不覆盖原档。
- 同名、改名或身份来源不明确时，进度要么由可验证身份正确关联，要么被隔离等待管理员映射，绝不自动串档。
- 重启、断线和 world unload 后静态数据库不会污染下一个世界。

## 阶段 4：安全网络最小闭环

### 源范围

- `betterquesting/network/`
- `betterquesting/network/handlers/`
- `bq_standard/network/handlers/`

### 工作

1. 保留高层 `QuestingPacket(ID, payload)` 思路，重写底层 transport 和 codec。
2. 握手交换：协议版本、数据格式版本、feature bits。
3. 第一批实现：登录 bulk sync、quest/chapter/settings/name/life/cache 同步。
4. 分片协议必须具有：
   - transfer ID；
   - 总长度、分片索引和分片数；
   - 单包、总包和集合上限；
   - 超时；
   - 每玩家并发 assembly 上限；
   - 重复、乱序、断线清理。
5. 每个 C2S handler 校验：方向、玩家身份、登录状态、权限、目标对象存在性、状态转移、字符串/集合/NBT 大小。
6. 客户端不得指定可信 UUID、完成状态、奖励数量或权限结果。
7. 每个修改性请求携带会话内 operation ID；服务端把它与连接会话、玩家和操作类型绑定，在原子提交后缓存结果并拒绝重复执行。缓存必须有数量/时间上限，断线时清理；重连后的业务幂等仍以已保存的领取/提交状态为准，而非信任旧会话 ID。
8. 编辑、领奖、队伍和提交操作在服务端主线程执行并保持幂等。
9. 对拒绝请求限频记录，避免恶意日志洪泛。

### 验收

- 客户端和专服可完成登录同步、断线重连和大型任务库分片。
- 未知 ID、错误方向、超长/畸形包和伪造 UUID 均安全拒绝。
- 用测试用 mutation handler 验证 operation ID 去重、权限拒绝和原子提交框架；真实编辑、领奖与队伍并发验收分别在阶段 7 和阶段 9 完成。
- 协议版本不兼容时明确拒绝，不产生部分写入。

## 阶段 5：只读任务 GUI MVP

### 工作

1. 保留并移植 BetterQuesting 自有 canvas/panel/theme 架构，不以 ManyLib widget 树替换。
2. 只迁满足阅读任务所需的最小集合：
   - `GuiScreenCanvas`、scene、transform、rectangle；
   - 基础 canvas/panel、文本、按钮、滚动条、物品槽；
   - theme/resource registry；
   - 主页、任务线、任务详情、搜索、书签；
   - 任务书打开热键。
3. 把 Tessellator、GL11、剪裁、字体、tooltip、输入、缩放和纹理绑定封装在客户端适配层。
4. 注册 `betterquesting`、`bq_standard` 等资源域，迁移纹理、声音和语言文件。
5. 第一阶段不迁 designer、NBT 编辑器、importer、完整 party editor。
6. 使用 ManyLib 统一实现客户端配置页、`openQuests` 组合热键、基础搜索/滚动/文本输入能力和 HUD 通知基础设施；BQ canvas 与自定义 `PanelTextField` 仍按自身事件契约适配，不能假设 ManyLib 对它们自动分发输入。

### 验收

- 854×480、1280×720 和多个 GUI scale 下可查看、滚动、搜索任务。
- 中英文无关键缺失 key，长文本可换行或滚动。
- 服务端状态变化会刷新客户端任务状态。
- 专用服务器不会加载任何 GUI、GL 或 Minecraft 客户端类。

## 阶段 6：标准任务纵向切片

按“事件易得、语义风险从低到高”的顺序移植，每种任务同时完成 factory、数据 codec、事件桥、网络、GUI panel、存档和测试：

1. Checkbox；
2. Location、Meeting、Hunt；
3. Block Break、Interact Item、Interact Entity；
4. Retrieval、Optional Retrieval；
5. Crafting、Smelting/Anvil 相关行为；
6. XP、Scoreboard；
7. Fluid（仅在平台能力确认后）。

### 关键要求

- 库存扫描避免每 tick 全量重复计算，保留登录/变化后的延迟扫描语义。
- MITE 对合成、经验、伤害、死亡和物品栈的修改必须按真实实现测试，不能套用 vanilla/Forge 1.7.10 假设。
- 缺失的任务类型载入时保留原数据并显示禁用原因。
- factory ID 保持兼容，并提供旧 ID alias 表。

### 验收

每种已启用任务均覆盖：创建、进度、完成、重置、保存重载、登录恢复、party/global、repeat 和断线重连。

## 阶段 7：标准奖励、队伍、命令和生命

### 奖励顺序

1. Item；
2. XP；
3. Command；
4. Quest Completion；
5. Scoreboard；
6. Choice；
7. Loot group/chest。

### 工作

- 奖励完全由服务端发放，重复请求幂等。
- Item 奖励处理背包满、掉落实体和断线窗口。
- Command 奖励明确执行身份，不继承不必要的控制台权限，并记录来源任务与玩家。
- 队伍邀请、加入、退出、踢人、owner 转移和共享进度按角色矩阵校验。
- 通过 Fish/RIC command register event 迁移管理员、用户和客户端命令。
- Hardcore/lives 设 feature flag；验证 MITE 的死亡、重生、封禁和世界删除行为后再默认启用。

### 验收

- 重复点击、同会话 operation ID 重放、断线重连后的旧请求及并发领取均不能双发奖励。
- 普通玩家不能导入默认库、编辑任务、清库或修改生命。
- owner/admin/member/invitee 权限矩阵测试通过。
- 背包满、死亡、重生、服务端重启和重连均保持数据一致。

## 阶段 8：方块、物品、TileEntity 与容器

### 内容

- Guide Book；
- Extra Life；
- Submit Station；
- Observation Station；
- Loot Chest；
- 必要时才注册 placeholder entity/item/fluid。

### 工作

1. 使用固定逻辑资源名；若 MITE 仍要求数值 ID，提供配置、默认区间和冲突检查。
2. 重写 `ISidedInventory`、TileEntity NBT、容器 slot、shift-click 和同步逻辑。
3. 先通过最小 `ContainerBridge` 验证服务端 window/container 与客户端 GUI 配对，再迁提交站。
4. 自动化提交、漏斗、区块卸载、拆方块和掉落均做场景测试。
5. 流体能力缺失时，流体槽和 Fluid Task 显式禁用，不让方块处于半可用状态。

### 验收

- 单人和专服注册 ID 一致。
- 方块放置、保存、区块重载、破坏后 NBT 与库存不丢失。
- 非法 GUI 打开包或 container 操作不能复制物品。

## 阶段 9：完整编辑器、主题与动态资源

### 顺序

1. Quest/QuestLine 编辑；
2. Task/Reward 编辑；
3. Party GUI；
4. NBT/Item/Entity/Fluid 选择器；
5. Designer/Toolbox；
6. Importer 和默认任务包导出。

### 工作

- 所有编辑请求经服务端权限、对象版本和字段上限验证。
- 对并发编辑使用版本号或冲突拒绝，避免后写静默覆盖。
- 动态主题/资源包只有在目标 resource reload API 验证后启用；否则启动时加载并提示重启。
- 编辑器保存的任务库必须能由无客户端 UI 的干净专服读取。

### 验收

- 可在游戏内创建任务线、标准任务和奖励，导出后由干净服务器导入。
- 非管理员无法通过构造数据包进入编辑模式。
- 多人同时编辑同一对象不会造成损坏或静默丢数据。

## 阶段 10：可选兼容模块

逐项独立评估和实现：

- QuestBook 轻量物品入口；
- HQM importer；
- **EMI 集成（高优先级）**：
  - 移除原 `bq_standard/integration/nei`（4 个文件）
  - 新增 `bq_standard/integration/emi` 模块
  - 依赖 `retroEMI-MITE-master` (EMI 1.1.20+1.6.4-MITE)
  - 实现 EmiPlugin 接口
  - 注册任务作为可查看的"配方"类型
  - 实现物品 → 任务的反向索引
- Vending Machine；
- DuraDisplay/GTNHLib 替代；
- CB4BQ。

每个 compat 包必须：

- 独立 feature flag；
- 运行时模组存在性检查；
- 核心代码无其类型的静态引用；
- 缺失或失败不阻止数据库加载和服务器启动；
- 有对应目标模组版本和 fixture 后才标为支持。

## 7. ManyLib 必需依赖实施方案

ManyLib 是本移植的固定基础设施依赖，不再维护“无 ManyLib 降级模式”。这能减少客户端配置、热键、基础控件、输入适配、HUD 消息、JSON 辅助和部分 1.6.4 GUI 兼容代码的重复开发，但不会显著减少任务领域模型、网络、存档、服务端事件、容器以及 BetterQuesting canvas/editor 的移植成本。

### 7.1 依赖接入

1. 优先使用独立发布的 `ManyLib 2.3.1` JAR；候选坐标为 `com.github.MinecraftIsTooEasy:ManyLib:2.3.1`，只有通过 Gradle 解析探针确认后才能写入正式构建。
2. `build.gradle` 使用固定版本的 `implementation`；如 FML Loom 需要额外的 mod 语义配置，则按实际可解析配置补充，但必须保证编译和运行均存在该依赖。
3. `fml.mod.json` 声明 `many-lib: ">=2.3.1"`，客户端和专服缺少依赖时由加载器给出明确错误。
4. 不 shade、不 relocate、不直接合并 `ManyLib-main/src`，避免破坏其 entrypoint、Mixin、Access Widener、资源域和 LGPL 独立库边界。
5. 发布文档明确所需版本、下载来源、许可证和兼容组合。

### 7.2 强制复用范围

- `ConfigManager`、`SimpleConfigs`、配置 option 与默认配置屏幕；
- `ConfigHotkey`、`KeybindMulti` 和打开任务界面的热键；
- 已验证可用的基础 screen/widget、搜索、滚动条和文本输入组件；
- 客户端 tick、world-load、HUD overlay；
- `JsonUtils` 及适合目标平台的颜色、字符串、GUI 工具。

### 7.3 禁止依赖范围

- 服务端任务数据库、玩家进度和迁移器；
- 网络协议、分片、权限及幂等；
- BetterQuesting canvas/panel/scene/theme/editor 领域框架；
- ManyLib 当前未接通的 mouse handler、tooltip-last 和 world-last dispatcher；
- 任何只因类名存在、但未由源码或运行探针证实会触发的接口。

### 7.4 前置阻断条件

ManyLib 元数据当前是 `environment: "*"`，main entrypoint 和 required GUI Mixin 可能影响专服。因此阶段 1 必须通过以下门槛后，后续代码才能大量引用其 API：

- Maven 坐标可重复解析；
- 客户端、单人和专服安装 ManyLib 后均可启动；
- required Mixin 在当前 FML/MITE 映射下全部应用成功；
- 与 BetterQuesting 新增 Mixin 无冲突；
- 依赖缺失和版本过低时加载器错误清晰。

若专服门槛失败，修复对象是 ManyLib 的 side isolation/metadata 或采用其兼容修订版；BetterQuesting 仍保持 ManyLib 必需依赖的产品决策，不另建一套无依赖实现。

无论如何，BetterQuesting 的任务 canvas、服务端存档、网络和状态机都保持自有实现。

## 8. 数据兼容策略

### 必须保持

- 任务、任务线、party 和玩家进度逻辑 ID；
- factory ID 和属性 key；
- UUID 表示；
- `format` / `build` 基本语义；
- 未识别数据的可恢复保存能力。

### 允许改变

- Java 包名；
- loader metadata；
- 网络 wire format；
- 平台事件和注册实现；
- 客户端渲染实现；
- 内部线程与 I/O 实现。

### 迁移规则

1. 初次加载旧档前自动备份。
2. 解析、验证、转换和写入分为独立步骤。
3. 只有完整验证成功才替换目标文件。
4. 未识别 factory/物品/实体/流体保留原标识和原始 payload。
5. 每次迁移输出机器可读和人类可读报告。
6. 不把网络协议兼容误称为存档兼容；旧客户端不能直接连接新服务器。

## 9. 测试与验证矩阵

### 9.1 自动化测试

- 属性容器、数据库、registry；
- UUID/身份迁移；
- NBT↔JSON golden round-trip；
- 任务前置、可见性、循环、repeat、global/share；
- 队伍权限；
- 奖励幂等；
- packet 长度、方向、权限和状态转移；
- 分片重复、乱序、缺失、超时、断线；
- 损坏和超大存档输入。

### 9.2 运行矩阵

| 场景 | 必测内容 |
| --- | --- |
| 客户端无 ManyLib | 加载器以明确的缺失依赖错误拒绝启动，不出现 `ClassNotFoundException` |
| 客户端有 ManyLib 2.3.1 | 配置、热键和基础 GUI 正常，无双重输入 |
| 客户端 ManyLib 版本过低 | 加载器以明确版本错误拒绝启动 |
| 专服无 ManyLib | 加载器以明确的缺失依赖错误拒绝启动 |
| 专服有 ManyLib 2.3.1 | common entrypoint 与 required GUI Mixin 不产生 side-only 崩溃，核心数据、协议和命令正常 |
| 单人集成服务器 | 新世界、保存退出、重进 |
| 专服 + 1 客户端 | 登录同步、任务完成、奖励、重启 |
| 专服 + 2 客户端 | party、并发领奖、并发编辑、断线重连 |
| 旧存档 | 备份、迁移、报告、重启后读取 |
| 大任务库 | 分片、GUI 性能、autosave 延迟 |
| 中英文、多分辨率 | 文本、滚动、点击区域、GUI scale |

### 9.3 每阶段通用门槛

- `./gradlew clean build` 通过；
- 相关单元和 fixture 测试通过；
- 客户端 smoke test 通过；
- 涉及公共/服务端代码时专服 smoke test 通过；
- 无新增 Forge/FML 1.7.10 import；
- 无未经 mapped jar 或依赖源码验证的 MITE API 猜测；
- 未完成能力有明确 feature flag 或可见错误，不静默失败。

## 10. 里程碑与可回退点

| 里程碑 | 可演示结果 | 回退点 |
| --- | --- | --- |
| A：平台骨架 | 正式元数据、包名、client/server 启动 | 空功能但可构建 tag |
| B：数据内核 | 任务模型、旧数据库只读导入和备份 | 保留全部旧文件 |
| C：网络与只读 GUI | 登录同步、任务线浏览 | 协议版本拒绝旧客户端 |
| D：标准任务与奖励 | 可完成任务、领奖、保存重载 | 单项 feature flag |
| E：队伍、命令、内容 | 多人、权限、任务书和基础方块 | 禁用高风险内容 |
| F：完整编辑器 | 游戏内创建、导入导出任务包 | 只读模式 |
| G：兼容与优化 | 可选集成、性能和发布准备 | 单独禁用 compat |

每个里程碑必须单独可构建、可启动、可测试，不允许依赖下一阶段的半成品才能运行。

## 11. 主要风险

### Blocker

1. **平台能力缺口**：目前没有已验证的通用 `IGuiHandler` 等价物和 server stop 高层回调；必须先用探针或最小 Mixin 解决。
2. **MITE 不是普通 1.6.4**：玩家、物品、经验、合成、服务端和映射可能与 vanilla/Forge 均不同，所有引用以 mapped jar 为准。
3. **网络重写**：SimpleImpl 不存在，RIC packet 也不自动提供 Forge 的方向、权限、限长和调度保障。

### High

1. 客户端类加载进入专服导致启动崩溃。
2. UUID/GameProfile 差异造成旧进度错配。
3. 原分片协议缺少 transfer ID 和边界保护，不能机械照搬。
4. GUI 规模过大，若先追求编辑器全量会长期无可玩版本。
5. Fluid、OreDictionary 和外部生态在目标平台可能不存在。
6. 原核心存在对标准扩展和 QuestBook 的反向依赖，影响分阶段编译。
7. MITE 合成、经验、死亡和库存语义可能让任务重复计数或漏计。
8. ManyLib 现在是必需依赖，其 required Mixin、专服 side isolation、版本发布和映射兼容会直接成为 BetterQuesting 的发布阻断项。

### Medium

1. Fish legacy event 将在 v4 移除，应限制使用并集中封装。
2. 1.6.4 数值 ID 可能冲突。
4. 动态资源包、声音和 texture stitch 的目标等价能力不明确。
5. ManyLib 为 LGPL-3.0；保持独立 JAR 可降低合规和技术耦合，若为专服兼容而修改其源码，则须发布对应修改源码并保留许可证与修改说明。

## 12. 预计新增的骨架文件

```text
src/main/java/com/github/postyizhan/betterquesting/BetterQuestingMod.java
src/main/java/com/github/postyizhan/betterquesting/core/BetterQuestingConstants.java
src/main/java/com/github/postyizhan/betterquesting/platform/api/PlatformLifecycle.java
src/main/java/com/github/postyizhan/betterquesting/platform/api/PlayerIdentityService.java
src/main/java/com/github/postyizhan/betterquesting/platform/api/WorldStorage.java
src/main/java/com/github/postyizhan/betterquesting/platform/api/PacketTransport.java
src/main/java/com/github/postyizhan/betterquesting/platform/api/EventBridge.java
src/main/java/com/github/postyizhan/betterquesting/platform/fml/CommonBootstrap.java
src/main/java/com/github/postyizhan/betterquesting/platform/fml/ClientBootstrap.java
src/main/java/com/github/postyizhan/betterquesting/platform/fml/ServerBootstrap.java
src/main/java/com/github/postyizhan/betterquesting/platform/fml/BetterQuestingRegistry.java
src/main/java/com/github/postyizhan/betterquesting/network/QuestNetwork.java
src/main/java/com/github/postyizhan/betterquesting/network/PacketLimits.java
src/main/java/com/github/postyizhan/betterquesting/storage/QuestSaveManager.java
src/main/java/com/github/postyizhan/betterquesting/storage/migration/LegacyQuestImporter.java
src/main/java/com/github/postyizhan/betterquesting/storage/migration/MigrationReport.java
src/main/resources/betterquesting.mixins.json
src/main/resources/betterquesting.accesswidener
src/test/java/com/github/postyizhan/betterquesting/
src/test/resources/fixtures/
```

这些是架构方向，不要求在一个提交中全部创建。只有当前里程碑真正需要的文件才落地，避免形成大量空壳。

## 13. 首轮实施顺序

正式开始编码时，首轮严格按以下顺序执行：

1. 完成阶段 0 的专服生命周期、双向 packet、container GUI 三个关键探针。
2. 修改工程坐标、元数据和入口，清除模板包。
3. 建立 common/client/server 边界与平台接口。
4. 移植 registry、storage、property 和 quest graph 纯数据层。
5. 锁定旧存档 fixture 与原子备份流程。
6. 实现登录同步和只读任务 GUI。
7. 用 Checkbox + Item Reward 做第一个完整纵向切片。
8. 通过专服双客户端测试后，再逐类扩展任务和奖励。

第一个可玩验收场景应是：管理员载入或创建一条包含 Checkbox Task 和 Item Reward 的任务线，客户端能够查看并提交，服务端验证完成并只发放一次奖励，退出和重启后进度保持。该场景通过前，不开始大规模编辑器、流体或外部兼容工作。

## 14. 最终验收标准

移植可标记为完成时必须满足：

- 所有生产 Java 包均以 `com.github.postyizhan.betterquesting` 开头；
- 主线运行时无 Forge/FML 1.7.10 依赖；
- 客户端、单人和专服均可启动和正常退出；
- 可创建、查看、完成、保存、重载包含标准任务与奖励的任务线；
- 队伍、权限、命令、奖励和编辑操作由服务端权威处理；
- 旧 BetterQuesting 数据可在备份后导入，或对不能无损导入的字段生成明确报告；
- 网络对超长、畸形、越权、重放和版本不匹配输入有受控行为；
- 专服不加载客户端类；
- 安装所要求的 ManyLib `2.3.1+` 后核心可运行；缺失或版本过低时加载器给出明确依赖错误，其他可选兼容模组缺失不影响核心；
- `./gradlew clean build`、自动化测试及运行矩阵通过；
- README、许可证、依赖和已知限制与实际发布内容一致。
