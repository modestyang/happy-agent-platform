import { describe, expect, it } from 'vitest';

import { exerciseFrameStep } from './exerciseFrames';

describe('exerciseFrameStep', () => {
  it('plays multiple exercise frames forward and backward without a last-to-first jump', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map((second) => exerciseFrameStep(second, 4)))
      .toEqual([1, 2, 3, 4, 3, 2, 1]);
  });

  it('keeps invalid or single-frame media on its first frame', () => {
    expect(exerciseFrameStep(8, 0)).toBe(1);
    expect(exerciseFrameStep(8, 1)).toBe(1);
    expect(exerciseFrameStep(-2, 4)).toBe(1);
  });
});
