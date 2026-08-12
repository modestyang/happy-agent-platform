import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Bot, CheckCircle2, CircleDashed, LoaderCircle, Rocket } from 'lucide-react';

import { ApiError, admin, type AgentDraft, type WorkbenchSnapshot } from '../api';
import { AgentRunMessage, applyAgentRunEvent, consumeAgentRunStream, type AgentRunEvent, type AgentRunUiMessage } from '../../components/AgentRunMessage';
import { PageHeading } from '../components/PageHeading';

type ChatMessage = AgentRunUiMessage;

type RuntimeReadiness = {
  ready: boolean;
  reason: string;
  providerReady: boolean;
  capabilitiesReady: boolean;
  published: boolean;
};

function readiness(snapshot: WorkbenchSnapshot | undefined, agent: AgentDraft | undefined): RuntimeReadiness {
  if (!agent) return { ready: false, reason: '没有可调试的已发布 Agent', providerReady: false, capabilitiesReady: false, published: false };
  const providerReady = Boolean(snapshot?.providers.some((item) => item.providerKey === agent.providerKey && item.configured));
  const model = snapshot?.components.find((item) => item.type === 'MODEL' && item.componentKey === agent.modelKey);
  const modelProvider = model?.config.providerKey;
  const modelAligned = typeof modelProvider === 'string' && modelProvider === agent.providerKey;
  const required = [
    ...agent.toolKeys.map((key) => ['TOOL', key]),
    ...agent.skillKeys.map((key) => ['SKILL', key]),
    ...agent.hookKeys.map((key) => ['HOOK', key]),
  ];
  const capabilitiesReady = required.every(([type, key]) => snapshot?.components.some((item) => item.type === type && item.componentKey === key && item.status === 'AVAILABLE'));
  const published = agent.publishedVersion > 0;
  if (!providerReady) return { ready: false, reason: 'Provider 凭据未配置', providerReady, capabilitiesReady, published };
  if (!model) return { ready: false, reason: '未找到已绑定模型', providerReady, capabilitiesReady, published };
  if (!modelAligned) return { ready: false, reason: '模型未绑定当前 Provider', providerReady, capabilitiesReady, published };
  if (!capabilitiesReady) return { ready: false, reason: '已绑定 Tool、Skill 或 Hook 尚不可用', providerReady, capabilitiesReady, published };
  if (!published) return { ready: false, reason: '尚未发布', providerReady, capabilitiesReady, published };
  return { ready: true, reason: '', providerReady, capabilitiesReady, published };
}

