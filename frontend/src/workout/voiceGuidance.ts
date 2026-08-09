import type { WorkoutSessionState } from './workoutSession';

export type PlayerExercise = {
  id: string;
  name: string;
  sets: number;
  seconds: number;
};

export type VoiceCue = { id: string; text: string; interrupt: boolean };

export type VoiceUtterance = {
  text: string;
  lang?: string;
  rate?: number;
  onend?: () => void;
  onerror?: () => void;
};

export type SpeechSynthesisLike = {
  resume(): void;
  cancel(): void;
  speak(utterance: VoiceUtterance): void;
};

export interface VoiceEngine {
  unlock(): void;
  speak(cue: VoiceCue): void;
  mute(value: boolean): void;
  stop(): void;
}

export function voiceCueForTransition(
  previous: WorkoutSessionState,
  current: WorkoutSessionState,
  exercise: PlayerExercise,
): VoiceCue | undefined {
  if (current.phase === 'COUNTDOWN' && (previous.phase !== 'COUNTDOWN' || previous.remaining !== current.remaining)) {
    return { id: `countdown:${current.remaining}`, text: current.remaining === 3 ? '训练准备，3' : String(current.remaining), interrupt: false };
  }

  if (current.phase === 'EXERCISE') {
    if (previous.phase !== 'EXERCISE' || previous.exerciseIndex !== current.exerciseIndex || previous.setIndex !== current.setIndex) {
      return { id: `exercise:${current.exerciseIndex}:set:${current.setIndex}`, text: `${exercise.name}，第 ${current.setIndex + 1} 组`, interrupt: false };
    }
    if (previous.remaining !== current.remaining && current.remaining <= 3) {
      return { id: `exercise:${current.exerciseIndex}:set:${current.setIndex}:countdown:${current.remaining}`, text: String(current.remaining), interrupt: false };
    }
  }

  if (current.phase === 'REST') {
    if (previous.phase !== 'REST' || previous.restKind !== current.restKind) {
      return { id: `rest:${current.restKind?.toLowerCase()}:${current.exerciseIndex}:${current.setIndex}:${current.remaining}`, text: `休息 ${current.remaining} 秒`, interrupt: false };
    }
    if (previous.remaining !== current.remaining && current.remaining <= 3) {
      return { id: `rest:${current.restKind?.toLowerCase()}:${current.exerciseIndex}:${current.setIndex}:countdown:${current.remaining}`, text: String(current.remaining), interrupt: false };
    }
  }

  if (current.phase === 'COMPLETED' && previous.phase !== 'COMPLETED') {
    return { id: 'completed', text: '训练完成，今天辛苦啦', interrupt: false };
  }
  return undefined;
}

export function isVoiceSupported(speech: SpeechSynthesisLike | undefined = browserSpeechSynthesis()): boolean {
  return Boolean(speech && typeof SpeechSynthesisUtterance !== 'undefined');
}

export function createVoiceEngine(
  speech: SpeechSynthesisLike | undefined,
  createUtterance: (text: string) => VoiceUtterance,
): VoiceEngine {
  const consumed = new Set<string>();
  const queue: VoiceCue[] = [];
  let muted = false;
  let speaking = false;
  let generation = 0;

  const playNext = () => {
    if (!speech || muted || speaking) return;
    const cue = queue.shift();
    if (!cue) return;
    speaking = true;
    const token = ++generation;
    const utterance = createUtterance(cue.text);
    utterance.lang = 'zh-CN';
    utterance.rate = 1;
    const finish = () => {
      if (token !== generation) return;
      speaking = false;
      playNext();
    };
    utterance.onend = finish;
    utterance.onerror = finish;
    speech.speak(utterance);
  };

  const stop = () => {
    const hasPendingSpeech = speaking || queue.length > 0;
    queue.length = 0;
    consumed.clear();
    generation += 1;
    speaking = false;
    if (hasPendingSpeech) speech?.cancel();
  };

  return {
    unlock() {
      speech?.resume();
    },
    speak(cue) {
      if (consumed.has(cue.id)) return;
      consumed.add(cue.id);
      if (muted || !speech) return;
      if (cue.interrupt) stop();
      queue.push(cue);
      playNext();
    },
    mute(value) {
      muted = value;
      if (value) stop();
    },
    stop,
  };
}

export function createBrowserVoiceEngine(): VoiceEngine {
  const speech = browserSpeechSynthesis();
  return createVoiceEngine(
    speech,
    (text) => new SpeechSynthesisUtterance(text) as unknown as VoiceUtterance,
  );
}

function browserSpeechSynthesis(): SpeechSynthesisLike | undefined {
  if (typeof window === 'undefined' || !('speechSynthesis' in window) || typeof SpeechSynthesisUtterance === 'undefined') return undefined;
  const browserSpeech = window.speechSynthesis;
  if (typeof browserSpeech.resume !== 'function' || typeof browserSpeech.cancel !== 'function' || typeof browserSpeech.speak !== 'function') return undefined;
  return {
    resume: () => browserSpeech.resume(),
    cancel: () => browserSpeech.cancel(),
    speak: (utterance) => browserSpeech.speak(utterance as unknown as SpeechSynthesisUtterance),
  };
}
