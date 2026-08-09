import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  AlertTriangle, Bot, CheckCircle2, Cloud, Cpu, FlaskConical, LayoutDashboard,
  LoaderCircle, LogOut, Menu, MessageSquare, Search, ShieldCheck, Sparkles, Webhook, Wrench, X,
  Network,
} from 'lucide-react';

import { admin, ApiError } from './api';
import './admin.css';

type NavItem = {
  path: string;
  label: string;
  icon: typeof LayoutDashboard;
  group?: string;
};

const navigation: NavItem[] = [
  { path: '/admin', label: '总览', icon: LayoutDashboard },
  { path: '/admin/agents', label: 'Agent 配置', icon: Bot },
  { path: '/admin/providers', label: '模型服务', icon: Cloud, group: 'components' },
  { path: '/admin/models', label: '模型', icon: Cpu, group: 'components' },
  { path: '/admin/prompts', label: '提示词', icon: MessageSquare, group: 'components' },
  { path: '/admin/tools', label: '工具', icon: Wrench, group: 'components' },
  { path: '/admin/skills', label: '技能', icon: Sparkles, group: 'components' },
  { path: '/admin/hooks', label: 'Hook', icon: Webhook, group: 'components' },
  { path: '/admin/playground', label: '调试台', icon: FlaskConical },
  { path: '/admin/traces', label: 'Trace', icon: Network },
];

function Loading() {
  return <main className="admin-load"><div><LoaderCircle /><strong>正在连接工作台</strong><small>读取 Agent 配置与组件状态…</small></div></main>;
}

function ErrorScreen({ error, status, onRetry }: { error: string; status: number; onRetry: () => void }) {
  return <main className="admin-load"><div className="admin-load__error"><AlertTriangle /><strong>工作台暂时无法打开</strong><small>{error}</small>{status !== 401 && <button onClick={onRetry}>重新连接</button>}</div></main>;
}

function DeveloperLogin({ onLoggedIn }: { onLoggedIn: (session: { username: string }) => void }) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault(); setPending(true); setError('');
    try { onLoggedIn(await admin.login(username, password)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : '登录失败，请重试。'); }
    finally { setPending(false); }
  }
  return <main className="admin-login"><section>
    <div className="admin-login__brand"><span><Bot /></span><div><strong>Agent Console</strong><small>Developer workspace</small></div></div>
    <div className="admin-login__copy"><p>欢迎回来</p><h1>开发者登录</h1><span>管理 Agent、组件与真实运行链路。</span></div>
    <form onSubmit={submit}><label>用户名<input aria-label="管理员用户名" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required /></label><label>密码<input aria-label="管理员密码" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required /></label>{error && <p role="alert">{error}</p>}<button className="admin-primary" disabled={pending}>{pending ? '正在验证…' : '进入工作台'}</button></form>
    <small className="admin-login__hint">此处使用独立的开发者会话，不关联健身应用账号。</small>
  </section></main>;
}

function Notice({ kind = 'success', children, onClose }: { kind?: 'success' | 'error'; children: ReactNode; onClose: () => void }) {
  return <div className={`admin-toast admin-toast--${kind}`} role="status">{kind === 'success' ? <CheckCircle2 /> : <AlertTriangle />}<span>{children}</span><button aria-label="关闭提示" onClick={onClose}><X /></button></div>;
}

