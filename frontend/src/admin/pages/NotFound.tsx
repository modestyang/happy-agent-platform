import { Link } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';

export function NotFound() {
  return <section className="admin-empty admin-empty--large">
    <strong>页面不存在</strong>
    <p>你访问的路径未在管理工作台中注册。</p>
    <Link className="admin-text-button" to="/admin"><ChevronLeft /> 回到总览</Link>
  </section>;
}
