import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  AlertTriangle, Bot, CheckCircle2, Cloud, Cpu, FlaskConical, LayoutDashboard,
  LoaderCircle, Menu, MessageSquare, Search, ShieldCheck, Sparkles, Webhook, Wrench, X,
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
];

function Loading() {
  return <main className="admin-load"><div><LoaderCircle /><strong>正在连接工作台</strong><small>读取 Agent 配置与组件状态…</small></div></main>;
}

function ErrorScreen({ error, status, onRetry }: { error: string; status: number; onRetry: () => void }) {
  return <main className="admin-load"><div className="admin-load__error"><AlertTriangle /><strong>工作台暂时无法打开</strong><small>{error}</small>{status === 401 ? <a href="/">前往移动端登录</a> : <button onClick={onRetry}>重新连接</button>}</div></main>;
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
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => { setBootError(undefined); }, [location.pathname]);

  async function bootstrap() {
    setReady(false); setBootError(undefined); setBootStatus(0);
    try {
      await admin.snapshot();
      setReady(true);
    } catch (caught) {
      setBootError(caught instanceof Error ? caught.message : '未知错误');
      setBootStatus(caught instanceof ApiError ? caught.status : 0);
    }
  }

  useEffect(() => { void bootstrap(); }, []);

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
            <span className="admin-user">JY</span>
          </div>
        </header>
        <div className="admin-content">
          <Routes>
            <Route path="/admin" element={<OverviewNavigate />} />
            <Route path="/admin/agents" element={<AgentList />} />
            <Route path="/admin/agents/:agentKey" element={<AgentEditor />} />
            <Route path="/admin/providers" element={<Providers />} />
            <Route path="/admin/models" element={<ComponentType type="MODEL" label="模型" />} />
            <Route path="/admin/prompts" element={<ComponentType type="PROMPT" label="提示词" />} />
            <Route path="/admin/tools" element={<ComponentType type="TOOL" label="工具" readOnly />} />
            <Route path="/admin/skills" element={<ComponentType type="SKILL" label="技能" />} />
            <Route path="/admin/hooks" element={<ComponentType type="HOOK" label="Hook" />} />
            <Route path="/admin/runs/:runId" element={<RunTracePage />} />
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
import { AgentEditor } from './pages/AgentEditor';
import { ComponentType } from './pages/ComponentType';
import { Providers } from './pages/Providers';
import { RunTracePage } from './pages/RunTracePage';
import { PlaygroundPage } from './pages/PlaygroundPage';
import { NotFound } from './pages/NotFound';
