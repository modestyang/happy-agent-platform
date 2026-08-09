import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';

import { admin, type RunTrace } from '../api';
import { PageHeading } from '../components/PageHeading';

export function RunTracePage() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [trace, setTrace] = useState<RunTrace>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!runId) return;
    let mounted = true;
    admin.runTrace(runId).then((result) => {
      if (mounted) { setTrace(result); setLoading(false); }
    }).catch((caught) => {
      if (mounted) { setError(caught instanceof Error ? caught.message : '加载失败'); setLoading(false); }
    });
    return () => { mounted = false; };
  }, [runId]);

  if (loading) return <PageHeading eyebrow="加载中" title="Run Trace" description="正在拉取 trace…" />;
  const returnToTrace = new URLSearchParams(location.search).get('from') === 'trace';
  const returnPath = returnToTrace ? '/admin/traces' : '/admin/playground';
  const returnLabel = returnToTrace ? '返回 Trace' : '返回调试台';
  if (error) return <PageHeading eyebrow="错误" title="Run Trace" description={error} action={<button className="admin-text-button" onClick={() => navigate(returnPath)}><ChevronLeft /> {returnLabel}</button>} />;
  if (!trace) return null;

  return <>
    <PageHeading
      eyebrow="Run Trace"
      title={trace.agentKey}
      description={`Run ${trace.runId} · v${trace.agentVersion} · ${trace.status}`}
      action={<button className="admin-secondary" onClick={() => navigate(returnPath)}><ChevronLeft /> {returnLabel}</button>}
    />
    <section className="admin-trace__metrics">
      <div className="admin-trace__metric"><small>状态</small><strong>{trace.status}</strong><p>{trace.errorCode ?? '无错误'}</p></div>
      <div className="admin-trace__metric"><small>耗时</small><strong>{trace.durationMs} ms</strong><p>{new Date(trace.startedAt).toLocaleString('zh-CN')}</p></div>
      <div className="admin-trace__metric"><small>Tokens</small><strong>{trace.promptTokens + trace.completionTokens}</strong><p>prompt {trace.promptTokens} · completion {trace.completionTokens}</p></div>
      <div className="admin-trace__metric"><small>成本</small><strong>${trace.costUsd.toFixed(4)}</strong><p>{trace.modelKey ?? 'model unknown'}</p></div>
    </section>
    <section className="admin-trace">
      <div className="admin-trace__section">
        <h3>输入</h3>
        <p>{trace.inputSummary || '—'}</p>
      </div>
      <div className="admin-trace__section">
        <h3>输出</h3>
        <p>{trace.outputSummary || '—'}</p>
      </div>
      {trace.errorMessage && (
        <div className="admin-trace__section" style={{background: '#fff3f3', borderColor: '#f3c0c1'}}>
          <h3 style={{color: '#a14c4e'}}>错误</h3>
          <p style={{color: '#a14c4e'}}>{trace.errorMessage}</p>
        </div>
      )}
      <div className="admin-trace__section">
        <h3>事件时间轴（{trace.events.length} 条）</h3>
        <div className="admin-trace__events">
          {trace.events.map((event) => {
            const variant = event.type === 'TOKEN' ? 'admin-token' : event.type === 'RUN_FAILED' ? 'admin-run-failed' : event.type === 'RUN_COMPLETED' ? 'admin-run-completed' : null;
            return <div key={event.sequence} className={`admin-trace__event ${variant ?? ''}`}>
              <small>{new Date(event.occurredAt).toLocaleTimeString('zh-CN')}</small>
              <strong>{event.type}</strong>
              <em><b>{event.title}</b> {event.detail}</em>
            </div>;
          })}
        </div>
      </div>
    </section>
  </>;
}
