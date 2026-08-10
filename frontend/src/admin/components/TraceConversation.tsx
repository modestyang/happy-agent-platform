import { AlertTriangle, Bot, CircleUserRound } from 'lucide-react';

import { ChatMarkdown } from '../../components/ChatMarkdown';
import type { RunTrace } from '../api';
import { ExecutionDetails } from './ExecutionDetails';

export function TraceConversation({ trace }: { trace: RunTrace }) {
  return <section className="admin-run-conversation">
    <header className="admin-run-conversation__meta">
      <span><b>{trace.status}</b><small>{trace.durationMs} ms</small></span>
      <span><b>{trace.modelKey ?? '未记录模型'}</b><small>{trace.promptTokens + trace.completionTokens} tokens</small></span>
      <span><b>{trace.toolCalls} 次工具调用</b><small>{new Date(trace.startedAt).toLocaleString('zh-CN', { hour12: false })}</small></span>
    </header>
    <div className="admin-trace-turn is-user">
      <span className="admin-trace-turn__avatar"><CircleUserRound /></span>
      <div><small>用户</small><p>{trace.inputSummary || '未记录输入内容'}</p></div>
    </div>
    <div className="admin-trace-turn is-assistant">
      <span className="admin-trace-turn__avatar"><Bot /></span>
      <div><small>Agent · {trace.agentKey} v{trace.agentVersion}</small><ChatMarkdown text={trace.outputSummary || (trace.errorMessage ? '本次回复未成功生成。' : '未记录回复内容')} className="admin-trace-markdown" /><ExecutionDetails events={trace.events} /></div>
    </div>
    {trace.errorMessage && <div className="admin-trace-error"><AlertTriangle /><div><strong>{trace.errorCode || '运行失败'}</strong><p>{trace.errorMessage}</p></div></div>}
  </section>;
}
