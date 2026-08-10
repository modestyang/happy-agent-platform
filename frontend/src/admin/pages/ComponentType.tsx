import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Check, ChevronLeft, ChevronRight, Code2, FileText, LoaderCircle, Plus, Save, Search, ShieldCheck, Sparkles, Wrench } from 'lucide-react';

import { admin, type WorkbenchComponent, type WorkbenchComponentUpdate } from '../api';
import { PageHeading } from '../components/PageHeading';
import { ResourceCreateModal } from '../components/ResourceCreateModal';

const TYPE_LABELS: Record<string, string> = {
  FRAMEWORK: '框架', PROVIDER: '服务商', MODEL: '模型', PROMPT: '提示词',
  MEMORY: '记忆', TOOL: '工具', SKILL: '技能', HOOK: 'Hook',
};

const updateOf = (component: WorkbenchComponent): WorkbenchComponentUpdate => ({
  displayName: component.displayName,
  description: component.description,
  status: component.status,
  tags: component.tags,
  config: component.config,
});

const equal = (left: WorkbenchComponentUpdate, right: WorkbenchComponentUpdate) => JSON.stringify(left) === JSON.stringify(right);
const statusText = (status: string) => ({ AVAILABLE: '使用中', DRAFT: '待完成', UNAVAILABLE: '不可用', DISABLED: '已停用' } as Record<string, string>)[status] ?? status;
const messageOf = (error: unknown) => error instanceof Error ? error.message : '保存失败，请稍后重试。';
const configText = (config: Record<string, unknown>, key: string) => typeof config[key] === 'string' ? config[key] as string : '';
const configStrings = (config: Record<string, unknown>, key: string) => Array.isArray(config[key]) ? (config[key] as unknown[]).filter((item): item is string => typeof item === 'string') : [];
const promptVariables = (template: string) => Array.from(new Set(Array.from(template.matchAll(/\{\{\s*([\w.-]+)\s*\}\}/g)).map((match) => match[1])));

