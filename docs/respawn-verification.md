# 复活等待回血与结果纸延迟

等待复活的玩家在进入观察者模式时立即补满当前最大生命值，并清除火焰和坠落距离。每次倒计时更新再次补满；观察者即使仍是 PLAYING 档案，也会取消虚空等环境伤害并补满血。真正的原版死亡先完成 respawn，再在下一 tick 为仍在对局中的观察者补满，不直接给死亡实体设置生命值。

倒计时先检查玩家在线、档案仍属于原对局、仍处于等待观察者模式且对局仍在运行/准备，再处理计数归零。离场、结算或轮次重启后，旧计时器不再调用 handleRespawn。

对局结果的两条纸张发放路径均较原先延后 2 tick：DefaultMatch 结果广播由立即发放改为 2 tick；Match.checkForConclusion 的原 2 tick 补发改为 4 tick。胜者仍用槽位 1、败者仍用槽位 0。DefaultMatch 延迟发放也复用原有档案/队列/Play Again pending 检查，防止玩家已经离场或开始排队时收到旧纸。

## 实机验证

停服替换自行构建的 `target/Alley-3.1.1.jar` 后启动。

1. 使用带 RespawnTimer 的套件反复掉虚空，检查进入观察者后和整个 3 秒倒计时内均满血，复活后仍满血；同时检查普通击杀、着火死亡。
2. 等待复活时另一方结束对局、退出或触发轮次重启，确认旧倒计时不会把玩家拉回战场。
3. 普通对局和 Bot 对局分别测试双方胜败，结果纸晚 2 tick 出现，槽位不变，死亡清包后纸仍可使用。
4. 通过聊天 Play Again 提前排队/离场，确认延迟回调不会把旧对局的纸加回背包。

本地回归 `dev.revere.alley.feature.match.utility.MatchRespawnHealthRegression` 检查低血量恢复、20/40 最大生命值、重复恢复、死亡/离线保护，以及不修改背包或游戏模式。编译后用 `target/legacy-test-classpath.txt` 的依赖路径与 `target/test-classes;target/classes` 运行；不替代上述实机时序检查。