export function AdminWorkbench() {
  const [bootError, setBootError] = useState<string>();
  const [bootStatus, setBootStatus] = useState(0);
  const [ready, setReady] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [notice, setNotice] = useState<string>();
  const [search, setSearch] = useState('');
  const [adminUser, setAdminUser] = useState('');
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => { setBootError(undefined); }, [location.pathname]);

  async function bootstrap() {
    setReady(false); setBootError(undefined); setBootStatus(0);
    try {
      const session = await admin.session();
      setAdminUser(session.username || 'admin');
      await admin.snapshot();
      setReady(true);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) { setAdminUser(''); return; }
      setBootError(caught instanceof Error ? caught.message : '未知错误'); setBootStatus(caught instanceof ApiError ? caught.status : 0);
    }
  }

  useEffect(() => { void bootstrap(); }, []);

  if (!ready && !bootError && !adminUser) return <DeveloperLogin onLoggedIn={(session) => { setAdminUser(session.username); void bootstrap(); }} />;
  if (!ready && !bootError) return <Loading />;
  if (!ready && bootError) return <ErrorScreen error={bootError} status={bootStatus} onRetry={bootstrap} />;

  return <div className="admin-stage">
    <div className="admin-shell">
      <aside className={`admin-sidebar${menuOpen ? ' is-open' : ''}`}>
        <div className="admin-brand"><span><Bot /></span><div><strong>Happy</strong><small>Agent Platform</small></div><button aria-label="关闭菜单" onClick={() => setMenuOpen(false)}><X /></button></div>
        <nav aria-label="管理工作台导航">
          {navigation.map(({ path, label, icon: Icon, group }) => (
            <NavLink
              key={path}
              to={path}
              end={path === '/admin'}
              className={({ isActive }) => isActive ? 'is-active' : ''}
              onClick={() => setMenuOpen(false)}
            >
              <Icon />
              <span>{label}</span>
              {group && <i />}
            </NavLink>
          ))}
        </nav>
        <div className="admin-sidebar__foot"><span><ShieldCheck /></span><div><strong>本机工作台</strong><small>会话保护已开启</small></div></div>
      </aside>

      <section className="admin-workspace">
        <header className="admin-topbar">
          <button className="admin-menu" aria-label="打开菜单" onClick={() => setMenuOpen(true)}><Menu /></button>
          <label>
            <Search />
            <input
              aria-label="全局搜索"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && search.trim()) {
                  navigate('/admin/playground');
                }
              }}
              placeholder="搜索 Agent、组件或运行记录"
            />
          </label>
          <div className="admin-topbar__right">
            <Link to="/admin/playground" aria-label="调试台"><FlaskConical /></Link>
            <button className="admin-user" aria-label="退出开发者工作台" title={`当前管理员：${adminUser}`} onClick={() => { void admin.logout().finally(() => { setReady(false); setAdminUser(''); }); }}><span>{adminUser.slice(0, 1).toUpperCase()}</span><LogOut /></button>
          </div>
        </header>
        <div className="admin-content">
          <Routes>
            <Route path="/admin" element={<OverviewNavigate />} />
            <Route path="/admin/agents" element={<AgentList />} />
            <Route path="/admin/agents/new" element={<AgentCreatePage />} />
            <Route path="/admin/agents/:agentKey" element={<AgentEditor />} />
            <Route path="/admin/providers" element={<Providers />} />
            <Route path="/admin/models" element={<ComponentType type="MODEL" label="模型" />} />
            <Route path="/admin/prompts" element={<ComponentType type="PROMPT" label="提示词" />} />
            <Route path="/admin/tools" element={<ComponentType type="TOOL" label="工具" readOnly />} />
            <Route path="/admin/skills" element={<ComponentType type="SKILL" label="技能" />} />
            <Route path="/admin/hooks" element={<ComponentType type="HOOK" label="Hook" />} />
            <Route path="/admin/runs/:runId" element={<RunTracePage />} />
            <Route path="/admin/traces" element={<ConversationTracePage />} />
            <Route path="/admin/playground" element={<PlaygroundPage />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </div>
      </section>
    </div>
    {notice && <Notice onClose={() => setNotice(undefined)}>{notice}</Notice>}
  </div>;
}

import { OverviewNavigate } from './pages/Overview';
import { AgentList } from './pages/AgentList';
import { AgentCreatePage } from './pages/AgentCreatePage';
import { AgentEditor } from './pages/AgentEditor';
import { ComponentType } from './pages/ComponentType';
import { Providers } from './pages/Providers';
import { RunTracePage } from './pages/RunTracePage';
import { ConversationTracePage } from './pages/ConversationTracePage';
import { PlaygroundPage } from './pages/PlaygroundPage';
import { NotFound } from './pages/NotFound';
