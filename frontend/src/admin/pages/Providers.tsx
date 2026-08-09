import { useEffect, useState } from 'react';
import { AlertTriangle, Check, Cloud, KeyRound, LoaderCircle, Save, ShieldCheck } from 'lucide-react';

import { admin, type Provider, type WorkbenchComponent } from '../api';
import { PageHeading } from '../components/PageHeading';

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '保存失败';
}

export function Providers() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [components, setComponents] = useState<Record<string, WorkbenchComponent>>({});
  const [endpoints, setEndpoints] = useState<Record<string, string>>({});
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let mounted = true;
    admin.snapshot().then((snapshot) => {
      if (!mounted) return;
      setProviders(snapshot.providers);
      setComponents(Object.fromEntries(snapshot.components.filter((item) => item.type === 'PROVIDER').map((item) => [item.componentKey, item])));
      setEndpoints(Object.fromEntries(snapshot.providers.map((item) => [item.providerKey, item.endpoint])));
      setLoading(false);
    }).catch((caught) => mounted && setError(messageOf(caught)));
    return () => { mounted = false; };
  }, []);

  async function save(provider: Provider) {
    const secret = secrets[provider.providerKey]?.trim();
    if (!secret) { setError('请输入 API Key。'); return; }
    setPending(provider.providerKey); setError(''); setSuccess('');
    try {
      const updated = await admin.saveProviderCredential(provider.providerKey, secret);
      setProviders((rows) => rows.map((row) => row.providerKey === updated.providerKey ? updated : row));
      setSecrets((current) => ({ ...current, [provider.providerKey]: '' }));
      setSuccess('密钥已加密保存');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  async function saveProvider(provider: Provider) {
    const component = components[provider.providerKey];
    const endpoint = endpoints[provider.providerKey]?.trim();
    if (!component || !endpoint) { setError('Provider Endpoint 必填。'); return; }
    setPending(`provider:${provider.providerKey}`); setError(''); setSuccess('');
    try {
      const updated = await admin.saveComponent('PROVIDER', provider.providerKey, {
        displayName: component.displayName,
        description: component.description,
        status: component.status,
        tags: component.tags,
        config: { ...component.config, endpoint },
      });
      setComponents((rows) => ({ ...rows, [updated.componentKey]: updated }));
      setProviders((rows) => rows.map((item) => item.providerKey === provider.providerKey ? { ...item, endpoint } : item));
      setSuccess('Provider 配置已保存；已发布版本不会被改写。');
    } catch (caught) { setError(messageOf(caught)); }
    finally { setPending(''); }
  }

  if (loading) return <PageHeading eyebrow="加载中" title="模型服务" description="正在拉取 Provider 列表…" />;

  return <>
    <PageHeading eyebrow="模型接入" title="模型服务" description="密钥仅用于写入，保存后页面和接口都不会返回明文。" />
    <section className="admin-provider-grid">
      {providers.map((provider) => (
        <article className="admin-card admin-provider" key={provider.providerKey}>
          <header><span><Cloud /></span><div><small>LLM Provider</small><h2>{provider.displayName}</h2></div><b className={provider.configured ? 'is-configured' : ''}>{provider.configured ? '已配置' : '待配置'}</b></header>
          <label>API Endpoint<input aria-label={`${provider.displayName} Endpoint`} value={endpoints[provider.providerKey] ?? provider.endpoint} onChange={(event) => setEndpoints((current) => ({ ...current, [provider.providerKey]: event.target.value }))} /></label>
          <label>API Key
            <div className="admin-secret"><KeyRound />
              <input aria-label="API Key" type="password" autoComplete="new-password" value={secrets[provider.providerKey] ?? ''} placeholder={provider.configured ? provider.maskedCredential : '输入服务商 API Key'} onChange={(event) => setSecrets((current) => ({ ...current, [provider.providerKey]: event.target.value }))} />
            </div>
            <small>保存后使用 AES-256-GCM 加密，管理端只能看到掩码。</small>
          </label>
          {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
          {success && <p className="admin-success-row"><Check />{success}</p>}
          <footer>
            <span><ShieldCheck />{provider.configured ? '凭据已加密存储' : '尚未保存凭据'}</span>
            <button className="admin-secondary" disabled={pending === `provider:${provider.providerKey}`} onClick={() => void saveProvider(provider)}>
              {pending === `provider:${provider.providerKey}` ? <LoaderCircle className="is-spin" /> : <Save />} 保存服务配置
            </button>
            <button className="admin-primary" disabled={pending === provider.providerKey} onClick={() => void save(provider)}>
              {pending === provider.providerKey ? <LoaderCircle className="is-spin" /> : <Save />} 保存密钥
            </button>
          </footer>
        </article>
      ))}
    </section>
  </>;
}
