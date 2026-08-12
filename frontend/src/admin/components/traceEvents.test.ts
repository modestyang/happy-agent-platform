import { describe, expect, it } from 'vitest';

import { groupTraceEvents } from './traceEvents';

describe('groupTraceEvents', () => {
  it('merges a completed tool call and hides token events', () => {
    const items = groupTraceEvents([
      { sequence: 1, type: 'TOOL_STARTED', title: '调用 Tool', detail: 'fitness.plan.save', payload: {}, occurredAt: '2026-08-11T00:00:00Z' },
      { sequence: 2, type: 'TOKEN', title: 'model output', detail: 'duplicate', payload: {}, occurredAt: '2026-08-11T00:00:01Z' },
      { sequence: 3, type: 'TOOL_COMPLETED', title: 'Tool 返回', detail: 'fitness.plan.save', payload: {}, occurredAt: '2026-08-11T00:00:02Z' },
    ]);

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ kind: 'tool', label: 'fitness.plan.save', status: 'completed' });
  });

  it('keeps framework block input and output with the matching tool turn', () => {
    const items = groupTraceEvents([
      { sequence: 1, type: 'BLOCK_STARTED', title: 'BLOCK_STARTED', detail: 'block=call-1', payload: { blockId: 'call-1', type: 'TOOL_CALL', block: { toolCallId: 'tool-1', toolName: 'fitness_plan_save', input: '{"scope":"DAY"}' } }, occurredAt: '2026-08-11T00:00:00Z' },
      { sequence: 2, type: 'BLOCK_STARTED', title: 'BLOCK_STARTED', detail: 'block=result-1', payload: { blockId: 'result-1', type: 'TOOL_RESULT', block: { toolCallId: 'tool-1', toolName: 'fitness_plan_save', output: '{"planIds":["p1"]}' } }, occurredAt: '2026-08-11T00:00:01Z' },
    ]);

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ kind: 'tool', label: 'fitness_plan_save', status: 'completed', payload: { input: '{"scope":"DAY"}', result: '{"planIds":["p1"]}' } });
  });

  it('keeps provider thinking details in the developer trace', () => {
    const items = groupTraceEvents([
      { sequence: 1, type: 'BLOCK_STARTED', title: 'BLOCK_STARTED', detail: 'block=thinking-1', payload: { blockId: 'thinking-1', type: 'THINKING' }, occurredAt: '2026-08-11T00:00:00Z' },
      { sequence: 2, type: 'BLOCK_DELTA', title: 'BLOCK_DELTA', detail: '', payload: { blockId: 'thinking-1', delta: 'Inspect the tool arguments.' }, occurredAt: '2026-08-11T00:00:01Z' },
      { sequence: 3, type: 'BLOCK_COMPLETED', title: 'BLOCK_COMPLETED', detail: 'block=thinking-1', payload: { blockId: 'thinking-1' }, occurredAt: '2026-08-11T00:00:02Z' },
    ]);

    expect(items).toEqual([
      expect.objectContaining({
        kind: 'system',
        label: '模型思考',
        detail: 'Inspect the tool arguments.',
        status: 'completed',
      }),
    ]);
  });
});
