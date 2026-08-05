# Fitness mobile UI refinement design

## Scope and boundary

This change updates the authenticated mobile fitness application in the formal repository. The demo repository is read-only and is used only to identify the visual language and interaction patterns. Authentication, PostgreSQL persistence, fitness APIs, Agent provider configuration, and existing request contracts remain formal application capabilities.

## Visual language

The interface uses a warm cream canvas, rounded fruit-color panels, bold rounded Chinese typography, compact supporting copy, and line icons that communicate actions without textual arrows. The five-tab navigation is a dark floating pill. The center “瘦瘦” tab rises above the rail and uses a warm coral accent. Motion is limited to page entrance, press feedback, the mascot greeting, and the AI presence indicator.

## Information architecture

### Today

- A mascot greeting introduces the day with one short encouraging sentence.
- The active goal card leads with current weight, target weight, and a visible progress ring/bar; its icon button opens goal/report details.
- Four icon-led cards are titled exactly “训练”、“饮食”、“记录”、“报告”.
- “今天的节奏” is removed.
- Training navigates to the plan. Food and Record open the one-layer recording sheet in the appropriate tab. Report opens the current-goal report in Profile.

### Plans

- A seven-day calendar strip controls the selected date.
- Today displays the real current plan from bootstrap.
- A future date without a plan displays an AI plan-generation entry and routes a prepared prompt into 瘦瘦.
- A past date without a plan displays a quiet “无训练计划” state and no generation action.
- The selected plan renders image-led exercise rows, always-visible cues/errors, and the existing follow-along/complete actions.

### 瘦瘦

- The first visit displays a mascot greeting and four high-frequency capability blocks: today’s training, meal recommendation, meal recording, and recent state review.
- Selecting a block starts the conversation and removes the welcome grid.
- Existing messages remain visible for the lifetime of the mounted session.
- The composer stays above bottom navigation and provides contextual quick prompts only during a conversation.
- A query-string `prompt` from other tabs is treated as a prepared first message and sent through the real AI API.

### Exercises

- Search plus body-part and equipment-style tags narrow the library.
- The grid prioritizes backend `imageUrls`; if an image is absent or fails, a local SVG pose illustration is shown instead.
- Detail uses a four-step visual grid followed by title, target area, steps, and common errors.

### Profile

- Identity and factual training configuration are presented first.
- Check-in days and achievements are derived only from real record dates/counts.
- The body activation map derives highlighted areas from the current plan.
- Weight trend uses real body records; body-fat state explicitly says “暂无数据” because the current API does not expose body fat.
- AI coach tone and personal preferences are selectable local interface settings; history counters use real bootstrap data.
- Logout remains a real API action.

## Accessibility and states

Icon-only controls have accessible names, buttons retain visible focus, dialogs remain inside the phone stacking context, and motion is reduced when the operating system requests it. Empty, loading, configured/unconfigured AI, image-error, and API-error states remain explicit.

## Verification

Vitest asserts the five-tab labels, C-positioned AI navigation, exact home card titles, removal of obsolete content, calendar empty states, AI welcome-to-conversation transition, four-step exercise detail, profile sections, and existing write/API flows. TypeScript, ESLint, production build, and browser inspection at mobile width complete acceptance.

