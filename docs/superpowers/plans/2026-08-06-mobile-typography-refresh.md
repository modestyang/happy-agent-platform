# Mobile Typography Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the fitness mobile app typography feel light and modern while preserving the approved warm visual system and all product behavior.

**Architecture:** Keep the change inside the existing shared mobile stylesheet so every fitness route receives the same type scale and font stack. Replace handwritten display fallbacks with local system sans-serif fonts, then selectively reduce overly heavy controls and heading tracking without changing layout primitives.

**Tech Stack:** React 18, TypeScript, Vite, Vitest, Testing Library, CSS.

## Global Constraints

- Change only the fitness mobile application typography in `frontend/src/app.css`.
- Use local/system fonts only; do not add web-font requests or dependencies.
- Preserve colors, radii, shadows, responsive structure, API behavior, and the agent workbench.
- Use `"Avenir Next", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif` as the primary stack.
- All mobile routes must avoid `FZLanTingHeiS-DB-GB`, `Hannotate SC`, and `Yuanti SC`.

---

### Task 1: Add a regression contract for modern mobile typography

**Files:**
- Modify: `frontend/src/App.test.tsx`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: the existing `App` bootstrap rendering and the `mockFetch()` test helper.
- Produces: a regression test which verifies the home title receives the modern font family at runtime.

- [x] **Step 1: Write the failing test**

Add this test inside the existing `describe('App', ...)` block:

```tsx
  it('uses the modern sans-serif display stack for the home heading', async () => {
    mockFetch();
    render(<App />);

    const heading = await screen.findByRole('heading', { name: '今天，慢慢变好' });
    const fontFamily = getComputedStyle(heading).fontFamily;
    expect(fontFamily).toContain('Avenir Next');
    expect(fontFamily).not.toMatch(/Hannotate SC|Yuanti SC|FZLanTingHeiS-DB-GB/);
  });
```

- [x] **Step 2: Run the test to verify it fails**

Run: `npm test -- --run src/App.test.tsx`

Expected: FAIL because the existing heading selector chooses the handwritten display stack.

- [x] **Step 3: Implement the smallest stylesheet change**

In `frontend/src/app.css`, replace the root and shared heading font declarations with:

```css
:root {
  font-family: "Avenir Next", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}

h1, h2, h3, strong {
  font-family: "Avenir Next", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}
```

Replace the two remaining component-level `Yuanti SC` font shorthands (`.mascot span` and `.plan-hero > span`) with the same stack, preserving their size and line-height.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `npm test -- --run src/App.test.tsx`

Expected: PASS, including the new typography contract and existing application interaction coverage.

- [x] **Step 5: Commit the test and stylesheet contract**

```bash
git add frontend/src/App.test.tsx frontend/src/app.css
git commit -m "style(frontend): modernize mobile typography"
```

### Task 2: Refine visual hierarchy without a layout redesign

**Files:**
- Modify: `frontend/src/app.css:72-80, 110-366`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: the shared modern font stack established in Task 1.
- Produces: lighter headings and controls which retain existing visual hierarchy through size and color rather than decorative handwriting or extreme bold weights.

- [x] **Step 1: Extend the failing regression test**

Add these assertions to the typography test after the computed-font assertions:

```tsx
    expect(getComputedStyle(heading).letterSpacing).not.toBe('-1.08px');
    expect(getComputedStyle(screen.getByRole('button', { name: '训练' })).fontWeight).toBe('700');
```

- [x] **Step 2: Run the test to verify it fails**

Run: `npm test -- --run src/App.test.tsx`

Expected: FAIL because the current title uses `letter-spacing: -.04em` and primary action styles use a 900 weight.

- [x] **Step 3: Implement the smallest hierarchy refinement**

In `frontend/src/app.css`:

```css
.page-head h1, .home-greeting h1, .ai-head h1 {
  letter-spacing: -.025em;
}

.primary, .soft-button,
.ai-plan-empty a,
.workout-start, .workout-finish {
  font-weight: 700;
}
```

Keep `800` only for small metadata and retain `800` for numerical emphasis where it is already visually meaningful. Do not adjust sizes, padding, grid definitions, or colors in this task.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `npm test -- --run src/App.test.tsx`

Expected: PASS.

- [x] **Step 5: Run the frontend quality suite**

Run:

```bash
npm test -- --run
npm run typecheck
npm run lint
npm run build
```

Expected: all commands exit with status 0.

### Task 3: Verify the mobile routes visually

**Files:**
- Verify: `frontend/src/app.css`
- Verify: running Vite app at `http://127.0.0.1:5176/`

**Interfaces:**
- Consumes: the typography styles from Tasks 1 and 2.
- Produces: manual visual evidence that no handset route reintroduces the handwritten display font or creates wrapping/overflow regressions.

- [ ] **Step 1: Open a separate mobile viewport test tab**

Open a new browser tab with the existing local session and set a handset viewport before loading the home route.

- [ ] **Step 2: Inspect home, plan, workout, AI, library, and profile**

Navigate to `/`, `/plan`, `/workout/50000000-0000-0000-0000-000000000008`, `/ai`, `/library`, and `/profile`. Confirm display titles remain readable, action labels stay on one line where previously intended, and navigation geometry is unchanged.

- [x] **Step 3: Run final repository checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the planned source, test, and plan-document changes are present before committing remaining documentation.

- [ ] **Step 4: Commit the execution plan**

```bash
git add docs/superpowers/plans/2026-08-06-mobile-typography-refresh.md
git commit -m "docs: plan mobile typography refresh"
```

## Self-review

- Spec coverage: Tasks 1 and 2 remove every handwritten font selector, apply the approved local system stack, and reduce only the headline/control heaviness. Task 3 verifies all six fitness routes without touching the agent workbench.
- Placeholder scan: no `TBD`, `TODO`, “implement later”, or unspecified testing steps remain.
- Type consistency: the test uses existing Testing Library APIs and the existing `App` render contract; all selectors and route paths match the current project conventions.
