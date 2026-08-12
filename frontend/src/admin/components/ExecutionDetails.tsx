import { Check, CircleAlert, Clock3, LoaderCircle, ShieldCheck, Sparkles, Wrench } from 'lucide-react';

import type { TraceEvent } from '../api';
import { groupTraceEvents } from './traceEvents';

function payloadText(payload: Record<string, unknown> | undefined) {
  if (!payload) return '';
  return JSON.stringify(payload, null, 2);
}

export function ExecutionDetails({ events, defaultOpen = false }: { events: TraceEvent[]; defaultOpen?: boolean }) {
  const items = groupTraceEvents(events);
  if (!items.length) return null;

  return <details className="admin-execution" open={defaultOpen}>
    <summary><span><Sparkles />执行过程</span><small>{items.length} 个步骤</small></summary>
    <div className="admin-execution__items">
      {items.map((item) => {
        const Icon = item.kind === 'tool' ? Wrench : item.kind === 'approval' ? ShieldCheck : item.status === 'failed' ? CircleAlert : item.status === 'running' ? LoaderCircle : item.status === 'waiting' ? Clock3 : Check;
        return <article key={item.key} className={`admin-execution__item is-${item.status}`}>
          <span><Icon className={item.status === 'running' ? 'is-spin' : ''} /></span>
          <div><strong>{item.label}</strong>{item.detail && item.detail !== item.label && <p>{item.detail}</p>}{item.payload && <details className="admin-execution__payload"><summary>查看上下文与数据</summary><pre>{payloadText(item.payload)}</pre></details>}</div>
          <time>{new Date(item.occurredAt).toLocaleTimeString('zh-CN', { hour12: false })}</time>
        </article>;
      })}
    </div>
  </details>;
}
