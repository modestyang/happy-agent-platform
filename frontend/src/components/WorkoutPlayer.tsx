import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { Check, ChevronLeft, Pause, Play, RotateCcw, SkipBack, SkipForward, Volume2, VolumeX, X } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';

import { api } from '../api';
import { beatForTransition, createBeatEngine, type BeatEngine } from '../workout/beatGuidance';
import { exerciseFrameStep } from '../workout/exerciseFrames';
import {
  advanceWorkoutSession,
  createWorkoutSession,
  nextWorkoutSession,
  previousWorkoutSession,
  startWorkoutSession,
  type WorkoutSessionState,
} from '../workout/workoutSession';
import {
  createBrowserVoiceEngine,
  isVoiceSupported,
  readVoiceStyle,
  saveVoiceStyle,
  VOICE_STYLE_OPTIONS,
  voiceCueForTransition,
  type VoiceEngine,
  type VoiceStyleKey,
} from '../workout/voiceGuidance';
import { ExerciseVisual } from './ExerciseVisual';

type PlayerExercise = {
  id: string;
  name: string;
  targetArea: string;
  sets: number;
  seconds: number;
  steps: string[];
  errors: string[];
  imageUrls?: string[];
};

type WorkoutPlan = {
  id: string;
  title: string;
  estimatedMinutes: number;
  status: string;
  exercises: PlayerExercise[];
};

type WorkoutPlayerProps = {
  plan?: WorkoutPlan;
  exerciseLibrary: PlayerExercise[];
  reload: () => Promise<void>;
};

