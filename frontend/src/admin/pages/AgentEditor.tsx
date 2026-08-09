import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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

const MANDATORY_SAFETY_HOOK = 'fitness.safety';

function withMandatorySafetyHook(hookKeys: string[]) {
  return hookKeys.includes(MANDATORY_SAFETY_HOOK) ? hookKeys : [...hookKeys, MANDATORY_SAFETY_HOOK];
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
    admin.snapshot().then((snapshot) => {
      if (!mounted) return;
      const found = snapshot.agents.find((item) => item.agentKey === agentKey);
      if (!found) {
        setError(`未找到 Agent ${agentKey}`);
        setLoading(false);
        return;
      }
      setAgent(found);
      setComponents(snapshot.components);
      setProviders(snapshot.providers);
      setDraft({
        name: found.name, description: found.description, frameworkKey: found.frameworkKey,
        providerKey: found.providerKey, modelKey: found.modelKey, promptKey: found.promptKey,
        toolKeys: found.toolKeys, skillKeys: found.skillKeys, hookKeys: withMandatorySafetyHook(found.hookKeys),
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

  function set<K extends keyof AgentDraftUpdate>(key: K, value: AgentDraftUpdate[K]) {
    setDraft((current) => current ? { ...current, [key]: value } : current);
  }
  function toggle(key: 'toolKeys' | 'skillKeys' | 'hookKeys', value: string) {
    if (key === 'hookKeys' && value === MANDATORY_SAFETY_HOOK) return;
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
        <label>提示词<select aria-label="提示词" value={draft.promptKey} onChange={(event) => set('promptKey', event.target.value)}>{componentOptions(components, 'PROMPT').map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>记忆<select aria-label="记忆" value={draft.memoryKey} onChange={(event) => set('memoryKey', event.target.value)}>{componentOptions(components, 'MEMORY').map((item) => <option value={item.componentKey} key={item.componentKey}>{item.displayName}</option>)}</select></label>
        <label>最大工具调用次数<input aria-label="最大工具调用次数" type="number" min="1" max="50" value={draft.maxToolCalls} onChange={(event) => set('maxToolCalls', Number(event.target.value))} /></label>
        <label className="is-wide admin-range">温度 <b>{draft.temperature.toFixed(1)}</b><input aria-label="温度" type="range" min="0" max="2" step="0.1" value={draft.temperature} onChange={(event) => set('temperature', Number(event.target.value))} /><small>更低更稳定 · 更高更灵活</small></label>
      </div>
    </section>
    <section className="admin-card admin-form-card">
      <div className="admin-section-title"><span><Sparkles /></span><div><h2>能力装配</h2><p>只展示代码或目录中已登记的能力；不可用项会阻止发布。</p></div></div>
      {(['TOOL', 'SKILL', 'HOOK'] as const).map((type) => {
        const key = type === 'TOOL' ? 'toolKeys' : type === 'SKILL' ? 'skillKeys' : 'hookKeys';
        const items = components.filter((item) => item.type === type);
        const Icon = type === 'TOOL' ? Wrench : type === 'SKILL' ? Sparkles : Webhook;
        const label = type === 'TOOL' ? '工具' : type === 'SKILL' ? '技能' : 'Hook';
        return <div className="admin-capability-group" key={type}>
          <h3><Icon /> {label}<small>{draft[key].length} 已选择</small></h3>
          {items.length ? <div>{items.map((item) => {
            const mandatory = type === 'HOOK' && item.componentKey === MANDATORY_SAFETY_HOOK;
            return <button type="button" className={draft[key].includes(item.componentKey) ? 'is-selected' : ''} aria-pressed={draft[key].includes(item.componentKey)} key={item.componentKey} disabled={mandatory} onClick={() => toggle(key, item.componentKey)}><span>{draft[key].includes(item.componentKey) ? <Check /> : <Icon />}</span><b>{item.displayName}</b><small>{mandatory ? 'MANDATORY' : item.status}</small></button>;
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
