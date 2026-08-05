import { describe, expect, it } from 'vitest';

import {
  advanceWorkoutSession,
  createWorkoutSession,
  nextWorkoutSession,
  previousWorkoutSession,
  startWorkoutSession,
} from './workoutSession';

const exercises = [
  { id: 'squat', name: '深蹲', sets: 2, seconds: 2 },
  { id: 'bridge', name: '臀桥', sets: 1, seconds: 2 },
];

describe('workoutSession', () => {
  it('moves through preparation, exercise, set rest, and the next set', () => {
    let state = startWorkoutSession(createWorkoutSession(exercises));
    expect(state).toMatchObject({ phase: 'COUNTDOWN', remaining: 3, exerciseIndex: 0, setIndex: 0 });

    state = advanceWorkoutSession(state);
    state = advanceWorkoutSession(state);
    state = advanceWorkoutSession(state);
    expect(state).toMatchObject({ phase: 'EXERCISE', remaining: 2, exerciseIndex: 0, setIndex: 0 });

    state = advanceWorkoutSession(state);
    state = advanceWorkoutSession(state);
    expect(state).toMatchObject({ phase: 'REST', remaining: 20, restKind: 'SET' });
    expect(state.completedSets).toBe(1);

    for (let second = 0; second < 20; second += 1) state = advanceWorkoutSession(state);
    expect(state).toMatchObject({ phase: 'EXERCISE', remaining: 2, exerciseIndex: 0, setIndex: 1 });
  });

  it('uses a 30 second rest between exercises and completes at the end', () => {
    let state = startWorkoutSession(createWorkoutSession([{ ...exercises[0], sets: 1 }, exercises[1]]));
    for (let second = 0; second < 3; second += 1) state = advanceWorkoutSession(state);
    for (let second = 0; second < 2; second += 1) state = advanceWorkoutSession(state);
    expect(state).toMatchObject({ phase: 'REST', remaining: 30, restKind: 'EXERCISE' });

    for (let second = 0; second < 30; second += 1) state = advanceWorkoutSession(state);
    expect(state).toMatchObject({ phase: 'EXERCISE', exerciseIndex: 1, setIndex: 0, remaining: 2 });
    state = advanceWorkoutSession(state);
    state = advanceWorkoutSession(state);
    expect(state.phase).toBe('COMPLETED');
    expect(state.completedSets).toBe(2);
  });

  it('supports explicit next and previous exercise navigation without crossing boundaries', () => {
    const started = startWorkoutSession(createWorkoutSession(exercises));
    const next = nextWorkoutSession(started);
    expect(next).toMatchObject({ phase: 'EXERCISE', exerciseIndex: 1, setIndex: 0, remaining: 2 });
    expect(nextWorkoutSession(next)).toEqual(next);
    expect(next.completedSets).toBe(0);
    expect(previousWorkoutSession(next)).toMatchObject({ phase: 'EXERCISE', exerciseIndex: 0, setIndex: 0, remaining: 2 });
    expect(previousWorkoutSession(started)).toEqual(started);
  });
});
