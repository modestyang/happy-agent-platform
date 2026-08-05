export type WorkoutExercise = {
  id: string;
  name: string;
  sets: number;
  seconds: number;
};

export type WorkoutPhase = 'READY' | 'COUNTDOWN' | 'EXERCISE' | 'REST' | 'COMPLETED';
export type WorkoutRestKind = 'SET' | 'EXERCISE';

export type WorkoutSessionState = {
  exercises: readonly WorkoutExercise[];
  phase: WorkoutPhase;
  exerciseIndex: number;
  setIndex: number;
  remaining: number;
  elapsedSeconds: number;
  completedSets: number;
  completedSetKeys: readonly string[];
  restKind?: WorkoutRestKind;
};

const exerciseSeconds = (exercise: WorkoutExercise) => Math.max(1, exercise.seconds);
const exerciseSets = (exercise: WorkoutExercise) => Math.max(1, exercise.sets);

export function createWorkoutSession(exercises: readonly WorkoutExercise[]): WorkoutSessionState {
  return {
    exercises,
    phase: exercises.length > 0 ? 'READY' : 'COMPLETED',
    exerciseIndex: 0,
    setIndex: 0,
    remaining: 0,
    elapsedSeconds: 0,
    completedSets: 0,
    completedSetKeys: [],
  };
}

export function startWorkoutSession(state: WorkoutSessionState): WorkoutSessionState {
  if (state.phase !== 'READY' || state.exercises.length === 0) return state;
  return { ...state, phase: 'COUNTDOWN', remaining: 3 };
}

export function advanceWorkoutSession(state: WorkoutSessionState): WorkoutSessionState {
  if (state.phase === 'READY' || state.phase === 'COMPLETED') return state;

  if (state.phase === 'COUNTDOWN') {
    if (state.remaining > 1) return { ...state, remaining: state.remaining - 1 };
    return beginExercise(state, state.exerciseIndex, state.setIndex);
  }

  if (state.phase === 'REST') {
    const elapsedState = { ...state, elapsedSeconds: state.elapsedSeconds + 1 };
    if (state.remaining > 1) return { ...elapsedState, remaining: state.remaining - 1 };
    if (state.restKind === 'SET') return beginExercise(elapsedState, state.exerciseIndex, state.setIndex + 1);
    return beginExercise(elapsedState, state.exerciseIndex + 1, 0);
  }

  const elapsedState = { ...state, elapsedSeconds: state.elapsedSeconds + 1 };
  if (state.remaining > 1) return { ...elapsedState, remaining: state.remaining - 1 };

  const current = state.exercises[state.exerciseIndex];
  const completedKey = `${state.exerciseIndex}:${state.setIndex}`;
  const completedSetKeys = state.completedSetKeys.includes(completedKey) ? state.completedSetKeys : [...state.completedSetKeys, completedKey];
  const completedState = { ...elapsedState, completedSets: completedSetKeys.length, completedSetKeys };
  if (state.setIndex + 1 < exerciseSets(current)) {
    return { ...completedState, phase: 'REST', remaining: 20, restKind: 'SET' };
  }
  if (state.exerciseIndex + 1 < state.exercises.length) {
    return { ...completedState, phase: 'REST', remaining: 30, restKind: 'EXERCISE' };
  }
  return { ...completedState, phase: 'COMPLETED', remaining: 0, restKind: undefined };
}

export function nextWorkoutSession(state: WorkoutSessionState): WorkoutSessionState {
  const nextIndex = state.exerciseIndex + 1;
  if (nextIndex >= state.exercises.length) {
    return state;
  }
  return beginExercise(state, nextIndex, 0);
}

export function previousWorkoutSession(state: WorkoutSessionState): WorkoutSessionState {
  if (state.exerciseIndex === 0 || state.phase === 'READY' || state.phase === 'COUNTDOWN') return state;
  return beginExercise(state, state.exerciseIndex - 1, 0);
}

function beginExercise(state: WorkoutSessionState, exerciseIndex: number, setIndex: number): WorkoutSessionState {
  const exercise = state.exercises[exerciseIndex];
  if (!exercise) return { ...state, phase: 'COMPLETED', remaining: 0, restKind: undefined };
  return {
    ...state,
    phase: 'EXERCISE',
    exerciseIndex,
    setIndex,
    remaining: exerciseSeconds(exercise),
    restKind: undefined,
  };
}
