# 阶段 0 平台能力探针

本文只记录当前工作树已由真实 JAR、发布源码或字节码确认的事实。构建成功不等同于客户端或专用服务器 smoke test 成功。

## 证据基线

- 映射后游戏 JAR：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-676ab05589/...jar`。
- RustedIronCore 1.5.0 发布源码 JAR：Gradle cache 中的 `RustedIronCore-1.5.0-sources.jar`。
- ManyLib 2.3.1 发布 JAR/源码 JAR：Gradle cache 中的 `ManyLib-2.3.1.jar` 与 `ManyLib-2.3.1-sources.jar`。
- ManyLib Maven 坐标 `com.github.MinecraftIsTooEasy:ManyLib:2.3.1` 可由当前 `ModdedMITE` 仓库解析；发布元数据中的真实 mod id 是 `many-lib`。

## 依赖运行时可用性

### Gson 只能使用 2.2.2 API 面

MC 1.6.4 的运行时 classpath 只有 Gson **2.2.2**。mapped 游戏 JAR 中 `com.google.gson.JsonObject.members` 的字段类型是 `com.google.gson.internal.StringMap`；`StringMap` 只存在于 Gson 2.1–2.2.x，而 Gson 2.3 起该字段改用的 `LinkedTreeMap` 在此游戏 JAR 中不存在。launcher libraries 也明确声明 `com.google.code.gson:gson:2.2.2`，两条证据相互吻合。因此 `build.gradle` 显式固定 2.2.2，让编译期 API 与运行期一致，不得升级。

Gson 2.2.2 不提供以下 API：

- `JsonObject.keySet()`、`JsonObject.size()`、`JsonObject.deepCopy()`；
- `JsonArray.isEmpty()`；
- `GsonBuilder.setLenient()`；
- `ToNumberPolicy`、`FormattingStyle`。

阶段 3 序列化批次读写上游 `QuestDatabase.json` 等文件时，只能使用 Gson 2.2.2 的 API 面；上游 1.7.10 代码中的新版 Gson 调用不能照搬。当前可用的基础接口包括 `JsonObject.entrySet()/has/get/add/remove/addProperty`、`JsonArray.size()/get/add/addAll`、`Gson.toJson/fromJson`、`GsonBuilder.serializeNulls/setPrettyPrinting/disableHtmlEscaping/create`，以及 `JsonWriter.setIndent/setLenient/beginObject/name/value`。

### 领域层集合不得依赖 fastutil 或 Trove

fastutil 与 Trove 均不在游戏、launcher libraries、FishModLoader、RustedIronCore 或 ManyLib 的运行时 classpath 中。本项目只有 `jar`、没有 `remapJar` 任务，因此 Loom 的 `include` 配置不能在当前构建中完成 jar-in-jar 打包；`com.github.MinecraftIsTooEasy:FastUtil:8.5.12` 坐标也只有空壳 POM，没有可供运行时加载的 JAR。

因此领域层只能使用 JDK 集合。新增任何依赖前，必须先核实对应类在实际运行时 classpath 上真实存在，不能把编译期成功视为运行时可用。

## Access Widener 状态

`src/main/resources/betterquesting.accesswidener` 已落地：

```text
accessible method net/minecraft/SaveHandler getWorldDirectory ()Ljava/io/File;
```

该 owner、成员名与 descriptor 已从当前 mapped JAR 核对。`ISaveHandler` 不声明 `getWorldDirectory()`，因此平台适配先防御性检查并转换为 `SaveHandler`。运行时由 `fml.mod.json` 的 `"accessWidener": "betterquesting.accesswidener"` 键接线；后续新增开放项仍必须逐项从当前 mapped JAR 核对，不得从其他 Minecraft 版本猜测。

## 世界目录解析

以下事实链已由当前 mapped JAR 与对应实现核对：

- `MinecraftServer.worldServers` 是 public 数组，overworld 可从索引 0 获取；`World.getSaveHandler()` 是 public。
- `SaveFormatOld.getSaveLoader(...)` 返回 `SaveHandler`，`AnvilSaveConverter.getSaveLoader(...)` 返回 `AnvilSaveHandler`，而后者继承 `SaveHandler`。因此 `instanceof SaveHandler` 在专服和集成服的正常世界存档路径都成立，禁用分支仅用于防御未知实现。
- `SaveHandlerMP` 不继承 `SaveHandler`，不能视为可提供本地世界目录的实现。
- 不得使用 `MinecraftServer.getFile()` 拼接世界存档路径；BQ 必须从 overworld 的 save handler 取得真实世界目录。

`MiteWorldStorage` 是世界生命周期绑定对象：集成服务器每次进入世界都会创建新的 server/save handler，实例必须在 world load 后创建并在 unload 时废弃，不能静态跨世界缓存。

## ChatAllowedCharacters 陷阱

MITE 的 `ChatAllowedCharacters.allowedCharacters` 是 `String`，内容来自客户端资源 `/font.txt`；对应的字符数组字段是 `allowedCharactersArray`，其内容是 15 个禁用字符：`/`、换行、回车、制表、NUL、换页、反引号、`?`、`*`、反斜杠、`<`、`>`、`|`、`"`、`:`。

