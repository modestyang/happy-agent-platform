import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ExerciseVisual } from './ExerciseVisual';

describe('ExerciseVisual', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('offers expansion for detail visuals but not compact navigation thumbnails', () => {
    const exercise = { name: '深蹲', targetArea: '下肢', imageUrls: ['/step-1.png', '/step-2.png'] };
    const { rerender } = render(<ExerciseVisual exercise={exercise} />);

    const trigger = screen.getByRole('button', { name: '放大查看深蹲动作示意' });
    expect(trigger).toHaveClass('expandable-surface__media-trigger');
    expect(trigger.querySelector('svg')).toBeNull();

    rerender(<ExerciseVisual exercise={exercise} compact />);
    expect(screen.queryByRole('button', { name: '放大查看深蹲动作示意' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看深蹲第2帧' })).not.toBeInTheDocument();
  });

  it('advances a multi-image preview to the next frame every 1.5 seconds', () => {
    vi.useFakeTimers();
    render(<ExerciseVisual
      autoPlay
      compact
      exercise={{ name: '深蹲', targetArea: '下肢', imageUrls: ['/step-1.png', '/step-2.png', '/step-3.png'] }}
    />);

    expect(screen.getByRole('img', { name: '深蹲第1步动作示意' })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1500));
    expect(screen.getByRole('img', { name: '深蹲第2步动作示意' })).toBeInTheDocument();
  });

  it('stops autoplay for reduced motion and lets the user choose a frame', () => {
    vi.useFakeTimers();
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: true,
      media: '(prefers-reduced-motion: reduce)',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })));
    render(<ExerciseVisual
      autoPlay
      compact
      exercise={{ name: '深蹲', targetArea: '下肢', imageUrls: ['/step-1.png', '/step-2.png'] }}
    />);

    act(() => vi.advanceTimersByTime(1500));
    expect(screen.getByRole('img', { name: '深蹲第1步动作示意' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '查看深蹲第2帧' }));
    expect(screen.getByRole('img', { name: '深蹲第2步动作示意' })).toBeInTheDocument();
  });
});
