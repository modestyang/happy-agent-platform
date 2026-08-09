# Agent Conversation and Trace Design

## Goal

Make every AI interaction belong to a durable user conversation, supply recent conversation turns to the runtime, and let the developer workbench inspect the chain from user to conversation, message, run, and trace.

## Boundaries

- `fitness_sessions` remains authentication state only; it is not reused as a conversation record.
- Business facts (goal, body records, plan, diet) remain long-lived context from the fitness service.
- Conversation messages are short-lived conversational context. A conversation is reusable for 24 hours after its last message; a later request starts a new one.
- An Agent Run belongs to exactly one user conversation. The run's events remain in `agent_run_events`.
- This is a pre-release schema. The database may be reset and seeded; no historical migration/backfill is required.

## Data Model

`agent_conversations` stores the stable session identity: `conversation_id`, `user_id`, `agent_key`, `title`, `status`, `started_at`, `last_message_at`, and `closed_at`.

`agent_conversation_messages` stores complete turns: `message_id`, `conversation_id`, nullable `run_id`, `role` (`USER`, `ASSISTANT`, `SYSTEM`), `content`, and `created_at`.

`agent_runs` gains mandatory `user_id` and `conversation_id` foreign-key columns. The repository creates the conversation and user message before creating the run; it completes the assistant message after the model call, or records the terminal failure/blocked response.

## Runtime Flow

1. Resolve an active 24-hour conversation for `(user_id, fitness.coach)` or create one.
2. Persist the incoming user message and create the Run with the same conversation id.
3. Load the last 20 persisted user/assistant messages, excluding the incoming turn that is already represented by the current prompt.
4. Build model messages as system prompt, verified business/tool facts, recent historical turns, and the current enriched user request.
5. Persist the final assistant, blocked, or failure message and finish the Run/Trace.

The context window is bounded to 20 turns. This keeps a personal deployment predictable and preserves enough continuity for normal coaching conversations without adding Redis or an external memory product.

## Workbench Experience

The navigation includes `Trace` below the Playground. The Trace page starts with a user-id search and displays that user's conversations (agent, title, last activity, message/run count, state). Selecting a conversation displays messages and associated runs. Selecting a run reuses the existing detail page for event-level Trace.

## Error Handling and Tests

The runtime must still record a terminal assistant-side message on blocked or failed model calls. Repository tests cover active-conversation reuse, expiry, message order, and conversation-to-run linkage. Runtime integration tests prove the model request receives earlier turns. Frontend tests cover menu visibility, user search, conversation selection, and trace navigation.
