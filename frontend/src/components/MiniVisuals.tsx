type BodyRecord = { recordedAt: string; weightJin?: number };

export function WeightSparkline({ records }: { records: BodyRecord[] }) {
  const points = [...records]
    .filter((record): record is BodyRecord & { weightJin: number } => typeof record.weightJin === 'number')
    .sort((left, right) => new Date(left.recordedAt).getTime() - new Date(right.recordedAt).getTime());

  if (!points.length) return <div className="chart-empty">记录体重后，这里会长出一条属于你的曲线。</div>;

  const values = points.map((point) => point.weightJin);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const spread = Math.max(max - min, 1);
  const plotted = points.map((point, index) => {
    const x = points.length === 1 ? 150 : 16 + (index / (points.length - 1)) * 268;
    const y = 80 - ((point.weightJin - min) / spread) * 56;
    return { x, y, value: point.weightJin };
  });
  const path = plotted.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ');

  return <div className="weight-chart" role="img" aria-label={`体重趋势，共 ${points.length} 条记录`}>
    <svg viewBox="0 0 300 102" aria-hidden="true">
      <defs><linearGradient id="weight-fill" x1="0" x2="0" y1="0" y2="1"><stop stopColor="#ff8d67" stopOpacity=".34" /><stop offset="1" stopColor="#ff8d67" stopOpacity="0" /></linearGradient></defs>
      <path className="weight-chart__area" d={`${path} L${plotted.at(-1)?.x},96 L${plotted[0].x},96 Z`} />
      <path className="weight-chart__line" d={path} />
      {plotted.map((point) => <circle key={`${point.x}-${point.value}`} cx={point.x} cy={point.y} r="4" />)}
    </svg>
    <div><span>{points[0].weightJin} 斤</span><strong>{points.at(-1)?.weightJin} 斤</strong></div>
  </div>;
}

export function BodyActivation({ areas }: { areas: string[] }) {
  const active = (pattern: RegExp) => areas.some((area) => pattern.test(area));
  const core = active(/核心|腹|全身/);
  const upper = active(/上肢|肩|胸|背|全身/);
  const lower = active(/下肢|臀|腿|全身/);

  return <div className="body-activation" role="img" aria-label={`当前计划点亮部位：${areas.join('、') || '暂无'}`}>
    <svg viewBox="0 0 160 220" aria-hidden="true">
      <circle cx="80" cy="28" r="18" className="body-base" />
      <path d="M80 50 C58 50 48 70 52 100 L59 138 H101 L108 100 C112 70 102 50 80 50Z" className={core ? 'body-hot' : 'body-base'} />
      <path d="M55 62 L25 116 M105 62 L135 116" className={upper ? 'body-hot-line' : 'body-base-line'} />
      <path d="M69 137 L52 202 M91 137 L108 202" className={lower ? 'body-hot-line' : 'body-base-line'} />
      <circle cx="80" cy="89" r="8" className={core ? 'body-pulse' : 'body-dot'} />
    </svg>
    <div>{areas.length ? areas.map((area) => <span key={area}>{area}</span>) : <span>等待今天的训练计划</span>}</div>
  </div>;
}
