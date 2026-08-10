import { useEffect, useState, type FormEvent } from 'react';
import { AlertTriangle, Check, LoaderCircle, Save } from 'lucide-react';

import { admin, type WorkbenchComponent } from '../api';
import { AdminModal } from './AdminModal';

const emptyPrompt = { key: '', displayName: '', description: '', template: '' };
const emptySkill = { key: '', displayName: '', description: '', whenToUse: '', whenNotToUse: '', content: '', requiredToolKeys: [] as string[] };

export function ResourceCreateModal({ type, open, tools, onClose, onCreated }: {
  type: 'PROMPT' | 'SKILL';
  open: boolean;
  tools: WorkbenchComponent[];
  onClose: () => void;
  onCreated: (component: WorkbenchComponent) => void;
}) {
  const [prompt, setPrompt] = useState(emptyPrompt);
  const [skill, setSkill] = useState(emptySkill);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setPrompt(emptyPrompt); setSkill(emptySkill); setError('');
  }, [open, type]);

  async function create(event: FormEvent) {
    event.preventDefault(); setPending(true); setError('');
    try {
      if (type === 'PROMPT') {
        const item = await admin.createPrompt({ promptKey: prompt.key, displayName: prompt.displayName, description: prompt.description, template: prompt.template });
        onCreated({ type, componentKey: item.promptKey, displayName: item.displayName, description: item.description, version: item.revision, status: 'AVAILABLE', tags: [], config: { template: item.template, revision: item.revision } });
      } else {
        const item = await admin.createSkill({ skillKey: skill.key, displayName: skill.displayName, description: skill.description, whenToUse: skill.whenToUse, whenNotToUse: skill.whenNotToUse, content: skill.content, requiredToolKeys: skill.requiredToolKeys });
        onCreated({ type, componentKey: item.skillKey, displayName: item.displayName, description: item.description, version: item.revision, status: item.runtimeReady ? 'AVAILABLE' : 'DISABLED', tags: [], config: { whenToUse: item.whenToUse, whenNotToUse: item.whenNotToUse, content: item.content, requiredTools: item.requiredToolKeys, revision: item.revision } });
      }
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : `新增${type === 'PROMPT' ? '提示词' : '技能'}失败`);
    } finally { setPending(false); }
  }

  const label = type === 'PROMPT' ? '提示词' : '技能';
  return <AdminModal open={open} title={`新增${label}`} description={type === 'PROMPT' ? '提示词可在 Agent 中复用，并通过变量接收运行时上下文。' : '技能描述 Agent 的执行方法，可绑定已经注册的真实工具。'} busy={pending} onClose={onClose} footer={<><button type="button" className="admin-secondary" disabled={pending} onClick={onClose}>取消</button><button type="submit" form="admin-resource-create" className="admin-primary" disabled={pending}>{pending ? <LoaderCircle className="is-spin" /> : <Save />} 保存{label}</button></>}>
    <form id="admin-resource-create" className="admin-modal-form" onSubmit={create}>
      <div className="admin-form-grid">
        <label>{type === 'PROMPT' ? 'Prompt Key' : 'Skill Key'}<input aria-label={type === 'PROMPT' ? 'Prompt Key' : 'Skill Key'} value={type === 'PROMPT' ? prompt.key : skill.key} onChange={(event) => type === 'PROMPT' ? setPrompt({ ...prompt, key: event.target.value }) : setSkill({ ...skill, key: event.target.value })} placeholder={type === 'PROMPT' ? 'fitness-coach' : 'plan-builder'} required /></label>
        <label>{label}名称<input aria-label={`${label}名称`} value={type === 'PROMPT' ? prompt.displayName : skill.displayName} onChange={(event) => type === 'PROMPT' ? setPrompt({ ...prompt, displayName: event.target.value }) : setSkill({ ...skill, displayName: event.target.value })} required /></label>
        <label className="is-wide">说明<textarea aria-label={`${label}说明`} rows={2} value={type === 'PROMPT' ? prompt.description : skill.description} onChange={(event) => type === 'PROMPT' ? setPrompt({ ...prompt, description: event.target.value }) : setSkill({ ...skill, description: event.target.value })} /></label>
        {type === 'PROMPT' ? <label className="is-wide">提示词模板<textarea aria-label="提示词模板" className="admin-code-editor" rows={9} value={prompt.template} onChange={(event) => setPrompt({ ...prompt, template: event.target.value })} placeholder={'你是健身教练，请根据 {{goal}} 提供建议。'} required /></label> : <>
          <label>何时使用<textarea aria-label="新增技能何时使用" rows={3} value={skill.whenToUse} onChange={(event) => setSkill({ ...skill, whenToUse: event.target.value })} required /></label>
          <label>何时不使用<textarea aria-label="新增技能何时不使用" rows={3} value={skill.whenNotToUse} onChange={(event) => setSkill({ ...skill, whenNotToUse: event.target.value })} /></label>
          <label className="is-wide">技能内容<textarea aria-label="新增技能内容" className="admin-code-editor" rows={8} value={skill.content} onChange={(event) => setSkill({ ...skill, content: event.target.value })} placeholder="# 执行步骤" required /></label>
          <fieldset className="admin-resource-tools is-wide"><legend>依赖工具</legend><p>只绑定技能执行确实需要的工具。</p><div className="admin-tool-chips">{tools.map((tool) => <button type="button" key={tool.componentKey} className={skill.requiredToolKeys.includes(tool.componentKey) ? 'is-active' : ''} onClick={() => setSkill({ ...skill, requiredToolKeys: skill.requiredToolKeys.includes(tool.componentKey) ? skill.requiredToolKeys.filter((key) => key !== tool.componentKey) : [...skill.requiredToolKeys, tool.componentKey] })}><Check />{tool.componentKey}</button>)}</div></fieldset>
        </>}
      </div>
      {error && <p className="admin-form-error"><AlertTriangle />{error}</p>}
    </form>
  </AdminModal>;
}
