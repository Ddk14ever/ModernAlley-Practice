# 全局战斗线程接入方案（待确认核心修改范围）

本文记录源码核对结果和拟议接入范围，不表示已实现或启用了运行时功能。本轮未修改战斗代码、服务器核心、服务器配置或客户端，也未编译、打包。

## 目标与边界

将战斗网络与跟踪服务作为全局基础设施，由 `KnockbackManager` 管理生命周期和参与玩家，与 DEFAULT、LEGACY、MINEMENCLUB 的公式选择分开。三个分支共用基础设施，各自保留伤害窗口、格挡和击退计算语义。

此前要求只修改插件；完整实现以下机制需要扩大到服务端核心。应先确认这一范围，再修改核心或增加依赖核心能力的插件代码。当前工作区已有的 MinemenClub 实现和用户的 horizontal=0.8755 均保留。

## 本地核对依据

- Club 源码：`D:/Apps/IntelliJ IDEA Community Edition 2025.1.3/projects/ClubSpigot-main/ClubSpigot-Server/src/main/java`。
- 当前运行核心的核对对象：`D:/Games/MinecraftServer/Aechopvp1.21.11/versions/1.21.11/sparklypaper-1.21.11.jar`，通过 `javap` 读取字节码，没有运行服务器或编译源码。
- 本机核心源码候选：`D:/Apps/IntelliJ IDEA Community Edition 2025.1.3/projects/SparklyPaper-ver-1.21.11`。尚未确认它与上述 jar 的构建提交完全一致；修改前需要对齐。

| 机制 | Club 实际行为 | 当前 1.21.11 的情况 | 完整移植需要修改的位置 |
| --- | --- | --- | --- |
| 独立战斗发包线程 | `NetworkManager` 将特定包交给 `CombatThread`，默认 40Hz 排空队列，再交 Channel executor 写入 | `Connection.sendPacket` 已将实际写入交给连接的 Netty event loop；flush=true 时执行 writeAndFlush | 核心 `Connection` 的发送、排队与 flush 策略，以及插件能力桥接 |
| 异步实体跟踪 | 替换原生 EntityTracker，在 worker 更新 tracker，并通过 WorldTickManager/WorldTicker 调整同步屏障和发包阶段 | `ChunkMap.TrackedEntity.seenBy` 使用非并发 ReferenceOpenHashSet；updatePlayer/removePlayer 有 AsyncCatcher；还涉及 Moonrise 跟踪状态 | 核心 `ChunkMap`、`ServerEntity`、Moonrise 跟踪器及世界 tick 同步阶段 |
| Instant interaction | `PacketPlayInUseEntity.instant()` 使 `PlayerConnectionUtils` 跳过切回主线程，在网络线程执行交互/攻击 | `ServerGamePacketListenerImpl.handleInteract` 首先调用 PacketUtils.ensureRunningOnSameThread；异步调用仍会被 PacketProcessor 调度回所属线程 | 核心交互包分发、攻击和事件执行的线程模型 |

SparklyPaper 的并行世界 tick 配置不等于已实现异步实体跟踪，也不等于攻击在网络线程即时执行。

## 拟议实施范围

### 1. 核心能力桥接与全局管理

在核心提供有版本标识的战斗能力接口，插件通过能力检测接入。`KnockbackManager.initialize/shutdown` 负责注册与释放；applyKnockback、清理玩家、换世界、传送、复活、重载和离场负责更新会话。

桥接服务按本插件管理的玩家会话工作，不以 MINEMENCLUB 为启用条件。没有对应核心能力时保留原有路径，并明确报告未启用的功能，不添加名义上开启但无实际作用的开关。候选接口名称和配置结构在核心接口确定后再落地。

### 2. 战斗发包

先测量当前核心从速度确定到 Channel write/flush 的延迟，再决定是否需要新增调度层。每条连接独立维护发送顺序；保留 ViaVersion、ViaBackwards、ViaRewind、ProtocolLib 所在的正常 pipeline，以及 bundle、传送确认、连接关闭的顺序约束。

