import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { BrowserRouter, Link, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Activity, Award, BarChart3, Bot, CalendarDays, Camera, Check, ChevronLeft, ChevronRight,
  Clock3, Dumbbell, Flame, Home, LogOut, Medal, MessageCirclePlus, Play, Plus, Salad,
  Search, Send, Sparkles, Target, Trophy, UserRound, Utensils, WandSparkles, X,
} from 'lucide-react';
import { ApiError, api } from './api';
import { ChatMarkdown } from './components/ChatMarkdown';
import { ExerciseVisual } from './components/ExerciseVisual';
import { MealRecommendationPage, mealTimingLabel, nextMealRecommendation, recommendationKcal, type MealRecommendation } from './components/MealRecommendationPage';
import { BodyActivation, WeightSparkline } from './components/MiniVisuals';
import { WorkoutPlayer } from './components/WorkoutPlayer';
import { AdminWorkbench } from './admin/AdminWorkbench';
import './app.css';

type Food = { name: string; estimatedKcal: number };
type Exercise = { id: string; name: string; targetArea: string; sets: number; seconds: number; steps: string[]; errors: string[]; illustrationMode?: string; imageUrls?: string[] };
type Dashboard = {
  user: { id: string; nickname: string };
  goal?: { id: string; name: string; startWeightJin: number; currentWeightJin: number; targetWeightJin: number; status: string; progressPercent: number };
  bodyRecords: { id: string; recordedAt: string; weightJin?: number; waistCm?: number }[];
  meals: { id: string; occurredAt: string; mealType: string; items: Food[] }[];
  mealRecommendations: MealRecommendation[];
  plan?: { id: string; title: string; estimatedMinutes: number; status: string; exercises: Exercise[] };
  exercises: Exercise[];
  completedWorkoutCount?: number;
  report?: { status: string; score: number; conclusion: string; metrics: { label: string; value: string; comparison?: string }[]; actions: string[] };
  ai: { configured: boolean; reason?: string };
};

type RecordTab = 'body' | 'meal';
type ChatMessage = { role: 'user' | 'assistant'; content: string };

const weekNames = ['日', '一', '二', '三', '四', '五', '六'];
const tones = ['温暖直接', '轻松逗趣', '冷静专业'];
const preferenceOptions = ['中式家常', '少跳跃', '晚餐清淡', '温和提醒'];
const AI_SESSION_TTL_MS = 24 * 60 * 60 * 1000;

function errorText(error: unknown) { return error instanceof Error ? error.message : '网络似乎出了点问题，请重试。'; }
function readJson<T>(key: string, fallback: T): T {
  try { const value = window.localStorage.getItem(key); return value ? JSON.parse(value) as T : fallback; } catch { return fallback; }
}
function readAiSession(key: string) {
  const stored = readJson<{ updatedAt?: number; messages?: ChatMessage[] }>(key, {});
  if (!stored.updatedAt || !Array.isArray(stored.messages) || Date.now() - stored.updatedAt >= AI_SESSION_TTL_MS) {
    try { window.localStorage.removeItem(key); } catch { /* Ignore unavailable storage. */ }
    return [];
  }
  return stored.messages.filter((message) => (message.role === 'user' || message.role === 'assistant') && typeof message.content === 'string');
}
function dayKey(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function sameDay(left: Date, right: Date) { return dayKey(left) === dayKey(right); }
function startOfToday() { const date = new Date(); date.setHours(0, 0, 0, 0); return date; }
function useToday() {
  const [today, setToday] = useState(startOfToday);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      const now = new Date();
      const tomorrow = new Date(now); tomorrow.setHours(24, 0, 1, 0);
      timer = setTimeout(() => { setToday(startOfToday()); schedule(); }, tomorrow.getTime() - now.getTime());
    };
    schedule();
    return () => clearTimeout(timer);
  }, []);
  return today;
}
function currentWeek(today: Date) {
  const start = new Date(today);
  start.setDate(today.getDate() - ((today.getDay() + 6) % 7));
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(start);
    date.setDate(start.getDate() + index);
    return date;
  });
}
function equipmentFor(exercise: Exercise) { return /哑铃|壶铃|弹力带/.test(exercise.name) ? '小器械' : '徒手'; }

