import { useEffect, useState, type FormEvent } from 'react';
import { BrowserRouter, Link, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { Bot, BookOpen, ChevronRight, Dumbbell, Home, Play, Send, Sparkles, UserRound, X } from 'lucide-react';
import { ApiError, api } from './api';
import './app.css';

type Food = { name: string; estimatedKcal: number };
type Exercise = { id: string; name: string; targetArea: string; sets: number; seconds: number; steps: string[]; errors: string[]; illustrationMode?: string; imageUrls?: string[] };
type Dashboard = {
  user: { id: string; nickname: string };
  goal?: { id: string; name: string; startWeightJin: number; currentWeightJin: number; targetWeightJin: number; status: string; progressPercent: number };
  bodyRecords: { id: string; recordedAt: string; weightJin?: number; waistCm?: number }[];
  meals: { id: string; occurredAt: string; mealType: string; items: Food[] }[];
  plan?: { id: string; title: string; estimatedMinutes: number; status: string; exercises: Exercise[] };
  exercises: Exercise[];
  report?: { status: string; score: number; conclusion: string; metrics: { label: string; value: string; comparison?: string }[]; actions: string[] };
  ai: { configured: boolean; reason?: string };
};

function errorText(error: unknown) { return error instanceof Error ? error.message : '网络似乎出了点问题，请重试。'; }

function Header({ nickname, title = '今天，慢慢变好' }: { nickname: string; title?: string }) {
  return <div className="topline"><div className="avatar">{nickname.slice(0, 1)}</div><div><p className="eyebrow">PRIVATE FITNESS LOG</p><h1>{title}</h1></div><div className="icon"><Sparkles size={18} /></div></div>;
}

function Navigation() {
  const { pathname } = useLocation();
  const items = [{ to: '/', label: '首页', icon: Home }, { to: '/plan', label: '计划', icon: Dumbbell }, { to: '/ai', label: 'AI花爷', icon: Bot }, { to: '/library', label: '动作库', icon: BookOpen }, { to: '/me', label: '我的', icon: UserRound }];
  return <nav className="bottom-nav">{items.map(({ to, label, icon: Icon }) => <Link key={to} className={`nav-link ${pathname === to ? 'active' : ''}`} to={to}><Icon />{label}</Link>)}</nav>;
}

function Empty({ text }: { text: string }) { return <div className="slim-card"><p className="subtle">{text}</p></div>; }

function HomePage({ data, reload }: { data: Dashboard; reload: () => Promise<void> }) {
  const [drawer, setDrawer] = useState(false); const navigate = useNavigate(); const goal = data.goal;
  const totalKcal = data.meals.flatMap((meal) => meal.items).reduce((sum, item) => sum + item.estimatedKcal, 0);
  return <section className="page"><Header nickname={data.user.nickname} />
    {goal ? <section className="goal-card"><div className="row"><span>{goal.name}</span><span className="meta">目标 {goal.targetWeightJin} 斤</span></div><div className="weight">{goal.currentWeightJin}<small> 斤</small></div><div className="progress"><i style={{ width: `${goal.progressPercent}%` }} /></div><div className="row"><span className="meta">已完成 {goal.progressPercent}%</span><button className="detail-link" onClick={() => navigate('/me#report')}>查看进度详情 <ChevronRight size={15} /></button></div></section> : <Empty text="还没有进行中的目标" />}
    <div className="quick-grid"><button className="quick orange" onClick={() => navigate('/plan')}>今日训练<small>{data.plan ? `${data.plan.estimatedMinutes} 分钟 · ${data.plan.exercises.length} 个动作` : '还未安排'}</small></button><button className="quick cream" onClick={() => setDrawer(true)}>今日饮食<small>{totalKcal} kcal 已记录</small></button><button className="quick mint" onClick={() => setDrawer(true)}>我要记录<small>身材 / 饮食</small></button><button className="quick blue" onClick={() => navigate('/me#report')}>我的报告<small>看见长期变化</small></button></div>
    <h2 className="section-title">今天的节奏</h2>{data.plan ? <section className="slim-card"><p className="eyebrow">TODAY'S SESSION · {data.plan.status}</p><h3 className="plan-name">{data.plan.title}</h3><p className="meta">{data.plan.exercises.length} 个动作 · {data.plan.estimatedMinutes} 分钟</p></section> : <Empty text="今天暂时没有训练计划" />}
    {drawer && <RecordDrawer initialRecord={data.bodyRecords[0]} onClose={() => setDrawer(false)} onSaved={reload} />}
  </section>;
}

function RecordDrawer({ initialRecord, onClose, onSaved }: { initialRecord?: Dashboard['bodyRecords'][number]; onClose: () => void; onSaved: () => Promise<void> }) {
  const [tab, setTab] = useState<'body' | 'meal'>('body'); const [weightJin, setWeightJin] = useState(initialRecord?.weightJin?.toString() ?? ''); const [waistCm, setWaistCm] = useState(initialRecord?.waistCm?.toString() ?? ''); const [mealType, setMealType] = useState('BREAKFAST'); const [items, setItems] = useState<Food[]>([{ name: '', estimatedKcal: 0 }]); const [saving, setSaving] = useState(false); const [error, setError] = useState('');
  const updateItem = (index: number, key: keyof Food, value: string) => setItems((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: key === 'estimatedKcal' ? Number(value) : value } : item));
  async function save(event: FormEvent) { event.preventDefault(); setError(''); if (tab === 'body' && !weightJin && !waistCm) { setError('请至少填写体重或腰围。'); return; } if (tab === 'meal' && items.some((item) => !item.name.trim())) { setError('请填写至少一项食物名称。'); return; } setSaving(true); try { if (tab === 'body') await api.bodyRecord({ ...(weightJin ? { weightJin: Number(weightJin) } : {}), ...(waistCm ? { waistCm: Number(waistCm) } : {}) }); else await api.meal(mealType, items); await onSaved(); onClose(); } catch (err) { setError(errorText(err)); } finally { setSaving(false); } }
  return <div className="drawer-backdrop" role="dialog" aria-label="记录抽屉"><form className="drawer" onSubmit={save}><button type="button" className="quiet" aria-label="关闭记录" onClick={onClose}><X size={18} /></button><div className="handle" /><h2>记录这一刻</h2><div className="tabs"><button type="button" className={tab === 'body' ? 'active' : ''} onClick={() => setTab('body')}>身材记录</button><button type="button" className={tab === 'meal' ? 'active' : ''} onClick={() => setTab('meal')}>饮食记录</button></div>{tab === 'body' ? <><label>体重 (斤)<input type="number" step="0.1" value={weightJin} onChange={(event) => setWeightJin(event.target.value)} /></label><label>腰围 (cm)<input type="number" step="0.1" value={waistCm} onChange={(event) => setWaistCm(event.target.value)} /></label></> : <><label>餐次<select aria-label="餐次" value={mealType} onChange={(event) => setMealType(event.target.value)}><option value="BREAKFAST">早餐</option><option value="LUNCH">午餐</option><option value="DINNER">晚餐</option><option value="SNACK">加餐</option></select></label>{items.map((item, index) => <div className="food-row" key={index}><label>吃了什么<input value={item.name} onChange={(event) => updateItem(index, 'name', event.target.value)} required /></label><label>热量 (kcal)<input type="number" min="0" value={item.estimatedKcal || ''} onChange={(event) => updateItem(index, 'estimatedKcal', event.target.value)} required /></label></div>)}<button type="button" className="chip" onClick={() => setItems((current) => [...current, { name: '', estimatedKcal: 0 }])}>+ 新增食物</button></>}{error && <p className="error">{error}</p>}<button className="primary" disabled={saving}>{saving ? '正在保存…' : tab === 'body' ? '保存身材记录' : '保存饮食记录'}</button></form></div>;
}

function PlanPage({ data }: { data: Dashboard }) {
  const [done, setDone] = useState(false); const [voiceText, setVoiceText] = useState(''); const [error, setError] = useState(''); const plan = data.plan;
  async function complete() { if (!plan) return; setError(''); try { await api.completeWorkout(plan.id, 1); setDone(true); navigator.vibrate?.(100); } catch (err) { setError(errorText(err)); } }
  function start() { const text = '开始跟练。保持稳定呼吸，按每个动作的步骤完成。'; if ('speechSynthesis' in window) window.speechSynthesis.speak(new SpeechSynthesisUtterance(text)); else setVoiceText(text); navigator.vibrate?.([50, 40, 50]); }
  if (!plan) return <section className="page"><Header nickname={data.user.nickname} title="训练计划" /><Empty text="今天还没有可跟练的计划" /></section>;
  return <section className="page"><Header nickname={data.user.nickname} title="训练计划" /><section className="card orange"><p className="eyebrow">TODAY · {plan.status}</p><h2>{plan.title}</h2><p className="meta">{plan.estimatedMinutes} 分钟 · {plan.exercises.length} 个动作</p></section>{plan.exercises.map((exercise, index) => <section className="card" key={exercise.id}><p className="eyebrow">{String(index + 1).padStart(2, '0')} / {plan.exercises.length} · {exercise.targetArea}</p><h2>{exercise.name}</h2><p className="meta">{exercise.sets} 组 · 每组 {exercise.seconds} 秒</p><h3>动作步骤</h3>{exercise.steps.map((step) => <p className="bullet" key={step}>{step}</p>)}<h3>容易出错</h3>{exercise.errors.map((item) => <p className="bullet" key={item}>{item}</p>)}</section>)}{voiceText && <p className="notice">{voiceText}</p>}{error && <p className="error">{error}</p>}{done ? <p className="notice">已完成，给今天的自己一个赞。</p> : <><button className="primary" onClick={start}><Play size={16} /> 开始跟练</button><button className="quiet" onClick={complete}>完成本次训练</button></>}</section>;
}

function AiPage({ data }: { data: Dashboard }) {
  const [messages, setMessages] = useState<{ role: 'user'; content: string }[]>([]); const [value, setValue] = useState(''); const [error, setError] = useState(''); const [sending, setSending] = useState(false); const starters = ['今天怎么练', '今晚吃什么', '帮我记一餐', '看看近期状态'];
  async function submit(message: string) { if (!message.trim()) return; setMessages((list) => [...list, { role: 'user', content: message }]); setValue(''); setSending(true); setError(''); try { await api.aiMessage(message); } catch (err) { setError(err instanceof ApiError && err.status === 503 ? `AI 服务尚未配置${data.ai.reason ? `：${data.ai.reason}` : ''}。你仍可以浏览训练、饮食和报告数据。` : errorText(err)); } finally { setSending(false); } }
  return <section className="page"><Header nickname={data.user.nickname} title="AI花爷" /><section className="card ai-card"><p className="eyebrow">YOUR STEADY COACH</p><h2>少一点内耗，多一点可执行。</h2><p className="subtle">花爷只给基于你已记录数据的建议。</p></section>{!data.ai.configured && <p className="notice">AI 当前未配置，浏览和记录功能不受影响。</p>}{messages.length === 0 && <div className="quick-grid">{starters.map((starter, index) => <button key={starter} className={`quick ${['orange', 'cream', 'mint', 'blue'][index]}`} onClick={() => void submit(starter)}>{starter}<small>从这里开始</small></button>)}</div>}{messages.map((message, index) => <section className="card" key={`${message.content}-${index}`}><p className="eyebrow">YOU</p><p>{message.content}</p></section>)}{error && <p className="error">{error}</p>}<div className="fixed-composer"><div className="chips">{['训练安排', '饮食建议', '身体状态'].map((chip) => <button className="chip" key={chip} onClick={() => setValue(chip)}>{chip}</button>)}</div><form className="composer" onSubmit={(event) => { event.preventDefault(); void submit(value); }}><input aria-label="问花爷" value={value} onChange={(event) => setValue(event.target.value)} placeholder="说说你现在的状态…" /><button className="send" aria-label="发送" disabled={sending}><Send size={17} /></button></form></div></section>;
}

function LibraryPage({ data }: { data: Dashboard }) {
  const [picked, setPicked] = useState<Exercise>();
  return <section className="page"><Header nickname={data.user.nickname} title="动作库" />{picked ? <><button className="quiet" onClick={() => setPicked(undefined)}>← 返回动作库</button><h2>{picked.name}</h2><p className="subtle">{picked.targetArea} · {picked.sets} 组 · {picked.seconds} 秒</p><div className="steps">{[1, 2, 3, 4].map((step) => <div className="step-art" key={step}>动作步骤 {step}<br /><span className="meta">{picked.steps[step - 1] ?? '稳定控制身体'}</span></div>)}</div></> : data.exercises.length ? <><div className="chips">{Array.from(new Set(data.exercises.map((item) => item.targetArea))).map((area) => <span className="chip" key={area}>{area}</span>)}</div><div className="quick-grid">{data.exercises.map((exercise) => <button className="exercise" key={exercise.id} onClick={() => setPicked(exercise)}>{exercise.name}<small>{exercise.targetArea}</small></button>)}</div></> : <Empty text="动作库正在整理中" />}</section>;
}

function MePage({ data }: { data: Dashboard }) {
  const [logoutError, setLogoutError] = useState(''); const report = data.report;
  async function logout() { try { await api.logout(); window.location.assign('/'); } catch (err) { setLogoutError(errorText(err)); } }
  return <section className="page"><Header nickname={data.user.nickname} title="我的" /><section className="card mint"><p className="eyebrow">{data.user.nickname}</p><h2>我的记录</h2><p className="subtle">身体 {data.bodyRecords.length} 条 · 饮食 {data.meals.length} 条</p></section><section id="report" className="card report-card">{report ? <><p className="eyebrow">当前目标累计报告 · {report.status}</p><h2>{report.conclusion}</h2><p className="meta">评分 {report.score}</p><h3>证据</h3>{report.metrics.map((metric) => <p className="subtle" key={metric.label}>{metric.label}：{metric.value}{metric.comparison ? ` · ${metric.comparison}` : ''}</p>)}<h3>下一步动作</h3>{report.actions.map((action) => <p className="bullet" key={action}>{action}</p>)}</> : <Empty text="当前目标还没有足够记录生成报告" />}</section>{logoutError && <p className="error">{logoutError}</p>}<button className="quiet" onClick={() => void logout()}>退出登录</button></section>;
}

function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const [username, setUsername] = useState(''); const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [pending, setPending] = useState(false);
  async function submit(event: FormEvent) { event.preventDefault(); setPending(true); setError(''); try { await api.login(username, password); await onLogin(); } catch (err) { setError(errorText(err)); } finally { setPending(false); } }
  return <div className="desktop"><main className="phone login"><div className="login-mark"><Dumbbell /></div><p className="eyebrow">HAPPY BODY / DAILY LOG</p><h1>把训练和吃饭，<br />做成自己的节奏。</h1><p className="subtle">登录后开始记录今天。</p><form onSubmit={submit}><label>用户名<input value={username} onChange={(event) => setUsername(event.target.value)} required /></label><label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{error && <p className="error">{error}</p>}<button className="primary" disabled={pending}>{pending ? '正在登录…' : '登录'}</button></form></main></div>;
}

