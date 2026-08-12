import type { WorkoutSessionState } from './workoutSession';

export type PlayerExercise = {
  id: string;
  name: string;
  sets: number;
  seconds: number;
};

export type VoiceCue = { id: string; text: string; interrupt: boolean; policy?: 'QUEUE' | 'LATEST' };

export type VoiceStyleKey = 'SYSTEM' | 'GENTLE_FEMALE' | 'MAGNETIC_MALE';

export const VOICE_STYLE_OPTIONS: ReadonlyArray<{ key: VoiceStyleKey; label: string }> = [
  { key: 'SYSTEM', label: '系统默认' },
  { key: 'GENTLE_FEMALE', label: '温柔女声' },
  { key: 'MAGNETIC_MALE', label: '磁性男声' },
];

export type LocalVoice = {
  name: string;
  lang: string;
};

export type VoiceUtterance = {
  text: string;
  lang?: string;
  rate?: number;
  pitch?: number;
  voice?: LocalVoice;
  onend?: () => void;
  onerror?: () => void;
};

export type SpeechSynthesisLike = {
  resume(): void;
  cancel(): void;
  speak(utterance: VoiceUtterance): void;
  getVoices?(): LocalVoice[];
};

export interface VoiceEngine {
  unlock(): void;
  speak(cue: VoiceCue): void;
  setStyle(value: VoiceStyleKey): void;
  mute(value: boolean): void;
  stop(): void;
}

type VoiceStyleStorage = {
  getItem(key: string): string | null;
  setItem(key: string, value: string): unknown;
};

const VOICE_STYLE_STORAGE_KEY = 'happy-agent.workout-voice-style';

export function readVoiceStyle(storage: VoiceStyleStorage | undefined = browserVoiceStorage()): VoiceStyleKey {
  const value = storage?.getItem(VOICE_STYLE_STORAGE_KEY);
  return VOICE_STYLE_OPTIONS.some((option) => option.key === value) ? value as VoiceStyleKey : 'SYSTEM';
}

export function saveVoiceStyle(style: VoiceStyleKey, storage: VoiceStyleStorage | undefined = browserVoiceStorage()): void {
  storage?.setItem(VOICE_STYLE_STORAGE_KEY, style);
}

export function voiceCueForTransition(
  previous: WorkoutSessionState,
  current: WorkoutSessionState,
  exercise: PlayerExercise,
): VoiceCue | undefined {
  if (current.phase === 'COUNTDOWN' && (previous.phase !== 'COUNTDOWN' || previous.remaining !== current.remaining)) {
    return { id: `countdown:${current.remaining}`, text: String(current.remaining), interrupt: false, policy: 'LATEST' };
  }

  if (current.phase === 'EXERCISE') {
    if (previous.phase !== 'EXERCISE' || previous.exerciseIndex !== current.exerciseIndex || previous.setIndex !== current.setIndex) {
      return { id: `exercise:${current.exerciseIndex}:set:${current.setIndex}`, text: `${exercise.name}，第 ${current.setIndex + 1} 组`, interrupt: false };
    }
    if (previous.remaining !== current.remaining && current.remaining <= 3) {
      return { id: `exercise:${current.exerciseIndex}:set:${current.setIndex}:countdown:${current.remaining}`, text: String(current.remaining), interrupt: false, policy: 'LATEST' };
    }
  }

  if (current.phase === 'REST') {
    if (previous.phase !== 'REST' || previous.restKind !== current.restKind) {
      return { id: `rest:${current.restKind?.toLowerCase()}:${current.exerciseIndex}:${current.setIndex}:${current.remaining}`, text: `休息 ${current.remaining} 秒`, interrupt: false };
    }
    if (previous.remaining !== current.remaining && current.remaining <= 3) {
      return { id: `rest:${current.restKind?.toLowerCase()}:${current.exerciseIndex}:${current.setIndex}:countdown:${current.remaining}`, text: String(current.remaining), interrupt: false, policy: 'LATEST' };
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
  let voiceStyle: VoiceStyleKey = 'SYSTEM';

  const playNext = () => {
    if (!speech || muted || speaking) return;
    const cue = queue.shift();
    if (!cue) return;
    speaking = true;
    const token = ++generation;
    const utterance = createUtterance(cue.text);
    const voice = selectVoice(speech.getVoices?.() ?? [], voiceStyle);
    const tuning = voiceTuning(voiceStyle);
    utterance.lang = voice?.lang ?? 'zh-CN';
    utterance.rate = tuning.rate;
    utterance.pitch = tuning.pitch;
    if (voice) utterance.voice = voice;
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
      if (cue.policy === 'LATEST') {
        for (let index = queue.length - 1; index >= 0; index -= 1) {
          if (queue[index]?.policy === 'LATEST') queue.splice(index, 1);
        }
      }
      queue.push(cue);
      playNext();
    },
    setStyle(value) {
      voiceStyle = value;
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
    getVoices: typeof browserSpeech.getVoices === 'function' ? () => browserSpeech.getVoices() : undefined,
  };
}

function voiceTuning(style: VoiceStyleKey): { rate: number; pitch: number } {
  if (style === 'GENTLE_FEMALE') return { rate: 0.92, pitch: 1.08 };
  if (style === 'MAGNETIC_MALE') return { rate: 0.88, pitch: 0.82 };
  return { rate: 1, pitch: 1 };
}

function selectVoice(voices: LocalVoice[], style: VoiceStyleKey): LocalVoice | undefined {
  if (style === 'SYSTEM') return undefined;
  const chineseVoices = voices.filter((voice) => /^zh(?:-|_)/i.test(voice.lang));
  const preferredName = style === 'GENTLE_FEMALE'
    ? /ting[ -]?ting|mei[ -]?jia|xiaoxiao|xiaoyi|huihui|female|女声/i
    : /yunxi|yunjian|kangkang|male|男声/i;
  return chineseVoices.find((voice) => preferredName.test(voice.name)) ?? chineseVoices[0];
}

function browserVoiceStorage(): Storage | undefined {
  return typeof window === 'undefined' ? undefined : window.localStorage;
}
