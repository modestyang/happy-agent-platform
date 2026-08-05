import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { Check, ChevronLeft, Pause, Play, RotateCcw, SkipBack, SkipForward, Volume2, VolumeX, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { api } from '../api';
import {
  advanceWorkoutSession,
  createWorkoutSession,
  nextWorkoutSession,
  previousWorkoutSession,
  startWorkoutSession,
  type WorkoutSessionState,
} from '../workout/workoutSession';
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

function speak(text: string, muted: boolean) {
  if (muted || !('speechSynthesis' in window) || typeof SpeechSynthesisUtterance === 'undefined') return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = 'zh-CN';
  utterance.rate = 1;
  window.speechSynthesis.speak(utterance);
}

function phaseVoice(state: WorkoutSessionState, exercise: PlayerExercise, muted: boolean) {
  if (state.phase === 'COUNTDOWN') speak(state.remaining === 3 ? '训练准备，3' : String(state.remaining), muted);
  if (state.phase === 'EXERCISE') {
    if (state.remaining === exercise.seconds) speak(`${exercise.name}，第 ${state.setIndex + 1} 组。${exercise.steps[0] ?? '保持稳定呼吸。'}`, muted);
    else if (state.remaining <= 3) speak(String(state.remaining), muted);
  }
  if (state.phase === 'REST') {
    if (state.remaining === 20) speak('休息 20 秒，准备下一组。', muted);
    else if (state.remaining === 30) speak('休息 30 秒，准备下一个动作。', muted);
    else if (state.remaining <= 3) speak(String(state.remaining), muted);
  }
  if (state.phase === 'COMPLETED') speak('训练完成，今天辛苦啦。', muted);
}

export function WorkoutPlayer({ plan, exerciseLibrary, reload }: WorkoutPlayerProps) {
  const navigate = useNavigate();
  const exercises = useMemo(() => plan?.exercises.map((exercise) => {
    const libraryItem = exerciseLibrary.find((item) => item.id === exercise.id);
    return libraryItem ? { ...libraryItem, ...exercise, imageUrls: libraryItem.imageUrls ?? exercise.imageUrls } : exercise;
  }) ?? [], [exerciseLibrary, plan]);
  const [session, setSession] = useState(() => createWorkoutSession(exercises));
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(false);
  const [exitOpen, setExitOpen] = useState(false);
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const completedPosted = useRef(false);
  const current = exercises[session.exerciseIndex] ?? exercises[0];

  useEffect(() => {
    if (paused || session.phase === 'READY' || session.phase === 'COMPLETED') return;
    const timer = window.setInterval(() => setSession((value) => advanceWorkoutSession(value)), 1000);
    return () => window.clearInterval(timer);
  }, [paused, session.phase]);

  useEffect(() => {
    if (!current || session.phase === 'READY') return;
    phaseVoice(session, current, muted);
  }, [current, muted, session.exerciseIndex, session.phase, session.remaining, session.setIndex]);

  useEffect(() => () => { if ('speechSynthesis' in window) window.speechSynthesis.cancel(); }, []);

  useEffect(() => {
    if (!plan || session.phase !== 'COMPLETED' || completedPosted.current) return;
    completedPosted.current = true;
    setSaveState('saving');
    void api.completeWorkout(plan.id, 1)
      .then(async () => { await reload(); setSaveState('saved'); })
      .catch(() => setSaveState('error'));
  }, [plan, reload, session.phase]);

  if (!plan || exercises.length === 0 || !current) {
    return <section className="page workout-page workout-page--empty"><button className="workout-top-button" aria-label="返回计划" onClick={() => navigate('/plan')}><ChevronLeft /></button><h1>这次训练暂时不可用</h1><p>返回计划页重新选择一份训练。</p></section>;
  }

  const totalSets = exercises.reduce((sum, exercise) => sum + Math.max(1, exercise.sets), 0);
  const completedSets = exercises.slice(0, session.exerciseIndex).reduce((sum, exercise) => sum + Math.max(1, exercise.sets), 0) + session.setIndex;
  const progress = session.phase === 'COMPLETED' ? 100 : Math.max(2, Math.round(completedSets / totalSets * 100));
  const nextExercise = exercises[session.exerciseIndex + 1];

  if (session.phase === 'READY') {
    return <section className="page workout-page workout-ready">
      <header className="workout-top"><button className="workout-top-button" aria-label="返回计划" onClick={() => navigate('/plan')}><ChevronLeft /></button><small>训练预览</small><button className="workout-top-button" aria-label={muted ? '打开声音' : '关闭声音'} onClick={() => setMuted((value) => !value)}>{muted ? <VolumeX /> : <Volume2 />}</button></header>
      <div className="workout-ready__hero"><span>今日跟练</span><h1>{plan.title}</h1><p>{plan.estimatedMinutes} 分钟 · {exercises.length} 个动作 · {totalSets} 组</p></div>
      <div className="workout-ready__visual"><ExerciseVisual exercise={current} /></div>
      <div className="workout-ready__list">{exercises.map((exercise, index) => <article key={exercise.id}><b>{String(index + 1).padStart(2, '0')}</b><span><strong>{exercise.name}</strong><small>{exercise.sets} 组 × {exercise.seconds} 秒 · {exercise.targetArea}</small></span></article>)}</div>
      <button className="workout-start" onClick={() => { navigator.vibrate?.([50, 40, 50]); setSession((value) => startWorkoutSession(value)); }}><Play /> 开始训练</button>
    </section>;
  }

  if (session.phase === 'COMPLETED') {
    return <section className="page workout-page workout-complete"><div className="workout-complete__mark"><Check /></div><small>完成得刚刚好</small><h1>今天的训练<br />已经收下啦</h1><p>实际跟练 {Math.max(1, Math.ceil(session.elapsedSeconds / 60))} 分钟 · 完成 {totalSets} 组</p><div className={`workout-save workout-save--${saveState}`}>{saveState === 'saving' ? '正在保存训练记录…' : saveState === 'error' ? '记录保存失败，返回后可重试' : '训练记录已同步'}</div><button className="workout-finish" onClick={() => navigate('/plan')}><Check /> 返回计划</button></section>;
  }

  const isRest = session.phase === 'REST';
  const phaseTitle = session.phase === 'COUNTDOWN' ? '准备开始' : isRest ? '休息一下' : current.name;
  const phaseHint = session.phase === 'COUNTDOWN' ? '站稳，调整呼吸' : isRest ? (session.restKind === 'SET' ? `下一组 · ${current.name}` : `下一个 · ${nextExercise?.name ?? current.name}`) : `第 ${session.setIndex + 1} / ${current.sets} 组 · ${current.targetArea}`;

  return <section className={`page workout-page${isRest ? ' workout-page--rest' : ''}`}>
    <header className="workout-top"><button className="workout-top-button" aria-label="退出训练" onClick={() => { setPaused(true); setExitOpen(true); }}><X /></button><div className="workout-progress"><i style={{ width: `${progress}%` }} /></div><button className="workout-top-button" aria-label={muted ? '打开声音' : '关闭声音'} onClick={() => setMuted((value) => !value)}>{muted ? <VolumeX /> : <Volume2 />}</button></header>
    <div className="workout-stage">
      <div className="workout-stage__visual"><ExerciseVisual exercise={current} step={(session.setIndex % 4) + 1} /></div>
      <div className="workout-stage__copy"><small>{phaseHint}</small><h1>{phaseTitle}</h1>{session.phase === 'EXERCISE' && <p>{current.steps[0] ?? '保持身体稳定，自然呼吸。'}</p>}</div>
      <div className="workout-timer" role="timer" aria-live="polite" aria-label={`剩余 ${session.remaining} 秒`} style={{ '--timer-progress': `${Math.max(0, session.remaining / (isRest ? (session.restKind === 'SET' ? 20 : 30) : session.phase === 'COUNTDOWN' ? 3 : current.seconds) * 360)}deg` } as CSSProperties}><strong>{session.remaining}</strong><small>秒</small></div>
      {paused && <div className="workout-paused"><Pause /><strong>已暂停</strong><small>准备好再继续</small></div>}
    </div>
    <div className="workout-controls"><button aria-label="上一个动作" disabled={session.exerciseIndex === 0} onClick={() => setSession((value) => previousWorkoutSession(value))}><SkipBack /></button><button className="workout-pause" aria-label={paused ? '继续训练' : '暂停训练'} onClick={() => { setPaused((value) => !value); speak(paused ? '继续训练' : '训练暂停', muted); }}>{paused ? <Play /> : <Pause />}</button><button aria-label="下一个动作" onClick={() => setSession((value) => nextWorkoutSession(value))}><SkipForward /></button></div>
    {nextExercise && <div className="workout-next"><small>接下来</small><strong>{nextExercise.name}</strong><span>{nextExercise.sets} 组</span></div>}
    {exitOpen && <div className="workout-exit" role="dialog" aria-modal="true" aria-label="退出训练确认"><div><RotateCcw /><h2>要先离开一会儿吗？</h2><p>本次进度不会记为完成。</p><button className="workout-finish" onClick={() => navigate('/plan')}>退出训练</button><button className="workout-stay" onClick={() => { setExitOpen(false); setPaused(false); }}>继续跟练</button></div></div>}
  </section>;
}