function Shell({ data, reload }: { data: Dashboard; reload: () => Promise<void> }) { return <div className="desktop"><main className="phone" aria-label="Happy Agent Platform"><Routes><Route path="/" element={<HomePage data={data} reload={reload} />} /><Route path="/plan" element={<PlanPage data={data} />} /><Route path="/ai" element={<AiPage data={data} />} /><Route path="/library" element={<LibraryPage data={data} />} /><Route path="/me" element={<MePage data={data} />} /><Route path="*" element={<HomePage data={data} reload={reload} />} /></Routes><Navigation /></main></div>; }

export function App() {
  const [data, setData] = useState<Dashboard>(); const [auth, setAuth] = useState<'loading' | 'login' | 'ready' | 'error'>('loading'); const [error, setError] = useState('');
  async function load() { setAuth('loading'); setError(''); try { const result = await api.bootstrap(); setData(result as Dashboard); setAuth('ready'); } catch (err) { if (err instanceof ApiError && err.status === 401) setAuth('login'); else { setError(errorText(err)); setAuth('error'); } } }
  useEffect(() => { void load(); }, []);
  if (auth === 'loading') return <div className="desktop"><main className="phone status"><div className="spinner" />正在读取你的记录…</main></div>;
  if (auth === 'login') return <Login onLogin={load} />;
  if (auth === 'error' || !data) return <div className="desktop"><main className="phone status"><p>{error || '暂时无法加载数据'}</p><button className="primary" onClick={() => void load()}>重新尝试</button></main></div>;
  return <BrowserRouter><Shell data={data} reload={load} /></BrowserRouter>;
}
