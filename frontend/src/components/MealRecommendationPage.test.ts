import { describe, expect, it } from 'vitest';

import { mealTimingLabel, nextMealRecommendation, nextMealType, type MealRecommendation } from './MealRecommendationPage';

const recommendation = (mealType: MealRecommendation['mealType'], status = 'READY'): MealRecommendation => ({
  id: mealType,
  recommendationDate: '2026-08-06',
  mealType,
  items: [{ name: mealType, estimatedKcal: 400 }],
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
});
