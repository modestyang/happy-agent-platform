# Meal Recommendation and Workout Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add database-backed next-meal recommendations, repair home/plan layouts, and build a state-driven workout player with synchronized browser speech and real completion persistence.

**Architecture:** Extend the existing Fitness Bootstrap contract with daily recommendation rows owned by `fitness`. Keep timer transitions in a pure TypeScript workout engine and render them in a dedicated route component. `App.tsx` remains the data shell/router while meal and workout pages live in focused components.

**Tech Stack:** Java 17, Spring Boot 3.5, JDBC, PostgreSQL/Flyway, React 19, TypeScript, React Router, Vitest, Testing Library, CSS, Web Speech API.

## Global Constraints

- Modify only `/Users/modest/IdeaProjects/happy-agent-platform`; `/Users/modest/IdeaProjects/fitness` is read-only.
- All recommendation content displayed as business data comes from PostgreSQL through the Bootstrap API.
- Missing Agent output is an explicit empty state; never manufacture a frontend recommendation.
- Workout completion calls the existing authenticated completion API exactly once.
- Keep the UI usable between 320px and 430px and preserve the warm, playful visual system.

---

### Task 1: Database-backed daily meal recommendations

**Files:**
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V3__daily_meal_recommendations.sql`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Produces `MealRecommendationDto(UUID id, LocalDate recommendationDate, MealType mealType, List<MealItemDto> items, String reason, String status, Instant generatedAt)`.
- Adds `List<MealRecommendationDto> mealRecommendations` to `BootstrapData` and `BootstrapDto`.

- [ ] Add failing integration assertions for three current-day recommendations and their structured items.
- [ ] Run the focused Testcontainers test and confirm the missing JSON path failure.
- [ ] Add V3 migration, constraints, unique user/date/meal index, and local seed inserts that derive the date from `CURRENT_DATE`.
- [ ] Extend DTOs, JDBC mapping, Bootstrap construction, and service response.
- [ ] Run the focused integration test until green and commit the backend slice.

### Task 2: Home cards, meal page, and plan layout

**Files:**
- Create: `frontend/src/components/MealRecommendationPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes `Dashboard.mealRecommendations` and the current local hour.
- Produces `/meal/today`, `nextMealRecommendation(recommendations, now)`, and icon-safe home summary cards.

- [ ] Add failing tests proving the food card shows next-meal food/kcal, navigates to the meal page, and never opens the record drawer.
- [ ] Add failing tests for three meal cards, next-meal emphasis, and missing recommendation empty state.
- [ ] Verify the new tests fail for absent UI behavior.
- [ ] Add the Bootstrap TypeScript contract, pure next-meal selector, route, and meal page.
- [ ] Rebuild home cards so icon, title, content, and arrow occupy independent layout areas.
- [ ] Change the plan exercise card to 42/58 columns with stable image aspect ratio and tighten the week-strip gap.
- [ ] Run focused tests and commit the page/layout slice.

### Task 3: Pure workout session engine

**Files:**
- Create: `frontend/src/workout/workoutSession.ts`
- Create: `frontend/src/workout/workoutSession.test.ts`

**Interfaces:**
- Produces `WorkoutSessionState`, `createWorkoutSession(exercises)`, and `advanceWorkoutSession(state)`.
- States are `READY`, `COUNTDOWN`, `EXERCISE`, `REST`, and `COMPLETED`; state includes exercise index, set index, remaining seconds, rest kind, and elapsed seconds.

- [ ] Write failing pure tests for 3-second preparation, exercise countdown, 20-second set rest, 30-second exercise rest, and terminal completion.
- [ ] Run the focused test and confirm missing module failure.
- [ ] Implement the smallest immutable transition engine that passes the sequence tests.
- [ ] Add tests for next/previous transitions and completion boundaries.
- [ ] Refactor duplicated transition construction while keeping all tests green, then commit.

### Task 4: Immersive workout player and synchronized voice

**Files:**
- Create: `frontend/src/components/WorkoutPlayer.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes a plan, exercise media, `api.completeWorkout`, and `reload`.
- Produces `/workout/:planId`; starting begins countdown, each tick advances the pure engine, and completion persists once.

- [ ] Add failing UI tests that plan start navigates to the player and shows READY rather than speaking only once.
- [ ] Add fake-timer tests for preparation speech, action/start cue, final 3-second counting, rest cue, pause/resume, mute, next, and completion API exactly once.
- [ ] Verify failures are caused by the missing route/player.
- [ ] Implement the immersive player, interval lifecycle, speech cancellation/queueing, progress, controls, next-action preview, and exit confirmation.
- [ ] On COMPLETED, await completion API and reload; expose retry when persistence fails.
- [ ] Run focused tests, accessibility queries, and commit the player slice.

### Task 5: Full verification and handoff

**Files:**
- Modify: `docs/acceptance/fitness-mobile-ui-refinement.md`
- Modify: `progress.md`
- Modify: `task_plan.md`

**Interfaces:**
- Produces reproducible acceptance evidence and a running local formal build.

- [ ] Run `npm test -- --run`, `npm run typecheck`, `npm run lint`, and `npm run build` in `frontend`.
- [ ] Run `./mvnw -pl starter -am verify -q` and confirm Testcontainers applies V3 from an empty database.
- [ ] Run `git diff --check` and inspect the final diff for frontend mock recommendation data.
- [ ] Restart the formal backend with the current JAR, keep Vite running, and verify authenticated Bootstrap contains current-day recommendations.
- [ ] Record exact results, known browser speech constraints, and remaining Agent Runtime dependency; commit the acceptance update.
