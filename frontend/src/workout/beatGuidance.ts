import type { WorkoutSessionState } from './workoutSession';

export type BeatKind = 'WORK' | 'REST' | 'ENDING';

type OscillatorLike = {
  frequency: { setValueAtTime(value: number, time: number): void };
  connect(destination: unknown): void;
  start(time?: number): void;
  stop(time?: number): void;
  onended?: () => void;
};

type GainLike = {
  gain: {
    setValueAtTime(value: number, time: number): void;
    exponentialRampToValueAtTime(value: number, time: number): void;
  };
  connect(destination: unknown): void;
};

type AudioContextLike = {
  currentTime: number;
  destination: unknown;
  state: string;
  resume(): Promise<void> | void;
  createOscillator(): OscillatorLike;
  createGain(): GainLike;
};

export interface BeatEngine {
  unlock(): void;
  play(kind: BeatKind): void;
  mute(value: boolean): void;
  stop(): void;
}

export function beatForTransition(
  previous: WorkoutSessionState,
  current: WorkoutSessionState,
): BeatKind | undefined {
  if (previous.phase !== current.phase || previous.remaining === current.remaining) return undefined;
  if (previous.exerciseIndex !== current.exerciseIndex || previous.setIndex !== current.setIndex || previous.restKind !== current.restKind) return undefined;
  if (current.phase !== 'COUNTDOWN' && current.phase !== 'EXERCISE' && current.phase !== 'REST') return undefined;
  if (current.remaining <= 3) return 'ENDING';
  return current.phase === 'REST' ? 'REST' : 'WORK';
}

export function createBeatEngine(
  createContext: () => AudioContextLike | undefined = createBrowserAudioContext,
): BeatEngine {
  const activeOscillators = new Set<OscillatorLike>();
  let context: AudioContextLike | undefined;
  let muted = false;

  const ensureContext = () => {
    context ??= createContext();
    return context;
  };
  const stop = () => {
    activeOscillators.forEach((oscillator) => oscillator.stop());
    activeOscillators.clear();
  };

  return {
    unlock() {
      const audioContext = ensureContext();
      if (audioContext?.state === 'suspended') void audioContext.resume();
    },
    play(kind) {
      if (muted) return;
      const audioContext = ensureContext();
      if (!audioContext) return;
      const oscillator = audioContext.createOscillator();
      const gain = audioContext.createGain();
      const frequency = kind === 'ENDING' ? 980 : kind === 'REST' ? 440 : 620;
      const peakGain = kind === 'ENDING' ? 0.075 : 0.035;
      const duration = kind === 'ENDING' ? 0.075 : 0.045;
      oscillator.frequency.setValueAtTime(frequency, audioContext.currentTime);
      gain.gain.setValueAtTime(0.0001, audioContext.currentTime);
      gain.gain.exponentialRampToValueAtTime(peakGain, audioContext.currentTime + 0.008);
      gain.gain.exponentialRampToValueAtTime(0.0001, audioContext.currentTime + duration);
      oscillator.connect(gain);
      gain.connect(audioContext.destination);
      oscillator.onended = () => activeOscillators.delete(oscillator);
      activeOscillators.add(oscillator);
      oscillator.start(audioContext.currentTime);
      oscillator.stop(audioContext.currentTime + duration);
    },
    mute(value) {
      muted = value;
      if (value) stop();
    },
    stop,
  };
}

function createBrowserAudioContext(): AudioContextLike | undefined {
  if (typeof window === 'undefined') return undefined;
  const AudioContextClass = window.AudioContext
    ?? (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  return AudioContextClass ? new AudioContextClass() as unknown as AudioContextLike : undefined;
}
