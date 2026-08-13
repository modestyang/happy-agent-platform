import type { CurrentGoalReport } from '../api/generated/public';
import { ExpandableSurface } from './ContentSurface';

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

function dateLabel(value: string) {
  const [, month, day] = value.split('-');
  return `${Number(month)}月${Number(day)}日`;
}

function ReportSectionTitle({ eyebrow, title }: { eyebrow: string; title: string }) {
  return <header className="goal-report-section-title"><small>{eyebrow}</small><h3>{title}</h3></header>;
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

  const chart = <article className="goal-report-chart"><div><strong>体重趋势</strong><small>至少四周</small></div><div className="goal-report-svg" role="img" aria-label="四周体重趋势"><svg viewBox="0 0 300 94" aria-hidden="true"><path className="goal-report-grid" d="M12 76H288M12 48H288M12 20H288" />{path && <path className="goal-report-weight-line" d={path} />}{points.map((point) => point.y === null ? <circle key={point.weekStart} className="goal-report-empty-dot" cx={point.x} cy="76" r="3" /> : <circle key={point.weekStart} className="goal-report-weight-dot" cx={point.x} cy={point.y} r="3.5" />)}</svg><div className="goal-report-axis">{report.weightTrend.map((point) => <span key={point.weekStart}>{weekLabel(point.weekStart)}</span>)}</div></div></article>;
  return <ExpandableSurface variant="media" label="四周体重趋势" title="四周体重趋势详情" expandedChildren={chart}>{chart}</ExpandableSurface>;
}

function TrainingTrend({ report }: { report: CompleteReport }) {
  const top = Math.max(...report.trainingVolume.map((point) => point.minutes), 1);
  const chart = <article className="goal-report-chart"><div><strong>训练量</strong><small>分钟 / 次数</small></div><div className="goal-report-svg" role="img" aria-label="四周训练量趋势"><div className="goal-report-bars">{report.trainingVolume.map((point) => <div key={point.weekStart}><i style={{ height: `${Math.max(4, (point.minutes / top) * 100)}%` }} /><small>{point.sessions}次</small><span>{weekLabel(point.weekStart)}</span></div>)}</div></div></article>;
  return <ExpandableSurface variant="media" label="四周训练量趋势" title="四周训练量趋势详情" expandedChildren={chart}>{chart}</ExpandableSurface>;
}

function actionFor(action: CompleteReport['nextActions'][number], handlers: Props) {
  if (action.action === 'GENERATE_PLAN') return handlers.onGeneratePlan;
  if (action.action === 'OPEN_RECORD') return handlers.onOpenRecord;
  return undefined;
}

