import { describe, expect, it, vi } from 'vitest';

import {
  createVoiceEngine,
  isVoiceSupported,
  voiceCueForTransition,
  type SpeechSynthesisLike,
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
      { id: 'countdown:3', text: '训练准备，3', interrupt: false },
      { id: 'countdown:2', text: '2', interrupt: false },
      { id: 'countdown:1', text: '1', interrupt: false },
      { id: 'exercise:0:set:0', text: '深蹲，第 1 组', interrupt: false },
      { id: 'exercise:0:set:0:countdown:3', text: '3', interrupt: false },
      { id: 'exercise:0:set:0:countdown:2', text: '2', interrupt: false },
      { id: 'exercise:0:set:0:countdown:1', text: '1', interrupt: false },
      { id: 'rest:exercise:0:0:30', text: '休息 30 秒', interrupt: false },
      { id: 'rest:exercise:0:0:countdown:3', text: '3', interrupt: false },
      { id: 'rest:exercise:0:0:countdown:2', text: '2', interrupt: false },
      { id: 'rest:exercise:0:0:countdown:1', text: '1', interrupt: false },
      { id: 'exercise:1:set:0', text: '臀桥，第 1 组', interrupt: false },
      { id: 'exercise:1:set:0:countdown:3', text: '3', interrupt: false },
      { id: 'exercise:1:set:0:countdown:2', text: '2', interrupt: false },
      { id: 'exercise:1:set:0:countdown:1', text: '1', interrupt: false },
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