function Mascot({ small = false }: { small?: boolean }) {
  return <div className={`mascot${small ? ' mascot--small' : ''}`} aria-hidden="true"><i /><span>•ᴗ•</span><b /></div>;
}

function Header({ nickname, title, subtitle }: { nickname: string; title: string; subtitle?: string }) {
  return <header className="page-head">
    <div className="avatar" aria-hidden="true">{nickname.slice(0, 1)}</div>
    <div><p>{subtitle ?? '今天也只做刚刚好的一点'}</p><h1>{title}</h1></div>
    <Mascot small />
  </header>;
}

function Navigation() {
  const { pathname } = useLocation();
  if (pathname.startsWith('/meal/') || pathname.startsWith('/workout/')) return null;
  const items = [
    { to: '/', label: '今天', icon: Home },
    { to: '/plan', label: '计划', icon: CalendarDays },
    { to: '/ai', label: '瘦瘦', icon: Bot, ai: true },
    { to: '/library', label: '动作', icon: Dumbbell },
    { to: '/me', label: '我的', icon: UserRound },
  ];
  return <nav className="bottom-nav" aria-label="主导航">{items.map(({ to, label, icon: Icon, ai }) => <Link key={to} aria-label={label} aria-current={pathname === to ? 'page' : undefined} className={`nav-link${pathname === to ? ' active' : ''}${ai ? ' nav-link--ai' : ''}`} to={to}><span><Icon /></span><small>{label}</small></Link>)}</nav>;
}

function Empty({ icon: Icon = Sparkles, title, text }: { icon?: typeof Sparkles; title: string; text: string }) {
  return <section className="empty-card"><span><Icon /></span><h2>{title}</h2><p>{text}</p></section>;
}

