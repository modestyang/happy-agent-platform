import type { TraceEvent } from '../api';

export type ExecutionItem = {
  key: string;
  kind: 'tool' | 'skill' | 'approval' | 'system' | 'error' | 'success';
  label: string;
  detail: string;
  status: 'running' | 'completed' | 'failed' | 'waiting' | 'info';
  occurredAt: string;
};

const normalizedLabel = (event: TraceEvent) => event.detail.trim() || event.title.trim() || event.type;

export function groupTraceEvents(events: TraceEvent[]): ExecutionItem[] {
  const items: ExecutionItem[] = [];
  const runningTools = new Map<string, number>();

  for (const event of events) {
    if (event.type === 'TOKEN') continue;
    const label = normalizedLabel(event);
    if (event.type === 'TOOL_STARTED') {
      runningTools.set(label, items.length);
      items.push({ key: String(event.sequence), kind: 'tool', label, detail: event.title, status: 'running', occurredAt: event.occurredAt });
      continue;
    }
    if (event.type === 'TOOL_COMPLETED') {
      const index = runningTools.get(label);
      if (index !== undefined) {
        items[index] = { ...items[index], detail: event.title, status: 'completed' };
        runningTools.delete(label);
      } else {
        items.push({ key: String(event.sequence), kind: 'tool', label, detail: event.title, status: 'completed', occurredAt: event.occurredAt });
      }
      continue;
    }

    const kind = event.type.includes('SKILL') ? 'skill'
      : event.type.includes('APPROVAL') ? 'approval'
        : event.type === 'RUN_FAILED' ? 'error'
          : event.type === 'RUN_COMPLETED' ? 'success' : 'system';
    const status = kind === 'error' ? 'failed'
      : kind === 'approval' && !event.type.includes('COMPLETED') ? 'waiting'
        : kind === 'success' || event.type.includes('COMPLETED') ? 'completed' : 'info';
    items.push({ key: String(event.sequence), kind, label: event.title || event.type, detail: event.detail, status, occurredAt: event.occurredAt });
  }
  return items;
}
