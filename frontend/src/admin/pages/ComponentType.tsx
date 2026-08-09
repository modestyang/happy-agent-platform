import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronRight, Save, Search, AlertTriangle, LoaderCircle, Check } from 'lucide-react';

import { admin, type WorkbenchComponent, type WorkbenchComponentUpdate } from '../api';
import { PageHeading } from '../components/PageHeading';

const TYPE_LABELS: Record<string, string> = {
  FRAMEWORK: '框架', PROVIDER: '服务商', MODEL: '模型', PROMPT: '提示词',
  MEMORY: '记忆', TOOL: '工具', SKILL: '技能', HOOK: 'Hook',
};

function statusText(status: string) {
  return ({ AVAILABLE: '可用', DRAFT: '待完成', UNAVAILABLE: '不可用' } as Record<string, string>)[status] ?? status;
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '保存失败';
}

export function ComponentType({ type, label, readOnly = false }: { type: string; label: string; readOnly?: boolean }) {
  const [components, setComponents] = useState<WorkbenchComponent[]>([]);
  const [selected, setSelected] = useState<WorkbenchComponent>();
  const [form, setForm] = useState<WorkbenchComponentUpdate>({
    displayName: '', description: '', status: 'DRAFT', tags: [], config: {},
  });
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(true);
  const requestId = useRef(0);

  async function loadComponents() {
    const currentRequest = ++requestId.current;
    setLoading(true);
    setComponents([]);
    setSelected(undefined);
    setForm({ displayName: '', description: '', status: 'DRAFT', tags: [], config: {} });
    setQuery('');
    setError('');
    setSuccess('');
    try {
      const snapshot = await admin.snapshot();
      if (currentRequest !== requestId.current) return;
      setComponents(snapshot.components.filter((item) => item.type === type));
      setLoading(false);
    } catch (caught) {
      if (currentRequest !== requestId.current) return;
      setError(messageOf(caught));
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadComponents();
    return () => { requestId.current += 1; };
  }, [type]);

  const filtered = useMemo(() => components.filter((item) => {
    const text = query.trim().toLowerCase();
    if (!text) return true;
    return `${item.displayName}${item.description}${item.tags.join('')}`.toLowerCase().includes(text);
  }), [components, query]);

  function pick(component: WorkbenchComponent) {
    setSelected(component);
    setForm({ displayName: component.displayName, description: component.description, status: component.status, tags: component.tags, config: component.config });
    setError('');
    setSuccess('');
  }

  async function save() {
    if (!selected) return;
    setPending(selected.componentKey); setError(''); setSuccess('');
    try {
      const updated = await admin.saveComponent(selected.type, selected.componentKey, form);
      setComponents((rows) => rows.map((row) => row.componentKey === updated.componentKey && row.version === updated.version ? updated : row));
      setSelected(updated);
      setSuccess('组件配置已保存');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  const staleRouteState = selected?.type !== undefined && selected.type !== type;
  if (loading || staleRouteState) return <PageHeading eyebrow="加载中" title={label} description="正在拉取组件目录…" />;

  if (error) return <>
    <PageHeading eyebrow="可组合能力" title={label} description={`登记可被 Agent 引用的 ${label} 组件，每个组件拥有独立版本与状态。`} />
    <div className="admin-empty"><AlertTriangle /><strong>组件目录加载失败</strong><p>{error}</p><button className="admin-primary" onClick={() => void loadComponents()}>重新加载</button></div>
  </>;

  return <>
    <PageHeading eyebrow="可组合能力" title={label} description={`登记可被 Agent 引用的 ${label} 组件，每个组件拥有独立版本与状态。`} />
    <section className="admin-component-layout">
      <div className="admin-toolbar">
        <label><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索组件" /></label>
        <span style={{color: '#7f8b97', fontSize: '10px'}}>{filtered.length} 个</span>
      </div>
      <div className="admin-component-list">
        {filtered.map((item) => (
          <button key={`${item.type}-${item.componentKey}-${item.version}`} className={selected?.componentKey === item.componentKey ? 'is-selected' : ''} onClick={() => pick(item)}>
            <span className={`admin-component-icon admin-component-icon--${item.type.toLowerCase()}`}><Check /></span>
            <div>
              <small>{TYPE_LABELS[item.type] ?? item.type} · v{item.version}</small>
              <strong>{item.displayName}</strong>
              <p>{item.description}</p>
              <footer>
                {item.tags.map((tag) => <i key={tag}>{tag}</i>)}
                <b className={`admin-status admin-status--${item.status.toLowerCase()}`}>{statusText(item.status)}</b>
              </footer>
            </div>
            <ChevronRight />
          </button>
        ))}
        {!filtered.length && <div className="admin-empty"><Search /><strong>没有匹配的组件</strong><p>换一个搜索词试试。</p></div>}
      </div>
      <aside className="admin-component-detail">
        {selected ? (
          <>
            <span className={`admin-component-icon admin-component-icon--${selected.type.toLowerCase()}`}><Check /></span>
            <small>{TYPE_LABELS[selected.type] ?? selected.type}</small>
            <h2>{selected.displayName}</h2>
            <p>{selected.description}</p>
            <dl>
              <div><dt>唯一标识</dt><dd>{selected.componentKey}</dd></div>
              <div><dt>状态</dt><dd>{statusText(selected.status)}</dd></div>
              <div><dt>版本</dt><dd>v{selected.version}</dd></div>
            </dl>
            <section className="admin-card admin-form-card">
              {readOnly ? <>
                <strong>运行时注册能力</strong>
                <p>Tool 由应用代码登记并受 scope guard 约束；工作台只展示其元数据，不能在此修改。</p>
                <pre className="admin-component-config">{JSON.stringify(selected.config, null, 2)}</pre>
              </> : <>
              <label>显示名称<input value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} /></label>
              <label className="is-wide">说明<textarea rows={3} value={form.description} onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))} /></label>
              <label>状态<select value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}>
                <option value="AVAILABLE">AVAILABLE</option>
                <option value="DRAFT">DRAFT</option>
                <option value="UNAVAILABLE">UNAVAILABLE</option>
              </select></label>
              <label>Tags（逗号分隔）<input value={form.tags.join(',')} onChange={(event) => setForm((current) => ({ ...current, tags: event.target.value.split(',').map((t) => t.trim()).filter(Boolean) }))} /></label>
              {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
              {success && <p className="admin-success-row"><Check />{success}</p>}
              <footer>
                <button className="admin-primary" disabled={Boolean(pending)} onClick={() => void save()}>
                  {pending === selected.componentKey ? <LoaderCircle className="is-spin" /> : <Save />} 保存
                </button>
              </footer>
              </>}
            </section>
          </>
        ) : <div className="admin-empty"><strong>选择左侧组件</strong><p>这里会展示其配置详情。</p></div>}
    </aside>
  </section>
  </>;
}
