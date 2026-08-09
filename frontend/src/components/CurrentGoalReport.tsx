import type { CurrentGoalReport } from '../api/generated/public';

type CompleteReport = Extract<CurrentGoalReport, { state: 'READY' | 'STALE' }>;

type Props = {
  report: CurrentGoalReport;
  onRetry: () => void;
  onGeneratePlan: () => void;
  onOpenRecord: () => void;
};

function weekLabel(value: string) {
  const [, month, day] = value.split('-');
  return `${Number(month)}/${Number(day)}`;
}

function WeightTrend({ report }: { report: CompleteReport }) {
  const values = report.weightTrend.map((point) => point.valueJin).filter((value): value is number => value !== null);
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 1);
  const span = Math.max(max - min, 1);
  const points = report.weightTrend.map((point, index) => {
    const value = point.valueJin;
    const x = report.weightTrend.length === 1 ? 150 : 14 + (index / (report.weightTrend.length - 1)) * 272;
    const y = value === null ? null : 74 - ((value - min) / span) * 52;
    return { ...point, x, y };
  });
  const path = points.filter((point) => point.y !== null).map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' ');

  return <article className="goal-report-chart"><div><strong>体重趋势</strong><small>至少四周</small></div><div className="goal-report-svg" role="img" aria-label="四周体重趋势"><svg viewBox="0 0 300 94" aria-hidden="true"><path className="goal-report-grid" d="M12 76H288M12 48H288M12 20H288" />{path && <path className="goal-report-weight-line" d={path} />}{points.map((point) => point.y === null ? <circle key={point.weekStart} className="goal-report-empty-dot" cx={point.x} cy="76" r="3" /> : <circle key={point.weekStart} className="goal-report-weight-dot" cx={point.x} cy={point.y} r="3.5" />)}</svg><div className="goal-report-axis">{report.weightTrend.map((point) => <span key={point.weekStart}>{weekLabel(point.weekStart)}</span>)}</div></div></article>;
}

function TrainingTrend({ report }: { report: CompleteReport }) {
  const top = Math.max(...report.trainingVolume.map((point) => point.minutes), 1);
  return <article className="goal-report-chart"><div><strong>训练量</strong><small>分钟 / 次数</small></div><div className="goal-report-svg" role="img" aria-label="四周训练量趋势"><div className="goal-report-bars">{report.trainingVolume.map((point) => <div key={point.weekStart}><i style={{ height: `${Math.max(4, (point.minutes / top) * 100)}%` }} /><small>{point.sessions}次</small><span>{weekLabel(point.weekStart)}</span></div>)}</div></div></article>;
}

function actionFor(action: CompleteReport['nextActions'][number], handlers: Props) {
  if (action.action === 'GENERATE_PLAN') return handlers.onGeneratePlan;
  if (action.action === 'OPEN_RECORD') return handlers.onOpenRecord;
  return undefined;
}

function ReadyCard({ report, onRetry, onGeneratePlan, onOpenRecord }: Props & { report: CompleteReport }) {
  const accumulating = report.weightTrend.some((point) => point.valueJin === null) || report.trainingVolume.every((point) => point.sessions === 0);
  return <section className="current-goal-report" aria-label="当前目标累计报告">
    <header className="goal-report-head"><div><small>{report.state === 'STALE' ? '有新记录待整理' : '当前目标累计报告'}</small><h2>{report.conclusion.summary}</h2></div><strong aria-label={`综合评分 ${report.conclusion.score} 分，等级 ${report.conclusion.grade}`}>{report.conclusion.score} 分 · {report.conclusion.grade}</strong></header>
    {report.state === 'STALE' && <button className="goal-report-stale" onClick={onRetry}>有新记录，刷新报告</button>}
    <section className="goal-report-metrics" aria-label="关键指标">{report.metrics.map((metric) => <article key={metric.key}><small>{metric.label}</small><strong>{metric.value}<em>{metric.unit}</em></strong><span className={`trend-${metric.trend.toLowerCase()}`}>{metric.comparison === undefined ? '暂无环比' : `较上期 ${metric.comparison > 0 ? '+' : ''}${metric.comparison}${metric.unit}`}</span></article>)}</section>
    <section className="goal-report-trends"><WeightTrend report={report} /><TrainingTrend report={report} /></section>
    {accumulating && <p className="goal-report-accumulating">数据积累中：四周横轴已保留，继续记录会让趋势更完整。</p>}
    <section className="goal-report-structure"><div><strong>训练结构</strong><p>{report.trainingStructure.length ? report.trainingStructure.map((item) => <span key={item.area}>{item.area} {item.percent}%</span>) : <span>暂无训练部位记录</span>}</p></div><div className="goal-report-ratio"><strong>{report.strengthPercent}%</strong><span>力量</span><i style={{ background: `linear-gradient(90deg, #dd865e ${report.strengthPercent}%, #80b8bd ${report.strengthPercent}%)` }} /><strong>{report.cardioPercent}%</strong><span>有氧</span></div></section>
    <section className="goal-report-evidence"><div><strong>做得不错</strong>{report.highlights.map((value) => <p key={value}>✓ {value}</p>)}</div><div><strong>下周留意</strong>{report.weaknesses.map((value) => <p key={value}>• {value}</p>)}</div></section>
    <section className="goal-report-actions" aria-label="下周行动">{report.nextActions.map((action) => <article key={`${action.title}-${action.action}`}><strong>{action.title}</strong><p>{action.rationale}</p>{actionFor(action, { report, onRetry, onGeneratePlan, onOpenRecord }) ? <button onClick={actionFor(action, { report, onRetry, onGeneratePlan, onOpenRecord })}>{action.title}</button> : <span>已记录</span>}</article>)}</section>
  </section>;
}

export function CurrentGoalReportCard(props: Props) {
  const { report, onRetry } = props;
  if (report.state === 'QUEUED' || report.state === 'GENERATING') {
    return <section className="current-goal-report goal-report-holding" aria-label="当前目标累计报告"><div aria-label="报告生成中" className="goal-report-loader"><i /><i /><i /></div><strong>瘦瘦正在整理你的当前目标</strong><p>先计算客观记录，再补充结论与下周行动。</p></section>;
  }
  if (report.state === 'FAILED') {
    return <section className="current-goal-report goal-report-failed" aria-label="当前目标累计报告"><strong>这次报告没有生成</strong><p role="alert">{report.failure.message}</p><button onClick={onRetry}>{report.failure.retryable ? '重试生成报告' : '配置后重试生成报告'}</button></section>;
  }
  return <ReadyCard {...props} report={report} />;
}