上游 `makeFileNameSafe` 遍历的是 1.7.10 中 `char[]` 类型的 `allowedCharacters`。后续移植文件名安全化时不得引用 MITE 的同名 `String` 字段，否则既会把客户端 `/font.txt` 资源依赖引入服务端，又会使用完全错误的过滤集。上述禁用集也不包含 Windows 保留设备名 `CON`、`NUL`、`AUX`、`PRN`、`COM1` 等，路径边界必须另行拒绝这些名称。

## 专服生命周期

**已实现（静态/构建验证）：**

- RIC 的 `InitializationHandler.onServerStarted(MinecraftServer)` 由 `DedicatedServerMixin.startServer` 的 RETURN 注入触发，可提供专服启动完成通知。
- RIC 1.5.0 没有 server-stop listener。mapped JAR 中 `net.minecraft.server.MinecraftServer.stopServer()V` 是 public，且 `run()V`、`initiateShutdown()V`、`saveAllWorlds(ZZ)V` 均存在。
- `MinecraftServerLifecycleMixin` 在 `stopServer()V` HEAD 建立当前所需的最窄停止 seam；`CommonBootstrap` 同时登记 RIC server-start listener。停止回调当前只输出探针日志，尚未承载存档逻辑。

**运行验证：** `runServer` 已实际加载 BetterQuesting 与 ManyLib 的 required mixin，生成世界并到达 `Done`，随后观察到 `Dedicated/integrated server start probe observed`。进程由 45 秒外部时限终止，未走正常 stop 命令，因此停止日志仍未被运行验证。后续如需保证“所有清理完成之后”的回调，应另行验证 `stopServer` 的异常路径与调用次数，不能把当前 HEAD seam 当作完成后通知。

## 双向 custom payload

**已实现（API/构建验证）：**

- mapped JAR 的 `NetServerHandler.handleCustomPayload(Packet250CustomPayload)V` 与 `NetClientHandler.handleCustomPayload(Packet250CustomPayload)V` 两个方向都存在；载荷类构造器为 `Packet250CustomPayload(String,[B)V`。
- RIC `PacketReader.registerServerPacketReader(ResourceLocation, PacketSupplier)` 与 `registerClientPacketReader(...)` 分别登记 C2S/S2C reader。其两个 required mixin 在 vanilla handler RETURN 读取 `payload.channel`，构造 `PacketByteBuf` 后调用 `Packet.apply(EntityPlayer)`。
- RIC `Network.sendToClient(ServerPlayer, Packet)` 和 `Network.sendToServer(Packet)` 提供对应发送路径。
- `ProbePackets` 登记独立的 `betterquesting:probe_c2s` 与 `betterquesting:probe_s2c`；C2S 收到 nonce 后由服务端回送同 nonce，代码不会信任客户端玩家标识。

**未完成与限制：** 当前没有生产行为触发探针包，也未完成真实连接回环。`ProbePackets` 只验证 transport 注册和双向发送形状，未演示或保证服务端主线程调度。RIC reader 未在分派前提供长度、线程切换或异常隔离；这些事实意味着它只是 transport seam，不是阶段 4 的安全业务协议。真实业务 handler 禁止照搬该探针：必须先建立主线程调度、载荷长度/字段校验、权限校验和异常隔离。

