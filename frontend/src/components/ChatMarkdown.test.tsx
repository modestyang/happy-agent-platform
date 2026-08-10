import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { ChatMarkdown } from './ChatMarkdown';

describe('ChatMarkdown', () => {
  afterEach(cleanup);

  it('renders GFM structure while keeping raw HTML inert', () => {
    render(<ChatMarkdown text={'## 本周计划\n\n- **深蹲**\n- 平板支撑\n\n| 日期 | 时长 |\n| --- | ---: |\n| 周一 | 30 分钟 |\n\n`核心收紧`\n\n<script>alert(1)</script>'} />);

    expect(screen.getByRole('heading', { name: '本周计划' })).toBeInTheDocument();
    expect(screen.getByRole('list')).toHaveTextContent('深蹲');
    expect(screen.getByRole('table')).toHaveTextContent('30 分钟');
    expect(screen.getByText('核心收紧')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument();
  });

  it('opens external links safely', () => {
    render(<ChatMarkdown text="[动作说明](https://example.com/guide)" />);

    expect(screen.getByRole('link', { name: '动作说明' })).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: '动作说明' })).toHaveAttribute('rel', 'noreferrer noopener');
  });
});
