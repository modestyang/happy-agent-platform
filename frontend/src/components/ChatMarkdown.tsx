import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

export function ChatMarkdown({ text, className = 'md' }: { text: string; className?: string }) {
  return <div className={className}>
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ children, ...props }) => <a {...props} target="_blank" rel="noreferrer noopener">{children}</a>,
      }}
    >
      {text}
    </ReactMarkdown>
  </div>;
}
