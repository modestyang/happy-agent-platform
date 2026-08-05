# Fitness Mobile UI Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the five authenticated fitness tabs in the formal project with the approved warm, playful demo language while preserving real APIs and persistence.

**Architecture:** Keep `App` as the authenticated data shell and router, extract reusable exercise/media and lightweight chart visuals into focused components, and render every summary from bootstrap data. No demo provider or mock repository enters the formal application.

**Tech Stack:** React 19, TypeScript 5.9, React Router 7, Lucide React, CSS, Vitest, Testing Library, Vite.

## Global Constraints

- Modify `/Users/modest/IdeaProjects/happy-agent-platform`; treat `/Users/modest/IdeaProjects/fitness` as read-only.
- Keep `/api/app/bootstrap`, record, workout, AI, login, and logout contracts unchanged.
- Use real bootstrap data or an explicit empty state; do not invent historical business records.
- Keep the UI usable at 320–430 px and retain desktop phone framing.

---

### Task 1: Acceptance tests for the new information architecture

**Files:**
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: the current `App` export and existing mocked bootstrap contract.
- Produces: regression coverage for required labels, navigation, state transitions, and API calls.

- [ ] Add tests asserting the four exact home cards, no “今天的节奏”, and five tabs labeled “今天/计划/瘦瘦/动作/我的”.
- [ ] Add tests selecting a future calendar day and following “AI 生成计划” into a conversation.
- [ ] Add tests that the AI welcome capability grid disappears after starting a conversation while contextual prompt chips remain.
- [ ] Add tests for exercise filtering/detail and Profile achievements, activation map, trend, preferences, and logout.
- [ ] Run `npm test -- --run` from `frontend` and confirm the new assertions fail for missing UI behavior rather than test setup errors.

### Task 2: Shared media and chart primitives

**Files:**
- Create: `frontend/src/components/ExerciseVisual.tsx`
- Create: `frontend/src/components/MiniVisuals.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Produces: `ExerciseVisual({ exercise, step, compact })`, `WeightSparkline({ records })`, and `BodyActivation({ areas })`.
- Consumes: exercise image URLs and body-record/area values supplied by `App`.

- [ ] Implement image-first exercise rendering with `onError` fallback to a semantic inline SVG pose.
- [ ] Implement an SVG weight sparkline whose points are derived from sorted real records and an explicit empty state.
- [ ] Implement an accessible body activation illustration whose highlighted regions come from real plan target areas.
- [ ] Run `npm test -- --run` and keep all tests green before page integration.

### Task 3: Today and floating navigation

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: `Dashboard`, `RecordDrawer`, React Router navigation.
- Produces: exact four-card home IA and center-raised `瘦瘦` navigation.

- [ ] Replace the formal header with a mascot-led short greeting.
- [ ] Convert the goal panel to icon-led progress hierarchy and remove the text arrow treatment.
- [ ] Replace home cards with icon, exact title, and concise state; route Food/Record drawers to their initial tabs.
- [ ] Remove the “今天的节奏” section.
- [ ] Implement dark pill navigation and a raised coral center AI tab with active/press motion.
- [ ] Run the focused home/navigation tests until green.

### Task 4: Plans and 瘦瘦 flows

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: current bootstrap plan, real `api.aiMessage`, and `ExerciseVisual`.
- Produces: selectable week strip, date-sensitive empty states, plan content, welcome and conversation states.

- [ ] Add a deterministic local-week model where today owns the real bootstrap plan.
- [ ] For past empty dates render “无训练计划”; for future empty dates render an AI generation card linking to `/ai?prompt=...`.
- [ ] Render exercise rows with illustrations, dose, visible cues, and errors without collapsible text.
- [ ] Preserve speech synthesis and completion API behavior.
- [ ] Rebuild AI welcome capability cards and parse one prepared prompt from the URL.
- [ ] Keep the composer fixed, show conversation prompts only after the first message, and retain explicit provider-unconfigured errors.
- [ ] Run focused plan/AI tests until green.

### Task 5: Exercises and Profile

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: `Dashboard.exercises`, records, meals, plan, report, `ExerciseVisual`, `WeightSparkline`, and `BodyActivation`.
- Produces: image-led filterable library/detail and the required real-data Profile dashboard.

- [ ] Add search and body-part tags, image-led exercise grid, result count, and empty filtering state.
- [ ] Rebuild exercise detail as a four-step visual grid plus always-visible steps and common errors.
- [ ] Derive check-in days/achievement states from unique real record dates and counts.
- [ ] Render activation areas from plan targets and weight sparkline from real body records; label body fat unavailable.
- [ ] Add fixed AI tone choices, preference chips, real history counters, and real logout.
- [ ] Run focused library/Profile tests until green.

### Task 6: Full verification and visual acceptance

**Files:**
- Modify: `progress.md`
- Modify: `task_plan.md`

**Interfaces:**
- Consumes: the complete frontend build and local backend.
- Produces: reproducible acceptance evidence.

- [ ] Run `npm test -- --run`, `npm run typecheck`, `npm run lint`, and `npm run build` from `frontend`.
- [ ] Run `git diff --check` from the repository root.
- [ ] Start or reuse the local services, inspect all five tabs at mobile width, and exercise navigation, drawers, date selection, AI starter, filtering, detail, and logout-safe visibility.
- [ ] Record exact results and known backend-dependent empty states in `progress.md`.

