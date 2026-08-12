import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  AlertTriangle, Check, CheckCircle2, CircleDashed, Code2, Cpu, LoaderCircle, Rocket,
  Save, ShieldCheck, Webhook, Sparkles, Wrench,
} from 'lucide-react';

import { admin, ApiError, type AgentDraft, type AgentDraftUpdate, type Provider, type ValidationResult, type WorkbenchComponent } from '../api';
import { PageHeading } from '../components/PageHeading';

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '操作没有完成，请稍后再试。';
}

function componentOptions(components: WorkbenchComponent[], type: string) {
  return components.filter((item) => item.type === type);
}

function capabilityItems(
  components: WorkbenchComponent[],
  type: 'TOOL' | 'SKILL' | 'HOOK',
  selectedKeys: string[],
) {
  const items = components.filter((item) => item.type === type);
  const registeredKeys = new Set(items.map((item) => item.componentKey));
  const label = type === 'TOOL' ? '工具' : type === 'SKILL' ? '技能' : 'Hook';
  return [
    ...items,
    ...selectedKeys
      .filter((key) => !registeredKeys.has(key))
      .map((componentKey) => ({
        type,
        componentKey,
        displayName: `未登记${label}：${componentKey}`,
        description: '该能力不再存在于当前目录，请移除后再保存草稿。',
        version: 0,
        status: 'UNAVAILABLE',
        tags: [],
        config: {},
      })),
  ];
}

const FITNESS_AGENT_KEY = 'fitness.coach';
const FITNESS_SAFETY_HOOK = 'fitness.safety';

function hooksForAgent(agentKey: string, hookKeys: string[]) {
  if (agentKey !== FITNESS_AGENT_KEY || hookKeys.includes(FITNESS_SAFETY_HOOK)) return hookKeys;
  return [...hookKeys, FITNESS_SAFETY_HOOK];
}

