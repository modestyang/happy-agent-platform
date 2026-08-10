# MiniMax 统一健身 Agent 实施计划

1. 为默认组件目录新增失败测试：MiniMax Provider、MiniMax-M3 模型及默认 Agent 绑定。
2. 为三餐生成与图片识别新增失败测试：发布后修改草稿或组件投影，运行时仍使用已发布快照。
3. 添加 MiniMax Provider/模型种子，并将全新数据库的 `fitness.coach` 默认绑定切换到 MiniMax-M3。
4. 修改三餐生成和图片识别配置解析及凭据解密，使其与报告/对话共用同一发布快照。
5. 执行 Spotless、相关后端测试、全量 Maven 验证及前端测试/类型检查。
6. 重启本地服务，通过真实管理页面保存密钥、切换并发布 Agent。
7. 通过真实健身页面验证四类 AI 能力，并记录任何 MiniMax 协议兼容差异。

