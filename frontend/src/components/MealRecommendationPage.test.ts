import { createElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MealRecommendationPage, mealTimingLabel, nextMealRecommendation, nextMealType, type MealRecommendation } from './MealRecommendationPage';

const recommendation = (mealType: MealRecommendation['mealType'], status = 'READY'): MealRecommendation => ({
  id: mealType,
  recommendationDate: '2026-08-06',
  mealType,
  items: [{ name: '推荐餐', estimatedKcal: 400 }],
  reason: '均衡',
  status,
  generatedAt: '2026-08-06T05:30:00+08:00',
});

describe('meal timing', () => {
  it('uses the documented 09:00, 12:00, and 18:00 boundaries', () => {
    expect(nextMealType(new Date('2026-08-06T08:59:59+08:00'))).toBe('BREAKFAST');
    expect(nextMealType(new Date('2026-08-06T09:00:00+08:00'))).toBe('LUNCH');
    expect(nextMealType(new Date('2026-08-06T12:00:00+08:00'))).toBe('DINNER');
    expect(mealTimingLabel(new Date('2026-08-06T17:59:59+08:00'), 'DINNER')).toBe('下一餐');
    expect(mealTimingLabel(new Date('2026-08-06T18:00:00+08:00'), 'DINNER')).toBe('今晚');
  });

  it('never treats generating, failed, or empty recommendations as ready food', () => {
    expect(nextMealRecommendation([recommendation('LUNCH', 'GENERATING')], new Date('2026-08-06T10:00:00+08:00'))).toBeUndefined();
    expect(nextMealRecommendation([{ ...recommendation('LUNCH'), items: [] }], new Date('2026-08-06T10:00:00+08:00'))).toBeUndefined();
    expect(nextMealRecommendation([recommendation('LUNCH')], new Date('2026-08-06T10:00:00+08:00'))?.mealType).toBe('LUNCH');
  });

  it('restores the unselected like state and makes a failed submission retryable', async () => {
    let attempt = 0;
    const fetchMock = vi.fn(async () => {
      attempt += 1;
      if (attempt === 1) {
        return new Response(JSON.stringify({ detail: '服务暂时不可用' }), {
          status: 503,
          headers: { 'Content-Type': 'application/problem+json' },
        });
      }
      return new Response(
        JSON.stringify({
          recommendationId: '11111111-1111-1111-1111-111111111111',
          sentiment: 'LIKE',
          createdAt: '2026-08-09T00:00:00Z',
          updatedAt: '2026-08-09T00:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(
      createElement(
        MemoryRouter,
        undefined,
        createElement(MealRecommendationPage, {
          recommendations: [{ ...recommendation('LUNCH'), id: '11111111-1111-1111-1111-111111111111' }],
          now: new Date('2026-08-06T10:00:00+08:00'),
        }),
      ),
    );

    const like = screen.getByRole('button', { name: '赞' });
    await user.click(like);

    expect(await screen.findByRole('alert')).toHaveTextContent('服务暂时不可用');
    expect(like).toHaveAttribute('aria-pressed', 'false');
    await user.click(like);
    await waitFor(() => expect(like).toHaveAttribute('aria-pressed', 'true'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('meal recommendation feedback', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('persists a like and retains the selected state on the ready meal card', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          recommendationId: '11111111-1111-1111-1111-111111111111',
          sentiment: 'LIKE',
          createdAt: '2026-08-09T00:00:00Z',
          updatedAt: '2026-08-09T00:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(
      createElement(
        MemoryRouter,
        undefined,
        createElement(MealRecommendationPage, {
          recommendations: [
            {
              ...recommendation('LUNCH'),
              id: '11111111-1111-1111-1111-111111111111',
            },
          ],
          now: new Date('2026-08-06T10:00:00+08:00'),
        }),
      ),
    );

    await user.click(screen.getByRole('button', { name: '赞' }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/app/meal-recommendations/11111111-1111-1111-1111-111111111111/feedback',
        expect.objectContaining({ method: 'PUT' }),
      ),
    );
    expect(screen.getByRole('button', { name: '赞' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('keeps each meal pending independently while concurrent feedback resolves out of order', async () => {
    let resolveBreakfast!: (value: Response) => void;
    let resolveLunch!: (value: Response) => void;
    const fetchMock = vi.fn((input: RequestInfo | URL) =>
      new Promise<Response>((resolve) => {
        if (String(input).includes('breakfast-id')) resolveBreakfast = resolve;
        else resolveLunch = resolve;
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(
      createElement(
        MemoryRouter,
        undefined,
        createElement(MealRecommendationPage, {
          recommendations: [
            { ...recommendation('BREAKFAST'), id: 'breakfast-id' },
            { ...recommendation('LUNCH'), id: 'lunch-id' },
          ],
          now: new Date('2026-08-06T08:00:00+08:00'),
        }),
      ),
    );

    const likes = screen.getAllByRole('button', { name: '赞' });
    await user.click(likes[0]);
    await user.click(likes[1]);
    expect(likes[0]).toBeDisabled();
    expect(likes[1]).toBeDisabled();

    resolveLunch(
      new Response(JSON.stringify({ recommendationId: 'lunch-id', sentiment: 'LIKE' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    await waitFor(() => expect(likes[1]).toHaveAttribute('aria-pressed', 'true'));
    expect(likes[0]).toBeDisabled();

    resolveBreakfast(
      new Response(JSON.stringify({ detail: '早餐保存失败' }), {
        status: 503,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    expect(await screen.findByText(/早餐保存失败/)).toBeInTheDocument();
    expect(likes[0]).toBeEnabled();
    expect(likes[1]).toHaveAttribute('aria-pressed', 'true');
  });

  it('uses an accessible bottom feedback dialog that closes on Escape and returns focus', async () => {
    vi.stubGlobal('fetch', vi.fn());
    const user = userEvent.setup();
    render(
      createElement(
        MemoryRouter,
        undefined,
        createElement(MealRecommendationPage, {
          recommendations: [recommendation('LUNCH')],
          now: new Date('2026-08-06T10:00:00+08:00'),
        }),
      ),
    );

    const dislike = screen.getByRole('button', { name: '踩' });
    await user.click(dislike);
    const dialog = screen.getByRole('dialog', { name: '这餐哪里不合适？' });
    expect(dialog).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: '这餐哪里不合适？' })).not.toBeInTheDocument();
    expect(dislike).toHaveFocus();
  });

  it('requires an explanation for OTHER dislike before persisting and retaining it', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          recommendationId: '11111111-1111-1111-1111-111111111111',
          sentiment: 'DISLIKE',
          reason: 'OTHER',
          note: '不喜欢香菜',
          createdAt: '2026-08-09T00:00:00Z',
          updatedAt: '2026-08-09T00:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(
      createElement(
        MemoryRouter,
        undefined,
        createElement(MealRecommendationPage, {
          recommendations: [{ ...recommendation('LUNCH'), id: '11111111-1111-1111-1111-111111111111' }],
          now: new Date('2026-08-06T10:00:00+08:00'),
        }),
      ),
    );

    await user.click(screen.getByRole('button', { name: '踩' }));
    await user.click(screen.getByRole('button', { name: '其他' }));
    expect(screen.getByRole('button', { name: '提交反馈' })).toBeDisabled();
    await user.type(screen.getByLabelText('其他原因说明'), '不喜欢香菜');
    await user.click(screen.getByRole('button', { name: '提交反馈' }));

    await waitFor(() => expect(screen.queryByLabelText('不喜欢的原因')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: '踩' })).toHaveAttribute('aria-pressed', 'true');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/app/meal-recommendations/11111111-1111-1111-1111-111111111111/feedback',
      expect.objectContaining({ body: expect.stringContaining('"OTHER"') }),
    );
  });
});