export function AgentEditor() {
  const { agentKey } = useParams<{ agentKey: string }>();
  const navigate = useNavigate();
  const [agent, setAgent] = useState<AgentDraft>();
  const [components, setComponents] = useState<WorkbenchComponent[]>([]);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [draft, setDraft] = useState<AgentDraftUpdate>();
  const [revision, setRevision] = useState(0);
  const [validation, setValidation] = useState<ValidationResult>();
  const [pending, setPending] = useState<'' | 'save' | 'validate' | 'publish'>('');
  const [error, setError] = useState('');
  const [lastSuccess, setLastSuccess] = useState<string>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!agentKey) return;
    let mounted = true;
    Promise.all([
      admin.listAgents(), admin.listProviders(), admin.listModels(), admin.listPrompts(),
      admin.listTools(), admin.listSkills(), admin.listHooks(), admin.listFrameworks(), admin.listMemories(),
    ]).then(([agents, nextProviders, models, prompts, tools, skills, hooks, frameworks, memories]) => {
      if (!mounted) return;
      const found = agents.find((item) => item.agentKey === agentKey);
      if (!found) {
        setError(`未找到 Agent ${agentKey}`);
        setLoading(false);
        return;
      }
      setAgent(found);
      setProviders(nextProviders.filter((item) => item.status === 'ACTIVE'));
      setComponents([
        ...models.map((item) => ({ type: 'MODEL', componentKey: item.modelKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: { providerKey: item.providerKey } })),
        ...prompts.map((item) => ({ type: 'PROMPT', componentKey: item.promptKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: { template: item.template } })),
        ...tools.map((item) => ({ type: 'TOOL', componentKey: item.toolKey, displayName: item.displayName, description: item.description, version: item.contractVersion, status: 'AVAILABLE', tags: [], config: { risk: item.riskLevel } })),
        ...skills.map((item) => ({ type: 'SKILL', componentKey: item.skillKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: {} })),
        ...hooks.map((item) => ({ type: 'HOOK', componentKey: item.hookKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: {} })),
        ...frameworks.map((item) => ({ type: 'FRAMEWORK', componentKey: item.frameworkKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: item.capabilities })),
        ...memories.map((item) => ({ type: 'MEMORY', componentKey: item.memoryKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: {} })),
      ]);
      setDraft({
        name: found.name, description: found.description, frameworkKey: found.frameworkKey,
        providerKey: found.providerKey, modelKey: found.modelKey, promptKey: found.promptKey,
        toolKeys: found.toolKeys, skillKeys: found.skillKeys, hookKeys: hooksForAgent(found.agentKey, found.hookKeys),
        memoryKey: found.memoryKey, temperature: found.temperature, maxToolCalls: found.maxToolCalls,
      });
      setRevision(found.revision);
      setLoading(false);
    }).catch((caught) => mounted && setError(messageOf(caught)));
    return () => { mounted = false; };
  }, [agentKey]);

  if (loading) return <PageHeading eyebrow="加载中" title="Agent 配置" description="正在拉取 Agent 草稿…" />;
  if (error) return <PageHeading eyebrow="错误" title="无法加载" description={error} action={<button className="admin-secondary" onClick={() => navigate('/admin/agents')}>返回列表</button>} />;
  if (!agent || !draft) return null;
  const selectedPrompt = components.find((item) => item.type === 'PROMPT' && item.componentKey === draft.promptKey);
  const systemPrompt = String((selectedPrompt?.config as Record<string, unknown> | undefined)?.template ?? selectedPrompt?.description ?? '尚未设置系统提示词内容。');

  function set<K extends keyof AgentDraftUpdate>(key: K, value: AgentDraftUpdate[K]) {
    setDraft((current) => current ? { ...current, [key]: value } : current);
  }
  function toggle(key: 'toolKeys' | 'skillKeys' | 'hookKeys', value: string) {
    if (key === 'hookKeys' && agent?.agentKey === FITNESS_AGENT_KEY && value === FITNESS_SAFETY_HOOK) return;
    setDraft((current) => current ? { ...current, [key]: current[key].includes(value) ? current[key].filter((item) => item !== value) : [...current[key], value] } : current);
  }

  async function save(event: FormEvent) {
    event.preventDefault(); setError('');
    if (!draft || !agent) return;
    setPending('save');
    try {
      const updated = await admin.updateDraft(agent.agentKey, draft, revision);
      setRevision(updated.revision);
      setAgent(updated);
      setLastSuccess('草稿已保存');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  async function validate() {
    if (!agent) return;
    setPending('validate'); setError('');
    try { setValidation(await admin.validate(agent.agentKey)); }
    catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  async function publish() {
    if (!agent) return;
    setPending('publish'); setError('');
    try {
      const publication = await admin.publish(agent.agentKey);
      setValidation(undefined);
      setLastSuccess(`版本 v${publication.publishedVersion} 已发布`);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 422 && validation) {
        setValidation({ valid: false, errors: [(caught as Error).message], warnings: [] });
      } else {
        setError(messageOf(caught));
      }
    }
    finally { setPending(''); }
  }

  return <form className="admin-editor" onSubmit={save}>
    <PageHeading
      eyebrow="Agent Builder"
      title={agent.name}
      description={agent.description}
      action={<span className="admin-revision">草稿 revision {revision}</span>}
    />
    <section className="admin-card admin-form-card">
      <div className="admin-section-title"><span><Code2 /></span><div><h2>基础信息</h2><p>它是谁，以及应该如何帮助用户。</p></div></div>
      <div className="admin-form-grid">
        <label>Agent 名称<input aria-label="Agent 名称" value={draft.name} onChange={(event) => set('name', event.target.value)} /></label>
        <label className="is-wide">描述<textarea aria-label="Agent 描述" rows={3} value={draft.description} onChange={(event) => set('description', event.target.value)} /></label>
      </div>
    </section>
    <section className="admin-card admin-form-card">
      <div className="admin-section-title"><span><Cpu /></span><div><h2>运行核心</h2><p>先选择模型服务，再从该服务商支持的模型中选择模型。</p></div></div>
      <div className="admin-form-grid">
        <label>Agent 框架<select aria-label="Agent 框架" value={draft.frameworkKey} onChange={(event) => set('frameworkKey', event.target.value)}>{componentOptions(components, 'FRAMEWORK').map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>模型服务<select aria-label="模型服务" value={draft.providerKey} onChange={(event) => { const providerKey = event.target.value; const firstModel = components.find((item) => item.type === 'MODEL' && (item.config as Record<string, unknown>).providerKey === providerKey); setDraft((current) => current ? { ...current, providerKey, modelKey: firstModel?.componentKey ?? current.modelKey } : current); }}>{providers.map((item) => <option value={item.providerKey} key={item.providerKey}>{item.displayName}{item.configured ? '' : '（未配置）'}</option>)}</select></label>
        <label>模型<select aria-label="模型" value={draft.modelKey} onChange={(event) => set('modelKey', event.target.value)}>{components.filter((item) => item.type === 'MODEL' && ((item.config as Record<string, unknown>).providerKey === draft.providerKey || item.componentKey === draft.modelKey)).map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>系统提示词<select aria-label="系统提示词" value={draft.promptKey} onChange={(event) => set('promptKey', event.target.value)}>{componentOptions(components, 'PROMPT').map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>记忆<select aria-label="记忆" value={draft.memoryKey} onChange={(event) => set('memoryKey', event.target.value)}>{componentOptions(components, 'MEMORY').map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>最大工具调用次数<input aria-label="最大工具调用次数" type="number" min="1" max="50" value={draft.maxToolCalls} onChange={(event) => set('maxToolCalls', Number(event.target.value))} /></label>
        <label className="is-wide admin-range">温度 <b>{draft.temperature.toFixed(1)}</b><input aria-label="温度" type="range" min="0" max="2" step="0.1" value={draft.temperature} onChange={(event) => set('temperature', Number(event.target.value))} /><small>更低更稳定 · 更高更灵活</small></label>
        <section className="is-wide admin-system-prompt" aria-label="当前系统提示词">
          <header><div><strong>当前系统提示词</strong><span>{selectedPrompt?.displayName ?? draft.promptKey}</span></div><Link to="/admin/prompts">维护系统提示词</Link></header>
          <p>{systemPrompt}</p>
          <small>提示词中心的修改需要重新发布 Agent 后，才会进入运行时版本。</small>
        </section>
      </div>
    </section>
    <section className="admin-card admin-form-card">
      <div className="admin-section-title"><span><Sparkles /></span><div><h2>能力装配</h2><p>只展示代码或目录中已登记的能力；不可用项会阻止发布。</p></div></div>
      {(['TOOL', 'SKILL', 'HOOK'] as const).map((type) => {
        const key = type === 'TOOL' ? 'toolKeys' : type === 'SKILL' ? 'skillKeys' : 'hookKeys';
        const items = capabilityItems(components, type, draft[key]);
        const Icon = type === 'TOOL' ? Wrench : type === 'SKILL' ? Sparkles : Webhook;
        const label = type === 'TOOL' ? '工具' : type === 'SKILL' ? '技能' : 'Hook';
        return <div className="admin-capability-group" key={type}>
          <h3><Icon /> {label}<small>{draft[key].length} 已选择</small></h3>
          {items.length ? <div>{items.map((item) => {
            const mandatory = type === 'HOOK' && agent.agentKey === FITNESS_AGENT_KEY && item.componentKey === FITNESS_SAFETY_HOOK;
            const selected = draft[key].includes(item.componentKey);
            return <button type="button" className={`${selected ? 'is-selected' : ''}${item.status === 'UNAVAILABLE' ? ' is-unavailable' : ''}`} aria-pressed={selected} key={item.componentKey} disabled={mandatory} onClick={() => toggle(key, item.componentKey)}><span>{selected ? <Check /> : <Icon />}</span><b>{item.displayName}</b><small>{mandatory ? 'MANDATORY' : item.status}</small></button>;
          })}</div> : <p className="admin-inline-empty">当前没有登记的{label}</p>}
        </div>;
      })}
    </section>
    {validation && <section className={`admin-validation ${validation.valid ? 'is-valid' : 'is-invalid'}`}><span>{validation.valid ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>{validation.valid ? '发布条件已满足' : '暂时不能发布'}</strong>{validation.errors.map((item) => <p key={item}>{item}</p>)}{validation.warnings.map((item) => <p key={item}>提醒：{item}</p>)}</div></section>}
    {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
    {lastSuccess && <p className="admin-success-row"><CheckCircle2 />{lastSuccess}</p>}
    <footer className="admin-sticky-actions">
      <span><CircleDashed /> 当前配置：{components.find((item) => item.componentKey === draft.frameworkKey)?.displayName ?? draft.frameworkKey} / {components.find((item) => item.componentKey === draft.modelKey)?.displayName ?? draft.modelKey}</span>
      <div>
        <button type="button" className="admin-secondary" disabled={Boolean(pending)} onClick={() => void validate()}>{pending === 'validate' ? <LoaderCircle className="is-spin" /> : <ShieldCheck />} 检查发布条件</button>
        <button type="submit" className="admin-secondary" disabled={Boolean(pending)}>{pending === 'save' ? <LoaderCircle className="is-spin" /> : <Save />} 保存草稿</button>
        <button type="button" className="admin-primary" disabled={Boolean(pending) || !validation?.valid} onClick={() => void publish()}>{pending === 'publish' ? <LoaderCircle className="is-spin" /> : <Rocket />} 发布版本</button>
      </div>
    </footer>
  </form>;
}