async function loadResourceCatalog(type: string): Promise<WorkbenchComponent[]> {
  if (type === 'PROMPT') return (await admin.listPrompts()).map((item) => ({ type, componentKey: item.promptKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', tags: [], config: { template: item.template, revision: item.revision } }));
  if (type === 'SKILL') {
    const [skills, tools] = await Promise.all([admin.listSkills(), admin.listTools()]);
    return [
      ...skills.map((item) => ({ type, componentKey: item.skillKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: { whenToUse: item.whenToUse, whenNotToUse: item.whenNotToUse, content: item.content, requiredTools: item.requiredToolKeys, revision: item.revision } })),
      ...tools.map((item) => ({ type: 'TOOL', componentKey: item.toolKey, displayName: item.displayName, description: item.description, version: item.contractVersion, status: 'AVAILABLE', tags: [], config: { risk: item.riskLevel, sideEffect: item.sideEffect, inputSchema: item.inputSchema, outputSchema: item.outputSchema } })),
    ];
  }
  if (type === 'HOOK') return (await admin.listHooks()).map((item) => ({ type, componentKey: item.hookKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status === 'ACTIVE' && item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: { phase: item.phase, mandatory: item.mandatory, revision: item.revision } }));
  if (type === 'TOOL') {
    const [tools, skills] = await Promise.all([admin.listTools(), admin.listSkills()]);
    return [
      ...tools.map((item) => ({ type, componentKey: item.toolKey, displayName: item.displayName, description: item.description, version: item.contractVersion, status: 'AVAILABLE', tags: [], config: { runtimeName: item.runtimeName, whenToUse: item.whenToUse, whenNotToUse: item.whenNotToUse, risk: item.riskLevel, sideEffect: item.sideEffect, requiredScopes: item.requiredScopes, inputSchema: item.inputSchema, outputSchema: item.outputSchema, source: 'CODE_REGISTERED' } })),
      ...skills.map((item) => ({ type: 'SKILL', componentKey: item.skillKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.status, tags: [], config: { requiredTools: item.requiredToolKeys } })),
    ];
  }
  return [];
}

function ComponentIcon({ type }: { type: string }) {
  const Icon = type === 'TOOL' ? Wrench : type === 'SKILL' ? Sparkles : type === 'PROMPT' ? FileText : Code2;
  return <span className={`admin-component-icon admin-component-icon--${type.toLowerCase()}`}><Icon /></span>;
}

export function ComponentType({ type, label, readOnly = false }: { type: string; label: string; readOnly?: boolean }) {
  const [catalog, setCatalog] = useState<WorkbenchComponent[]>([]);
  const [selected, setSelected] = useState<WorkbenchComponent>();
  const [form, setForm] = useState<WorkbenchComponentUpdate>();
  const [baseline, setBaseline] = useState<WorkbenchComponentUpdate>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<'all' | 'on' | 'off'>('all');
  const [preview, setPreview] = useState(false);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const requestId = useRef(0);

  async function load() {
    const request = ++requestId.current;
    setLoading(true); setCatalog([]); setSelected(undefined); setForm(undefined); setBaseline(undefined); setError(''); setSuccess(''); setQuery(''); setFilter('all');
    try {
      const resources = await loadResourceCatalog(type);
      if (request !== requestId.current) return;
      setCatalog(resources); setLoading(false);
    } catch (caught) {
      if (request !== requestId.current) return;
      setError(messageOf(caught)); setLoading(false);
    }
  }

  useEffect(() => { void load(); return () => { requestId.current += 1; }; }, [type]);

  const components = useMemo(() => catalog.filter((item) => item.type === type), [catalog, type]);
  const filtered = useMemo(() => components.filter((item) => {
    const text = query.trim().toLowerCase();
    const stateMatches = filter === 'all' || (filter === 'on' ? item.status === 'AVAILABLE' : item.status !== 'AVAILABLE');
    return stateMatches && (!text || `${item.displayName}${item.description}${item.componentKey}${item.tags.join(' ')}`.toLowerCase().includes(text));
  }), [components, filter, query]);
  const dirty = Boolean(form && baseline && !equal(form, baseline));

  function pick(component: WorkbenchComponent) {
    const next = updateOf(component);
    setSelected(component); setForm(next); setBaseline(next); setError(''); setSuccess(''); setPreview(false);
  }
  function update<K extends keyof WorkbenchComponentUpdate>(key: K, value: WorkbenchComponentUpdate[K]) {
    setForm((current) => current ? { ...current, [key]: value } : current);
  }
  function updateConfig(key: string, value: unknown) {
    setForm((current) => current ? { ...current, config: { ...current.config, [key]: value } } : current);
  }
  async function save() {
    if (!selected || !form || readOnly) return;
    setPending(true); setError(''); setSuccess('');
    try {
      const revision = Number(selected.config.revision ?? selected.version);
      const status = form.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED';
      let saved: WorkbenchComponent;
      if (selected.type === 'PROMPT') {
        const updated = await admin.updatePrompt(selected.componentKey, { displayName: form.displayName, description: form.description, template: configText(form.config, 'template'), status }, revision);
        saved = { ...selected, displayName: updated.displayName, description: updated.description, version: updated.revision, status: updated.status === 'ACTIVE' ? 'AVAILABLE' : 'DISABLED', config: { template: updated.template, revision: updated.revision } };
      } else if (selected.type === 'SKILL') {
        const updated = await admin.updateSkill(selected.componentKey, { displayName: form.displayName, description: form.description, whenToUse: configText(form.config, 'whenToUse'), whenNotToUse: configText(form.config, 'whenNotToUse'), content: configText(form.config, 'content'), requiredToolKeys: configStrings(form.config, 'requiredTools'), status }, revision);
        saved = { ...selected, displayName: updated.displayName, description: updated.description, version: updated.revision, status: updated.status === 'ACTIVE' && updated.runtimeReady ? 'AVAILABLE' : 'DISABLED', config: { ...form.config, revision: updated.revision } };
      } else if (selected.type === 'HOOK') {
        const updated = await admin.updateHook(selected.componentKey, { displayName: form.displayName, description: form.description, phase: configText(form.config, 'phase'), mandatory: Boolean(form.config.mandatory), status }, revision);
        saved = { ...selected, displayName: updated.displayName, description: updated.description, version: updated.revision, status: updated.status === 'ACTIVE' && updated.runtimeReady ? 'AVAILABLE' : 'DISABLED', config: { phase: updated.phase, mandatory: updated.mandatory, revision: updated.revision } };
      } else return;
      const updated = saved;
      setCatalog((current) => current.map((item) => item.type === updated.type && item.componentKey === updated.componentKey && item.version === updated.version ? updated : item));
      setSelected(updated); const next = updateOf(updated); setForm(next); setBaseline(next); setSuccess(`${TYPE_LABELS[selected.type]}配置已保存`);
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(false); }
  }

  if (loading) return <PageHeading eyebrow="加载中" title={label} description={`正在读取${label}目录…`} />;
  if (error && !selected) return <><PageHeading eyebrow={`${label}目录`} title={label} description={`读取可被 Agent 引用的${label}。`} /><div className="admin-empty"><AlertTriangle /><strong>{label}目录加载失败</strong><p>{error}</p><button className="admin-primary" onClick={() => void load()}>重新加载</button></div></>;

  if (selected && form) return <ComponentDetail component={selected} form={form} catalog={catalog} readOnly={readOnly} preview={preview} dirty={dirty} pending={pending} error={error} success={success} onBack={() => { setSelected(undefined); setForm(undefined); setBaseline(undefined); }} onPreview={setPreview} onUpdate={update} onUpdateConfig={updateConfig} onSave={save} />;

  return <>
    <PageHeading eyebrow={`${label}目录`} title={label} description={`${type === 'TOOL' ? '代码注册的运行时能力，仅查看其真实元数据。' : `可维护、可被 Agent 引用的${label}。`} · 共 ${components.length} 个`} action={(type === 'PROMPT' || type === 'SKILL') && <button className="admin-primary" onClick={() => setCreating(true)}><Plus />新增{label}</button>} />
    {(type === 'PROMPT' || type === 'SKILL') && <ResourceCreateModal type={type} open={creating} tools={catalog.filter((item) => item.type === 'TOOL' && item.status === 'AVAILABLE')} onClose={() => setCreating(false)} onCreated={(component) => { setCatalog((current) => [...current, component]); setSuccess(`${label}已新增`); }} />}
    <section className="admin-component-toolbar">
      <label><Search /><input aria-label={`搜索${label}`} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索名称 / 标识…" /></label>
      <div>{([['all', '全部'], ['on', '使用中'], ['off', '已停用']] as const).map(([value, text]) => <button key={value} className={filter === value ? 'is-active' : ''} onClick={() => setFilter(value)}>{text}</button>)}</div>
    </section>
    <p className="admin-component-selection-hint">选择左侧{label}</p>
    <section className="admin-component-grid">
      {filtered.map((item) => <button className="admin-component-card" key={`${item.type}-${item.componentKey}-${item.version}`} onClick={() => pick(item)}>
        <div className="admin-component-card__top"><ComponentIcon type={item.type} /><div><strong>{item.displayName}</strong><small>{item.componentKey}</small></div><b className={`admin-status admin-status--${item.status.toLowerCase()}`}>{statusText(item.status)}</b></div>
        <p>{item.description}</p><footer><span>{type === 'PROMPT' ? `${promptVariables(configText(item.config, 'template')).length} 个变量` : type === 'SKILL' ? `依赖 ${configStrings(item.config, 'requiredTools').length} 个工具` : type === 'TOOL' ? `${String(item.config.risk ?? 'LOW')} · ${String(item.config.sideEffect ?? '只读')}` : item.tags.join(' · ') || '未分类'}</span><ChevronRight /></footer>
      </button>)}
      {!filtered.length && <div className="admin-empty admin-empty--wide"><Search /><strong>没有匹配的{label}</strong><p>换一个搜索词或筛选条件试试。</p></div>}
    </section>
  </>;
}

function ComponentDetail({ component, form, catalog, readOnly, preview, dirty, pending, error, success, onBack, onPreview, onUpdate, onUpdateConfig, onSave }: {
  component: WorkbenchComponent; form: WorkbenchComponentUpdate; catalog: WorkbenchComponent[]; readOnly: boolean; preview: boolean; dirty: boolean; pending: boolean; error: string; success: string; onBack: () => void; onPreview: (value: boolean) => void; onUpdate: <K extends keyof WorkbenchComponentUpdate>(key: K, value: WorkbenchComponentUpdate[K]) => void; onUpdateConfig: (key: string, value: unknown) => void; onSave: () => void;
}) {
  const template = configText(form.config, 'template');
  const toolKeys = catalog.filter((item) => item.type === 'TOOL' && item.status === 'AVAILABLE').map((item) => item.componentKey);
  const requiredTools = configStrings(form.config, 'requiredTools');
  const toggleTool = (key: string) => onUpdateConfig('requiredTools', requiredTools.includes(key) ? requiredTools.filter((item) => item !== key) : [...requiredTools, key]);

  return <>
    <button className="admin-back" onClick={onBack}><ChevronLeft /> 返回{TYPE_LABELS[component.type]}列表</button>
    <header className="admin-detail-head"><ComponentIcon type={component.type} /><div><small>{TYPE_LABELS[component.type]} · v{component.version}</small><h1>{component.displayName}</h1><code>{component.componentKey}</code></div><b className={`admin-status admin-status--${component.status.toLowerCase()}`}>{statusText(component.status)}</b></header>
    {readOnly ? <ToolDetail component={component} catalog={catalog} /> : <div className="admin-detail-stack">
      {component.type === 'PROMPT' && <section className="admin-detail-card"><header><div><h2>模板内容</h2><p>使用 <code>{'{{变量名}}'}</code> 声明运行时变量。</p></div><span>{promptVariables(template).map((value) => <i key={value}>{value}</i>) || '未检测到变量'}</span></header><textarea aria-label="模板内容" className="admin-code-editor" rows={13} value={template} onChange={(event) => onUpdateConfig('template', event.target.value)} /><pre className="admin-template-preview">{template || '模板内容将在这里预览。'}</pre></section>}
      {component.type === 'SKILL' && <>
        <section className="admin-detail-card admin-detail-card--grid"><label>何时使用<textarea aria-label="何时使用" rows={3} value={configText(form.config, 'whenToUse')} onChange={(event) => onUpdateConfig('whenToUse', event.target.value)} placeholder="例如：用户要求制定、调整或复盘训练计划时" /></label><label>何时不使用<textarea aria-label="何时不使用" rows={3} value={configText(form.config, 'whenNotToUse')} onChange={(event) => onUpdateConfig('whenNotToUse', event.target.value)} placeholder="例如：单纯动作讲解或闲聊" /></label></section>
        <section className="admin-detail-card"><header><div><h2>技能逻辑</h2><p>以 Markdown 描述 Agent 的执行步骤与边界。</p></div><div className="admin-segment"><button className={!preview ? 'is-active' : ''} onClick={() => onPreview(false)}>编辑</button><button className={preview ? 'is-active' : ''} onClick={() => onPreview(true)}>预览</button></div></header>{preview ? <pre className="admin-markdown-preview">{configText(form.config, 'content') || '尚未填写技能逻辑。'}</pre> : <textarea aria-label="技能逻辑" className="admin-code-editor" rows={12} value={configText(form.config, 'content')} onChange={(event) => onUpdateConfig('content', event.target.value)} placeholder="# 训练计划编排\n\n1. 查询训练记录\n2. 评估恢复状况\n3. 生成计划" />}</section>
        <section className="admin-detail-card"><header><div><h2>依赖工具</h2><p>仅可选择当前已启用的代码注册工具。</p></div></header><div className="admin-tool-chips">{toolKeys.map((key) => <button key={key} className={requiredTools.includes(key) ? 'is-active' : ''} onClick={() => toggleTool(key)}><Check />{key}</button>)}{!toolKeys.length && <p>暂无可用工具。</p>}</div></section>
      </>}
      <section className="admin-detail-card admin-detail-card--grid"><label>名称<input aria-label="显示名称" value={form.displayName} onChange={(event) => onUpdate('displayName', event.target.value)} /></label><label>标签（逗号分隔）<input aria-label="标签" value={form.tags.join(', ')} onChange={(event) => onUpdate('tags', event.target.value.split(',').map((item) => item.trim()).filter(Boolean))} /></label><label className="admin-detail-card__wide">说明<textarea aria-label="说明" rows={3} value={form.description} onChange={(event) => onUpdate('description', event.target.value)} /></label><label>状态<select aria-label="状态" value={form.status} onChange={(event) => onUpdate('status', event.target.value)}><option value="AVAILABLE">使用中</option><option value="DRAFT">待完成</option><option value="UNAVAILABLE">不可用</option></select></label></section>
      {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}{success && <p className="admin-success-row"><Check />{success}</p>}
      {dirty && <footer className="admin-savebar"><span>有未保存的修改</span><button onClick={onBack}>放弃</button><button className="admin-primary" disabled={pending} onClick={onSave}>{pending ? <LoaderCircle className="is-spin" /> : <Save />} 保存</button></footer>}
    </div>}
  </>;
}

function ToolDetail({ component, catalog }: { component: WorkbenchComponent; catalog: WorkbenchComponent[] }) {
  const config = component.config;
  const references = catalog.filter((item) => item.type === 'SKILL' && configStrings(item.config, 'requiredTools').includes(component.componentKey));
  return <div className="admin-detail-stack"><section className="admin-readonly-banner"><ShieldCheck />此工具由应用代码注册，工作台只读；任何参数变更都必须在代码和 Tool Schema 中完成。</section><section className="admin-detail-card"><h2>运行时注册能力</h2><dl className="admin-kv"><div><dt>风险等级</dt><dd>{String(config.risk ?? 'LOW')}</dd></div><div><dt>副作用</dt><dd>{String(config.sideEffect ?? 'READ_ONLY')}</dd></div><div><dt>来源</dt><dd>{String(config.source ?? 'CODE_REGISTERED')}</dd></div><div><dt>被 Skill 引用</dt><dd>{references.length ? references.map((item) => item.displayName).join('、') : '无'}</dd></div></dl></section><section className="admin-detail-card"><h2>注册 Schema</h2><pre className="admin-code-editor">{JSON.stringify(config, null, 2)}</pre></section></div>;
}