export function WorkoutPlayer({ plan, exerciseLibrary, reload }: WorkoutPlayerProps) {
  const navigate = useNavigate();
  const { planId } = useParams();
  const activePlan = plan?.id === planId ? plan : undefined;
  const exercises = useMemo(() => activePlan?.exercises.map((exercise) => {
    const libraryItem = exerciseLibrary.find((item) => item.id === exercise.id);
    return libraryItem ? { ...libraryItem, ...exercise, imageUrls: libraryItem.imageUrls ?? exercise.imageUrls } : exercise;
  }) ?? [], [activePlan, exerciseLibrary]);
  const [session, setSession] = useState(() => createWorkoutSession(exercises));
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(false);
  const [voiceStyle, setVoiceStyle] = useState<VoiceStyleKey>(() => readVoiceStyle());
  const [exitOpen, setExitOpen] = useState(false);
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [refreshFailed, setRefreshFailed] = useState(false);
  const completedPosted = useRef(false);
  const started = useRef(false);
  const voiceEngine = useRef<VoiceEngine | undefined>(undefined);
  const beatEngine = useRef<BeatEngine | undefined>(undefined);
  const previousVoiceSession = useRef<WorkoutSessionState>(session);
  const voiceControlSequence = useRef(0);
  const exitTrigger = useRef<HTMLButtonElement>(null);
  const exitDialog = useRef<HTMLDivElement>(null);
  const continueButton = useRef<HTMLButtonElement>(null);
  const pausedBeforeExit = useRef(false);
  const current = exercises[session.exerciseIndex] ?? exercises[0];
  const totalSets = exercises.reduce((sum, exercise) => sum + Math.max(1, exercise.sets), 0);
  const completionRatio = totalSets ? Math.min(1, session.completedSets / totalSets) : 0;
  const visualStep = current ? exerciseFrameStep(
    session.phase === 'EXERCISE' ? current.seconds - session.remaining : 0,
    current.imageUrls?.length ?? 0,
  ) : 1;
  if (!voiceEngine.current) voiceEngine.current = createBrowserVoiceEngine();
  if (!beatEngine.current) beatEngine.current = createBeatEngine();
  voiceEngine.current.setStyle(voiceStyle);
  const voiceAvailable = isVoiceSupported();

  useEffect(() => {
    if (paused || session.phase === 'READY' || session.phase === 'COMPLETED') return;
    const timer = window.setTimeout(() => setSession((value) => advanceWorkoutSession(value)), 1000);
    return () => window.clearTimeout(timer);
  }, [paused, session.exerciseIndex, session.phase, session.remaining, session.setIndex]);

  useEffect(() => {
    if (!current) return;
    const previous = previousVoiceSession.current;
    const cue = voiceCueForTransition(previous, session, current);
    const beat = beatForTransition(previous, session);
    previousVoiceSession.current = session;
    if (cue) voiceEngine.current?.speak(cue);
    if (beat) beatEngine.current?.play(beat);
  }, [current, session]);

  useEffect(() => () => {
    voiceEngine.current?.stop();
    beatEngine.current?.stop();
  }, []);

  const persistCompletion = useCallback(async () => {
    if (!activePlan || !started.current || totalSets === 0) return;
    setSaveState('saving');
    setRefreshFailed(false);
    try {
      await api.completeWorkout(activePlan.id, completionRatio);
      setSaveState('saved');
      try { await reload(); } catch { setRefreshFailed(true); }
    } catch { setSaveState('error'); }
  }, [activePlan, completionRatio, reload, totalSets]);

  useEffect(() => {
    if (!activePlan || !started.current || exercises.length === 0 || session.phase !== 'COMPLETED' || completedPosted.current) return;
    completedPosted.current = true;
    void persistCompletion();
  }, [activePlan, exercises.length, persistCompletion, session.phase]);

  useEffect(() => {
    if (!exitOpen) return;
    continueButton.current?.focus();
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        setExitOpen(false);
        setPaused(pausedBeforeExit.current);
        exitTrigger.current?.focus();
        return;
      }
      if (event.key !== 'Tab' || !exitDialog.current) return;
      const buttons = Array.from(exitDialog.current.querySelectorAll<HTMLButtonElement>('button'));
      if (!buttons.length) return;
      const first = buttons[0]; const last = buttons[buttons.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [exitOpen]);

  if (!activePlan || exercises.length === 0 || !current) {
    return <section className="page workout-page workout-page--empty"><button className="workout-top-button" aria-label="返回计划" onClick={() => navigate('/plan')}><ChevronLeft /></button><h1>这次训练暂时不可用</h1><p>返回计划页重新选择一份训练。</p></section>;
  }

  const progress = session.phase === 'COMPLETED' ? Math.round(completionRatio * 100) : Math.max(2, Math.round(session.completedSets / totalSets * 100));
  const nextExercise = exercises[session.exerciseIndex + 1];
  const toggleMuted = () => setMuted((value) => {
    const next = !value;
    voiceEngine.current?.mute(next);
    beatEngine.current?.mute(next);
    return next;
  });

  if (session.phase === 'READY') {
    return <section className="page workout-page workout-ready">
      <header className="workout-top"><button className="workout-top-button" aria-label="返回计划" onClick={() => navigate('/plan')}><ChevronLeft /></button><small>训练预览</small><button className="workout-top-button" aria-label={muted ? '打开声音' : '关闭声音'} onClick={toggleMuted}>{muted ? <VolumeX /> : <Volume2 />}</button></header>
      <div className="workout-ready__hero"><span>今日跟练</span><h1>{activePlan.title}</h1><p>{activePlan.estimatedMinutes} 分钟 · {exercises.length} 个动作 · {totalSets} 组</p></div>
      {!voiceAvailable && <p className="workout-voice-note"><VolumeX /> 当前浏览器不支持语音播报，计时仍会正常继续。</p>}
      <label className="workout-voice-style" htmlFor="workout-voice-style"><span>语音风格</span><select id="workout-voice-style" value={voiceStyle} onChange={(event) => { const next = event.target.value as VoiceStyleKey; setVoiceStyle(next); saveVoiceStyle(next); voiceEngine.current?.setStyle(next); }}>{VOICE_STYLE_OPTIONS.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}</select></label>
      <div className="workout-ready__visual"><ExerciseVisual exercise={current} autoPlay /></div>
      <div className="workout-ready__list">{exercises.map((exercise, index) => <article key={exercise.id}><b>{String(index + 1).padStart(2, '0')}</b><span><strong>{exercise.name}</strong><small>{exercise.sets} 组 × {exercise.seconds} 秒 · {exercise.targetArea}</small></span></article>)}</div>
      <button className="workout-start" onClick={() => { voiceEngine.current?.unlock(); beatEngine.current?.unlock(); started.current = true; navigator.vibrate?.([50, 40, 50]); setSession((value) => startWorkoutSession(value)); }}><Play /> 开始训练</button>
    </section>;
  }

  if (session.phase === 'COMPLETED') {
    return <section className="page workout-page workout-complete"><div className="workout-complete__mark"><Check /></div><small>完成得刚刚好</small><h1>今天的训练<br />已经收下啦</h1><p>实际跟练 {Math.max(1, Math.ceil(session.elapsedSeconds / 60))} 分钟 · 完成 {session.completedSets} / {totalSets} 组</p><div className={`workout-save workout-save--${saveState}`}>{saveState === 'saving' ? '正在保存训练记录…' : saveState === 'error' ? '训练记录保存失败' : refreshFailed ? '训练已保存，页面数据稍后刷新' : '训练记录已同步'}</div>{saveState === 'error' && <button className="workout-retry" onClick={() => void persistCompletion()}>重新保存</button>}<button className="workout-finish" onClick={() => navigate('/plan')}><Check /> 返回计划</button></section>;
  }

  const isRest = session.phase === 'REST';
  const phaseTitle = session.phase === 'COUNTDOWN' ? '准备开始' : isRest ? '休息一下' : current.name;
  const phaseHint = session.phase === 'COUNTDOWN' ? '站稳，调整呼吸' : isRest ? (session.restKind === 'SET' ? `下一组 · ${current.name}` : `下一个 · ${nextExercise?.name ?? current.name}`) : `第 ${session.setIndex + 1} / ${current.sets} 组 · ${current.targetArea}`;

  return <section className={`page workout-page${isRest ? ' workout-page--rest' : ''}`}>
    <header className="workout-top"><button ref={exitTrigger} className="workout-top-button" aria-label="退出训练" onClick={() => { voiceEngine.current?.stop(); beatEngine.current?.stop(); pausedBeforeExit.current = paused; setPaused(true); setExitOpen(true); }}><X /></button><div className="workout-progress"><i style={{ width: `${progress}%` }} /></div><button className="workout-top-button" aria-label={muted ? '打开声音' : '关闭声音'} onClick={toggleMuted}>{muted ? <VolumeX /> : <Volume2 />}</button></header>
    {!voiceAvailable && <p className="workout-voice-note"><VolumeX /> 当前浏览器不支持语音播报，计时仍会正常继续。</p>}
    <div className="workout-stage">
      <div className="workout-stage__visual"><ExerciseVisual exercise={current} step={visualStep} /></div>
      <div className="workout-stage__copy"><small>{phaseHint}</small><h1>{phaseTitle}</h1>{session.phase === 'EXERCISE' && <p>{current.steps[0] ?? '保持身体稳定，自然呼吸。'}</p>}</div>
      <div className="sr-only" aria-live="assertive">{session.remaining <= 3 ? `${phaseTitle}，${session.remaining} 秒` : phaseTitle}</div>
      <div className="workout-timer" role="timer" aria-label={`剩余 ${session.remaining} 秒`} style={{ '--timer-progress': `${Math.max(0, session.remaining / (isRest ? (session.restKind === 'SET' ? 20 : 30) : session.phase === 'COUNTDOWN' ? 3 : current.seconds) * 360)}deg` } as CSSProperties}><strong>{session.remaining}</strong><small>秒</small></div>
      {paused && <div className="workout-paused"><Pause /><strong>已暂停</strong><small>准备好再继续</small></div>}
    </div>
    <div className="workout-controls"><button aria-label="上一个动作" disabled={session.exerciseIndex === 0} onClick={() => { voiceEngine.current?.stop(); beatEngine.current?.stop(); setSession((value) => previousWorkoutSession(value)); }}><SkipBack /></button><button className="workout-pause" aria-label={paused ? '继续训练' : '暂停训练'} onClick={() => { const next = !paused; if (next) beatEngine.current?.stop(); voiceEngine.current?.speak({ id: `control:${next ? 'pause' : 'resume'}:${voiceControlSequence.current++}`, text: next ? '训练暂停' : '继续训练', interrupt: false }); setPaused(next); }}>{paused ? <Play /> : <Pause />}</button><button aria-label="下一个动作" disabled={session.exerciseIndex === exercises.length - 1} onClick={() => { voiceEngine.current?.stop(); beatEngine.current?.stop(); setSession((value) => nextWorkoutSession(value)); }}><SkipForward /></button></div>
    {nextExercise && <div className="workout-next"><small>接下来</small><strong>{nextExercise.name}</strong><span>{nextExercise.sets} 组</span></div>}
    {exitOpen && <div ref={exitDialog} className="workout-exit" role="dialog" aria-modal="true" aria-label="退出训练确认"><div><RotateCcw /><h2>要先离开一会儿吗？</h2><p>本次进度不会记为完成。</p><button className="workout-finish" onClick={() => navigate('/plan')}>退出训练</button><button ref={continueButton} className="workout-stay" onClick={() => { setExitOpen(false); setPaused(pausedBeforeExit.current); exitTrigger.current?.focus(); }}>继续跟练</button></div></div>}
  </section>;
}
