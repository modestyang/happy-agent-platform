import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, Bot, LoaderCircle, Plus } from 'lucide-react';

import { admin } from '../api';
import { PageHeading } from '../components/PageHeading';

export function AgentCreatePage() {
  const navigate = useNavigate();
  const [agentKey, setAgentKey] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  async function createAgent(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    try {
      const created = await admin.createAgent({ agentKey: agentKey.trim(), name: name.trim(), description: description.trim() });
      navigate(`/admin/agents/${encodeURIComponent(created.agentKey)}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '新建 Agent 失败，请稍后重试。');
    } finally {
      setPending(false);
    }
  }

  return <>
    <PageHeading eyebrow="Agent Builder" title="创建 Agent" description="先登记 Agent 的身份；创建后继续装配模型、提示词与业务能力。" action={<Link className="admin-secondary" to="/admin/agents"><ArrowLeft /> 返回列表</Link>} />
    <section className="admin-create-agent admin-create-agent--page" aria-label="新建 Agent">
      <header><div><span><Bot /></span><div><small>NEW AGENT</small><h2>从一份干净草稿开始</h2><p>会带入平台通用系统提示词、会话记忆和运行骨架；业务工具、技能与 Hook 保持为空，避免跨应用误授权。</p></div></div></header>
      <form onSubmit={createAgent}>
        <label>Agent Key<input aria-label="Agent Key" value={agentKey} onChange={(event) => setAgentKey(event.target.value.toLowerCase())} placeholder="例如 baby.food.coach" maxLength={160} required autoFocus /><small>唯一标识；使用小写字母、数字、点或连字符。</small></label>
        <label>名称<input aria-label="新 Agent 名称" value={name} onChange={(event) => setName(event.target.value)} placeholder="例如 辅食助手" maxLength={160} required /></label>
        <label className="is-wide">说明<textarea aria-label="新 Agent 说明" rows={3} value={description} onChange={(event) => setDescription(event.target.value)} placeholder="它面向谁、负责解决什么问题？" required /></label>
        {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
        <footer><Link className="admin-secondary" to="/admin/agents">取消</Link><button type="submit" className="admin-primary" disabled={pending}>{pending ? <LoaderCircle className="is-spin" /> : <Plus />} {pending ? '正在创建…' : '创建并配置'}</button></footer>
      </form>
    </section>
  </>;
}
