# Fitness UI refinement plan

## Goal

在正式项目中，以 `/Users/modest/IdeaProjects/fitness` 为只读视觉参考，完成 Today、Plans、瘦瘦、Exercises、Profile 五个移动端页面的结构、视觉与交互升级；保持正式后端 API 和数据链路不被 demo 逻辑替换。

## Phases

1. [complete] 审计正式实现、demo 参考与现有接口边界。
2. [in_progress] 先补充会失败的页面行为测试，覆盖五个 Tab 和关键交互。
3. [pending] 统一字体、暖色视觉、图标引导、卡片与 C 位 AI 导航。
4. [pending] 按需求重构首页、计划、瘦瘦、动作、我的页面。
5. [pending] 运行单测、类型检查、Lint、构建和浏览器逐页验收。
6. [pending] 记录验收证据、遗留依赖与交付方式。

## Decisions

- 正式项目路径：`/Users/modest/IdeaProjects/happy-agent-platform`。
- demo 路径只读：`/Users/modest/IdeaProjects/fitness`。
- 保留正式项目的真实 API、认证、PostgreSQL 持久化和 Agent 配置机制。
- 视觉方向：暖奶油底色、果汁暖色卡片、圆润高对比标题、图标化操作、克制的可爱动效；避免紫色渐变与模板化 AI 风格。
- 当前需求已经给出完整信息架构和 demo 基线，按已确认方案直接实施，不额外暂停询问。

## Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| 首次读取输出被 node_modules 清单截断 | 1 | 改用定向文件读取与排除 node_modules |
| 从 `frontend` 目录读取后端相对路径失败 | 1 | 记录基准测试结果，后续统一从仓库根目录读取后端文件 |
