import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';

import { admin, type RunTrace } from '../api';
import { PageHeading } from '../components/PageHeading';
import { TraceConversation } from '../components/TraceConversation';

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
    <TraceConversation trace={trace} />
  </>;
}
