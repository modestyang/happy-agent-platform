import { CheckCircle2, CircleX, LoaderCircle } from 'lucide-react';
import { ChatMarkdown } from './ChatMarkdown';

export type TrainingPlanProposal = {
  scope: 'DAY' | 'WEEK';
  days: { date: string; title: string; estimatedMinutes: number; exercises: { exerciseId: string; name: string }[] }[];
};

export type RunApproval = {
  approvalId: string;
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  title?: string;
  proposal?: TrainingPlanProposal;
};

export type AgentRunUiMessage = {
  role: 'user' | 'assistant';
  content: string;
  runId?: string;
  progress?: string[];
  approval?: RunApproval;
  deciding?: boolean;
};

export type AgentRunEvent = { type: string; data: Record<string, unknown> };

export function parseSseFrames(remainder: string, chunk: string) {
  const source = remainder + chunk.replace(/\r\n/g, '\n');
  const frames = source.split('\n\n');
  const tail = frames.pop() ?? '';
  const events = frames.flatMap((frame) => {
    const data = frame.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
    if (!data) return [];
    try { return [JSON.parse(data) as AgentRunEvent]; } catch { return []; }
  });
  return { events, remainder: tail };
}

export async function consumeAgentRunStream(
  url: string,
  onEvent: (event: AgentRunEvent) => void,
  signal?: AbortSignal,
) {
  const response = await fetch(url, { credentials: 'include', headers: { Accept: 'text/event-stream' }, signal });
  if (!response.ok || !response.body) throw new Error('无法连接 AI 实时响应');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let remainder = '';
  while (true) {
    const { done, value } = await reader.read();
    const parsed = parseSseFrames(remainder, decoder.decode(value, { stream: !done }));
    remainder = parsed.remainder;
    parsed.events.forEach(onEvent);
    if (done) break;
  }
}

export function AgentRunMessage({
  message,
  className = '',
  markdownClassName,
  onDecision,
}: {
  message: AgentRunUiMessage;
  className?: string;
  markdownClassName?: string;
  onDecision?: (approvalId: string, decision: 'APPROVE' | 'REJECT') => void;
}) {
  const progress = message.progress ?? [];
  const approval = message.approval;
  return <div className={className}>
    <ChatMarkdown text={message.content} className={markdownClassName} />
    {progress.length > 0 && <details className="run-progress">
      <summary>思考与执行</summary>
      <ul>{progress.map((item) => <li key={item}>{item}</li>)}</ul>
    </details>}
    {approval?.proposal && <section className="run-approval" aria-label="训练计划确认">
      <header><strong>{approval.title ?? '确认训练计划'}</strong><small>{approval.proposal.scope === 'WEEK' ? '未来 7 天' : '当天'}</small></header>
      <div>{approval.proposal.days.map((day) => <article key={day.date}><b>{day.date} · {day.title}</b><small>{day.estimatedMinutes} 分钟 · {day.exercises.map((item) => item.name).join(' / ')}</small></article>)}</div>
      {approval.status === 'REQUESTED' ? <footer>
        <button disabled={message.deciding} onClick={() => onDecision?.(approval.approvalId, 'REJECT')}><CircleX />暂不保存</button>
        <button disabled={message.deciding} onClick={() => onDecision?.(approval.approvalId, 'APPROVE')}>{message.deciding ? <LoaderCircle className="is-spin" /> : <CheckCircle2 />}确认并保存</button>
      </footer> : <p className={`run-approval__status is-${approval.status.toLowerCase()}`}>{approval.status === 'APPROVED' ? '已确认并保存' : '已取消，未保存'}</p>}
    </section>}
  </div>;
}
