import { useEffect, useRef, useState } from 'react';
import { Camera, Plus, RotateCcw, Trash2 } from 'lucide-react';

import { api } from '../api';

export type Food = { name: string; estimatedKcal: number };
export type MealRecognitionState =
  | { status: 'IDLE' }
  | { status: 'UPLOADING'; previewUrl: string }
  | { status: 'RECOGNIZING'; jobId: string; previewUrl: string }
  | { status: 'READY'; jobId: string; previewUrl: string; items: Food[] }
  | { status: 'FAILED'; message: string; previewUrl?: string };

const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const MAX_BYTES = 10_485_760;

export function MealRecordForm({ onSaved }: { onSaved: () => Promise<void> | void }) {
  const [mealType, setMealType] = useState('BREAKFAST');
  const [items, setItems] = useState<Food[]>([{ name: '', estimatedKcal: 0 }]);
  const [state, setState] = useState<MealRecognitionState>({ status: 'IDLE' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const lastFile = useRef<File | undefined>(undefined);
  const previewUrl = 'previewUrl' in state ? state.previewUrl : undefined;
  const locked = state.status === 'UPLOADING' || state.status === 'RECOGNIZING';

  useEffect(() => () => { if (previewUrl) URL.revokeObjectURL(previewUrl); }, [previewUrl]);

  const updateItem = (index: number, key: keyof Food, value: string) => {
    setItems((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: key === 'estimatedKcal' ? Number(value) : value } : item));
  };

  async function startRecognition(file: File) {
    setError('');
    if (!ALLOWED_TYPES.has(file.type)) { setError('仅支持 JPEG、PNG 或 WebP 图片。'); return; }
    if (file.size > MAX_BYTES) { setError('图片不能超过 10 MB。'); return; }
    lastFile.current = file;
    const preview = URL.createObjectURL(file);
    setState({ status: 'UPLOADING', previewUrl: preview });
    try {
      const sha256 = await digest(file);
      const ticket = await api.createMediaUploadTicket(file.type, file.size, sha256);
      await api.uploadMedia(ticket.uploadUrl, file, ticket.headers);
      setState({ status: 'RECOGNIZING', jobId: ticket.mediaId, previewUrl: preview });
      const job = await api.createMealRecognitionJob(ticket.mediaId, mealType, new Date().toISOString());
      if (job.status === 'SUCCEEDED' && job.candidates.length > 0) {
        const recognized = job.candidates.map(({ name, estimatedKcal }) => ({ name, estimatedKcal }));
        setItems(recognized);
        setState({ status: 'READY', jobId: job.jobId, previewUrl: preview, items: recognized });
      } else {
        setState({ status: 'FAILED', message: job.failure?.message ?? '识别未返回可编辑食物，请重试或改为手动填写。', previewUrl: preview });
      }
    } catch (cause) {
      setState({ status: 'FAILED', message: cause instanceof Error ? cause.message : '识别失败，请重试。', previewUrl: preview });
    }
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    if (items.some((item) => !item.name.trim() || item.estimatedKcal < 0)) { setError('请填写至少一项食物名称和非负热量。'); return; }
    setSaving(true);
    try {
      const occurredAt = new Date().toISOString();
      const body = {
        mealType,
        occurredAt,
        source: state.status === 'READY' ? 'RECOGNITION_CONFIRMED' : 'MANUAL',
        ...(state.status === 'READY' ? { recognitionJobId: state.jobId } : {}),
        items: items.map((item) => ({ name: item.name.trim(), estimatedKcal: item.estimatedKcal })),
      };
      await api.createMealRecord(body);
      await onSaved();
    } catch (cause) { setError(cause instanceof Error ? cause.message : '保存失败，请重试。'); } finally { setSaving(false); }
  }

  return <form className="meal-record-form" onSubmit={save} aria-label="饮食记录表单">
    <label>餐次<select aria-label="餐次" value={mealType} onChange={(event) => setMealType(event.target.value)} disabled={locked}><option value="BREAKFAST">早餐</option><option value="LUNCH">午餐</option><option value="DINNER">晚餐</option><option value="SNACK">加餐</option></select></label>
    <label className="meal-photo-picker">拍照识别<input aria-label="拍照识别" type="file" accept="image/jpeg,image/png,image/webp" disabled={locked} onChange={(event) => { const file = event.target.files?.[0]; if (file) void startRecognition(file); }} /><span><Camera /> 选择饮食照片</span></label>
    {previewUrl && <img className="meal-photo-preview" src={previewUrl} alt="已选择的饮食照片" />}
    {state.status === 'UPLOADING' && <p className="notice">正在上传照片，暂时锁定编辑区…</p>}
    {state.status === 'RECOGNIZING' && <p className="notice">正在识别食物，暂时锁定编辑区…</p>}
    {state.status === 'FAILED' && <div className="error"><p>{state.message}</p><button type="button" className="soft-button" onClick={() => lastFile.current && void startRecognition(lastFile.current)}><RotateCcw /> 重试</button><button type="button" className="soft-button" onClick={() => setState({ status: 'IDLE' })}>改为手动填写</button></div>}
    <div aria-disabled={locked}>{items.map((item, index) => <div className="food-row" key={index}><label>食物名称 {index + 1}<input aria-label={`食物名称 ${index + 1}`} value={item.name} disabled={locked} onChange={(event) => updateItem(index, 'name', event.target.value)} required /></label><label>热量 (kcal)<input aria-label={`热量 ${index + 1}`} type="number" min="0" value={item.estimatedKcal || ''} disabled={locked} onChange={(event) => updateItem(index, 'estimatedKcal', event.target.value)} required /></label>{items.length > 1 && <button aria-label={`删除食物 ${index + 1}`} type="button" disabled={locked} onClick={() => setItems((current) => current.filter((_, itemIndex) => itemIndex !== index))}><Trash2 /></button>}</div>)}</div>
    <button type="button" className="soft-button" disabled={locked} onClick={() => setItems((current) => [...current, { name: '', estimatedKcal: 0 }])}><Plus /> 新增食物</button>
    {error && <p className="error">{error}</p>}<button className="primary" disabled={locked || saving}>{saving ? '正在保存…' : '保存饮食记录'}</button>
  </form>;
}

async function digest(file: File) {
  if (!globalThis.crypto?.subtle) throw new Error('当前浏览器不支持文件校验，请更新浏览器后重试。');
  const bytes = await file.arrayBuffer();
  const hash = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('');
}
