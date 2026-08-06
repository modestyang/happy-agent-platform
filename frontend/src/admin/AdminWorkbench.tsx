import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import {
  Activity, AlertTriangle, Bell, Blocks, Bot, Check, CheckCircle2, ChevronRight, CircleDashed,
  Cloud, Code2, Cpu, Database, FlaskConical, Gauge, KeyRound, LayoutDashboard, LoaderCircle,
  Menu, MessageSquare, PlayCircle, Rocket, Save, Search, Settings2, ShieldCheck, Sparkles,
  Webhook, Wrench, X,
} from 'lucide-react';

import { ApiError, api } from '../api';
import type { AgentDraft, AgentDraftUpdate, Provider, ValidationResult, WorkbenchComponent, WorkbenchSnapshot } from './types';
import './admin.css';

type View = 'overview' | 'agents' | 'components' | 'providers' | 'runs' | 'playground';

const navigation: { view: View; label: string; icon: typeof LayoutDashboard }[] = [
  { view: 'overview', label: '总览', icon: LayoutDashboard },
  { view: 'agents', label: 'Agent 配置', icon: Bot },
  { view: 'components', label: '组件中心', icon: Blocks },
  { view: 'providers', label: '模型服务', icon: Cloud },
  { view: 'runs', label: '运行记录', icon: Activity },
  { view: 'playground', label: '调试台', icon: FlaskConical },
];

const typeMeta: Record<string, { label: string; icon: typeof Blocks }> = {
  FRAMEWORK: { label: '框架', icon: Code2 }, PROVIDER: { label: '服务商', icon: Cloud },
  MODEL: { label: '模型', icon: Cpu }, PROMPT: { label: '提示词', icon: MessageSquare },
  MEMORY: { label: '记忆', icon: Database }, TOOL: { label: '工具', icon: Wrench },
  SKILL: { label: '技能', icon: Sparkles }, HOOK: { label: 'Hook', icon: Webhook },
};

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '操作没有完成，请稍后再试。';
}

function platformText(status: string) {
  return status === 'READY' ? '运行准备就绪' : ['NEEDS_CONFIGURATION', 'DEGRADED'].includes(status) ? '需要完成配置' : status;
}