function ReadyCard({ report, onRetry, onGeneratePlan, onOpenRecord }: Props & { report: CompleteReport }) {
  const accumulating = report.weightTrend.some((point) => point.valueJin === null) || report.trainingVolume.every((point) => point.sessions === 0);
  const prioritizedMetricKeys = ['CURRENT_MONTH_WORKOUT_COUNT', 'CURRENT_MONTH_WORKOUT_MINUTES', 'GOAL_PROGRESS', 'WEIGHT', 'BODY_RECORD_COUNT', 'MEAL_RECORD_COUNT'];
  const metrics = prioritizedMetricKeys.map((key) => report.metrics.find((metric) => metric.key === key)).filter((metric): metric is CompleteReport['metrics'][number] => metric !== undefined);
  const visibleMetrics = metrics.length >= 4 ? metrics : report.metrics.slice(0, 6);
  return <section className="current-goal-report" aria-label="当前目标累计报告">
    <header className="goal-report-head"><small>{report.state === 'STALE' ? '有新记录待整理' : 'CURRENT GOAL BRIEF'}</small><h2>{report.conclusion.summary}</h2><p>{dateLabel(report.windowStart)}—{dateLabel(report.windowEnd)} · 数据更新至 {dateLabel(report.computedThrough.slice(0, 10))}</p></header>
    {report.state === 'STALE' && <button className="goal-report-stale" onClick={onRetry}>有新记录，刷新报告</button>}
    <section className="goal-report-section" aria-label="本月概览"><ReportSectionTitle eyebrow="01 · SNAPSHOT" title="本月概览" /><div className="goal-report-metrics">{visibleMetrics.map((metric) => <article key={metric.key}><small>{metric.label}</small><strong>{metric.value}<em>{metric.unit}</em></strong><span className={`trend-${metric.trend.toLowerCase()}`}>{metric.comparison === undefined ? '当前目标记录' : `较上期 ${metric.comparison > 0 ? '+' : ''}${metric.comparison}${metric.unit}`}</span></article>)}</div></section>
    <section className="goal-report-section" aria-label="趋势与结构"><ReportSectionTitle eyebrow="02 · PATTERN" title="趋势与结构" /><div className="goal-report-trends"><WeightTrend report={report} /><TrainingTrend report={report} /></div>{accumulating && <p className="goal-report-accumulating">数据积累中：完整时间轴已保留；持续记录后，趋势判断会更可靠。</p>}<div className="goal-report-structure"><div><strong>训练部位覆盖次数占比</strong><p>{report.trainingStructure.length ? report.trainingStructure.map((item) => <span key={item.area}>{item.area} {item.percent}%</span>) : <span>暂无训练部位记录</span>}</p></div><div className="goal-report-ratio"><small>力量 / 有氧按计划时长估算</small><strong>{report.strengthPercent}%</strong><span>力量</span><i style={{ background: `linear-gradient(90deg, #dd865e ${report.strengthPercent}%, #80b8bd ${report.strengthPercent}%)` }} /><strong>{report.cardioPercent}%</strong><span>有氧</span></div></div></section>
    <section className="goal-report-section" aria-label="分析结论"><ReportSectionTitle eyebrow="03 · INTERPRETATION" title="分析结论" /><div className="goal-report-evidence"><div><strong>已有证据</strong>{report.highlights.map((value) => <p key={value}>✓ {value}</p>)}</div><div><strong>需要留意</strong>{report.weaknesses.map((value) => <p key={value}>• {value}</p>)}</div></div></section>
    <section className="goal-report-section" aria-label="行动建议"><ReportSectionTitle eyebrow="04 · NEXT STEPS" title="行动建议" /><div className="goal-report-actions">{report.nextActions.map((action) => <article key={`${action.title}-${action.action}`}><strong>{action.title}</strong><p>{action.rationale}</p>{actionFor(action, { report, onRetry, onGeneratePlan, onOpenRecord }) ? <button onClick={actionFor(action, { report, onRetry, onGeneratePlan, onOpenRecord })}>{action.title}</button> : <span>已记录</span>}</article>)}</div></section>
    <footer className="goal-report-method" role="contentinfo"><strong>数据与方法</strong><p>本报告只使用当前目标周期内的身体、饮食和已完成训练记录；月度数据指截至报告日、且落在当前目标周期内的当月记录。</p><p>当前系统未记录训练强度与力量训练日，因此不能据此判断是否达到指南建议。成人每周活动量可参考 <a href="https://www.who.int/europe/news-room/fact-sheets/item/physical-activity" target="_blank" rel="noreferrer">WHO 成人身体活动指南</a>，健康风险或症状请咨询专业人员。</p></footer>
  </section>;
}

export function CurrentGoalReportCard(props: Props) {
  const { report, onRetry } = props;
  if (report.state === 'QUEUED' || report.state === 'GENERATING') {
    return <section className="current-goal-report goal-report-holding" aria-label="当前目标累计报告"><div aria-label="报告生成中" className="goal-report-loader"><i /><i /><i /></div><strong>花爷正在整理你的当前目标</strong><p>先计算客观记录，再补充结论与下周行动。</p></section>;
  }
  if (report.state === 'FAILED') {
    return <section className="current-goal-report goal-report-failed" aria-label="当前目标累计报告"><strong>这次报告没有生成</strong><p role="alert">{report.failure.message}</p><button onClick={onRetry}>{report.failure.retryable ? '重试生成报告' : '配置后重试生成报告'}</button></section>;
  }
  return <ReadyCard {...props} report={report} />;
}
