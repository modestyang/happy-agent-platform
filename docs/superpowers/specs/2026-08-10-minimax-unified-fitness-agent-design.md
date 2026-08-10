# MiniMax 统一健身 Agent 设计

## 目标

健身应用只维护一个 `fitness.coach` Agent。对话、目标报告、三餐建议和饮食图片识别虽然由不同任务处理器执行，但必须使用同一个已发布 Agent、同一个 Provider、同一个模型和同一版加密凭据快照。

## 配置

- Provider key：`minimax`
- Endpoint：`https://api.minimaxi.com/v1`
- Model key：`minimax-m3`
- API model：`MiniMax-M3`
- 能力：文本、多轮对话、工具调用、结构化输出、图片理解

百炼组件保留为可选目录项，不删除；当前 `fitness.coach` 切换并发布到 MiniMax。

## 运行时边界

所有健身 AI 任务只读取 `agent_versions` 中最新的 `PUBLISHED` 配置，并从其中的 `currentGoalReportRuntime` 快照解析 Provider、Model 和 Credential。任务处理器可以保留不同的提示词和响应 Schema，但不得读取可变草稿或实时组件投影来重新选择模型。

管理页面只负责写入 MiniMax API Key。服务端使用既有 AES-256-GCM 存储；密钥不进入源码、日志或响应。

## 验证

先用测试证明目录种子和不可变快照选择，再实现。重启后通过管理页面保存密钥、把 `fitness.coach` 切换到 `minimax-m3` 并发布，最后从真实页面验证对话、目标报告、三餐建议与饮食图片识别。

