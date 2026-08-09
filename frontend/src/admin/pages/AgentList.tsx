import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, Boxes, ChevronRight, Clock3, Plus, Wrench } from 'lucide-react';

import { admin, type AgentDraft } from '../api';
import { PageHeading } from '../components/PageHeading';

export function AgentList() {
  const [agents, setAgents] = useState<AgentDraft[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let mounted = true;
    admin.snapshot().then((snapshot) => {
      if (mounted) {
        setAgents(snapshot.agents);
        setLoading(false);
      }
    }).catch((caught) => {
      if (mounted) {
        setError(caught instanceof Error ? caught.message : '加载失败');
        setLoading(false);
      }
    });
    return () => { mounted = false; };
  }, []);

  if (loading) return <PageHeading eyebrow="加载中" title="Agent 列表" description="正在拉取 Agent 草稿。" />;
  if (error) return <PageHeading eyebrow="错误" title="Agent 列表" description={error} />;

  return <>
    <PageHeading
      eyebrow="Agent Builder"
      title="Agent"
      description="每张卡片代表一个可独立配置、发布和追踪运行记录的 Agent。"
      action={<div className="admin-page-actions"><span className="admin-page-meta">{agents.length} 个已登记</span><Link className="admin-primary" to="/admin/agents/new"><Plus /> 新建 Agent</Link></div>}
    />
    <section className="admin-agent-grid">
      {agents.map((agent) => (
        <article className="admin-agent-tile" key={agent.agentKey}>
          <header>
            <span className="admin-agent-tile__icon"><Bot /></span>
            <div><small>Agent Key</small><code>{agent.agentKey}</code></div>
            <b className={`admin-badge admin-badge--${agent.status.toLowerCase()}`}>{agent.status}</b>
          </header>
          <h2>{agent.name}</h2>
          <p>{agent.description}</p>
          <div className="admin-agent-tile__facts">
            <span><Boxes /> {agent.frameworkKey}</span>
            <span><Wrench /> {agent.toolKeys.length} Tool · {agent.skillKeys.length} Skill</span>
            <span><Clock3 /> {new Date(agent.updatedAt).toLocaleString('zh-CN')}</span>
          </div>
          <footer>
            <span>草稿 r{agent.revision} · {agent.publishedVersion ? `已发布 v${agent.publishedVersion}` : '尚未发布'}</span>
            <Link to={`/admin/agents/${encodeURIComponent(agent.agentKey)}`} className="admin-primary">进入配置 <ChevronRight /></Link>
          </footer>
        </article>
      ))}
      {!agents.length && <div className="admin-empty admin-empty--wide"><Bot /><strong>暂无 Agent</strong><p>当前尚未登记任何 Agent。</p></div>}
    </section>
  </>;
}
