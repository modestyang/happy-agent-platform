# Findings

- 正式仓库工作区初始状态干净，HEAD 为 `eec807d fix: finalize local experience flow`。
- 正式前端为 React 19 + React Router + Lucide + Vitest，主要页面集中在 `frontend/src/App.tsx` 与 `frontend/src/app.css`。
- 正式版本已有真实登录、首页记录抽屉、训练完成、AI 请求、动作详情、目标报告等 API 连接。
- 当前首页仍有“今天的节奏”，四个功能卡片以文字为主；导航未强化中间 AI Tab。
- 当前动作指导已经有四宫格信息结构，但需要把视觉焦点从文字块升级为图片/GIF 演示优先。
- demo 的动作图不是外部版权素材，而是代码生成的 SVG 姿态插画；可在正式项目里用独立组件承载，并优先显示后端 `imageUrls`。
- 正式前端基准测试为 6/6 通过，后续可以通过新增失败测试执行 TDD。
