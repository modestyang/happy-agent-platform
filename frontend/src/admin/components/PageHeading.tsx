import type { ReactNode } from 'react';

export function PageHeading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return <header className="admin-page-head">
    <div><small>{eyebrow}</small><h1>{title}</h1><p>{description}</p></div>
    {action}
  </header>;
}