function HomePage({ data, onOpenRecord }: { data: Dashboard; onOpenRecord: (tab: RecordTab) => void }) {
  const navigate = useNavigate();
  const goal = data.goal;
  const now = new Date();
  const nextMeal = nextMealRecommendation(data.mealRecommendations ?? [], now);
  const mealName = nextMeal?.mealType === 'BREAKFAST' ? '早餐' : nextMeal?.mealType === 'LUNCH' ? '午餐' : '晚餐';
  const hour = now.getHours();
  const greeting = hour < 11 ? '早上好' : hour < 18 ? '下午好' : '晚上好';
  const totalSets = data.plan?.exercises.reduce((sum, exercise) => sum + exercise.sets, 0) ?? 0;
  const quickActions = [
    { title: '训练', eyebrow: '今日主题', headline: data.plan?.title ?? '今天还没安排', meta: data.plan ? `${data.plan.estimatedMinutes} 分钟 · ${data.plan.exercises.length} 动作 · ${totalSets} 组` : '去看看本周计划', icon: Flame, tone: 'tangerine', action: () => navigate('/plan') },
    { title: '饮食', eyebrow: nextMeal ? `${mealTimingLabel(now, nextMeal.mealType)} · ${mealName}` : '今日推荐', headline: nextMeal?.items[0]?.name ?? '今日建议尚未生成', meta: nextMeal ? `约 ${recommendationKcal(nextMeal)} kcal` : '生成后会展示在这里', icon: Salad, tone: 'butter', action: () => navigate('/meal/today') },
    { title: '记录', eyebrow: '留下真实数据', headline: '记录身材或一餐', meta: '体重 · 腰围 · 饮食', icon: Plus, tone: 'mint', action: () => onOpenRecord('body') },
    { title: '报告', eyebrow: '当前目标', headline: '累计变化分析', meta: '由瘦瘦整理依据与建议', icon: BarChart3, tone: 'sky', action: () => navigate('/ai?prompt=请生成我的当前目标累计报告') },
  ];

  return <section className="page home-page">
    <header className="home-greeting">
      <div className="home-greeting__copy"><small>{greeting}，{data.user.nickname}</small><h1>今天，慢慢变好</h1><p>不用追赶谁，照顾好此刻的自己。</p></div>
      <Mascot />
    </header>
    {goal ? <section className="goal-card">
      <div className="goal-card__top"><span><Target /> 当前目标</span><button aria-label="查看目标进度" onClick={() => navigate('/ai?prompt=分析我的当前目标进度')}><ChevronRight /></button></div>
      <h2>{goal.name}</h2>
      <div className="goal-card__numbers"><strong>{goal.currentWeightJin}<small>斤</small></strong><span>目标<br /><b>{goal.targetWeightJin} 斤</b></span></div>
      <div className="progress" role="progressbar" aria-label={`目标已完成 ${goal.progressPercent}%`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={goal.progressPercent}><i style={{ width: `${goal.progressPercent}%` }} /></div>
      <p>已经走完 {goal.progressPercent}% <Sparkles /></p>
    </section> : <Empty icon={Target} title="设一个刚刚好的目标" text="有方向，但不用给自己太大压力。" />}
    <section className="home-actions" aria-label="今日快捷功能">{quickActions.map(({ title, eyebrow, headline, meta, icon: Icon, tone, action }) => <button key={title} className={`home-action home-action--${tone}`} aria-label={title} onClick={action}><span className="home-action__icon"><Icon /></span><div className="home-action__title"><small>{eyebrow}</small><strong>{title}</strong></div><div className="home-action__summary"><b>{headline}</b><small>{meta}</small></div><i><ChevronRight /></i></button>)}</section>
  </section>;
}

function RecordDrawer({ initialTab, initialRecord, onClose, onSaved }: { initialTab: RecordTab; initialRecord?: Dashboard['bodyRecords'][number]; onClose: () => void; onSaved: () => Promise<void> }) {
  const drawerRef = useRef<HTMLFormElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [tab, setTab] = useState<RecordTab>(initialTab);
  const [weightJin, setWeightJin] = useState(initialRecord?.weightJin?.toString() ?? '');
  const [waistCm, setWaistCm] = useState(initialRecord?.waistCm?.toString() ?? '');
  const [mealType, setMealType] = useState('BREAKFAST');
  const [items, setItems] = useState<Food[]>([{ name: '', estimatedKcal: 0 }]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    closeRef.current?.focus();
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
      if (event.key !== 'Tab' || !drawerRef.current) return;
      const focusable = Array.from(drawerRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'));
      if (!focusable.length) return;
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);
  const updateItem = (index: number, key: keyof Food, value: string) => setItems((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: key === 'estimatedKcal' ? Number(value) : value } : item));
  async function save(event: FormEvent) {
    event.preventDefault(); setError('');
    if (tab === 'body' && !weightJin && !waistCm) { setError('请至少填写体重或腰围。'); return; }
    if (tab === 'meal' && items.some((item) => !item.name.trim())) { setError('请填写至少一项食物名称。'); return; }
    setSaving(true);
    try {
      if (tab === 'body') await api.bodyRecord({ ...(weightJin ? { weightJin: Number(weightJin) } : {}), ...(waistCm ? { waistCm: Number(waistCm) } : {}) });
      else await api.meal(mealType, items);
      await onSaved(); onClose();
    } catch (err) { setError(errorText(err)); } finally { setSaving(false); }
  }
  return <div className="drawer-backdrop" role="dialog" aria-modal="true" aria-label="记录抽屉"><form ref={drawerRef} className="drawer" onSubmit={save}>
    <div className="drawer-head"><div><small>留下真实的一笔</small><h2>记录这一刻</h2></div><button ref={closeRef} type="button" className="icon-button" aria-label="关闭记录" onClick={onClose}><X /></button></div>
    <div className="tabs"><button type="button" aria-pressed={tab === 'body'} className={tab === 'body' ? 'active' : ''} onClick={() => setTab('body')}>身材记录</button><button type="button" aria-pressed={tab === 'meal'} className={tab === 'meal' ? 'active' : ''} onClick={() => setTab('meal')}>饮食记录</button></div>
    {tab === 'body' ? <div className="record-form-grid"><label>体重 (斤)<input type="number" step="0.1" value={weightJin} onChange={(event) => setWeightJin(event.target.value)} /></label><label>腰围 (cm)<input type="number" step="0.1" value={waistCm} onChange={(event) => setWaistCm(event.target.value)} /></label></div> : <><label>餐次<select aria-label="餐次" value={mealType} onChange={(event) => setMealType(event.target.value)}><option value="BREAKFAST">早餐</option><option value="LUNCH">午餐</option><option value="DINNER">晚餐</option><option value="SNACK">加餐</option></select></label>{items.map((item, index) => <div className="food-row" key={index}><label>吃了什么<input value={item.name} onChange={(event) => updateItem(index, 'name', event.target.value)} required /></label><label>热量 (kcal)<input type="number" min="0" value={item.estimatedKcal || ''} onChange={(event) => updateItem(index, 'estimatedKcal', event.target.value)} required /></label></div>)}<button type="button" className="soft-button" onClick={() => setItems((current) => [...current, { name: '', estimatedKcal: 0 }])}><Plus /> 新增食物</button></>}
    {error && <p className="error">{error}</p>}<button className="primary" disabled={saving}>{saving ? '正在保存…' : tab === 'body' ? '保存身材记录' : '保存饮食记录'}</button>
  </form></div>;
}

function PlanPage({ data, reload }: { data: Dashboard; reload: () => Promise<void> }) {
  const navigate = useNavigate();
  const today = useToday();
  const todayKey = dayKey(today);
  const days = useMemo(() => currentWeek(today), [todayKey]);
  const [selectedKey, setSelectedKey] = useState(dayKey(today));
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');
  const selectedDate = days.find((date) => dayKey(date) === selectedKey) ?? today;
  const plan = sameDay(selectedDate, today) ? data.plan : undefined;
  const canGenerate = selectedDate.getTime() >= today.getTime();
  useEffect(() => { setSelectedKey(todayKey); }, [todayKey]);
  async function complete() { if (!plan) return; setError(''); try { await api.completeWorkout(plan.id, 1); await reload(); setDone(true); navigator.vibrate?.(100); } catch (err) { setError(errorText(err)); } }
  const prompt = `请为我生成${selectedDate.getMonth() + 1}月${selectedDate.getDate()}日的训练计划`;

  return <section className="page plan-page"><Header nickname={data.user.nickname} title="本周计划" subtitle="选一天，看看身体要做什么" />
    <section className="week-strip" role="region" aria-label="本周日期">{days.map((date) => {
      const selected = dayKey(date) === selectedKey;
      const isToday = sameDay(date, today);
      return <button key={dayKey(date)} aria-pressed={selected} aria-label={`周${weekNames[date.getDay()]} ${date.getMonth() + 1}月${date.getDate()}日${isToday ? ' 今天' : ''}`} className={selected ? 'is-active' : ''} onClick={() => setSelectedKey(dayKey(date))}><small>{weekNames[date.getDay()]}</small><strong>{date.getDate()}</strong>{isToday && <i />}</button>;
    })}</section>
    {plan ? <>
      <section className="plan-hero"><div><small>{done || plan.status === 'COMPLETED' ? '今天完成啦' : '今天的训练'}</small><h2>{plan.title}</h2><p><Clock3 /> {plan.estimatedMinutes} 分钟 <i /> {plan.exercises.reduce((sum, exercise) => sum + exercise.sets, 0)} 组</p></div><span>{plan.exercises.length}<small>个动作</small></span></section>
      <div className="plan-exercises">{plan.exercises.map((exercise, index) => {
        const media = data.exercises.find((item) => item.id === exercise.id) ?? exercise;
        return <article className="plan-exercise" key={exercise.id}><ExerciseVisual exercise={media} compact /><div className="plan-exercise__copy"><small>{String(index + 1).padStart(2, '0')} · {exercise.targetArea}</small><h2>{exercise.name}</h2><p>{exercise.sets} 组 × {exercise.seconds} 秒</p><div className="cue"><strong>动作要点</strong>{exercise.steps.map((step) => <span key={step}>{step}</span>)}</div>{exercise.errors.length > 0 && <div className="mistakes"><strong>常见错误</strong>{exercise.errors.map((item) => <span key={item}>{item}</span>)}</div>}</div></article>;
      })}</div>
      {error && <p className="error">{error}</p>}
      {done || plan.status === 'COMPLETED' ? <p className="success-card"><Check /> 今天的训练已经好好收下。</p> : <div className="plan-actions"><button className="primary" onClick={() => navigate(`/workout/${plan.id}`)}><Play /> 开始跟练</button><button className="soft-button" onClick={complete}><Check /> 完成本次训练</button></div>}
    </> : canGenerate ? <section className="ai-plan-empty"><span><WandSparkles /></span><small>这一天还没有安排</small><h2>交给瘦瘦，拼一份刚刚好的训练</h2><p>会结合当前目标和已有记录，不盲目加量。</p><Link to={`/ai?prompt=${encodeURIComponent(prompt)}`} aria-label="AI 生成训练计划">AI 生成训练计划 <ChevronRight /></Link></section> : <Empty icon={CalendarDays} title="无训练计划" text="这一天没有留下训练安排，休息也算计划的一部分。" />}
  </section>;
}

const aiFeatures = [
  { title: '今天怎么练', description: '按时间和计划排好顺序', prompt: '根据我的计划，告诉我今天怎么练', icon: Dumbbell, tone: 'tangerine' },
  { title: '今晚吃什么', description: '结合今天记录推荐晚餐', prompt: '结合我今天的记录，推荐今晚吃什么', icon: Salad, tone: 'butter' },
  { title: '帮我记一餐', description: '整理食物与热量信息', prompt: '帮我记录刚刚吃的这一餐', icon: Camera, tone: 'mint' },
  { title: '看看最近状态', description: '训练、饮食和身体一起看', prompt: '帮我看看最近的训练、饮食和身体变化', icon: Activity, tone: 'sky' },
];

function AiPage({ data }: { data: Dashboard }) {
  const [searchParams] = useSearchParams();
  const preparedPrompt = searchParams.get('prompt') ?? '';
  const preparedSent = useRef('');
  const sessionKey = `happy-fitness-ai-session:${data.user.id}`;
  const [messages, setMessages] = useState<ChatMessage[]>(() => readAiSession(sessionKey));
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const conversationGeneration = useRef(0);
  useEffect(() => {
    try {
      if (messages.length) window.localStorage.setItem(sessionKey, JSON.stringify({ updatedAt: Date.now(), messages }));
      else window.localStorage.removeItem(sessionKey);
    } catch { /* Storage may be disabled; the in-memory conversation remains usable. */ }
  }, [messages, sessionKey]);

  async function submit(message: string) {
    if (!message.trim() || sending) return;
    const generation = conversationGeneration.current;
    setMessages((list) => [...list, { role: 'user', content: message }]); setValue(''); setSending(true); setError('');
    try {
      const response = await api.aiMessage(message);
      if (conversationGeneration.current !== generation) return;
      if (response.message) setMessages((list) => [...list, { role: 'assistant', content: response.message }]);
    } catch (err) {
      if (conversationGeneration.current !== generation) return;
      setError(err instanceof ApiError && err.status === 503 ? '瘦瘦还没接上大模型，请先在 Agent 工作台配置 Provider。' : errorText(err));
    } finally { if (conversationGeneration.current === generation) setSending(false); }
  }
  useEffect(() => {
    if (!preparedPrompt || preparedSent.current === preparedPrompt) return;
    preparedSent.current = preparedPrompt;
    void submit(preparedPrompt);
  }, [preparedPrompt]);
  const isWelcome = messages.length === 0;

  return <section className="page ai-page">
    <header className="ai-head"><div className="ai-avatar"><Bot /><i /></div><div><small>你的 AI 健身伴侣</small><h1>瘦瘦</h1></div><button aria-label="新建会话" onClick={() => { conversationGeneration.current += 1; setMessages([]); setError(''); setSending(false); }}><MessageCirclePlus /></button></header>
    <div className="ai-scroll">{isWelcome ? <>
      <section className="ai-greeting"><Mascot small /><div><strong>嗨，{data.user.nickname}。</strong><p>今天想让我陪你做点什么？</p></div><Sparkles /></section>
      <section className="ai-capabilities" role="region" aria-label="瘦瘦快捷能力">{aiFeatures.map(({ title, description, prompt, icon: Icon, tone }) => <button key={title} aria-label={`${title}：${description}`} className={`ai-capability ai-capability--${tone}`} onClick={() => void submit(prompt)}><span><Icon /></span><strong>{title}</strong><small>{description}</small><ChevronRight /></button>)}</section>
      {!data.ai.configured && <p className="ai-offline"><Bot /> 大模型尚未配置，其他记录与训练功能不受影响。</p>}
    </> : <section className="conversation" aria-label="当前对话">{messages.map((message, index) => <div className={`message message--${message.role}`} key={`${message.content}-${index}`}>{message.role === 'assistant' && <Bot />}<div className="message-body"><ChatMarkdown text={message.content} /></div></div>)}{sending && <div className="typing" aria-label="瘦瘦正在回复"><i /><i /><i /></div>}{error && <p className="error">{error}</p>}</section>}</div>
    <div className="fixed-composer">{!isWelcome && <div className="prompt-row" aria-label="推荐问题">{['具体怎么做', '换一个选择', '看看近期依据'].map((chip) => <button key={chip} onClick={() => setValue(chip)}>{chip}</button>)}</div>}<form className="composer" onSubmit={(event) => { event.preventDefault(); void submit(value); }}><Plus aria-hidden="true" /><input aria-label="问瘦瘦" value={value} onChange={(event) => setValue(event.target.value)} placeholder="告诉瘦瘦你今天的情况…" /><button className="send" aria-label="发送" disabled={sending}><Send /></button></form><small className="session-note">会话 24 小时无操作后自动结束</small></div>
  </section>;
}

function LibraryPage({ data }: { data: Dashboard }) {
  const [picked, setPicked] = useState<Exercise>();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('全部');
  const filters = ['全部', ...Array.from(new Set(data.exercises.map((item) => item.targetArea))), '徒手', '小器械'];
  const filtered = data.exercises.filter((exercise) => {
    const normalized = query.trim().toLowerCase();
    const queryMatches = !normalized || `${exercise.name}${exercise.targetArea}`.toLowerCase().includes(normalized);
    const tagMatches = filter === '全部' || exercise.targetArea === filter || equipmentFor(exercise) === filter;
    return queryMatches && tagMatches;
  });

  return <section className="page library-page"><Header nickname={data.user.nickname} title="动作素材库" subtitle="先看明白，再安心地练" />
    {picked ? <section className="exercise-detail"><button className="back-button" aria-label="返回动作库" onClick={() => setPicked(undefined)}><ChevronLeft /></button><div className="detail-title"><small>{picked.targetArea} · {equipmentFor(picked)}</small><h2>{picked.name}</h2><p>{picked.sets} 组 × {picked.seconds} 秒</p></div><div className="step-grid">{[1, 2, 3, 4].map((step) => <article key={step}><ExerciseVisual exercise={picked} step={step} /><strong>动作步骤 {step}</strong><p>{picked.steps[step - 1] ?? '稳定控制身体，保持自然呼吸。'}</p></article>)}</div><section className="detail-block"><h3>姿势要点</h3>{picked.steps.map((step) => <p key={step}><Check /> {step}</p>)}</section><section className="detail-block detail-block--warning"><h3>常见错误</h3>{picked.errors.length ? picked.errors.map((item) => <p key={item}><X /> {item}</p>) : <p>暂无特别提醒，保持动作稳定即可。</p>}</section></section> : <>
      <label className="library-search"><Search /><input type="search" aria-label="搜索动作" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索动作或训练部位" /></label>
      <div className="filter-tags" aria-label="动作筛选">{filters.map((item) => <button key={item} aria-pressed={filter === item} className={filter === item ? 'is-active' : ''} onClick={() => setFilter(item)}>{item}</button>)}</div>
      <div className="result-title"><strong>{filter === '全部' ? '推荐动作' : filter}</strong><small>{filtered.length} 个</small></div>
      {filtered.length ? <section className="exercise-grid">{filtered.map((exercise) => <button key={exercise.id} aria-label={`查看${exercise.name}详情`} onClick={() => setPicked(exercise)}><ExerciseVisual exercise={exercise} compact /><span><small>{exercise.targetArea} · {equipmentFor(exercise)}</small><strong>{exercise.name}</strong><em>{exercise.sets} 组 × {exercise.seconds} 秒</em></span><ChevronRight /></button>)}</section> : <Empty icon={Search} title="没有找到这个动作" text="换个关键词或清空筛选试试。" />}
    </>}
  </section>;
}

function MePage({ data, onLoggedOut }: { data: Dashboard; onLoggedOut: () => void }) {
  const preferenceKey = `happy-fitness-preferences:${data.user.id}`;
  const savedPreferences = readJson<{ tone?: string; preferences?: string[] }>(preferenceKey, {});
  const [logoutError, setLogoutError] = useState('');
  const [tone, setTone] = useState(() => tones.includes(savedPreferences.tone ?? '') ? savedPreferences.tone as string : tones[0]);
  const [preferences, setPreferences] = useState<string[]>(() => savedPreferences.preferences?.filter((item) => preferenceOptions.includes(item)) ?? ['晚餐清淡', '温和提醒']);
  useEffect(() => {
    try { window.localStorage.setItem(preferenceKey, JSON.stringify({ tone, preferences })); } catch { /* Keep the current in-memory choice. */ }
  }, [preferenceKey, preferences, tone]);
  const recordDays = new Set([...data.bodyRecords.map((record) => dayKey(new Date(record.recordedAt))), ...data.meals.map((meal) => dayKey(new Date(meal.occurredAt)))]).size;
  const areas = Array.from(new Set(data.plan?.exercises.map((exercise) => exercise.targetArea) ?? []));
  const achievements = [
    { label: '第一步', earned: data.bodyRecords.length + data.meals.length > 0, icon: Medal },
    { label: '认真吃饭', earned: data.meals.length >= 3, icon: Utensils },
    { label: '目标同行', earned: (data.goal?.progressPercent ?? 0) >= 25, icon: Trophy },
  ];
  async function logout() { try { await api.logout(); onLoggedOut(); } catch (err) { setLogoutError(errorText(err)); } }
  function togglePreference(option: string) { setPreferences((current) => current.includes(option) ? current.filter((item) => item !== option) : [...current, option]); }

  return <section className="page profile-page">
    <header className="profile-cover"><div className="profile-avatar">{data.user.nickname.slice(0, 1)}</div><div><small>和瘦瘦一起认真生活</small><h1>{data.user.nickname}</h1><p>{data.goal?.name ?? '还没有进行中的目标'}</p></div><Sparkles /></header>
    <section className="profile-config" aria-label="训练配置"><article><Clock3 /><span>本次时长<strong>{data.plan ? `${data.plan.estimatedMinutes} 分钟` : '未安排'}</strong></span></article><article><Dumbbell /><span>动作数量<strong>{data.plan?.exercises.length ?? 0} 个</strong></span></article><article><Target /><span>训练部位<strong>{areas[0] ?? '待安排'}</strong></span></article></section>
    <div className="section-heading"><div><small>每一次都算数</small><h2>坚持足迹</h2></div><strong>{recordDays}<small>天</small></strong></div>
    <section className="achievements">{achievements.map(({ label, earned, icon: Icon }) => <article className={earned ? 'is-earned' : ''} key={label}><span><Icon /></span><strong>{label}</strong><small>{earned ? '已点亮' : '继续积累'}</small></article>)}</section>
    <section className="profile-panel activation-panel"><div className="panel-title"><span><Activity /></span><div><small>本次计划</small><h2>运动点亮图</h2></div></div><BodyActivation areas={areas} /></section>
    <section className="profile-panel trend-panel"><div className="panel-title"><span><BarChart3 /></span><div><small>客观记录</small><h2>体重 / 体脂趋势</h2></div></div><WeightSparkline records={data.bodyRecords} /><p className="body-fat-empty">体脂暂无数据 · 记录后会和体重一起呈现</p></section>
    <section className="profile-panel"><div className="panel-title"><span><Bot /></span><div><small>陪伴方式</small><h2>AI 教练语气</h2></div></div><div className="choice-row">{tones.map((item) => <button key={item} aria-pressed={tone === item} className={tone === item ? 'is-active' : ''} onClick={() => setTone(item)}>{tone === item && <Check />}{item}</button>)}</div></section>
    <section className="profile-panel"><div className="panel-title"><span><Sparkles /></span><div><small>瘦瘦记住的你</small><h2>个人偏好</h2></div></div><div className="preference-tags">{preferenceOptions.map((option) => <button key={option} aria-pressed={preferences.includes(option)} className={preferences.includes(option) ? 'is-active' : ''} onClick={() => togglePreference(option)}>{preferences.includes(option) && <Check />}{option}</button>)}</div></section>
    <section className="profile-panel"><div className="panel-title"><span><Award /></span><div><small>真实数据</small><h2>历史记录</h2></div></div><div className="history-list"><p><span>训练历史</span><strong>{data.completedWorkoutCount ?? 0} 次</strong></p><p><span>指标记录</span><strong>{data.bodyRecords.length} 条</strong></p><p><span>饮食记录</span><strong>{data.meals.length} 餐</strong></p></div></section>
    {logoutError && <p className="error">{logoutError}</p>}<button className="logout-button" onClick={() => void logout()}><LogOut /> 退出登录</button>
  </section>;
}

function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const [username, setUsername] = useState(''); const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [pending, setPending] = useState(false);
  async function submit(event: FormEvent) { event.preventDefault(); setPending(true); setError(''); try { await api.login(username, password); await onLogin(); } catch (err) { setError(errorText(err)); } finally { setPending(false); } }
  return <div className="desktop"><main className="phone login"><Mascot /><small>HAPPY BODY · DAILY LOG</small><h1>把训练和吃饭，<br />过成自己的节奏。</h1><p>欢迎回来，今天也不用太用力。</p><form onSubmit={submit}><label>用户名<input value={username} onChange={(event) => setUsername(event.target.value)} required /></label><label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{error && <p className="error">{error}</p>}<button className="primary" disabled={pending}>{pending ? '正在登录…' : '登录'}</button></form></main></div>;
}

