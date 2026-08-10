import { useEffect, useState, type FormEvent } from 'react';
import { AlertTriangle, Check, Cloud, KeyRound, LoaderCircle, Plus, Save, ShieldCheck } from 'lucide-react';

import { admin, type Provider } from '../api';
import { PageHeading } from '../components/PageHeading';

const messageOf = (error: unknown) => error instanceof Error ? error.message : '保存失败';

export function Providers() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [endpoints, setEndpoints] = useState<Record<string, string>>({});
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [creating, setCreating] = useState(false);
  const [newProvider, setNewProvider] = useState({ providerKey: '', displayName: '', endpoint: '' });
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let mounted = true;
    admin.listProviders().then((items) => {
      if (!mounted) return;
      setProviders(items);
      setEndpoints(Object.fromEntries(items.map((item) => [item.providerKey, item.endpoint])));
      setLoading(false);
    }).catch((caught) => { if (mounted) { setError(messageOf(caught)); setLoading(false); } });
    return () => { mounted = false; };
  }, []);

  async function create(event: FormEvent) {
    event.preventDefault(); setPending('create'); setError(''); setSuccess('');
    try {
      const created = await admin.createProvider(newProvider);
      setProviders((rows) => [...rows, created]);
      setEndpoints((current) => ({ ...current, [created.providerKey]: created.endpoint }));
      setNewProvider({ providerKey: '', displayName: '', endpoint: '' }); setCreating(false);
      setSuccess('模型服务已新增，请继续保存 API Key 并登记模型。');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  async function saveCredential(provider: Provider) {
    const secret = secrets[provider.providerKey]?.trim();
    if (!secret) { setError('请输入 API Key。'); return; }
    setPending(provider.providerKey); setError(''); setSuccess('');
    try {
      const updated = await admin.saveProviderCredential(provider.providerKey, secret);
      setProviders((rows) => rows.map((row) => row.providerKey === updated.providerKey ? { ...row, configured: updated.configured, maskedCredential: updated.maskedCredential } : row));
      setSecrets((current) => ({ ...current, [provider.providerKey]: '' })); setSuccess('密钥已加密保存');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  async function saveProvider(provider: Provider, nextStatus = provider.status) {
    const endpoint = endpoints[provider.providerKey]?.trim();
    if (!endpoint) { setError('Provider Endpoint 必填。'); return; }
    setPending(`provider:${provider.providerKey}`); setError(''); setSuccess('');
    try {
      const updated = await admin.updateProvider(provider.providerKey, { displayName: provider.displayName, endpoint, status: nextStatus }, provider.revision);
      setProviders((rows) => rows.map((item) => item.providerKey === updated.providerKey ? updated : item));
      setSuccess(updated.status === 'ACTIVE' ? '服务配置已保存' : '服务已停用；历史配置和凭据仍保留');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  if (loading) return <PageHeading eyebrow="加载中" title="模型服务" description="正在拉取 Provider 列表…" />;

  return <>
    <PageHeading eyebrow="模型接入" title="模型服务" description="支持手动接入 OpenAI 标准协议；服务商和其模型保持明确从属关系。" action={<button className="admin-primary" onClick={() => setCreating((value) => !value)}><Plus /> 新增服务</button>} />
    {creating && <form className="admin-card admin-form-card" onSubmit={create}>
      <div className="admin-section-title"><span><Cloud /></span><div><h2>新增 OpenAI 兼容服务</h2><p>Endpoint 例如 https://api.example.com/v1。</p></div></div>
      <div className="admin-form-grid"><label>Provider Key<input aria-label="Provider Key" value={newProvider.providerKey} onChange={(event) => setNewProvider({ ...newProvider, providerKey: event.target.value })} placeholder="my-provider" required /></label><label>显示名称<input aria-label="服务商显示名称" value={newProvider.displayName} onChange={(event) => setNewProvider({ ...newProvider, displayName: event.target.value })} required /></label><label className="is-wide">API Endpoint<input aria-label="新服务 Endpoint" value={newProvider.endpoint} onChange={(event) => setNewProvider({ ...newProvider, endpoint: event.target.value })} placeholder="https://api.example.com/v1" required /></label></div>
      <footer className="admin-sticky-actions"><span>协议固定为 OPENAI_COMPATIBLE</span><div><button type="button" onClick={() => setCreating(false)}>取消</button><button className="admin-primary" disabled={pending === 'create'}>{pending === 'create' ? <LoaderCircle className="is-spin" /> : <Save />} 保存服务</button></div></footer>
    </form>}
    {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
    {success && <p className="admin-success-row"><Check />{success}</p>}
    <section className="admin-provider-grid">
      {providers.map((provider) => <article className="admin-card admin-provider" key={provider.providerKey}>
        <header><span><Cloud /></span><div><small>{provider.protocol}</small><h2>{provider.displayName}</h2></div><b className={provider.configured ? 'is-configured' : ''}>{provider.status === 'DISABLED' ? '已停用' : provider.configured ? '已配置' : '待配置'}</b></header>
        <label>API Endpoint<input aria-label={`${provider.displayName} Endpoint`} value={endpoints[provider.providerKey] ?? provider.endpoint} onChange={(event) => setEndpoints((current) => ({ ...current, [provider.providerKey]: event.target.value }))} /></label>
        <label>API Key<div className="admin-secret"><KeyRound /><input aria-label={`${provider.displayName} API Key`} type="password" autoComplete="new-password" value={secrets[provider.providerKey] ?? ''} placeholder={provider.configured ? provider.maskedCredential : '输入服务商 API Key'} onChange={(event) => setSecrets((current) => ({ ...current, [provider.providerKey]: event.target.value }))} /></div><small>保存后使用 AES-256-GCM 加密，管理端只返回掩码。</small></label>
        <footer><span><ShieldCheck />{provider.configured ? '凭据已加密存储' : '尚未保存凭据'}</span><button className="admin-secondary" disabled={pending === `provider:${provider.providerKey}`} onClick={() => void saveProvider(provider)}>{pending === `provider:${provider.providerKey}` ? <LoaderCircle className="is-spin" /> : <Save />} 保存配置</button><button className="admin-secondary" onClick={() => void saveProvider(provider, provider.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')}>{provider.status === 'ACTIVE' ? '停用' : '启用'}</button><button className="admin-primary" disabled={pending === provider.providerKey || provider.status === 'DISABLED'} onClick={() => void saveCredential(provider)}>{pending === provider.providerKey ? <LoaderCircle className="is-spin" /> : <Save />} 保存密钥</button></footer>
      </article>)}
    </section>
  </>;
}
