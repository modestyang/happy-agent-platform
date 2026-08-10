import { useEffect, type ReactNode } from 'react';
import { X } from 'lucide-react';

export function AdminModal({ open, title, description, busy = false, footer, children, onClose }: {
  open: boolean;
  title: string;
  description?: string;
  busy?: boolean;
  footer?: ReactNode;
  children: ReactNode;
  onClose: () => void;
}) {
  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose();
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      document.body.style.overflow = previous;
      window.removeEventListener('keydown', closeOnEscape);
    };
  }, [busy, onClose, open]);

  if (!open) return null;
  return <div className="admin-modal-backdrop" onMouseDown={(event) => {
    if (event.target === event.currentTarget && !busy) onClose();
  }}>
    <section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="admin-modal-title">
      <header className="admin-modal__head">
        <div><h2 id="admin-modal-title">{title}</h2>{description && <p>{description}</p>}</div>
        <button type="button" className="admin-icon-button" aria-label="关闭弹窗" disabled={busy} onClick={onClose}><X /></button>
      </header>
      <div className="admin-modal__body">{children}</div>
      {footer && <footer className="admin-modal__footer">{footer}</footer>}
    </section>
  </div>;
}
