import type { ReactNode } from 'react';

function inlineMarkdown(text: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, index) =>
    part.length > 4 && part.startsWith('**') && part.endsWith('**')
      ? <strong key={index}>{part.slice(2, -2)}</strong>
      : part,
  );
}

export function ChatMarkdown({ text, className = 'md' }: { text: string; className?: string }) {
  const nodes: ReactNode[] = [];
  let bullets: string[] = [];
  const flushBullets = (key: string) => {
    if (!bullets.length) return;
    const items = bullets;
    bullets = [];
    nodes.push(<ul key={key}>{items.map((item, itemIndex) => <li key={itemIndex}>{inlineMarkdown(item)}</li>)}</ul>);
  };
  text.split('\n').forEach((raw, index) => {
    const line = raw.trim();
    const bullet = line.match(/^(?:[-•*]|\d+[.、)])\s*(.+)$/);
    if (bullet) {
      bullets.push(bullet[1]);
      return;
    }
    flushBullets(`ul-${index}`);
    if (line) nodes.push(<p key={`p-${index}`}>{inlineMarkdown(line)}</p>);
  });
  flushBullets('ul-end');
  return <div className={className}>{nodes}</div>;
}
