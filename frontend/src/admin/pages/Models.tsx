import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { AlertTriangle, Check, Cpu, LoaderCircle, MoreHorizontal, Plus, Save } from 'lucide-react';

import { admin, type Model, type Provider } from '../api';
import { AdminModal } from '../components/AdminModal';
import { PageHeading } from '../components/PageHeading';

const empty = {
  modelKey: '', providerKey: '', modelId: '', displayName: '', description: '',
  supportsStreaming: true, supportsToolCalling: true, supportsVision: false,
};

export function Models() {
  const [models, setModels] = useState<Model[]>([]);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [form, setForm] = useState(empty);
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function load() {
    setError('');
    try {
      const [nextModels, nextProviders] = await Promise.all([admin.listModels(), admin.listProviders()]);
      setModels(nextModels); setProviders(nextProviders);
      setForm((current) => ({ ...current, providerKey: current.providerKey || nextProviders.find((item) => item.status === 'ACTIVE')?.providerKey || '' }));
    } catch (caught) { setError(caught instanceof Error ? caught.message : '模型列表加载失败'); }
  }

  useEffect(() => { void load(); }, []);
  const providerNames = useMemo(() => Object.fromEntries(providers.map((item) => [item.providerKey, item.displayName])), [providers]);

  async function create(event: FormEvent) {
    event.preventDefault(); setPending('create'); setError(''); setSuccess('');
    try {
      const created = await admin.createModel(form);
      setModels((current) => [...current, created]); setForm({ ...empty, providerKey: form.providerKey });
      setCreating(false); setSuccess('模型已新增，可立即在 Agent 配置中选择。');
    } catch (caught) { setError(caught instanceof Error ? caught.message : '新增模型失败'); }
    finally { setPending(''); }
  }

  async function toggle(model: Model) {
    setPending(model.modelKey); setError(''); setSuccess('');
    try {
      const updated = await admin.updateModel(model.modelKey, {
        modelId: model.modelId, displayName: model.displayName, description: model.description,
        supportsStreaming: model.supportsStreaming, supportsToolCalling: model.supportsToolCalling,
        supportsVision: model.supportsVision, status: model.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
      }, model.revision);
      setModels((current) => current.map((item) => item.modelKey === updated.modelKey ? updated : item));
      setSuccess(updated.status === 'ACTIVE' ? '模型已启用' : '模型已停用；历史 Agent 版本不受影响');
    } catch (caught) { setError(caught instanceof Error ? caught.message : '更新模型失败'); }
    finally { setPending(''); }
  }

  return <>
    <PageHeading eyebrow="模型目录" title="模型" description="模型隶属于具体服务商；新增模型无需改代码或重新发布应用。" action={<button className="admin-primary" onClick={() => setCreating(true)}><Plus /> 新增模型</button>} />
    <AdminModal open={creating} title="新增模型" description="登记服务商 OpenAI 兼容接口实际接受的模型。" busy={pending === 'create'} onClose={() => setCreating(false)} footer={<><button type="button" className="admin-secondary" disabled={pending === 'create'} onClick={() => setCreating(false)}>取消</button><button type="submit" form="admin-model-create" className="admin-primary" disabled={pending === 'create'}>{pending === 'create' ? <LoaderCircle className="is-spin" /> : <Save />} 保存模型</button></>}>
      <form id="admin-model-create" className="admin-modal-form" onSubmit={create}>
        <div className="admin-form-grid">
        <label>模型服务<select aria-label="模型服务" value={form.providerKey} onChange={(event) => setForm({ ...form, providerKey: event.target.value })}>{providers.filter((item) => item.status === 'ACTIVE').map((item) => <option key={item.providerKey} value={item.providerKey}>{item.displayName}</option>)}</select></label>
        <label>Model Key<input aria-label="Model Key" value={form.modelKey} onChange={(event) => setForm({ ...form, modelKey: event.target.value })} placeholder="minimax-m3" required /></label>
        <label>服务商 Model ID<input aria-label="Model ID" value={form.modelId} onChange={(event) => setForm({ ...form, modelId: event.target.value })} placeholder="MiniMax-M3" required /></label>
        <label>显示名称<input aria-label="模型显示名称" value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} required /></label>
        <label className="is-wide">说明<textarea aria-label="模型说明" value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} /></label>
        </div>
        <details className="admin-capability-disclosure">
          <summary>模型能力声明 <small>仅当服务商模型不支持时关闭</small></summary>
          <div>
            <label><input type="checkbox" checked={form.supportsToolCalling} onChange={(event) => setForm({ ...form, supportsToolCalling: event.target.checked })} /><span><strong>工具调用</strong><small>允许 Agent 向模型声明并调用已绑定工具</small></span></label>
            <label><input type="checkbox" checked={form.supportsVision} onChange={(event) => setForm({ ...form, supportsVision: event.target.checked })} /><span><strong>图片理解</strong><small>允许向该模型发送图片输入</small></span></label>
          </div>
        </details>
      </form>
    </AdminModal>
    {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
    {success && <p className="admin-success-row"><Check />{success}</p>}
    <section className="admin-component-grid">
      {models.map((model) => <article className="admin-component-card" key={model.modelKey}>
        <div className="admin-component-card__top"><span className="admin-component-icon admin-component-icon--model"><Cpu /></span><div><strong>{model.displayName}</strong><small>{model.modelKey}</small></div><div className="admin-model-card__actions"><b className={`admin-status admin-status--${model.status.toLowerCase()}`}>{model.status === 'ACTIVE' ? '使用中' : '已停用'}</b><details className="admin-card-menu"><summary aria-label={`${model.displayName}操作`}><MoreHorizontal /></summary><button disabled={pending === model.modelKey} onClick={() => void toggle(model)}>{pending === model.modelKey && <LoaderCircle className="is-spin" />}{model.status === 'ACTIVE' ? '停用模型' : '启用模型'}</button></details></div></div>
        <p>{model.description || model.modelId}</p>
        <footer><span>{providerNames[model.providerKey] ?? model.providerKey} · {model.modelId}</span></footer>
      </article>)}
    </section>
  </>;
}
