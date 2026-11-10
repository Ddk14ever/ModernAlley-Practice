# 项目工作约定

- 默认不自动构建、打包或执行会触发编译的验证脚本；仅在用户明确要求时执行。
- 发布 jar 使用 Maven 的标准名称 `Alley-${project.version}.jar`（当前为 `Alley-3.1.1.jar`），不要额外生成带修复名、日期等后缀的副本。
- 临时分析文件和日志放在系统临时目录，不堆积到 `target`。

<!-- dsh-subagent:begin -->
## 子代理委派:统一走 DSH

本机已把整个 DeepSeek Harness(DSH)实例注册成 MCP 工具(服务器名 `dsh`,`mcp_servers.dsh`):
`dsh_task` / `dsh_task_status` / `dsh_task_cancel` / `dsh_health`。

- 需要把一项自包含任务外包出去、并行调研,或想让探索不占用自己的上下文时,优先调用 `dsh_task`;
- `prompt` 必须自包含(DSH 看不到本会话历史,也不会追问):写清目标、绝对路径、约束、期望产出;
- 返回 `status: running` 时必须继续轮询 `dsh_task_status`,直到 `ok`/`error` 再汇报。
<!-- dsh-subagent:end -->
