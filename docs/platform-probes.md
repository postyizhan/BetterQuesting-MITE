# 阶段 0 平台能力探针

本文只记录当前工作树已由真实 JAR、发布源码或字节码确认的事实。构建成功不等同于客户端或专用服务器 smoke test 成功。

## 证据基线

- 映射后游戏 JAR：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-676ab05589/...jar`。
- RustedIronCore 1.5.0 发布源码 JAR：Gradle cache 中的 `RustedIronCore-1.5.0-sources.jar`。
- ManyLib 2.3.1 发布 JAR/源码 JAR：Gradle cache 中的 `ManyLib-2.3.1.jar` 与 `ManyLib-2.3.1-sources.jar`。
- ManyLib Maven 坐标 `com.github.MinecraftIsTooEasy:ManyLib:2.3.1` 可由当前 `ModdedMITE` 仓库解析；发布元数据中的真实 mod id 是 `many-lib`。

## Access Widener 状态

`src/main/resources/betterquesting.accesswidener` 当前只有 `accessWidener v2 named` header。阶段 0/1 没有开放项是有意状态：现有生命周期与网络探针不需要扩大成员可见性。未来若确需开放成员，每一项都必须先从当前 mapped JAR 核对 owner、成员名和真实 mapped descriptor；不得从其他 Minecraft 版本猜测 descriptor，也不得添加占位规则。

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

## ManyLib 2.3.1 专服风险

- 发布元数据为 `environment: "*"`，main entrypoint `fi.dy.masa.malilib.ManyLib` 会加载 `ManyLibConfig`。
- `manylib.mixins.json` 是 `required: true`，列出的 7 个目标全部为客户端 GUI/Minecraft 类：`GuiIngameMenu`、`GuiIngame`、`GuiMainMenu`、`GuiScreen`、`GuiTextField`、`Minecraft`、`SlotCreativeInventory`。
- 这些静态证据构成显著风险，但本次 `runServer` 已实际选择并准备 ManyLib 的 7 个 required mixin，随后专服成功到达 `Done`；因此当前 FML merged-jar 开发运行方式下已证明能够加载到明确阶段。尚未验证生产发布组合、正常停止以及无开发 merged client classes 的严格服务端分发环境，发布门槛仍未完全关闭。工程按产品要求固定接入独立 2.3.1 并声明 `many-lib >=2.3.1`；没有复制源码，也没有无 ManyLib 降级路径。
