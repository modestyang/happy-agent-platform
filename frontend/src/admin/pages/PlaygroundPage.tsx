import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Bot, CheckCircle2, CircleDashed, LoaderCircle, Rocket } from 'lucide-react';

import { ApiError, admin } from '../api';
import { ChatMarkdown } from '../../components/ChatMarkdown';
import { PageHeading } from '../components/PageHeading';

const AGENT_KEY = 'fitness.coach';

type ChatMessage = { role: 'user' | 'assistant'; content: string };

export function PlaygroundPage() {
  const [message, setMessage] = useState('');
  const [trace, setTrace] = useState<ChatMessage[]>([]);
  const [sendError, setSendError] = useState('');
  const [sending, setSending] = useState(false);
  const [ready, setReady] = useState(false);
  const [readyReason, setReadyReason] = useState('');
  const [publishedVersion, setPublishedVersion] = useState(0);
  const [latestRunId, setLatestRunId] = useState('');
  const bodyRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    admin.snapshot().then((snapshot) => {
      const agent = snapshot.agents.find((item) => item.agentKey === AGENT_KEY);
      const providerReady = Boolean(agent && snapshot.providers.some((item) => item.providerKey === agent.providerKey && item.configured));
      const required = agent ? [
        ...agent.toolKeys.map((key) => ['TOOL', key]),
        ...agent.skillKeys.map((key) => ['SKILL', key]),
        ...agent.hookKeys.map((key) => ['HOOK', key]),
      ] : [];
      const capabilitiesReady = required.every(([type, key]) => snapshot.components.some((item) => item.type === type && item.componentKey === key && item.status === 'AVAILABLE'));
      setReady(Boolean(providerReady && capabilitiesReady && agent && agent.publishedVersion > 0));
      setPublishedVersion(agent?.publishedVersion ?? 0);
      if (!providerReady) setReadyReason(' Provider 凭据未配置');
      else if (!capabilitiesReady) setReadyReason(' 已绑定 Tool、Skill 或 Hook 尚不可用');
      else if (!agent || agent.publishedVersion === 0) setReadyReason(' 尚未发布');
    }).catch((caught) => setReadyReason(String(caught)));
  }, []);

  useEffect(() => { bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight }); }, [trace, sending]);

  async function send() {
    const payload = message.trim();
    if (!payload || sending || !ready) return;
    setSendError('');
    setSending(true);
    setTrace((current) => [...current, { role: 'user', content: payload }]);
    setMessage('');
    try {
      const response2 = await fetch('/api/app/ai/messages', {
        method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: payload }),
      });
      if (!response2.ok) {
        const problem = await response2.json().catch(() => ({}));
        const message = (problem as { detail?: string }).detail ?? '调用失败';
        setTrace((current) => [...current, { role: 'assistant', content: `调用失败：${message}` }]);
        setSendError(message);
        return;
      }
      const json = await response2.json();
      setTrace((current) => [...current, { role: 'assistant', content: json.message ?? '(empty)' }]);
      try {
        const runs = await admin.listRuns({ agent: AGENT_KEY, size: 1 });
        setLatestRunId(runs.items[0]?.runId ?? '');
      } catch {
        // The reply is still real; trace lookup is a separate observability convenience.
      }
    } catch (caught) {
      const message = caught instanceof ApiError && caught.status === 503
        ? '瘦瘦还没接上大模型，请先在 Agent 工作台配置 Provider。'
        : caught instanceof Error ? caught.message : '调用失败';
      setSendError(message);
      setTrace((current) => [...current, { role: 'assistant', content: `调用失败：${message}` }]);
    } finally { setSending(false); }
  }

  return <>
    <PageHeading eyebrow="安全调试" title="Agent 调试台" description="仅在运行依赖真实就绪后允许发起测试，不伪造模型回复。" />
    <section className="admin-playground">
      <div className="admin-playground__chat">
        <header><span><Bot /></span><div><strong>fitness.coach</strong><small>v{publishedVersion} · {ready ? '可以开始调试' : `运行依赖尚未就绪${readyReason}`}</small></div><i className={ready ? 'is-online' : ''} /></header>
        <div className="admin-playground__body" ref={bodyRef}>
          {trace.length ? trace.map((item, index) => (
            <div key={`${item.role}-${index}`} className={`admin-playground__bubble ${item.role === 'user' ? 'is-user' : 'is-assistant'}`}>
              <small>{item.role === 'user' ? '你' : '瘦瘦'}</small>
              <ChatMarkdown text={item.content} className="admin-md" />
            </div>
          )) : <div className="admin-empty"><CircleDashed /><strong>等待一次真实对话</strong><p>这里会调用真实 LLM，结果将与后台可观测性数据一起写入数据库。</p></div>}
          {sending && <div className="admin-playground__bubble is-assistant is-thinking"><small>瘦瘦</small><p><LoaderCircle className="is-spin" /> 正在思考…</p></div>}
          {sendError && <p className="admin-form-error"><AlertTriangle />{sendError}</p>}
          {latestRunId && <p className="admin-success-row"><CheckCircle2 />真实 Run 已写入 <Link to={`/admin/runs/${latestRunId}`}>查看 Trace</Link></p>}
        </div>
        <footer>
          <input value={message} onChange={(event) => setMessage(event.target.value)} disabled={!ready || sending} placeholder={ready ? '请输入测试问题' : '完成前置准备后解锁'} onKeyDown={(event) => event.key === 'Enter' && void send()} />
          <button className={ready ? 'admin-primary' : ''} disabled={!ready || sending || !message.trim()} onClick={() => void send()}>
            {sending ? <LoaderCircle className="is-spin" /> : <Rocket />} 发送
          </button>
        </footer>
      </div>
      <aside className="admin-card admin-runtime-check">
        <small>运行前检查</small>
        <h2>{ready ? '依赖已经就绪' : '还有依赖未完成'}</h2>
        <p className={ready ? 'is-ok' : ''}>{ready ? <CheckCircle2 /> : <AlertTriangle />} Provider 已配置密钥</p>
        <p className={ready ? 'is-ok' : ''}>{ready ? <CheckCircle2 /> : <AlertTriangle />} 已绑定 Tool、Skill、Hook 均可用</p>
        <p className={publishedVersion ? 'is-ok' : ''}>{publishedVersion ? <CheckCircle2 /> : <AlertTriangle />} 已发布 Agent 版本</p>
      </aside>
    </section>
  </>;
}
