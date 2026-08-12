import { describe, expect, it, vi } from 'vitest';

import {
  createVoiceEngine,
  isVoiceSupported,
  readVoiceStyle,
  saveVoiceStyle,
  voiceCueForTransition,
  type SpeechSynthesisLike,
  type VoiceUtterance,
} from './voiceGuidance';
import { advanceWorkoutSession, createWorkoutSession, startWorkoutSession, type WorkoutSessionState } from './workoutSession';

const exercise = { id: 'squat', name: '深蹲', sets: 1, seconds: 4 };
const bridge = { id: 'bridge', name: '臀桥', sets: 1, seconds: 4 };

function collectCues(initial: WorkoutSessionState) {
  const cues: Array<{ id: string; text: string }> = [];
  let previous = initial;
  let current = startWorkoutSession(initial);
  for (let second = 0; second < 50; second += 1) {
    const currentExercise = current.exercises[current.exerciseIndex] ?? exercise;
    const cue = voiceCueForTransition(previous, current, currentExercise);
    if (cue) cues.push(cue);
    previous = current;
    current = advanceWorkoutSession(current);
  }
  return cues;
}

function speechDouble(): SpeechSynthesisLike & { utterances: Array<{ text: string; onend?: () => void; onerror?: () => void }> } {
  const utterances: Array<{ text: string; onend?: () => void; onerror?: () => void }> = [];
  return {
    utterances,
    resume: vi.fn(),
    cancel: vi.fn(),
    speak: vi.fn((utterance) => utterances.push(utterance)),
  };
}

