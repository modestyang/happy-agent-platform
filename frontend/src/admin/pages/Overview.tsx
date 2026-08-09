import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity, Bot, Blocks, ChevronRight, CircleDashed, Cloud, Cpu, Gauge, PlayCircle,
  Settings2,
} from 'lucide-react';

import { admin, type WorkbenchSnapshot } from '../api';
import { PageHeading } from '../components/PageHeading';

function statusText(status: string) {
  return ({ AVAILABLE: '可用', DRAFT: '待完成', UNAVAILABLE: '不可用', READY: '就绪', DRAFT_AGENT: '草稿' } as Record<string, string>)[status] ?? status;
}
function platformText(status: string) {
  return status === 'READY' ? '已配置运行依赖' : ['NEEDS_CONFIGURATION', 'DEGRADED'].includes(status) ? '尚未配置运行依赖' : status;
}

export function OverviewNavigate() {
  return <Overview />;
}

export function Overview() {
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<WorkbenchSnapshot>();
  const [error, setError] = useState('');

  useEffect(() => {
    let mounted = true;
    admin.snapshot().then(setSnapshot).catch((caught) => mounted && setError(String(caught)));
    return () => { mounted = false; };
  }, []);

  if (error) return <main className="admin-load"><div className="admin-load__error">{error}</div></main>;
  if (!snapshot) return <main className="admin-load"><div><span>正在加载…</span></div></main>;

  const agent = snapshot.agents[0];
  const unavailable = snapshot.components.filter((item) => item.status !== 'AVAILABLE');
  const releaseState = !agent ? '尚未创建' : agent.publishedVersion > 0
    ? `已发布 v${agent.publishedVersion}${agent.status === 'DRAFT' ? ' · 存在未发布草稿' : ''}`
    : '尚未发布';

  return <>
    <PageHeading eyebrow="工作台概览" title="Agent 工作台" description="从配置、发布到运行追踪，都在同一个清晰的工作面里。" action={<button className="admin-primary" onClick={() => navigate('/admin/agents')}><Settings2 /> 配置 Agent</button>} />
    <section className="admin-kpis">
      <article className="admin-kpi admin-kpi--coral"><span><Bot /></span><div><small>Agent</small><strong>{snapshot.overview.agentCount}</strong><p>{releaseState}</p></div></article>
      <article className="admin-kpi admin-kpi--blue"><span><Blocks /></span><div><small>可用组件</small><strong>{snapshot.overview.availableComponents}</strong><p>共 {snapshot.components.length} 个已登记</p></div></article>
      <article className="admin-kpi admin-kpi--mint"><span><Cloud /></span><div><small>已配置服务</small><strong>{snapshot.overview.configuredProviders}</strong><p>共 {snapshot.providers.length} 个 Provider</p></div></article>
      <article className="admin-kpi admin-kpi--sand"><span><PlayCircle /></span><div><small>运行次数</small><strong>{snapshot.overview.runCount}</strong><p>来自真实执行记录</p></div></article>
    </section>
    <section className="admin-overview-grid">
      <article className="admin-card admin-agent-card">
        <div className="admin-card__head">
          <div><small>当前 Agent</small><h2>{agent?.name ?? '尚未创建'}</h2></div>
          <span className={`admin-badge admin-badge--${agent?.status.toLowerCase()}`}>{releaseState}</span>
        </div>
        {agent && (
          <>
            <p>{agent.description}</p>
            <div className="admin-agent-route">
              <span><Code2 />{snapshot.components.find((item) => item.componentKey === agent.frameworkKey)?.displayName ?? agent.frameworkKey}</span>
              <ChevronRight />
              <span><Cloud />{snapshot.providers.find((item) => item.providerKey === agent.providerKey)?.displayName ?? agent.providerKey}</span>
              <ChevronRight />
              <span><Cpu />{snapshot.components.find((item) => item.componentKey === agent.modelKey)?.displayName ?? agent.modelKey}</span>
            </div>
            <footer>
              <span>草稿 revision {agent.revision} · 发布版本 v{agent.publishedVersion || '—'}</span>
              <span>更新于 {new Date(agent.updatedAt).toLocaleString('zh-CN')}</span>
              <button onClick={() => navigate('/admin/agents')}>打开配置 <ChevronRight /></button>
            </footer>
          </>
        )}
      </article>
      <article className="admin-card admin-readiness">
        <div className="admin-card__head">
          <div><small>发布准备度</small><h2>{platformText(snapshot.overview.platformStatus)}</h2></div>
          <Gauge />
        </div>
        <div className="admin-readiness__meter"><i style={{ width: `${Math.round(snapshot.components.filter((item) => item.status === 'AVAILABLE').length / Math.max(snapshot.components.length, 1) * 100)}%` }} /></div>
        <p>{unavailable.length ? `还有 ${unavailable.length} 个组件未就绪；草稿与已发布版本始终分开，发布前会重新校验。` : '必要组件已就绪，可以执行发布检查。'}</p>
        <ul>{unavailable.slice(0, 3).map((item) => <li key={item.componentKey}><span className={`admin-dot admin-dot--${item.status.toLowerCase()}`} /> <b>{item.displayName}</b><small>{String(item.config.reason ?? statusText(item.status))}</small></li>)}</ul>
        <button className="admin-text-button" onClick={() => navigate('/admin/skills')}>查看可维护组件 <ChevronRight /></button>
      </article>
      <article className="admin-card admin-runs-card">
        <div className="admin-card__head">
          <div><small>真实执行</small><h2>Run Trace</h2></div>
          <button onClick={() => navigate('/admin/playground')}>开始调试</button>
        </div>
        {snapshot.runs.length ? snapshot.runs.slice(0, 4).map((run) => (
          <div className="admin-run-row" key={run.runId} onClick={() => navigate(`/admin/runs/${run.runId}`)}>
            <span className={`admin-run-icon admin-run-icon--${run.status.toLowerCase()}`}><PlayCircle /></span>
            <div><strong>{run.agentKey}</strong><small>{new Date(run.startedAt).toLocaleString('zh-CN')}</small></div>
            <em>{run.durationMs} ms</em>
          </div>
        )) : <div className="admin-empty"><CircleDashed /><strong>暂无真实 Run</strong><p>调试台只会展示实际运行所写入的 Trace，不使用模拟数据。</p><button onClick={() => navigate('/admin/playground')}>前往调试台</button></div>}
      </article>
      <article className="admin-card admin-activity-card">
        <div className="admin-card__head">
          <div><small>平台状态</small><h2>配置动态</h2></div>
          <Activity />
        </div>
        <div className="admin-timeline">
          <p><i className="is-coral" /><span><small>草稿</small><strong>草稿可继续编辑，已发布版本保持不变</strong></span></p>
          <p><i className="is-blue" /><span><small>安全</small><strong>Provider 凭据采用加密存储</strong></span></p>
          <p><i className="is-sand" /><span><small>发布</small><strong>{unavailable[0]?.displayName ?? '执行一次发布检查'}</strong></span></p>
        </div>
      </article>
    </section>
  </>;
}

// Local helper to mirror existing style references. Code2 is imported below.
import { Code2 } from 'lucide-react';