不直接复制 Club 的 Spigot404Write：该实现使用 static 全局包队列，队列元素没有目标 Channel，排空时却使用某个 writer 的 Channel，多连接下可能错发。固定 40Hz 排队还可能引入接近一个周期（25ms）的额外等待，不能据此承诺改善当前核心延迟。

实现应以实际延迟为依据，优先保留现有按连接的事件驱动写入；若需要独立战斗调度器，必须证明它有益，且不能绕开原有包转换和事件，也不能为了提前发包重复发送速度。

### 3. 实体跟踪

在核心划定跟踪阶段：所属世界线程采集一致的输入，worker 计算跟踪结果，所属线程在明确屏障处提交 viewer 与 tracker 状态，再按连接顺序发送。实体新增/移除、跨世界、传送和区块可见性变更必须与该阶段同步。

这是面向当前核心的并行化方案，不能仅把现有 updatePlayer/sendChanges 放到插件线程池，也不能仅将 seenBy 改成并发集合或关闭 AsyncCatcher。实体快照上的异步计算也不能在未接管原生跟踪阶段时被宣称为完整的异步实体跟踪。

### 4. Instant interaction

严格复刻 Club 意味着让交互/攻击脱离原有主线程切换。必须先审查原生攻击调用链、Bukkit 同步伤害事件、Alley 对局状态、第三方监听器和 SparklyPaper 并行世界状态的线程归属，确定可执行的核心改造边界。

仅在网络线程做解析或校验、随后排到所属线程执行攻击，可以作为减少调度等待的另一种优化，但仍有所属线程等待，不应标记为已经实现 Club 原版 Instant interaction。此替代方案是否采用，需要明确说明语义差异。

不得从 ProtocolLib 异步回调直接调用 Player.attack、修改生命值或实体速度，也不得通过删除线程检查来宣称完成。

## 插件接入时必须保留的语义

- DEFAULT、LEGACY、MINEMENCLUB 的最终向量、hit delay、格挡和分支选择规则保持各自实现。
- 每次有效攻击只执行一次伤害与一次最终速度交付，拒绝或取消的攻击不会因快速路径额外产生击退。
- Bot 没有真实客户端，继续使用现有服务端速度交付与反馈路径，不等待不存在的网络连接。
- 不机械删除当前为避免 NMS 覆写、重复速度而设置的延后任务；逐个核实因果关系后才调整。
- 会话携带连接/世界/对局代次，离场、复活、传送、重载后不发送旧对局残留任务。
- 既有假死、立即重生、旁观、对局结果与 Play Again 逻辑继续走原有流程。

## 验证与判定

用户明确要求后才编译或打包。运行验证至少覆盖三个 KB 分支、1.7.10 与现代客户端、Via 转换链、真人与 Bot、连续格挡、伤害取消、传送/离场/复活、同一世界和多个世界同时对局。

记录攻击包到达、攻击开始、速度确定、出站写入/flush 的时间，比较相同负载下的中位数、P95/P99 和 tick 耗时，不能用调度线程频率替代收益证明。Channel flush 不代表客户端已经收到。

跟踪部分检查实体生成/销毁/移动顺序、幽灵实体、可见性与跨世界数据残留；事件部分检查线程归属、取消语义和单次攻击计数；网络部分检查跨连接隔离和 Via/ProtocolLib 兼容。未完成这些验证前不能宣称与 Club 的线程机制完整等价。

## 当前待决定事项

是否允许在现有插件之外修改 `SparklyPaper-ver-1.21.11` 核心源码，并增加插件与核心的能力桥接。核心源码与当前 jar 的提交对应关系也需要核实；即使允许修改源码，构建、替换服务器 jar 和启动实服测试仍遵守原有的明确授权要求。