function statusText(status: string) {
  return ({ AVAILABLE: '可用', DRAFT: '待完成', UNAVAILABLE: '不可用', READY: '就绪', DRAFT_AGENT: '草稿' } as Record<string, string>)[status] ?? status;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function Loading() {
  return <main className="admin-load"><div><LoaderCircle /><strong>正在连接工作台</strong><small>读取 Agent 配置与组件状态…</small></div></main>;
}

function Notice({ kind = 'success', children, onClose }: { kind?: 'success' | 'error'; children: ReactNode; onClose: () => void }) {
  return <div className={`admin-toast admin-toast--${kind}`} role="status">{kind === 'success' ? <CheckCircle2 /> : <AlertTriangle />}<span>{children}</span><button aria-label="关闭提示" onClick={onClose}><X /></button></div>;
}

export function AdminWorkbench() {
  const [snapshot, setSnapshot] = useState<WorkbenchSnapshot>();
  const [view, setView] = useState<View>('overview');
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);

  async function load() {
    setError(''); setErrorStatus(0);
    try { setSnapshot(await api.admin.snapshot()); }
    catch (caught) { setError(messageOf(caught)); setErrorStatus(caught instanceof ApiError ? caught.status : 0); }
  }

  useEffect(() => { void load(); }, []);

  if (!snapshot && !error) return <Loading />;
  if (!snapshot) return <main className="admin-load"><div className="admin-load__error"><AlertTriangle /><strong>工作台暂时无法打开</strong><small>{error}</small>{errorStatus === 401 ? <a href="/">前往移动端登录</a> : <button onClick={() => void load()}>重新连接</button>}</div></main>;

  const currentAgent = snapshot.agents[0];
  return <div className="admin-stage">
    <div className="admin-shell">
      <aside className={`admin-sidebar${menuOpen ? ' is-open' : ''}`}>
        <div className="admin-brand"><span><Bot /></span><div><strong>Happy</strong><small>Agent Platform</small></div><button aria-label="关闭菜单" onClick={() => setMenuOpen(false)}><X /></button></div>
        <nav aria-label="管理工作台导航">{navigation.map(({ view: itemView, label, icon: Icon }) => <button key={itemView} aria-label={label} className={view === itemView ? 'is-active' : ''} onClick={() => { setView(itemView); setMenuOpen(false); }}><Icon /><span>{label}</span>{view === itemView && <i />}</button>)}</nav>
        <div className="admin-sidebar__foot"><span><ShieldCheck /></span><div><strong>本机工作台</strong><small>会话保护已开启</small></div></div>
      </aside>

      <section className="admin-workspace">
        <header className="admin-topbar"><button className="admin-menu" aria-label="打开菜单" onClick={() => setMenuOpen(true)}><Menu /></button><label><Search /><input aria-label="全局搜索" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索 Agent、组件或运行记录" /></label><div className="admin-topbar__right"><button aria-label="通知"><Bell /><i /></button><span className="admin-user">JY</span></div></header>
        <div className="admin-content">
          {view === 'overview' && <Overview snapshot={snapshot} onNavigate={setView} />}
          {view === 'agents' && currentAgent && <AgentEditor agent={currentAgent} components={snapshot.components} providers={snapshot.providers} onAgent={(agent) => setSnapshot((current) => current ? { ...current, agents: current.agents.map((item) => item.agentKey === agent.agentKey ? agent : item) } : current)} onNotice={setNotice} />}
          {view === 'components' && <ComponentCatalog components={snapshot.components} query={search} />}
          {view === 'providers' && <ProviderSettings providers={snapshot.providers} onProvider={(provider) => setSnapshot((current) => current ? { ...current, providers: current.providers.map((item) => item.providerKey === provider.providerKey ? provider : item), overview: { ...current.overview, configuredProviders: current.providers.filter((item) => item.configured || item.providerKey === provider.providerKey).length } } : current)} onNotice={setNotice} />}
          {view === 'runs' && <RunHistory snapshot={snapshot} />}
          {view === 'playground' && <Playground snapshot={snapshot} onNavigate={setView} />}
        </div>
      </section>
    </div>
    {notice && <Notice onClose={() => setNotice('')}>{notice}</Notice>}
  </div>;
}

