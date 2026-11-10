# 1.7.10 侧栏验证

服务端使用 Paper 1.21.11、ProtocolLib、ViaVersion、ViaBackwards 和 ViaRewind，记录各插件版本；用真实 1.7.10 客户端测试。若 Via 安装在代理端，需要确认服务端能取得真实客户端协议。协议 4/5 才会进入 1.7 分支。

## 部署与配置

- 完全停止服务器，备份旧 jar，替换自行构建的 `target/Alley-3.1.1.jar` 后启动。不要用插件热重载。
- 1.7 Tab 现在固定使用真实玩家名单和名字颜色，不生成页眉、页脚或空白行。旧 `tab-list.legacy.*` 配置自动忽略，不需要手动修改。新版客户端仍使用原有页眉页脚配置。
- 在服务器 `providers/scoreboard.yml` 设置 `scoreboard.legacy-diagnostic: 'ascii'`，必要时设置 `scoreboard.legacy-glyphs.debug: true`，重启。玩家自己的 scoreboard 开关需要开启。
- 启动时应出现 `[LegacySidebar] renderer=teams-v4`，用于确认包含名字标签队伍颜色修复的新 jar 已加载。`debug` 每名玩家最多每 10 秒输出一次渲染后的摘要；默认不打印逐包日志。需要抓服务端包时单独设置 `scoreboard.legacy-glyphs.packet-trace: true`，每 10 秒最多 20 条详情，其余汇总。
- 如果出现 `Duplicate UUID on frontend connection` / `You logged in from another location`，先确保同一账号只有一个连接，并排查代理、自动重连或重复转发。插件已防止旧连接的退出事件删除新连接侧栏，但不会消除来自 Via/服务端的重复连接和踢人。

## 按阶段检查

1. `ascii`：标题 `Legacy sidebar test` 和 `Sidebar OK` 应持续存在；`Tick` 应每秒变化；两个空白行各占一行。停留至少一分钟，然后重复退出、重连。
2. 改为 `colors` 并重启：检查绿色、粗体、重置后的白色文字。
3. 改为 `long` 并重启：长文本会按每半段最多 16 个原始字符（包括颜色代码）截断；跨分段的红色粗体应连续，无孤立颜色代码。每个配置行仍只占一行。
4. 改为 `unicode` 并重启：检查中文与字体替换；字形缺失和整块侧栏消失应分别记录。
5. 改回 `legacy-diagnostic: 'off'` 并重启：验证正常大厅、排队、对战、观战、返回大厅；有血量显示的 kit 同时显示名字下血量、名字标签和侧栏。关闭/开启侧栏不应清空血量或名字标签。
6. 两名玩家同时进入（至少一名 1.7.10），确认名字标签、正常玩家列表和侧栏互不影响；再用新版客户端验证正常布局。

如果依然空白，保存从加入到消失的日志、检测到的协议号及客户端画面。`LegacyScoreboardPacketTrace` 看到的是服务端现代协议包，不能证明 ViaRewind 翻译后发往 1.7 的最终数据包正确。

## 本地回归检查

使用 Java 21 和 Maven 运行 `scripts/test-legacy-sidebar.ps1`（可通过 `-Maven`、`-Java` 指定可执行文件）。检查 16/64 字符边界、跨分段的颜色/格式继承、重置、Unicode 边界及标题限制。这些检查不替代真实客户端联调。

若本机禁止运行 PowerShell 脚本，可直接在项目根目录运行等价命令：

```powershell
mvn -o test-compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath '-Dmdep.outputFile=target/legacy-test-classpath.txt'
$legacyTestDependencies = (Get-Content -Raw target/legacy-test-classpath.txt).Trim()
java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.library.assemble.LegacySidebarRegression
java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.library.assemble.AssembleLifecycleRegression
java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.visual.nametag.LegacyNametagRegression
```

生命周期检查使用每次查询都返回新 Objective 包装对象的模拟 Bukkit API，检查数值格式不会因重复查询而重发、同 UUID 的新旧连接不会互删侧栏、清理侧栏不会移除名字标签或血量 objective。

## ViaRewind 4.1.3 队伍颜色兼容

检查实际安装的 ViaRewind 4.1.3 字节码发现：`ScoreboardPacketRewriter1_8` 处理 mode=2 的队伍颜色变化时，会查询新颜色对应的侧栏；如果不存在，就发送 `SET_DISPLAY_OBJECTIVE(slot=1, name="")`，即使普通侧栏仍然存在，也会使 1.7 显示空白。Alley 的名字标签在创建队伍后调用 `setColor`，会触发这一分支。

`teams-v4` 对 1.7 观察者保留默认队伍颜色，通过 prefix 的颜色代码显示名字标签。1.8+ 保持队伍着色。这里针对的是已检查的 jar 实现，不表示已完成真实客户端验证。
