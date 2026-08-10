import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, ChevronRight, CircleUserRound, Clock3, LoaderCircle, MessageCircle, RefreshCw, Workflow } from 'lucide-react';

import { ChatMarkdown } from '../../components/ChatMarkdown';
import { admin, type ConversationDetail, type ConversationSummary, type RunTrace } from '../api';
import { ExecutionDetails } from '../components/ExecutionDetails';
import { PageHeading } from '../components/PageHeading';

const displayTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false });

export function ConversationTracePage() {
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [detail, setDetail] = useState<ConversationDetail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load() {
    setLoading(true); setError('');
    try {
      const conversations = await admin.listConversations();
      setItems(conversations);
      if (conversations.length) setDetail(await admin.conversationTrace(conversations[0].conversationId));
      else setDetail(undefined);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '最近会话加载失败');
    } finally { setLoading(false); }
  }

  useEffect(() => { void load(); }, []);

  async function select(conversation: ConversationSummary) {
    setLoading(true); setError('');
    try { setDetail(await admin.conversationTrace(conversation.conversationId)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : '会话详情加载失败'); }
    finally { setLoading(false); }
  }

  return <>
    <PageHeading eyebrow="Observability" title="会话 Trace" description="按最近会话查看真实对话，并在 Agent 回复内展开工具与技能执行过程。" action={<button className="admin-secondary" disabled={loading} onClick={() => void load()}>{loading ? <LoaderCircle className="is-spin" /> : <RefreshCw />}刷新</button>} />
    {error && <p className="admin-form-error">{error}</p>}
    <section className="admin-conversation-trace">
      <aside className="admin-conversation-list">
        <header><div><small>Recent conversations</small><h2>最近会话</h2></div><span>{items.length}</span></header>
        {!loading && items.length === 0 && <div className="admin-empty"><MessageCircle /><strong>还没有真实会话</strong><p>在健身应用中发起一次 AI 对话后，这里会自动出现。</p></div>}
        {items.map((item) => <button key={item.conversationId} className={detail?.conversation.conversationId === item.conversationId ? 'is-selected' : ''} onClick={() => void select(item)}>
          <span><b>{item.title || '未命名对话'}</b><small>{item.agentKey}</small></span><ChevronRight />
          <footer><i><MessageCircle /> {item.messageCount} 条消息</i><i><Workflow /> {item.runCount} 次运行</i><time>{displayTime(item.lastMessageAt)}</time></footer>
        </button>)}
      </aside>
      <article className="admin-conversation-detail">
        {!detail && <div className="admin-empty admin-empty--large"><Clock3 /><strong>{loading ? '正在加载会话' : '选择一个会话'}</strong><p>对话和执行过程会在这里按真实交流顺序展示。</p></div>}
        {detail && <>
          <header><div><small>{detail.conversation.agentKey}</small><h2>{detail.conversation.title || '未命名对话'}</h2><p>开始于 {displayTime(detail.conversation.startedAt)} · {detail.messages.length} 条消息</p></div><span>{detail.conversation.status}</span></header>
          <div className="admin-conversation-messages">
            {detail.messages.map((message) => <div key={message.messageId} className={`admin-conversation-message ${message.role === 'USER' ? 'is-user' : 'is-assistant'}`}>
              <span className="admin-conversation-message__avatar">{message.role === 'USER' ? <CircleUserRound /> : <Bot />}</span>
              <div><small>{message.role === 'USER' ? '用户' : 'Agent'} · {displayTime(message.createdAt)}</small>{message.role === 'USER' ? <p>{message.content}</p> : <><ChatMarkdown text={message.content} className="admin-trace-markdown" />{message.runId && <RunExecution runId={message.runId} />}</>}</div>
            </div>)}
          </div>
        </>}
      </article>
    </section>
  </>;
}

function RunExecution({ runId }: { runId: string }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [trace, setTrace] = useState<RunTrace>();
  const [error, setError] = useState('');

  async function toggle() {
    if (open) { setOpen(false); return; }
    setOpen(true);
    if (trace) return;
    setLoading(true); setError('');
    try { setTrace(await admin.runTrace(runId)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : '执行过程加载失败'); }
    finally { setLoading(false); }
  }

  return <div className="admin-inline-execution">
    <div><button type="button" onClick={() => void toggle()}><Workflow />{open ? '收起执行过程' : '查看执行过程'}</button><Link to={`/admin/runs/${runId}?from=trace`}>打开完整 Run <ChevronRight /></Link></div>
    {open && <>{loading && <p><LoaderCircle className="is-spin" />正在读取执行过程…</p>}{error && <p>{error}</p>}{trace && <ExecutionDetails events={trace.events} defaultOpen />}</>}
  </div>;
}
