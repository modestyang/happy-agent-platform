import { describe, expect, it, vi } from 'vitest';

import { beatForTransition, createBeatEngine } from './beatGuidance';
import { advanceWorkoutSession, createWorkoutSession, startWorkoutSession } from './workoutSession';

describe('beatForTransition', () => {
  it('derives a quiet per-second beat and a distinct ending beat from workout transitions', () => {
    const ready = createWorkoutSession([{ id: 'squat', name: '深蹲', sets: 2, seconds: 5 }]);
    const countdown3 = startWorkoutSession(ready);
    const countdown2 = advanceWorkoutSession(countdown3);
    const countdown1 = advanceWorkoutSession(countdown2);
    const exercise5 = advanceWorkoutSession(countdown1);
    const exercise4 = advanceWorkoutSession(exercise5);
    const exercise3 = advanceWorkoutSession(exercise4);

    expect(beatForTransition(ready, countdown3)).toBeUndefined();
    expect(beatForTransition(countdown3, countdown2)).toBe('ENDING');
    expect(beatForTransition(countdown1, exercise5)).toBeUndefined();
    expect(beatForTransition(exercise5, exercise4)).toBe('WORK');
    expect(beatForTransition(exercise4, exercise3)).toBe('ENDING');
    expect(beatForTransition(exercise3, exercise3)).toBeUndefined();

    let previous = exercise3;
    let current = advanceWorkoutSession(previous);
    while (current.phase !== 'REST') {
      previous = current;
      current = advanceWorkoutSession(current);
    }
    const restTick = advanceWorkoutSession(current);
    expect(beatForTransition(previous, current)).toBeUndefined();
    expect(beatForTransition(current, restTick)).toBe('REST');
  });

  it('plays distinct short tones and stops them when muted', () => {
    const frequencies: number[] = [];
    const stop = vi.fn();
    const resume = vi.fn();
    const context = {
      currentTime: 10,
      destination: {},
      state: 'suspended',
      resume,
      createOscillator: () => ({
        frequency: { setValueAtTime: (value: number) => frequencies.push(value) },
        connect: vi.fn(),
        start: vi.fn(),
        stop,
        onended: undefined as (() => void) | undefined,
      }),
      createGain: () => ({
        gain: {
          setValueAtTime: vi.fn(),
          exponentialRampToValueAtTime: vi.fn(),
        },
        connect: vi.fn(),
      }),
    };
    const engine = createBeatEngine(() => context);

    engine.unlock();
    engine.play('WORK');
    engine.play('REST');
    engine.play('ENDING');
    expect(resume).toHaveBeenCalledTimes(1);
    expect(frequencies).toEqual([620, 440, 980]);

    engine.mute(true);
    engine.play('WORK');
    expect(frequencies).toHaveLength(3);
    expect(stop).toHaveBeenCalledTimes(6);
  });

  it('does not treat manual exercise navigation as a one-second tick', () => {
    const countdown = startWorkoutSession(createWorkoutSession([
      { id: 'squat', name: '深蹲', sets: 1, seconds: 5 },
      { id: 'bridge', name: '臀桥', sets: 1, seconds: 8 },
    ]));
    const exercise = advanceWorkoutSession(advanceWorkoutSession(advanceWorkoutSession(countdown)));
    const navigated = { ...exercise, exerciseIndex: 1, remaining: 8 };

    expect(beatForTransition(exercise, navigated)).toBeUndefined();
  });
});