function Shell({ data, reload, onLoggedOut }: { data: Dashboard; reload: () => Promise<void>; onLoggedOut: () => void }) {
  const [recordTab, setRecordTab] = useState<RecordTab>();
  const restoreFocus = useRef<HTMLElement | null>(null);
  const openRecord = useCallback((tab: RecordTab) => {
    restoreFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setRecordTab(tab);
  }, []);
  const closeRecord = useCallback(() => {
    restoreFocus.current?.focus();
    setRecordTab(undefined);
  }, []);
  return <div className="desktop"><main className={`phone${recordTab ? ' page-modal' : ''}`} aria-label="Happy Agent Platform"><Routes><Route path="/" element={<HomePage data={data} onOpenRecord={openRecord} />} /><Route path="/meal/today" element={<MealRecommendationPage recommendations={data.mealRecommendations ?? []} />} /><Route path="/plan" element={<PlanPage data={data} reload={reload} />} /><Route path="/workout/:planId" element={<WorkoutPlayer plan={data.plan} exerciseLibrary={data.exercises} reload={reload} />} /><Route path="/ai" element={<AiPage data={data} />} /><Route path="/library" element={<LibraryPage data={data} />} /><Route path="/me" element={<MePage data={data} onLoggedOut={onLoggedOut} />} /><Route path="*" element={<HomePage data={data} onOpenRecord={openRecord} />} /></Routes><Navigation />{recordTab && <RecordDrawer initialTab={recordTab} initialRecord={data.bodyRecords[0]} onClose={closeRecord} onSaved={reload} />}</main></div>;
}

