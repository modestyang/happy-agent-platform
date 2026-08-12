import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { DataTable } from './ContentSurface';

function normalizeAssistantBrand(text: string) {
  return text.replace(/瘦瘦(?:\s*AI\s*花爷)?/g, '花爷');
}

export function ChatMarkdown({ text, className = 'md' }: { text: string; className?: string }) {
  return <div className={className}>
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ children, ...props }) => <a {...props} target="_blank" rel="noreferrer noopener">{children}</a>,
        table: ({ children }) => <DataTable>{children}</DataTable>,
      }}
    >
      {normalizeAssistantBrand(text)}
    </ReactMarkdown>
  </div>;
}
