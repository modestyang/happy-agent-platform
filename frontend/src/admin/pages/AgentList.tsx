import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, Plus } from 'lucide-react';

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
      eyebrow="多 Agent"
      title="Agent 草稿列表"
      description="选择要编辑的 Agent 草稿。每条记录对应一个独立 Agent，可在工作台分别发布与回滚。"
      action={<button className="admin-secondary" disabled title="暂未启用"> <Plus /> 新建 Agent</button>}
    />
    <section className="admin-card">
      <table className="admin-table">
        <thead>
          <tr>
            <th>Agent Key</th>
            <th>名称</th>
            <th>状态</th>
            <th>已发布版本</th>
            <th>草稿 revision</th>
            <th>更新于</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {agents.map((agent) => (
            <tr key={agent.agentKey}>
              <td><code>{agent.agentKey}</code></td>
              <td>{agent.name}</td>
              <td><span className={`admin-badge admin-badge--${agent.status.toLowerCase()}`}>{agent.status}</span></td>
              <td>v{agent.publishedVersion}</td>
              <td>{agent.revision}</td>
              <td>{new Date(agent.updatedAt).toLocaleString('zh-CN')}</td>
              <td>
                <Link to={`/admin/agents/${encodeURIComponent(agent.agentKey)}`} className="admin-text-button">
                  打开 <ChevronRight />
                </Link>
              </td>
            </tr>
          ))}
          {!agents.length && (
            <tr><td colSpan={7}><div className="admin-empty"><strong>暂无 Agent</strong><p>请新建或导入 Agent 草稿。</p></div></td></tr>
          )}
        </tbody>
      </table>
    </section>
  </>;
}