## Container GUI

**证据与 blocker：**

- mapped JAR 的 `ServerPlayer` 使用 private `incrementWindowID()V`，发送 `Packet100OpenWindow`，随后设置 `openContainer`、`Container.windowId` 并调用 `Container.addCraftingToCrafters(ICrafting)`。例如 `displayGUIWorkbench(III)V` 和 `displayGUIChest(IIILnet/minecraft/IInventory;)V` 均完整执行这一配对流程。
- 客户端存在 `NetClientHandler.handleOpenWindow(Packet100OpenWindow)V`、`Packet100OpenWindow.handleOpenWindow(EntityClientPlayerMP)V` 与 `GuiContainer(Container)`，但现有 API 只识别原版 `inventoryType`。仓库及 RIC 1.5.0 中未找到 `IGuiHandler` 或自定义 window type 注册表。

**未实现：** 不冒用原版 window type，也不提前创建虚假 GUI。最窄建议 seam 是：

1. 服务端在 `ServerPlayer` 中复用原版 window-id/container 配对步骤；由于 `incrementWindowID()V` 为 private，应使用 accessor/invoker Mixin 或对单一 `openBetterQuestingContainer` 方法做窄注入，而不是复制整个类。
2. 为 BQ 自有 S2C open 消息携带 window id 与受限 GUI type；客户端 handler 创建对应 `GuiContainer`，并将同一 window id 写入 container。
3. 在落地前以真实客户端连接验证 slot click、关闭和错误 window id；该选择属于阶段 8 内容设计，本轮不实现。

## ResourceLocation 不可用作逻辑 ID（blocker，已由字节码确认）

MITE 的 `net.minecraft.ResourceLocation` 不是纯值对象：它的构造器会把实例登记进一个静态待校验队列，随后由集成服务器 tick 周期性校验该资源文件是否真实存在，缺失时在客户端 HUD 上持续渲染红字错误。上游 BetterQuesting 把 `ResourceLocation` 当作纯逻辑标识（属性 key、factory ID、注册表 key），直接照搬会让每一个逻辑 ID 都变成一条虚假的资源缺失报错。

证据链（全部来自当前 mapped JAR 的 `javap -c`）：

1. `ResourceLocation(String,String)` 与 `ResourceLocation(String)` 分别以 `iconst_1` 委派到带 `boolean` 的构造器，即默认 `verify=true`。只有 3 参 `ResourceLocation(String,String,boolean)` 与 2 参 `ResourceLocation(String,boolean)` 能显式传 `false` 跳过。
2. `verify=true` 时构造器调用 private `setVerificationPending()V`；该方法对不以 `.mcmeta` 结尾的 path 执行 `resources_to_verify.add(this)`。
3. `MinecraftServer` 在 tick 中执行 `isDedicatedServer()` 取反且 `tickCounter % 20 == 0` 时调用 `ResourceLocation.verifyResourceLocations()V`。即该校验只在集成服务器（单人）跑，专服不跑，且每秒一次。
4. `verifyResourceLocations()` 遍历队列逐个 `verifyExistence()`，随后 `clear()`。`verifyExistence()` 在 `exists()` 为假时调用 `Minecraft.setErrorMessage("Resource not found: " + getResourcePath())`。
5. `exists()` 依次查 `Minecraft.theMinecraft.mcDefaultResourcePack.resourceExists(...)` 与 `Minecraft.MITE_resource_pack.resourceExists(...)`；两者皆无则为假。注意 `theMinecraft == null` 时它也会 `setErrorMessage("...checking too early")` 并返回假。
6. `Minecraft.setErrorMessage(String)` 写入静态 `error_message`（仅保留首个非空值，重复值不重复打印 stderr）。`GuiIngame` 读取 `getErrorMessage()`，非空时在屏幕左上以颜色 `0xFF1193` 系红字绘制该消息以及 `Press [c] to clear error message.`。

