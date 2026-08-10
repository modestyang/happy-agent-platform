import { describe, expect, it } from 'vitest';

import { groupTraceEvents } from './traceEvents';

describe('groupTraceEvents', () => {
  it('merges a completed tool call and hides token events', () => {
    const items = groupTraceEvents([
      { sequence: 1, type: 'TOOL_STARTED', title: '调用 Tool', detail: 'fitness.plan.save', occurredAt: '2026-08-11T00:00:00Z' },
      { sequence: 2, type: 'TOKEN', title: 'model output', detail: 'duplicate', occurredAt: '2026-08-11T00:00:01Z' },
      { sequence: 3, type: 'TOOL_COMPLETED', title: 'Tool 返回', detail: 'fitness.plan.save', occurredAt: '2026-08-11T00:00:02Z' },
    ]);

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ kind: 'tool', label: 'fitness.plan.save', status: 'completed' });
  });
});
