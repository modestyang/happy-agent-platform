import type { AiContentBlock } from '../api/generated/public';
import { ChatMarkdown } from './ChatMarkdown';
import { ConfirmationCard, ExpandableSurface, SurfaceCard, type ConfirmationViewModel } from './ContentSurface';
import { TrendChart } from './MiniVisuals';

export type ConfirmationRenderState = {
  deciding?: boolean;
  status?: ConfirmationViewModel['status'];
};

export type RenderContext = {
  confirmationStates?: Readonly<Record<string, ConfirmationRenderState>>;
  onCancel?: (confirmationId: string) => void;
  onConfirm?: (confirmationId: string) => void;
};

type RuntimeConfirmationBlock = {
  cancelLabel: string;
  confirmationId: string;
  confirmLabel: string;
  kind: 'CONFIRMATION';
  message: string;
  title: string;
};

function record(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function string(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

export function isRenderableConfirmationBlock(value: unknown): value is RuntimeConfirmationBlock {
  const candidate = record(value);
  return candidate?.kind === 'CONFIRMATION'
    && Boolean(string(candidate.confirmationId))
    && Boolean(string(candidate.title))
    && Boolean(string(candidate.message))
    && Boolean(string(candidate.confirmLabel))
    && Boolean(string(candidate.cancelLabel));
}

function unsupported() {
  return <SurfaceCard className="unsupported-content"><p>暂不支持这类内容</p></SurfaceCard>;
}

const metricLabels = new Map([
  ['BODY_FAT', '体脂趋势'],
  ['RESTING_HEART_RATE', '静息心率趋势'],
  ['WAIST', '腰围趋势'],
  ['WEIGHT', '体重趋势'],
]);

export function AiContentRenderer({ block, context = {} }: { block: AiContentBlock | unknown; context?: RenderContext }) {
  const candidate = record(block);
  if (!candidate) return unsupported();
  if (candidate.kind === 'TEXT') {
    const markdown = string(candidate.markdown);
    return markdown === undefined ? unsupported() : <ChatMarkdown text={markdown} />;
  }
  if (candidate.kind === 'CONFIRMATION') {
    if (!isRenderableConfirmationBlock(candidate)) return unsupported();
    const { cancelLabel, confirmationId, confirmLabel, message, title } = candidate;
    const state = context.confirmationStates?.[confirmationId];
    return <ConfirmationCard
      deciding={state?.deciding}
      model={{ id: confirmationId, title, message, confirmLabel, cancelLabel, status: state?.status }}
      onConfirm={context.onConfirm ? () => context.onConfirm?.(confirmationId) : undefined}
      onCancel={context.onCancel ? () => context.onCancel?.(confirmationId) : undefined}
    />;
  }
  if (candidate.kind === 'BODY_TREND') {
    const metric = string(candidate.metric);
    const unit = string(candidate.unit);
    const label = metric ? metricLabels.get(metric) : undefined;
    if (!label || !unit || !Array.isArray(candidate.points)) return unsupported();
    const points = candidate.points.flatMap((value) => {
      const point = record(value);
      const measuredAt = string(point?.measuredAt);
      const numericValue = point?.value;
      return measuredAt && typeof numericValue === 'number' && Number.isFinite(numericValue) ? [{ key: measuredAt, value: numericValue }] : [];
    }).sort((left, right) => new Date(left.key).getTime() - new Date(right.key).getTime());
    if (points.length !== candidate.points.length) return unsupported();
    const chart = <TrendChart emptyText="继续记录后，这里会显示变化曲线。" label={label} points={points} unit={unit} />;
    return <SurfaceCard eyebrow="身体变化" title={label}>
      <ExpandableSurface variant="media" label={label} title={`${label}详情`} expandedChildren={chart}>{chart}</ExpandableSurface>
    </SurfaceCard>;
  }
  return unsupported();
}
