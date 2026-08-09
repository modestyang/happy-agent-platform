import { useState, type FormEvent } from 'react';
import { ChevronRight, Clock3, MessageCircle, Search, Workflow } from 'lucide-react';

import { admin, type ConversationDetail, type ConversationSummary } from '../api';
import { PageHeading } from '../components/PageHeading';

const displayTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false });

export function ConversationTracePage() {
  const [userId, setUserId] = useState('');
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [detail, setDetail] = useState<ConversationDetail>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function search(event: FormEvent) {
    event.preventDefault();
    if (!userId.trim()) return;
    setLoading(true); setError(''); setDetail(undefined);
    try { setItems(await admin.listConversations(userId.trim())); }
    catch (caught) { setItems([]); setError(caught instanceof Error ? caught.message : '查询失败'); }
    finally { setLoading(false); }
  }

  async function select(conversation: ConversationSummary) {
    setLoading(true); setError('');
    try { setDetail(await admin.conversationTrace(conversation.conversationId)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : '会话详情加载失败'); }
    finally { setLoading(false); }
  }

  return <>
    <PageHeading eyebrow="Observability" title="会话 Trace" description="按用户查看 AI 对话、关联 Run 与逐步执行 Trace。" />
    <form className="admin-trace-search" onSubmit={search}>
      <label><Search /><input aria-label="用户 ID" value={userId} onChange={(event) => setUserId(event.target.value)} placeholder="输入用户 UUID" /></label>
      <button className="admin-primary" disabled={loading || !userId.trim()}>{loading ? '查询中…' : '查询会话'}</button>
    </form>
    {error && <p className="admin-form-error">{error}</p>}
    <section className="admin-conversation-trace">
      <aside className="admin-conversation-list">
        <header><div><small>Conversation list</small><h2>会话列表</h2></div><span>{items.length}</span></header>
        {!loading && items.length === 0 && <div className="admin-empty"><MessageCircle /><strong>输入用户 ID 后查询</strong><p>会话由用户消息、Agent 回复和每次 Run 自动沉淀。</p></div>}
        {items.map((item) => <button key={item.conversationId} className={detail?.conversation.conversationId === item.conversationId ? 'is-selected' : ''} onClick={() => void select(item)}>
          <span><b>{item.title || '未命名对话'}</b><small>{item.agentKey}</small></span><ChevronRight />
          <footer><i><MessageCircle /> {item.messageCount} 条消息</i><i><Workflow /> {item.runCount} 次运行</i><time>{displayTime(item.lastMessageAt)}</time></footer>
        </button>)}
      </aside>
      <article className="admin-conversation-detail">
        {!detail && <div className="admin-empty admin-empty--large"><Clock3 /><strong>选择一个会话</strong><p>这里会按时间展示原始对话，并可继续打开对应的 Run Trace。</p></div>}
        {detail && <>
          <header><div><small>{detail.conversation.agentKey}</small><h2>{detail.conversation.title || '未命名对话'}</h2><p>用户 {detail.conversation.userId} · 开始于 {displayTime(detail.conversation.startedAt)}</p></div><span>{detail.conversation.status}</span></header>
          <div className="admin-conversation-messages">
            {detail.messages.map((message) => <div key={message.messageId} className={message.role === 'USER' ? 'is-user' : 'is-assistant'}><small>{message.role === 'USER' ? '用户' : 'Agent'} · {displayTime(message.createdAt)}</small><p>{message.content}</p></div>)}
          </div>
          <section className="admin-conversation-runs"><h3>关联运行</h3>{detail.runs.map((run) => <a key={run.runId} href={`/admin/runs/${run.runId}?from=trace`}><span><b>{run.status}</b><small>{run.modelKey || '未记录模型'} · {displayTime(run.startedAt)}</small></span><ChevronRight /></a>)}</section>
        </>}
      </article>
    </section>
  </>;
}