describe('voice guidance', () => {
  it('derives one stable cue per meaningful workout state transition', () => {
    const cues = collectCues(createWorkoutSession([exercise, bridge]));

    expect(cues).toEqual([
      { id: 'countdown:3', text: '3', interrupt: false, policy: 'LATEST' },
      { id: 'countdown:2', text: '2', interrupt: false, policy: 'LATEST' },
      { id: 'countdown:1', text: '1', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:0:set:0', text: '深蹲，第 1 组', interrupt: false },
      { id: 'exercise:0:set:0:countdown:3', text: '3', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:0:set:0:countdown:2', text: '2', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:0:set:0:countdown:1', text: '1', interrupt: false, policy: 'LATEST' },
      { id: 'rest:exercise:0:0:30', text: '休息 30 秒', interrupt: false },
      { id: 'rest:exercise:0:0:countdown:3', text: '3', interrupt: false, policy: 'LATEST' },
      { id: 'rest:exercise:0:0:countdown:2', text: '2', interrupt: false, policy: 'LATEST' },
      { id: 'rest:exercise:0:0:countdown:1', text: '1', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:1:set:0', text: '臀桥，第 1 组', interrupt: false },
      { id: 'exercise:1:set:0:countdown:3', text: '3', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:1:set:0:countdown:2', text: '2', interrupt: false, policy: 'LATEST' },
      { id: 'exercise:1:set:0:countdown:1', text: '1', interrupt: false, policy: 'LATEST' },
      { id: 'completed', text: '训练完成，今天辛苦啦', interrupt: false },
    ]);
    expect(new Set(cues.map((cue) => cue.id)).size).toBe(cues.length);
  });

  it('queues continuous cues FIFO without cancelling the current utterance', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.speak({ id: 'one', text: '一', interrupt: false });
    engine.speak({ id: 'two', text: '二', interrupt: false });
    engine.speak({ id: 'two', text: '重复', interrupt: false });

    expect(speech.speak).toHaveBeenCalledTimes(1);
    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['一']);
    expect(speech.cancel).not.toHaveBeenCalled();
    speech.utterances[0]?.onend?.();
    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['一', '二']);
  });

  it('drops an obsolete countdown cue before it can be replayed from the queue', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.speak({ id: 'exercise', text: '深蹲，第一组', interrupt: false });
    engine.speak({ id: 'countdown:3', text: '3', interrupt: false, policy: 'LATEST' });
    engine.speak({ id: 'countdown:2', text: '2', interrupt: false, policy: 'LATEST' });
    speech.utterances[0]?.onend?.();

    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['深蹲，第一组', '2']);
    expect(speech.cancel).not.toHaveBeenCalled();
  });

  it('unlocks in the user gesture and only cancels for mute, skip, or exit', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.unlock();
    engine.speak({ id: 'start', text: '开始', interrupt: false });
    engine.mute(true);
    engine.mute(false);
    engine.speak({ id: 'next', text: '下一动作', interrupt: false });
    engine.stop();

    expect(speech.resume).toHaveBeenCalledTimes(1);
    expect(speech.cancel).toHaveBeenCalledTimes(2);
    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['开始', '下一动作']);
  });

  it('clears queued speech and ignores late utterance handlers during cleanup', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.speak({ id: 'first', text: '第一句', interrupt: false });
    engine.speak({ id: 'second', text: '第二句', interrupt: false });
    engine.stop();
    speech.utterances[0]?.onend?.();

    expect(speech.cancel).toHaveBeenCalledTimes(1);
    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['第一句']);
  });

  it('does not cancel an idle engine during lifecycle cleanup', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.stop();

    expect(speech.cancel).not.toHaveBeenCalled();
  });

  it('allows a visited action cue to play again after an explicit navigation stop', () => {
    const speech = speechDouble();
    const engine = createVoiceEngine(speech, (text) => ({ text }));

    engine.speak({ id: 'exercise:0:set:0', text: '深蹲，第 1 组', interrupt: false });
    engine.stop();
    engine.speak({ id: 'exercise:0:set:0', text: '深蹲，第 1 组', interrupt: false });

    expect(speech.utterances.map((utterance) => utterance.text)).toEqual(['深蹲，第 1 组', '深蹲，第 1 组']);
  });

  it('applies a matching local Chinese voice and tuning for each voice style', () => {
    const femaleVoice = { name: 'Tingting', lang: 'zh-CN' };
    const maleVoice = { name: 'Yunxi', lang: 'zh-CN' };
    const speech = speechDouble();
    speech.getVoices = () => [maleVoice, femaleVoice];
    const utterances: VoiceUtterance[] = [];
    const engine = createVoiceEngine(speech, (text) => {
      const utterance = { text };
      utterances.push(utterance);
      return utterance;
    });

    engine.setStyle('GENTLE_FEMALE');
    engine.speak({ id: 'gentle', text: '保持呼吸', interrupt: false });
    expect(utterances[0]).toMatchObject({ voice: femaleVoice, lang: 'zh-CN', rate: 0.92, pitch: 1.08 });
    speech.utterances[0]?.onend?.();

    engine.setStyle('MAGNETIC_MALE');
    engine.speak({ id: 'magnetic', text: '继续训练', interrupt: false });
    expect(utterances[1]).toMatchObject({ voice: maleVoice, lang: 'zh-CN', rate: 0.88, pitch: 0.82 });
  });

  it('persists a valid local voice style and rejects an unknown stored value', () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    };

    saveVoiceStyle('MAGNETIC_MALE', storage);
    expect(readVoiceStyle(storage)).toBe('MAGNETIC_MALE');
    values.set('happy-agent.workout-voice-style', 'UNKNOWN');
    expect(readVoiceStyle(storage)).toBe('SYSTEM');
  });

  it('has a safe no-op engine when Web Speech is unavailable', () => {
    expect(isVoiceSupported(undefined)).toBe(false);
    const engine = createVoiceEngine(undefined, (text) => ({ text }));

    expect(() => {
      engine.unlock();
      engine.speak({ id: 'cue', text: '训练准备，3', interrupt: false });
      engine.mute(true);
      engine.stop();
    }).not.toThrow();
  });
});