function PageHeading({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return <header className="admin-page-head"><div><small>{eyebrow}</small><h1>{title}</h1><p>{description}</p></div>{action}</header>;
}

function Overview({ snapshot, onNavigate }: { snapshot: WorkbenchSnapshot; onNavigate: (view: View) => void }) {
  const agent = snapshot.agents[0];
  const unavailable = snapshot.components.filter((item) => item.status !== 'AVAILABLE');
  return <>
    <PageHeading eyebrow="工作台概览" title="Agent 工作台" description="从配置、发布到运行追踪，都在同一个清晰的工作面里。" action={<button className="admin-primary" onClick={() => onNavigate('agents')}><Settings2 /> 配置 Agent</button>} />
    <section className="admin-kpis">
      <article className="admin-kpi admin-kpi--coral"><span><Bot /></span><div><small>Agent</small><strong>{snapshot.overview.agentCount}</strong><p>{agent ? `${agent.name} · v${agent.publishedVersion}` : '暂无 Agent'}</p></div></article>
      <article className="admin-kpi admin-kpi--blue"><span><Blocks /></span><div><small>可用组件</small><strong>{snapshot.overview.availableComponents}</strong><p>共 {snapshot.components.length} 个已登记</p></div></article>
      <article className="admin-kpi admin-kpi--mint"><span><Cloud /></span><div><small>已配置服务</small><strong>{snapshot.overview.configuredProviders}</strong><p>共 {snapshot.providers.length} 个 Provider</p></div></article>
      <article className="admin-kpi admin-kpi--sand"><span><PlayCircle /></span><div><small>运行次数</small><strong>{snapshot.overview.runCount}</strong><p>来自真实执行记录</p></div></article>
    </section>
    <section className="admin-overview-grid">
      <article className="admin-card admin-agent-card"><div className="admin-card__head"><div><small>当前 Agent</small><h2>{agent?.name ?? '尚未创建'}</h2></div><span className={`admin-badge admin-badge--${agent?.status.toLowerCase()}`}>{statusText(agent?.status ?? 'DRAFT')}</span></div>{agent && <><p>{agent.description}</p><div className="admin-agent-route"><span><Code2 />{snapshot.components.find((item) => item.componentKey === agent.frameworkKey)?.displayName ?? agent.frameworkKey}</span><ChevronRight /><span><Cloud />{snapshot.providers.find((item) => item.providerKey === agent.providerKey)?.displayName ?? agent.providerKey}</span><ChevronRight /><span><Cpu />{snapshot.components.find((item) => item.componentKey === agent.modelKey)?.displayName ?? agent.modelKey}</span></div><footer><span>草稿 revision {agent.revision}</span><span>更新于 {formatTime(agent.updatedAt)}</span><button onClick={() => onNavigate('agents')}>打开配置 <ChevronRight /></button></footer></>}</article>
      <article className="admin-card admin-readiness"><div className="admin-card__head"><div><small>发布准备度</small><h2>{platformText(snapshot.overview.platformStatus)}</h2></div><Gauge /></div><div className="admin-readiness__meter"><i style={{ width: `${Math.round(snapshot.components.filter((item) => item.status === 'AVAILABLE').length / Math.max(snapshot.components.length, 1) * 100)}%` }} /></div><p>{unavailable.length ? `还有 ${unavailable.length} 个组件需要处理，未满足条件时不会允许发布。` : '必要组件已就绪，可以执行发布检查。'}</p><ul>{unavailable.slice(0, 3).map((item) => <li key={item.componentKey}><span className={`admin-dot admin-dot--${item.status.toLowerCase()}`} /> <b>{item.displayName}</b><small>{String(item.config.reason ?? statusText(item.status))}</small></li>)}</ul><button className="admin-text-button" onClick={() => onNavigate('components')}>查看全部组件 <ChevronRight /></button></article>
      <article className="admin-card admin-runs-card"><div className="admin-card__head"><div><small>最近执行</small><h2>运行记录</h2></div><button onClick={() => onNavigate('runs')}>查看全部</button></div>{snapshot.runs.length ? snapshot.runs.slice(0, 4).map((run) => <div className="admin-run-row" key={run.runId}><span className={`admin-run-icon admin-run-icon--${run.status.toLowerCase()}`}><PlayCircle /></span><div><strong>{run.agentKey}</strong><small>{formatTime(run.startedAt)}</small></div><em>{run.durationMs} ms</em></div>) : <div className="admin-empty"><CircleDashed /><strong>暂无运行记录</strong><p>运行数据会在 Agent 真正执行后出现，不使用模拟数据填充。</p><button onClick={() => onNavigate('playground')}>前往调试台</button></div>}</article>
      <article className="admin-card admin-activity-card"><div className="admin-card__head"><div><small>平台状态</small><h2>配置动态</h2></div><Activity /></div><div className="admin-timeline"><p><i className="is-coral" /><span><small>当前</small><strong>草稿配置已保存到数据库</strong></span></p><p><i className="is-blue" /><span><small>安全</small><strong>Provider 凭据采用加密存储</strong></span></p><p><i className="is-sand" /><span><small>待办</small><strong>{unavailable[0]?.displayName ?? '执行一次发布检查'}</strong></span></p></div></article>
    </section>
  </>;
}

function componentOptions(components: WorkbenchComponent[], type: string, current: string) {
  const options = components.filter((item) => item.type === type);
  return options.some((item) => item.componentKey === current) || !current ? options : [{ type, componentKey: current, displayName: current, description: '', version: 1, status: 'DRAFT', tags: [], config: {} }, ...options];
}

function AgentEditor({ agent, components, providers, onAgent, onNotice }: { agent: AgentDraft; components: WorkbenchComponent[]; providers: Provider[]; onAgent: (agent: AgentDraft) => void; onNotice: (notice: string) => void }) {
  const [draft, setDraft] = useState<AgentDraftUpdate>(() => ({ name: agent.name, description: agent.description, frameworkKey: agent.frameworkKey, providerKey: agent.providerKey, modelKey: agent.modelKey, promptKey: agent.promptKey, toolKeys: agent.toolKeys, skillKeys: agent.skillKeys, hookKeys: agent.hookKeys, memoryKey: agent.memoryKey, temperature: agent.temperature, maxToolCalls: agent.maxToolCalls }));
  const [revision, setRevision] = useState(agent.revision);
  const [validation, setValidation] = useState<ValidationResult>();
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const componentName = (key: string) => components.find((item) => item.componentKey === key)?.displayName ?? key;

  function set<K extends keyof AgentDraftUpdate>(key: K, value: AgentDraftUpdate[K]) { setDraft((current) => ({ ...current, [key]: value })); }
  function toggle(key: 'toolKeys' | 'skillKeys' | 'hookKeys', value: string) { set(key, draft[key].includes(value) ? draft[key].filter((item) => item !== value) : [...draft[key], value]); }
  async function save(event: FormEvent) { event.preventDefault(); setPending('save'); setError(''); try { const updated = await api.admin.updateDraft(agent.agentKey, draft, revision); setRevision(updated.revision); onAgent(updated); onNotice('草稿已保存'); } catch (caught) { setError(messageOf(caught)); } finally { setPending(''); } }
  async function validate() { setPending('validate'); setError(''); try { setValidation(await api.admin.validate(agent.agentKey)); } catch (caught) { setError(messageOf(caught)); } finally { setPending(''); } }
  async function publish() { setPending('publish'); setError(''); try { const publication = await api.admin.publish(agent.agentKey); onNotice(`版本 v${publication.publishedVersion} 已发布`); setValidation(undefined); } catch (caught) { setError(messageOf(caught)); } finally { setPending(''); } }

  return <>
    <PageHeading eyebrow="Agent Builder" title="Agent 配置" description="非必要参数保留平台默认值，先把角色、模型与能力边界说明白。" action={<span className="admin-revision">草稿 revision {revision}</span>} />
    <form className="admin-editor" onSubmit={save}>
      <section className="admin-card admin-form-card"><div className="admin-section-title"><span><Bot /></span><div><h2>基础信息</h2><p>它是谁，以及应该如何帮助用户。</p></div></div><div className="admin-form-grid"><label>Agent 名称<input aria-label="Agent 名称" value={draft.name} onChange={(event) => set('name', event.target.value)} /></label><label className="is-wide">描述<textarea aria-label="Agent 描述" rows={3} value={draft.description} onChange={(event) => set('description', event.target.value)} /></label></div></section>
      <section className="admin-card admin-form-card"><div className="admin-section-title"><span><Cpu /></span><div><h2>运行核心</h2><p>框架与模型通过适配层组合，业务应用不直接依赖具体框架。</p></div></div><div className="admin-form-grid"><label>Agent 框架<select aria-label="Agent 框架" value={draft.frameworkKey} onChange={(event) => set('frameworkKey', event.target.value)}>{componentOptions(components, 'FRAMEWORK', draft.frameworkKey).map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label><label>模型服务<select aria-label="模型服务" value={draft.providerKey} onChange={(event) => set('providerKey', event.target.value)}>{providers.map((item) => <option value={item.providerKey} key={item.providerKey}>{item.displayName}{item.configured ? '' : '（未配置）'}</option>)}</select></label><label>模型<select aria-label="模型" value={draft.modelKey} onChange={(event) => set('modelKey', event.target.value)}>{componentOptions(components, 'MODEL', draft.modelKey).map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label><label>提示词<select aria-label="提示词" value={draft.promptKey} onChange={(event) => set('promptKey', event.target.value)}>{componentOptions(components, 'PROMPT', draft.promptKey).map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label><label>记忆<select aria-label="记忆" value={draft.memoryKey} onChange={(event) => set('memoryKey', event.target.value)}>{componentOptions(components, 'MEMORY', draft.memoryKey).map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label><label>最大工具调用次数<input aria-label="最大工具调用次数" type="number" min="1" max="50" value={draft.maxToolCalls} onChange={(event) => set('maxToolCalls', Number(event.target.value))} /></label><label className="is-wide admin-range">温度 <b>{draft.temperature.toFixed(1)}</b><input aria-label="温度" type="range" min="0" max="2" step="0.1" value={draft.temperature} onChange={(event) => set('temperature', Number(event.target.value))} /><small>更低更稳定 · 更高更灵活</small></label></div></section>
      <section className="admin-card admin-form-card"><div className="admin-section-title"><span><Blocks /></span><div><h2>能力装配</h2><p>只展示代码或目录中已登记的能力；不可用项会阻止发布。</p></div></div>{(['TOOL', 'SKILL', 'HOOK'] as const).map((type) => { const key = type === 'TOOL' ? 'toolKeys' : type === 'SKILL' ? 'skillKeys' : 'hookKeys'; const items = components.filter((item) => item.type === type); const Icon = typeMeta[type].icon; return <div className="admin-capability-group" key={type}><h3>{typeMeta[type].label}<small>{draft[key].length} 已选择</small></h3>{items.length ? <div>{items.map((item) => <button type="button" className={draft[key].includes(item.componentKey) ? 'is-selected' : ''} aria-pressed={draft[key].includes(item.componentKey)} key={item.componentKey} onClick={() => toggle(key, item.componentKey)}><span>{draft[key].includes(item.componentKey) ? <Check /> : <Icon />}</span><b>{item.displayName}</b><small>{statusText(item.status)}</small></button>)}</div> : <p className="admin-inline-empty">当前没有登记的{typeMeta[type].label}</p>}</div>; })}</section>
      {validation && <section className={`admin-validation ${validation.valid ? 'is-valid' : 'is-invalid'}`}><span>{validation.valid ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>{validation.valid ? '发布条件已满足' : '暂时不能发布'}</strong>{validation.errors.map((item) => <p key={item}>{item}</p>)}{validation.warnings.map((item) => <p key={item}>提醒：{item}</p>)}</div></section>}
      {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
      <footer className="admin-sticky-actions"><span><CircleDashed /> 当前配置：{componentName(draft.frameworkKey)} / {componentName(draft.modelKey)}</span><div><button type="button" className="admin-secondary" disabled={Boolean(pending)} onClick={() => void validate()}>{pending === 'validate' ? <LoaderCircle className="is-spin" /> : <ShieldCheck />} 检查发布条件</button><button type="submit" className="admin-secondary" disabled={Boolean(pending)}>{pending === 'save' ? <LoaderCircle className="is-spin" /> : <Save />} 保存草稿</button><button type="button" className="admin-primary" disabled={Boolean(pending) || !validation?.valid} onClick={() => void publish()}>{pending === 'publish' ? <LoaderCircle className="is-spin" /> : <Rocket />} 发布版本</button></div></footer>
    </form>
  </>;
}

function ComponentCatalog({ components, query }: { components: WorkbenchComponent[]; query: string }) {
  const types = ['ALL', ...Array.from(new Set(components.map((item) => item.type)))];
  const [type, setType] = useState('ALL');
  const [selected, setSelected] = useState<WorkbenchComponent>();
  const normalized = query.trim().toLowerCase();
  const filtered = components.filter((item) => (type === 'ALL' || item.type === type) && (!normalized || `${item.displayName}${item.description}${item.tags.join('')}`.toLowerCase().includes(normalized)));
  return <>
    <PageHeading eyebrow="可组合能力" title="组件中心" description="Tool、Skill、Hook、Provider 与运行框架使用一致的登记与状态模型。" />
    <div className="admin-component-tabs">{types.map((item) => <button key={item} aria-pressed={type === item} className={type === item ? 'is-active' : ''} onClick={() => setType(item)}>{item === 'ALL' ? '全部' : typeMeta[item]?.label ?? item}<small>{item === 'ALL' ? components.length : components.filter((component) => component.type === item).length}</small></button>)}</div>
    <section className="admin-component-layout"><div className="admin-component-list" role="region" aria-label="组件目录">{filtered.map((item) => { const Icon = typeMeta[item.type]?.icon ?? Blocks; const reason = item.config.reason; return <button key={item.componentKey} className={selected?.componentKey === item.componentKey ? 'is-selected' : ''} onClick={() => setSelected(item)}><span className={`admin-component-icon admin-component-icon--${item.type.toLowerCase()}`}><Icon /></span><div><small>{typeMeta[item.type]?.label ?? item.type} · v{item.version}</small><strong>{item.displayName}</strong><p>{item.description}</p>{reason != null && <em>{String(reason)}</em>}<footer>{item.tags.map((tag) => <i key={tag}>{tag}</i>)}<b className={`admin-status admin-status--${item.status.toLowerCase()}`}>{statusText(item.status)}</b></footer></div><ChevronRight /></button>; })}{!filtered.length && <div className="admin-empty"><Search /><strong>没有匹配的组件</strong><p>换一个类型或搜索词试试。</p></div>}</div><aside className="admin-component-detail">{selected ? <><span className={`admin-component-icon admin-component-icon--${selected.type.toLowerCase()}`}>{(() => { const Icon = typeMeta[selected.type]?.icon ?? Blocks; return <Icon />; })()}</span><small>{typeMeta[selected.type]?.label ?? selected.type}</small><h2>{selected.displayName}</h2><p>{selected.description}</p><dl><div><dt>唯一标识</dt><dd>{selected.componentKey}</dd></div><div><dt>状态</dt><dd>{statusText(selected.status)}</dd></div><div><dt>版本</dt><dd>v{selected.version}</dd></div></dl>{Object.keys(selected.config).length > 0 && <div className="admin-config-preview"><strong>登记信息</strong>{Object.entries(selected.config).map(([key, value]) => <p key={key}><span>{key}</span><b>{String(value)}</b></p>)}</div>}</> : <div className="admin-empty"><Blocks /><strong>选择一个组件</strong><p>这里会展示其标识、版本与可用状态。</p></div>}</aside></section>
  </>;
}

function ProviderSettings({ providers, onProvider, onNotice }: { providers: Provider[]; onProvider: (provider: Provider) => void; onNotice: (notice: string) => void }) {
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  async function save(provider: Provider) { const secret = secrets[provider.providerKey]?.trim(); if (!secret) { setError('请输入 API Key。'); return; } setPending(provider.providerKey); setError(''); try { const updated = await api.admin.saveProviderCredential(provider.providerKey, secret); setSecrets((current) => ({ ...current, [provider.providerKey]: '' })); onProvider(updated); onNotice('密钥已安全保存'); } catch (caught) { setError(messageOf(caught)); } finally { setPending(''); } }
  return <>
    <PageHeading eyebrow="模型接入" title="模型服务" description="密钥仅用于写入，保存后页面和接口都不会返回明文。" />
    <section className="admin-provider-grid">{providers.map((provider) => <article className="admin-card admin-provider" key={provider.providerKey}><header><span><Cloud /></span><div><small>LLM Provider</small><h2>{provider.displayName}</h2></div><b className={provider.configured ? 'is-configured' : ''}>{provider.configured ? '已配置' : '待配置'}</b></header><label>API Endpoint<input aria-label={`${provider.displayName} Endpoint`} value={provider.endpoint} readOnly /></label><label>API Key<div className="admin-secret"><KeyRound /><input aria-label="API Key" type="password" autoComplete="new-password" value={secrets[provider.providerKey] ?? ''} placeholder={provider.configured ? provider.maskedCredential : '输入服务商 API Key'} onChange={(event) => setSecrets((current) => ({ ...current, [provider.providerKey]: event.target.value }))} /></div><small>保存后使用 AES-256-GCM 加密，管理端只能看到掩码。</small></label>{error && <p className="admin-form-error"><AlertTriangle />{error}</p>}<footer><span><ShieldCheck />{provider.configured ? '凭据已加密存储' : '尚未保存凭据'}</span><button className="admin-primary" disabled={pending === provider.providerKey} onClick={() => void save(provider)}>{pending === provider.providerKey ? <LoaderCircle className="is-spin" /> : <Save />} 保存密钥</button></footer></article>)}</section>
  </>;
}

function RunHistory({ snapshot }: { snapshot: WorkbenchSnapshot }) {
  return <><PageHeading eyebrow="可观测性" title="运行记录" description="查看每一次真实调用、工具执行数量与事件轨迹。" />{snapshot.runs.length ? <section className="admin-card admin-run-table"><header><span>执行</span><span>Agent</span><span>状态</span><span>耗时</span><span>工具调用</span></header>{snapshot.runs.map((run) => <article key={run.runId}><span><PlayCircle />{formatTime(run.startedAt)}</span><span>{run.agentKey}<small>v{run.agentVersion}</small></span><span><i className={`admin-dot admin-dot--${run.status.toLowerCase()}`} />{run.status}</span><span>{run.durationMs} ms</span><span>{run.toolCalls}</span></article>)}</section> : <section className="admin-card admin-empty admin-empty--large"><CircleDashed /><strong>暂无运行记录</strong><p>这里不会用 Mock 填满表格。Agent Runtime 真正执行后，Trace 会按顺序写入数据库并显示在这里。</p></section>}</>;
}

function Playground({ snapshot, onNavigate }: { snapshot: WorkbenchSnapshot; onNavigate: (view: View) => void }) {
  const providerReady = snapshot.providers.some((item) => item.configured);
  const toolsReady = snapshot.components.filter((item) => item.type === 'TOOL').some((item) => item.status === 'AVAILABLE');
  return <><PageHeading eyebrow="安全调试" title="Agent 调试台" description="仅在运行依赖真实就绪后允许发起测试，不伪造模型回复。" />
    <section className="admin-playground"><div className="admin-playground__chat"><header><span><Bot /></span><div><strong>{snapshot.agents[0]?.name ?? 'Agent'}</strong><small>{providerReady && toolsReady ? '可以开始调试' : '运行依赖尚未就绪'}</small></div><i className={providerReady && toolsReady ? 'is-online' : ''} /></header><div className="admin-playground__body"><div className="admin-empty"><CircleDashed /><strong>等待一次真实对话</strong><p>调试记录将与发布版本绑定，并写入 Run 与 Trace。</p></div></div><footer><input disabled placeholder="完成配置后可以发送测试问题" /><button disabled>发送</button></footer></div><aside className="admin-card admin-runtime-check"><small>运行前检查</small><h2>{providerReady && toolsReady ? '依赖已经就绪' : '还有依赖未完成'}</h2><p className={providerReady ? 'is-ok' : ''}>{providerReady ? <CheckCircle2 /> : <AlertTriangle />} Provider 凭据</p><p className={toolsReady ? 'is-ok' : ''}>{toolsReady ? <CheckCircle2 /> : <AlertTriangle />} 可用业务 Tool</p><p className={snapshot.agents[0]?.publishedVersion ? 'is-ok' : ''}>{snapshot.agents[0]?.publishedVersion ? <CheckCircle2 /> : <AlertTriangle />} 已发布 Agent 版本</p><button className="admin-primary" onClick={() => onNavigate(providerReady ? 'components' : 'providers')}>去完成配置 <ChevronRight /></button></aside></section>
  </>;
}