export function PlaygroundPage() {
  const [message, setMessage] = useState('');
  const [trace, setTrace] = useState<ChatMessage[]>([]);
  const [sendError, setSendError] = useState('');
  const [sending, setSending] = useState(false);
  const [snapshot, setSnapshot] = useState<WorkbenchSnapshot>();
  const [selectedAgentKey, setSelectedAgentKey] = useState('');
  const [latestRunId, setLatestRunId] = useState('');
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const activeStream = useRef<AbortController | undefined>(undefined);

  const publishedAgents = useMemo(
    () => (snapshot?.agents ?? []).filter((item) => item.publishedVersion > 0),
    [snapshot],
  );
  const selectedAgent = publishedAgents.find((item) => item.agentKey === selectedAgentKey);
  const runtime = useMemo(() => readiness(snapshot, selectedAgent), [snapshot, selectedAgent]);
  const agentName = selectedAgent?.name ?? 'Agent';

  useEffect(() => {
    let active = true;
    Promise.all([admin.listAgents(), admin.listProviders(), admin.listModels(), admin.listTools(), admin.listSkills(), admin.listHooks()]).then(([agents, providers, models, tools, skills, hooks]) => {
      if (!active) return;
      const components = [
        ...models.map((item) => ({ type: 'MODEL', componentKey: item.modelKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: { providerKey: item.providerKey } })),
        ...tools.map((item) => ({ type: 'TOOL', componentKey: item.toolKey, displayName: item.displayName, description: item.description, version: item.contractVersion, status: 'AVAILABLE', tags: [], config: {} })),
        ...skills.map((item) => ({ type: 'SKILL', componentKey: item.skillKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: {} })),
        ...hooks.map((item) => ({ type: 'HOOK', componentKey: item.hookKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: {} })),
      ];
      const next: WorkbenchSnapshot = { overview: { agentCount: agents.length, platformStatus: 'READY', availableComponents: components.filter((item) => item.status === 'AVAILABLE').length, configuredProviders: providers.filter((item) => item.configured).length, runCount: 0 }, agents, providers, components, runs: [] };
      setSnapshot(next);
      const published = next.agents.filter((item) => item.publishedVersion > 0);
      setSelectedAgentKey((current) => {
        if (published.some((item) => item.agentKey === current)) return current;
        return published.find((item) => item.agentKey === 'fitness.coach')?.agentKey ?? published[0]?.agentKey ?? '';
      });
    }).catch((caught) => {
      if (!active) return;
      setSendError(caught instanceof Error ? caught.message : '无法读取 Agent 配置');
    });
    return () => { active = false; };
  }, []);

  useEffect(() => { bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight }); }, [trace, sending]);

  function selectAgent(agentKey: string) {
    activeStream.current?.abort();
    setSelectedAgentKey(agentKey);
    setTrace([]);
    setSendError('');
    setLatestRunId('');
  }

  async function send() {
    const payload = message.trim();
    if (!payload || sending || !runtime.ready || !selectedAgent) return;
    setSendError('');
    setSending(true);
    setTrace((current) => [...current, { role: 'user', content: payload }]);
    setMessage('');
    try {
      const run = await admin.createPlaygroundRun(selectedAgent.agentKey, payload, crypto.randomUUID());
      setLatestRunId(run.runId);
      setTrace((current) => [...current, { role: 'assistant', content: '', runId: run.runId, progress: ['已创建真实 Run'] }]);
      const controller = new AbortController();
      activeStream.current = controller;
      await consumeAgentRunStream(`/api/v1/admin/playground/runs/${run.runId}/events`, (event) => applyEvent(run.runId, event), controller.signal);
    } catch (caught) {
      const error = caught instanceof ApiError && caught.status === 503
        ? `运行时暂不可用：${caught.message}`
        : caught instanceof Error ? caught.message : '调用失败';
      setSendError(error);
      setTrace((current) => [...current, { role: 'assistant', content: `调用失败：${error}` }]);
    } finally { setSending(false); }
  }

  function applyEvent(runId: string, event: AgentRunEvent) {
    setTrace((items) => items.map((item) => item.runId === runId ? applyAgentRunEvent(item, event) : item));
    if (event.type === 'ERROR') setSendError(String(event.data.message ?? 'AI 运行失败'));
  }

  async function decide(runId: string, approvalId: string, decision: 'APPROVE' | 'REJECT') {
    setTrace((items) => items.map((item) => item.runId === runId ? { ...item, deciding: true, decidingApprovalId: approvalId } : item));
    try {
      await admin.decidePlaygroundRunApproval(runId, approvalId, decision, crypto.randomUUID());
    } catch (caught) {
      setTrace((items) => items.map((item) => item.runId === runId ? { ...item, deciding: false, decidingApprovalId: undefined } : item));
      setSendError(caught instanceof Error ? caught.message : '确认操作失败');
    }
  }

  return <>
    <PageHeading eyebrow="安全调试" title="Agent 调试台" description="选择一个已发布 Agent，按其发布版本的模型、提示词和能力配置执行真实测试。" />
    <section className="admin-playground">
      <div className="admin-playground__chat">
        <header>
          <span><Bot /></span>
          <div><strong>{selectedAgent?.agentKey ?? '选择 Agent'}</strong><small>{selectedAgent ? `v${selectedAgent.publishedVersion} · ${runtime.ready ? '可以开始调试' : `运行依赖尚未就绪：${runtime.reason}`}` : runtime.reason}</small></div>
          <label className="admin-playground__agent-switch"><span>调试 Agent</span><select aria-label="调试 Agent" value={selectedAgentKey} onChange={(event) => selectAgent(event.target.value)} disabled={!publishedAgents.length || sending}>{publishedAgents.length ? publishedAgents.map((item) => <option key={item.agentKey} value={item.agentKey}>{item.name} · {item.agentKey}</option>) : <option value="">暂无已发布 Agent</option>}</select></label>
          <i className={runtime.ready ? 'is-online' : ''} />
        </header>
        <div className="admin-playground__body" ref={bodyRef}>
          {trace.length ? trace.map((item, index) => (
            <div key={`${item.role}-${index}`} className={`admin-playground__bubble ${item.role === 'user' ? 'is-user' : 'is-assistant'}`}>
              <small>{item.role === 'user' ? '你' : agentName}</small>
              {item.role === 'assistant' ? <AgentRunMessage message={item} markdownClassName="admin-md" onDecision={(approvalId, decision) => item.runId && void decide(item.runId, approvalId, decision)} /> : <span>{item.content}</span>}
            </div>
          )) : <div className="admin-empty"><CircleDashed /><strong>{selectedAgent ? `等待与 ${agentName} 的一次真实对话` : '先发布一个 Agent'}</strong><p>{selectedAgent ? '模型调用、会话上下文与 Run Trace 会一并写入数据库。' : '只有已发布的版本才会进入调试台，避免把草稿配置误用于真实调用。'}</p></div>}
          {sending && trace.at(-1)?.role !== 'assistant' && <div className="admin-playground__bubble is-assistant is-thinking"><small>{agentName}</small><p><LoaderCircle className="is-spin" /> 正在思考…</p></div>}
          {sendError && <p className="admin-form-error"><AlertTriangle />{sendError}</p>}
          {latestRunId && <p className="admin-success-row"><CheckCircle2 />真实 Run 已写入 <Link to={`/admin/runs/${latestRunId}`}>查看 Trace</Link></p>}
        </div>
        <footer>
          <input value={message} onChange={(event) => setMessage(event.target.value)} disabled={!runtime.ready || sending} placeholder={runtime.ready ? '请输入测试问题' : '完成前置准备后解锁'} onKeyDown={(event) => event.key === 'Enter' && void send()} />
          <button className={runtime.ready ? 'admin-primary' : ''} disabled={!runtime.ready || sending || !message.trim()} onClick={() => void send()}>
            {sending ? <LoaderCircle className="is-spin" /> : <Rocket />} 发送
          </button>
        </footer>
      </div>
      <aside className="admin-card admin-runtime-check">
        <small>运行前检查</small>
        <h2>{runtime.ready ? '依赖已经就绪' : '还有依赖未完成'}</h2>
        <p className={runtime.providerReady ? 'is-ok' : ''}>{runtime.providerReady ? <CheckCircle2 /> : <AlertTriangle />} Provider 已配置密钥</p>
        <p className={runtime.capabilitiesReady ? 'is-ok' : ''}>{runtime.capabilitiesReady ? <CheckCircle2 /> : <AlertTriangle />} 已绑定 Tool、Skill、Hook 均可用</p>
        <p className={runtime.published ? 'is-ok' : ''}>{runtime.published ? <CheckCircle2 /> : <AlertTriangle />} 已发布 Agent 版本</p>
      </aside>
    </section>
  </>;
}
