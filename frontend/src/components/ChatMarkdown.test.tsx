import { readFileSync } from 'node:fs';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('keeps wide tables in a local scroll viewport and expands them in a dialog', async () => {
    const user = userEvent.setup();
    render(<ChatMarkdown text={'| 动作 | 说明 |\n| --- | --- |\n| 深蹲 | 膝盖跟随脚尖方向 |'} />);

    const viewport = screen.getByRole('region', { name: '可横向滑动的表格' });
    expect(viewport).toContainElement(screen.getByRole('table'));

    const trigger = screen.getByRole('button', { name: '放大查看表格' });
    await user.click(trigger);
    expect(screen.getByRole('dialog', { name: '表格详情' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: '表格详情' })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('keeps table layout rules after the expanded table is portaled outside markdown', async () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);
    const user = userEvent.setup();

    try {
      render(<ChatMarkdown text={'| 动作 | 说明 |\n| --- | --- |\n| 深蹲 | 膝盖跟随脚尖方向 |'} />);
      await user.click(screen.getByRole('button', { name: '放大查看表格' }));

      const dialog = screen.getByRole('dialog', { name: '表格详情' });
      const expandedTable = within(dialog).getByRole('table');
      const expandedCell = within(dialog).getByRole('columnheader', { name: '动作' });
      expect(getComputedStyle(expandedTable).minWidth).toBe('100%');
      expect(getComputedStyle(expandedCell).whiteSpace).toBe('nowrap');
      expect(getComputedStyle(expandedCell).borderTopWidth).toBe('1px');
    } finally {
      style.remove();
    }
  });

  it('shows the expanded table edge-to-edge without a nested container frame', async () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);
    const user = userEvent.setup();

    try {
      render(<ChatMarkdown text={'| 动作 | 说明 |\n| --- | --- |\n| 深蹲 | 膝盖跟随脚尖方向 |'} />);
      await user.click(screen.getByRole('button', { name: '放大查看表格' }));

      const dialog = screen.getByRole('dialog', { name: '表格详情' });
      const content = dialog.querySelector('.surface-dialog__content') as HTMLElement;
      const viewport = dialog.querySelector('.data-table-viewport--expanded') as HTMLElement;
      expect(viewport).toContainElement(within(dialog).getByRole('table'));
      expect(getComputedStyle(dialog).paddingTop).toBe('0px');
      expect(getComputedStyle(content).borderRadius).toBe('0px');
      expect(getComputedStyle(viewport).borderTopWidth).toBe('0px');
      expect(getComputedStyle(viewport).borderRadius).toBe('0px');
      expect(getComputedStyle(viewport).overflowX).toBe('auto');
    } finally {
      style.remove();
    }
  });

  it('normalizes the retired assistant brand without exposing duplicate names', () => {
    render(<ChatMarkdown text="瘦瘦 AI 花爷陪你继续，瘦瘦会记住这次反馈。" />);

    expect(screen.getByText('花爷陪你继续，花爷会记住这次反馈。')).toBeInTheDocument();
    expect(screen.queryByText(/瘦瘦/)).not.toBeInTheDocument();
  });
});