function MobileApp() {
  const [data, setData] = useState<Dashboard>(); const [auth, setAuth] = useState<'loading' | 'login' | 'ready' | 'error'>('loading'); const [error, setError] = useState('');
  async function load(background = false) { if (!background) { setAuth('loading'); setError(''); } try { const result = await api.bootstrap(); setData(result as Dashboard); setAuth('ready'); } catch (err) { if (err instanceof ApiError && err.status === 401) setAuth('login'); else if (!background) { setError(errorText(err)); setAuth('error'); } else { setError(errorText(err)); throw err; } } }
  useEffect(() => { void load(false); }, []);
  if (auth === 'loading') return <div className="desktop"><main className="phone status"><Mascot /><div className="spinner" />正在读取你的记录…</main></div>;
  if (auth === 'login') return <Login onLogin={() => load(false)} />;
  if (auth === 'error' || !data) return <div className="desktop"><main className="phone status"><Mascot /><p>{error || '暂时无法加载数据'}</p><button className="primary" onClick={() => void load(false)}>重新尝试</button></main></div>;
  return <BrowserRouter><Shell data={data} reload={() => load(true)} onLoggedOut={() => { setData(undefined); setAuth('login'); }} /></BrowserRouter>;
}

export function App() {
  return window.location.pathname.startsWith('/admin') ? <AdminWorkbench /> : <MobileApp />;
}
