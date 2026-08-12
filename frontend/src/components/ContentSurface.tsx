import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, CircleX, LoaderCircle, Maximize2, X } from 'lucide-react';

type SurfaceCardProps = {
  ariaLabel?: string;
  children: ReactNode;
  className?: string;
  eyebrow?: string;
  title?: string;
};

export function SurfaceCard({ ariaLabel, children, className = '', eyebrow, title }: SurfaceCardProps) {
  return <section className={`surface-card${className ? ` ${className}` : ''}`} aria-label={ariaLabel}>
    {(eyebrow || title) && <header className="surface-card__head">
      <div>{eyebrow && <small>{eyebrow}</small>}{title && <strong>{title}</strong>}</div>
    </header>}
    {children}
  </section>;
}

export type ConfirmationViewModel = {
  cancelLabel: string;
  confirmLabel: string;
  id: string;
  message?: string;
  scopeLabel?: string;
  status?: 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  title: string;
};

type ConfirmationCardProps = {
  children?: ReactNode;
  deciding?: boolean;
  disabled?: boolean;
  model: ConfirmationViewModel;
  onCancel?: () => void;
  onConfirm?: () => void;
};

export function ConfirmationCard({ children, deciding = false, disabled = false, model, onCancel, onConfirm }: ConfirmationCardProps) {
  const status = model.status ?? 'REQUESTED';
  const statusText = status === 'APPROVED' ? '已确认并保存' : status === 'REJECTED' ? '已取消，未保存' : status === 'EXPIRED' ? '确认已过期' : '';
  return <SurfaceCard className="run-approval confirmation-card" ariaLabel="操作确认">
    <header><strong>{model.title}</strong>{model.scopeLabel && <small>{model.scopeLabel}</small>}</header>
    {model.message && <p className="confirmation-card__message">{model.message}</p>}
    {children}
    {status === 'REQUESTED' ? <footer>
      <button type="button" disabled={disabled || deciding || !onCancel} onClick={onCancel}><CircleX />{model.cancelLabel}</button>
      <button type="button" disabled={disabled || deciding || !onConfirm} onClick={onConfirm}>{deciding ? <LoaderCircle className="is-spin" /> : <CheckCircle2 />}{model.confirmLabel}</button>
    </footer> : <p className={`run-approval__status is-${status.toLowerCase()}`}>{statusText}</p>}
  </SurfaceCard>;
}

type ExpandableSurfaceProps = {
  children: ReactNode;
  className?: string;
  edgeToEdge?: boolean;
  expandedChildren?: ReactNode;
  label: string;
  title: string;
  variant?: 'default' | 'media';
};

export function ExpandableSurface({ children, className = '', edgeToEdge = false, expandedChildren = children, label, title, variant = 'default' }: ExpandableSurfaceProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const media = variant === 'media';

  useEffect(() => {
    if (!open) return;
    const previousOverflow = document.body.style.overflow;
    const backgroundElements = Array.from(document.body.children).flatMap((element) =>
      element instanceof HTMLElement && !element.classList.contains('surface-dialog-backdrop')
        ? [{ element, inert: element.inert }]
        : []);
    document.body.style.overflow = 'hidden';
    backgroundElements.forEach(({ element }) => { element.inert = true; });
    closeRef.current?.focus();
    const handleDialogKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ));
      const first = focusable[0];
      const last = focusable.at(-1);
      if (!first || !last) {
        event.preventDefault();
        return;
      }
      const active = document.activeElement;
      if (event.shiftKey ? active === first || !dialog.contains(active) : active === last || !dialog.contains(active)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    };
    document.addEventListener('keydown', handleDialogKey);
    return () => {
      document.removeEventListener('keydown', handleDialogKey);
      document.body.style.overflow = previousOverflow;
      backgroundElements.forEach(({ element, inert }) => { element.inert = inert; });
      triggerRef.current?.focus();
    };
  }, [open]);

  function closeFromBackdrop(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget) setOpen(false);
  }

  return <div className={`expandable-surface${className ? ` ${className}` : ''}`}>
    <div className="expandable-surface__inline">{children}</div>
    <button ref={triggerRef} type="button" className={media ? 'expandable-surface__media-trigger' : 'expandable-surface__trigger'} aria-label={`放大查看${label}`} onClick={() => setOpen(true)}>{!media && <Maximize2 />}</button>
    {open && createPortal(
      <div className="surface-dialog-backdrop" onMouseDown={closeFromBackdrop}>
        <section ref={dialogRef} className={`surface-dialog${edgeToEdge ? ' surface-dialog--edge-to-edge' : ''}${media ? ' surface-dialog--media' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
          {media
            ? <button ref={closeRef} className="surface-dialog__close" type="button" aria-label={`关闭${title}`} onClick={() => setOpen(false)}><X /></button>
            : <header><strong>{title}</strong><button ref={closeRef} type="button" aria-label={`关闭${title}`} onClick={() => setOpen(false)}><X /></button></header>}
          <div className="surface-dialog__content">{expandedChildren}</div>
        </section>
      </div>,
      document.body,
    )}
  </div>;
}

export function DataTable({ children }: { children?: ReactNode }) {
  if (!children) return <SurfaceCard className="data-table-empty"><p>暂无表格数据</p></SurfaceCard>;
  const renderTable = () => <table>{children}</table>;
  return <ExpandableSurface
    className="data-table-surface"
    edgeToEdge
    label="表格"
    title="表格详情"
    expandedChildren={<div className="data-table-viewport data-table-viewport--expanded">{renderTable()}</div>}
  >
    <div className="data-table-viewport" role="region" aria-label="可横向滑动的表格" tabIndex={0}>{renderTable()}</div>
    <small className="data-table-hint">左右滑动查看</small>
  </ExpandableSurface>;
}
