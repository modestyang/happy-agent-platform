import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps, ComponentType } from 'react';
import { describe, expect, it } from 'vitest';

import { ExpandableSurface } from './ContentSurface';

describe('ExpandableSurface', () => {
  it('locks background scroll and closes from the backdrop with focus restored', async () => {
    const user = userEvent.setup();
    render(<ExpandableSurface label="体重趋势" title="体重趋势详情"><div>曲线</div></ExpandableSurface>);

    const trigger = screen.getByRole('button', { name: '放大查看体重趋势' });
    const appRoot = trigger.closest('body > div') as HTMLElement;
    const originalInert = appRoot.inert;
    await user.click(trigger);

    const dialog = screen.getByRole('dialog', { name: '体重趋势详情' });
    const close = screen.getByRole('button', { name: '关闭体重趋势详情' });
    expect(close).toHaveFocus();
    expect(document.body).toHaveStyle({ overflow: 'hidden' });
    expect(appRoot).toHaveProperty('inert', true);

    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(close).toHaveFocus();

    fireEvent.mouseDown(dialog.parentElement as HTMLElement);

    expect(screen.queryByRole('dialog', { name: '体重趋势详情' })).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
    expect(appRoot.inert).toBe(originalInert);
    expect(trigger).toHaveFocus();
  });

  it('opens media directly without an expand icon, visible title, or dialog frame', async () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);
    const user = userEvent.setup();
    const MediaSurface = ExpandableSurface as ComponentType<
      ComponentProps<typeof ExpandableSurface> & { variant: 'media' }
    >;

    try {
      render(
        <MediaSurface
          variant="media"
          label="动作图"
          title="动作图详情"
          expandedChildren={<img src="/squat-large.png" alt="放大的深蹲动作" />}
        >
          <img src="/squat.png" alt="深蹲动作" />
        </MediaSurface>,
      );

      const trigger = screen.getByRole('button', { name: '放大查看动作图' });
      expect(trigger).toHaveClass('expandable-surface__media-trigger');
      expect(trigger.querySelector('svg')).toBeNull();
      await user.click(trigger);

      const dialog = screen.getByRole('dialog', { name: '动作图详情' });
      const backdrop = dialog.parentElement as HTMLElement;
      const expandedImage = screen.getByRole('img', { name: '放大的深蹲动作' });
      const close = screen.getByRole('button', { name: '关闭动作图详情' });
      expect(dialog.querySelector('header')).toBeNull();
      expect(screen.queryByText('动作图详情')).not.toBeInTheDocument();
      expect(dialog).toContainElement(expandedImage);
      expect(getComputedStyle(dialog).backgroundColor).toBe('rgba(0, 0, 0, 0)');
      expect(getComputedStyle(dialog).borderTopWidth).toBe('0px');
      expect(getComputedStyle(dialog).boxShadow).toBe('none');
      expect(getComputedStyle(expandedImage).width).toBe('100%');
      expect(getComputedStyle(close).right).toContain('safe-area-inset-right');
      expect(getComputedStyle(backdrop).paddingRight).toContain('safe-area-inset-right');
      expect(getComputedStyle(backdrop).paddingLeft).toContain('safe-area-inset-left');
    } finally {
      style.remove();
    }
  });
});
