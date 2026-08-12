import { type FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, ChevronLeft, ChevronRight, CircleUserRound, Clock3, LoaderCircle, MessageCircle, RefreshCw, Search, Workflow } from 'lucide-react';

import { ChatMarkdown } from '../../components/ChatMarkdown';
import { admin, type ConversationDetail, type ConversationPage, type ConversationSummary, type RunTrace } from '../api';
import { ExecutionDetails } from '../components/ExecutionDetails';
import { PageHeading } from '../components/PageHeading';
import './conversation-trace.css';

const displayTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false });
const PAGE_SIZE = 10;
const EMPTY_PAGE: ConversationPage = { items: [], page: 0, size: PAGE_SIZE, hasNext: false };

export function ConversationTracePage() {
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [result, setResult] = useState<ConversationPage>(EMPTY_PAGE);
  const [selected, setSelected] = useState<ConversationSummary>();
  const [detail, setDetail] = useState<ConversationDetail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load(nextQuery: string, nextPage: number) {
    setLoading(true); setError('');
    try {
      const next = await admin.listConversations(nextQuery, nextPage, PAGE_SIZE);
      setQuery(nextQuery);
      setResult(next);
      const first = next.items[0];
      setSelected(first);
      setDetail(undefined);
      setDetail(first ? await admin.conversationTrace(first.conversationId) : undefined);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '最近会话加载失败');
    } finally { setLoading(false); }
  }

  useEffect(() => { void load('', 0); }, []);

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    void load(queryInput.trim(), 0);
  }

  async function select(conversation: ConversationSummary) {
    setLoading(true); setError('');
    setSelected(conversation); setDetail(undefined);
    try { setDetail(await admin.conversationTrace(conversation.conversationId)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : '会话详情加载失败'); }
    finally { setLoading(false); }
  }

  return <>
    <PageHeading eyebrow="Observability" title="会话 Trace" description="按最近会话查看真实对话，并在 Agent 回复内展开工具与技能执行过程。" action={<button className="admin-secondary" disabled={loading} onClick={() => void load(query, result.page)}>{loading ? <LoaderCircle className="is-spin" /> : <RefreshCw />}刷新</button>} />
    <form className="admin-trace-search" role="search" onSubmit={submitSearch}>
      <label><Search /><input type="search" aria-label="搜索会话" placeholder="搜索用户名、用户 ID 或会话 ID" value={queryInput} onChange={(event) => setQueryInput(event.target.value)} /></label>
      <button type="submit" className="admin-primary" disabled={loading}>搜索</button>
    </form>
    {error && <p className="admin-form-error">{error}</p>}
    <section className="admin-conversation-trace">
      <aside className="admin-conversation-list">
        <header><div><small>Recent conversations</small><h2>最近会话</h2></div><span>{result.items.length}</span></header>
        <div className="admin-conversation-list__items">
          {!loading && result.items.length === 0 && <div className="admin-empty"><MessageCircle /><strong>{query ? '未找到匹配会话' : '还没有真实会话'}</strong><p>{query ? '请修改用户名、用户 ID 或会话 ID 后重试。' : '在健身应用中发起一次 AI 对话后，这里会自动出现。'}</p></div>}
          {result.items.map((item) => <button key={item.conversationId} disabled={loading} className={selected?.conversationId === item.conversationId ? 'is-selected' : ''} onClick={() => void select(item)}>
            <span><b>{item.title || '未命名对话'}</b><small>{item.agentKey}</small><span className="admin-conversation-identity"><strong>{item.username}</strong><code>{item.userId}</code><code>{item.conversationId}</code></span></span><ChevronRight />
            <footer><i><MessageCircle /> {item.messageCount} 条消息</i><i><Workflow /> {item.runCount} 次运行</i><time>{displayTime(item.lastMessageAt)}</time></footer>
          </button>)}
        </div>
        <footer className="admin-conversation-pagination" aria-label="会话分页">
          <button type="button" disabled={loading || result.page === 0} onClick={() => void load(query, result.page - 1)}><ChevronLeft />上一页</button>
          <span>第 {result.page + 1} 页</span>
          <button type="button" disabled={loading || !result.hasNext} onClick={() => void load(query, result.page + 1)}>下一页<ChevronRight /></button>
        </footer>
      </aside>
      <article className="admin-conversation-detail">
        {!detail && <div className="admin-empty admin-empty--large"><Clock3 /><strong>{loading ? '正在加载会话' : '选择一个会话'}</strong><p>对话和执行过程会在这里按真实交流顺序展示。</p></div>}
        {detail && selected && <>
          <header><div><small>{detail.conversation.agentKey}</small><h2>{detail.conversation.title || '未命名对话'}</h2><p>开始于 {displayTime(detail.conversation.startedAt)} · {detail.messages.length} 条消息</p><div className="admin-conversation-detail__identity"><span><b>用户名</b><code>{selected.username}</code></span><span><b>用户 ID</b><code>{selected.userId}</code></span><span><b>会话 ID</b><code>{selected.conversationId}</code></span></div></div><span>{detail.conversation.status}</span></header>
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