**结论与工程决定：** 领域层的逻辑标识必须使用自有值类型 `com.github.postyizhan.betterquesting.api.util.ResourceKey`，不得使用 `ResourceLocation`。`ResourceKey` 保持 `domain:path` 字符串形式与 MITE 的 domain 解析规则（按首个 `:` 切分，`indexOf > 1` 时取前缀为 domain，domain 小写化，缺省 `minecraft`），因此存档与 factory ID 的文本表示与上游一致。只有客户端在真正加载纹理、声音等实际资源文件时才转换为 `ResourceLocation`，并在该处显式决定是否参与校验。

## 玩家名与本地身份大小写契约

以下证据均来自当前 mapped JAR 的 `javap -p -c`：

1. `NetLoginHandler.handleClientProtocol(Packet2ClientProtocol)` 将包内 `getUsername()` 原样保存为 `clientUsername`，随后只调用 `StringUtils.stripControlCodes(String)`，用 `String.equals(Object)` 比较清理前后文本；没有 lowercase，也没有长度或 `[A-Za-z0-9_]` 字符集校验。因此平台登录层只明确拒绝控制码。
2. `ServerConfigurationManager.createPlayerForUser(String)` 遍历在线玩家，以 `ServerPlayer.getCommandSenderName()` 对传入名调用 `String.equalsIgnoreCase(String)`，并踢出所有大小写变体的既有连接。`getPlayerForUsername(String)` 同样用 `equalsIgnoreCase` 查找在线玩家。服务器在线身份因此按大小写不敏感处理。
3. `SaveHandler.writePlayerData(EntityPlayer)` 直接用 `EntityPlayer.getCommandSenderName() + ".dat.tmp"` 和 `+ ".dat"` 构造文件名；`getPlayerData(String)` 直接用传入字符串加 `.dat`。代码本身不规范化大小写。
4. 本移植的目标运行环境是 Windows；其常用世界存档文件系统无法可靠地让仅大小写不同的两个玩家文件名代表两份独立存档。

**工程决定：** BQ 使用 `Locale.ROOT` 将保守 ASCII 玩家名折叠为小写，再派生本地逻辑 UUID。这与服务端在线玩家的大小写不敏感语义及目标 Windows 存档行为一致，是有意、稳定的身份契约。不得声称该规则能够在大小写敏感文件系统上自动发现或合并两份仅大小写不同的历史玩家存档；这类来源仍需迁移报告与管理员显式决定。

BQ 主动把可派生身份的输入收窄为 `[A-Za-z0-9_]{1,16}`。其中 1 字符下界是当前声明的保守边界；没有证据支持猜测为 3 字符。平台运行时若出现规则外但非 null 的名称，身份服务返回可报告的 `UNSUPPORTED_USERNAME` unresolved 结果，不能将其当作 `PlayerIdentity`；管理员 map/merge/replace 操作仍拒绝此类名称。`EntityPlayer.hasUsername()` 的字节码包含 `username == null` 防御，说明平台对象确实允许该边界；adapter 因而在调用严格的身份服务前隔离 null，并以 `<null>` 生成 unresolved 报告。

## ManyLib 2.3.1 专服风险

- 发布元数据为 `environment: "*"`，main entrypoint `fi.dy.masa.malilib.ManyLib` 会加载 `ManyLibConfig`。
- `manylib.mixins.json` 是 `required: true`，列出的 7 个目标全部为客户端 GUI/Minecraft 类：`GuiIngameMenu`、`GuiIngame`、`GuiMainMenu`、`GuiScreen`、`GuiTextField`、`Minecraft`、`SlotCreativeInventory`。
- 这些静态证据构成显著风险，但本次 `runServer` 已实际选择并准备 ManyLib 的 7 个 required mixin，随后专服成功到达 `Done`；因此当前 FML merged-jar 开发运行方式下已证明能够加载到明确阶段。尚未验证生产发布组合、正常停止以及无开发 merged client classes 的严格服务端分发环境，发布门槛仍未完全关闭。工程按产品要求固定接入独立 2.3.1 并声明 `many-lib >=2.3.1`；没有复制源码，也没有无 ManyLib 降级路径。
