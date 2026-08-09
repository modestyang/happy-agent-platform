import { useEffect } from 'react';

import type { CurrentGoalReport } from '../api/generated/public';

const noop = () => {};

function inProgress(report: CurrentGoalReport) {
  return report.state === 'QUEUED' || report.state === 'GENERATING';
}

/** Repeats a bounded GET while durable report work is still non-terminal. */
export function useCurrentGoalReportPolling(
  report: CurrentGoalReport | undefined,
  read: () => Promise<CurrentGoalReport>,
  onReport: (report: CurrentGoalReport) => void,
  delayMs: number,
  onError: (error: unknown) => void = noop,
) {
  useEffect(() => {
    if (!report || !inProgress(report)) return;
    let cancelled = false;
    let timer: number | undefined;
    const schedule = () => {
      timer = window.setTimeout(async () => {
        try {
          const next = await read();
          if (cancelled) return;
          onReport(next);
          if (inProgress(next)) schedule();
        } catch (error) {
          if (cancelled) return;
          onError(error);
          schedule();
        }
      }, delayMs);
    };
    schedule();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [delayMs, onError, onReport, read, report?.reportId, report?.state]);
}
