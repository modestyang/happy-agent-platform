import type { TraceEvent } from '../api';

export type ExecutionItem = {
  key: string;
  kind: 'tool' | 'skill' | 'approval' | 'system' | 'error' | 'success';
  label: string;
  detail: string;
  payload?: Record<string, unknown>;
  status: 'running' | 'completed' | 'failed' | 'waiting' | 'info';
  occurredAt: string;
};

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined;

const text = (value: unknown): string => typeof value === 'string' ? value : '';

const normalizedLabel = (event: TraceEvent) => event.detail.trim() || event.title.trim() || event.type;

const toolKey = (payload: Record<string, unknown>, fallback: string) =>
  text(payload.toolCallId) || text(payload.toolName) || text(payload.toolKey) || fallback;

function blockItem(event: TraceEvent, runningTools: Map<string, number>, items: ExecutionItem[]): boolean {
  const blockType = text(event.payload.type);
  const block = asRecord(event.payload.block);
  const blockId = text(event.payload.blockId);
  if (event.type === 'BLOCK_STARTED') {
    if (blockType === 'TEXT') return true;
    if (blockType === 'THINKING') {
      items.push({ key: String(event.sequence), kind: 'system', label: '模型思考', detail: '', status: 'running', occurredAt: event.occurredAt });
      runningTools.set(blockId, items.length - 1);
      return true;
    }
    if (blockType === 'TOOL_CALL') {
      const key = toolKey(block ?? {}, blockId);
      runningTools.set(key, items.length);
      items.push({
        key: String(event.sequence), kind: 'tool', label: text(block?.toolName) || '工具调用', detail: text(block?.input),
        payload: text(block?.input) ? { input: block?.input } : undefined, status: 'running', occurredAt: event.occurredAt,
      });
      return true;
    }
    if (blockType === 'TOOL_RESULT') {
      const key = toolKey(block ?? {}, blockId);
      const index = runningTools.get(key);
      const detail = text(block?.output);
      if (index !== undefined) {
        items[index] = { ...items[index], detail, payload: detail ? { ...(items[index].payload ?? {}), result: detail } : items[index].payload, status: 'completed' };
      } else {
        items.push({ key: String(event.sequence), kind: 'tool', label: text(block?.toolName) || '工具调用', detail, payload: detail ? { result: detail } : undefined, status: 'completed', occurredAt: event.occurredAt });
      }
      return true;
    }
  }
  if (event.type === 'BLOCK_DELTA') {
    const index = runningTools.get(blockId);
    if (index !== undefined && items[index]?.label === '模型思考') {
      items[index] = { ...items[index], detail: `${items[index].detail}${text(event.payload.delta)}` };
    }
    return true;
  }
  if (event.type === 'BLOCK_COMPLETED') {
    const index = runningTools.get(blockId);
    if (index !== undefined && items[index]?.label === '模型思考') {
      items[index] = { ...items[index], status: 'completed' };
      runningTools.delete(blockId);
    }
    return true;
  }
  return false;
}

export function groupTraceEvents(events: TraceEvent[]): ExecutionItem[] {
  const items: ExecutionItem[] = [];
  const runningTools = new Map<string, number>();

  for (const event of events) {
    if (event.type === 'TOKEN') continue;
    if (blockItem(event, runningTools, items)) continue;
    const label = normalizedLabel(event);
    const key = toolKey(event.payload, label);
    if (event.type === 'TOOL_STARTED') {
      runningTools.set(key, items.length);
      items.push({ key: String(event.sequence), kind: 'tool', label: text(event.payload.toolName) || text(event.payload.toolKey) || label, detail: event.detail, payload: event.payload, status: 'running', occurredAt: event.occurredAt });
      continue;
    }
    if (event.type === 'TOOL_RESULT' || event.type === 'TOOL_COMPLETED') {
      const index = runningTools.get(key);
      if (index !== undefined) {
        items[index] = { ...items[index], detail: event.detail || items[index].detail, payload: { ...(items[index].payload ?? {}), ...event.payload }, status: 'completed' };
        runningTools.delete(key);
      } else {
        items.push({ key: String(event.sequence), kind: 'tool', label: text(event.payload.toolName) || text(event.payload.toolKey) || label, detail: event.detail, payload: event.payload, status: 'completed', occurredAt: event.occurredAt });
      }
      continue;
    }
    if (event.type === 'TOOL_FAILED') {
      items.push({ key: String(event.sequence), kind: 'tool', label: text(event.payload.toolName) || text(event.payload.toolKey) || label, detail: text(event.payload.errorMessage) || event.detail, payload: event.payload, status: 'failed', occurredAt: event.occurredAt });
      continue;
    }

    const kind = event.type.includes('SKILL') ? 'skill'
      : event.type.includes('APPROVAL') || event.type.includes('CONFIRMATION') ? 'approval'
        : event.type === 'RUN_FAILED' || event.type.endsWith('_FAILED') ? 'error'
          : event.type === 'RUN_COMPLETED' || event.type === 'REPLY_ENDED' ? 'success' : 'system';
    const status = kind === 'error' ? 'failed'
      : kind === 'approval' && !event.type.includes('RECEIVED') ? 'waiting'
        : kind === 'success' || event.type.endsWith('_COMPLETED') ? 'completed' : 'info';
    items.push({ key: String(event.sequence), kind, label: event.title || event.type, detail: event.detail, payload: Object.keys(event.payload).length ? event.payload : undefined, status, occurredAt: event.occurredAt });
  }
  return items;
}
