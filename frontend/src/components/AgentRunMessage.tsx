import { AiContentRenderer, isRenderableConfirmationBlock, type ConfirmationRenderState } from './AiContentRenderer';
import { ChatMarkdown } from './ChatMarkdown';
import { ConfirmationCard } from './ContentSurface';

export type TrainingPlanProposal = {
  scope: 'DAY' | 'MULTI_DAY' | 'WEEK';
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
  blockTypes?: Record<string, string>;
  lastTextBlockDelta?: string;
  approval?: RunApproval;
  blocks?: unknown[];
  deciding?: boolean;
  decidingApprovalId?: string;
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

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

const USER_PROGRESS_STAGES = [
  '正在理解你的需求',
  '正在查看相关记录',
  '正在整理建议',
  '计划已准备好，请核对是否保存',
] as const;

const isUserProgressStage = (value: string): value is typeof USER_PROGRESS_STAGES[number] =>
  USER_PROGRESS_STAGES.includes(value as typeof USER_PROGRESS_STAGES[number]);

function appendProgress(message: AgentRunUiMessage, next: string): AgentRunUiMessage {
  if (!isUserProgressStage(next) || message.progress?.includes(next)) return message;
  const progress = [...(message.progress ?? []).filter(isUserProgressStage), next]
    .sort((left, right) => USER_PROGRESS_STAGES.indexOf(left) - USER_PROGRESS_STAGES.indexOf(right));
  return { ...message, progress };
}

function normalizeProposal(value: unknown): TrainingPlanProposal | undefined {
  const proposal = asRecord(value);
  if (!proposal
    || (proposal.scope !== 'DAY' && proposal.scope !== 'MULTI_DAY' && proposal.scope !== 'WEEK')
    || !Array.isArray(proposal.days)) return undefined;
  const days = proposal.days.flatMap((rawDay) => {
    const day = asRecord(rawDay);
    if (!day) return [];
    const date = text(day.date) || text(day.scheduledFor);
    if (!date) return [];
    const rawExercises = Array.isArray(day.exercises) ? day.exercises : [];
    const exercises = rawExercises.flatMap((rawExercise, index) => {
      const exercise = asRecord(rawExercise);
      const exerciseId = text(exercise?.exerciseId) || text(exercise?.id);
      return exerciseId ? [{ exerciseId, name: text(exercise?.name).trim() || `动作 ${index + 1}` }] : [];
    });
    if (!exercises.length && Array.isArray(day.exerciseIds)) {
      exercises.push(...day.exerciseIds
        .filter((id): id is string => typeof id === 'string')
        .map((exerciseId, index) => ({ exerciseId, name: `动作 ${index + 1}` })));
    }
    return [{ date, title: text(day.title) || '训练计划', estimatedMinutes: Number(day.estimatedMinutes) || 0, exercises }];
  });
  return { scope: proposal.scope, days };
}

function runEventSummary(type: string): string {
  if (type === 'MODEL_CALL_COMPLETED') return '正在整理建议';
  if (type.startsWith('TOOL_')) return '正在查看相关记录';
  if (type === 'RUN_WAITING_APPROVAL' || type === 'CONFIRMATION_REQUIRED') return '计划已准备好，请核对是否保存';
  if (type === 'MODEL_CALL_STARTED'
    || type === 'CONTEXT_ASSEMBLED'
    || type === 'MEMORY_LOADED'
    || type === 'SKILL_DISCOVERED'
    || type === 'SKILL_LOADED'
    || type === 'HOOK_STARTED'
    || type === 'HOOK_COMPLETED') return '正在理解你的需求';
  return '';
}

/** Applies framework-neutral durable events to the compact chat representation. */
export function applyAgentRunEvent(message: AgentRunUiMessage, event: AgentRunEvent): AgentRunUiMessage {
  if (event.type === 'TEXT_DELTA') {
    const delta = text(event.data.delta);
    if (delta && delta === message.lastTextBlockDelta) return { ...message, lastTextBlockDelta: undefined };
    return { ...message, content: message.content + delta, lastTextBlockDelta: undefined, ...(delta ? { progress: undefined } : {}) };
  }
  if (event.type === 'RUN_STATE') {
    const summary = text(event.data.summary);
    if (isUserProgressStage(summary)) return appendProgress(message, summary);
    if (event.data.status === 'WAITING_APPROVAL') return appendProgress(message, '计划已准备好，请核对是否保存');
    if (event.data.status === 'RUNNING') return appendProgress(message, '正在理解你的需求');
    return message;
  }
  if (event.type === 'APPROVAL') {
    const incoming = event.data as RunApproval;
    const proposal = normalizeProposal(event.data.proposal);
    return {
      ...message,
      deciding: false,
      decidingApprovalId: undefined,
      progress: undefined,
      approval: { ...(message.approval ?? incoming), ...incoming, ...(proposal ? { proposal } : {}) },
    };
  }
  if (event.type === 'STRUCTURED_COMPONENT') {
    if (event.data.block === undefined) return message;
    return { ...message, progress: undefined, blocks: [...(message.blocks ?? []), event.data.block] };
  }
  if (event.type !== 'RUN_EVENT') return message;
  const eventType = text(event.data.eventType);
  if (eventType === 'BLOCK_STARTED') {
    const blockId = text(event.data.blockId);
    const blockType = text(event.data.type);
    const next = { ...message, blockTypes: { ...(message.blockTypes ?? {}), ...(blockId ? { [blockId]: blockType } : {}) } };
    if (blockType === 'THINKING') return appendProgress(next, '正在整理建议');
    if (blockType === 'TOOL_CALL' || blockType === 'TOOL_RESULT') return appendProgress(next, '正在查看相关记录');
    return next;
  }
  if (eventType === 'BLOCK_DELTA') {
    const blockId = text(event.data.blockId);
    const delta = text(event.data.delta);
    const blockType = message.blockTypes?.[blockId];
    if (blockType === 'TEXT') return { ...message, content: message.content + delta, lastTextBlockDelta: delta, ...(delta ? { progress: undefined } : {}) };
    return message;
  }
  return appendProgress(message, runEventSummary(eventType));
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
  const progress = (message.progress ?? []).filter(isUserProgressStage);
  const approval = message.approval;
  const confirmationStates: Record<string, ConfirmationRenderState> = {};
  if (approval) confirmationStates[approval.approvalId] = { status: approval.status };
  if (message.deciding && message.decidingApprovalId) {
    confirmationStates[message.decidingApprovalId] = {
      ...confirmationStates[message.decidingApprovalId],
      deciding: true,
    };
  }
  const hasMatchingStructuredConfirmation = Boolean(approval && message.blocks?.some((block) => {
    return isRenderableConfirmationBlock(block) && block.confirmationId === approval.approvalId;
  }));
  const replyStarted = Boolean(message.content.trim() || message.blocks?.length || approval);
  return <div className={className}>
    {!replyStarted && progress.length > 0 && <div className="run-progress" role="status" aria-live="polite"><i aria-hidden="true" /><span>{progress.at(-1)}</span></div>}
    <ChatMarkdown text={message.content} className={markdownClassName} />
    {message.blocks?.map((block, index) => <AiContentRenderer
      key={`structured-${index}`}
      block={block}
      context={{
        confirmationStates,
        onCancel: onDecision ? (confirmationId) => onDecision(confirmationId, 'REJECT') : undefined,
        onConfirm: onDecision ? (confirmationId) => onDecision(confirmationId, 'APPROVE') : undefined,
      }}
    />)}
    {approval && !hasMatchingStructuredConfirmation && <ConfirmationCard
      deciding={message.deciding}
      model={{
        id: approval.approvalId,
        title: approval.title ?? '确认操作',
        scopeLabel: approval.proposal
          ? approval.proposal.scope === 'WEEK'
            ? '未来 7 天'
            : approval.proposal.scope === 'MULTI_DAY' ? '多个训练日' : '当天'
          : undefined,
        confirmLabel: '确认并保存',
        cancelLabel: '暂不保存',
        status: approval.status,
      }}
      onCancel={onDecision ? () => onDecision(approval.approvalId, 'REJECT') : undefined}
      onConfirm={onDecision ? () => onDecision(approval.approvalId, 'APPROVE') : undefined}
    >
      {approval.proposal && <div>{approval.proposal.days.map((day) => <article key={day.date}><b>{day.date} · {day.title}</b><small>{day.estimatedMinutes} 分钟 · {day.exercises.map((item) => item.name).join(' / ')}</small></article>)}</div>}
    </ConfirmationCard>}
  </div>;
}
