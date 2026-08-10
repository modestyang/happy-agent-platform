// Admin-specific API helpers. Routes here talk to the workbench backend at
// /api/admin/** and return strongly-typed DTOs.

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    });
  } catch {
    throw new ApiError('后端服务连接失败，请确认后端服务已启动并可访问', 0);
  }
  const payload: unknown = await response.json().catch(() => ({}));
  if (!response.ok) {
    const problem = payload as { detail?: string; message?: string; code?: string };
    throw new ApiError(problem.detail ?? problem.message ?? '请求未能完成，请重试。', response.status, problem.code);
  }
  return payload as T;
}

// --- DTOs --------------------------------------------------------------------

export type ComponentStatus = string;

export type WorkbenchOverview = {
  agentCount: number;
  platformStatus: string;
  availableComponents: number;
  configuredProviders: number;
  runCount: number;
};

export type AgentDraft = {
  agentKey: string;
  name: string;
  description: string;
  status: string;
  frameworkKey: string;
  providerKey: string;
  modelKey: string;
  promptKey: string;
  toolKeys: string[];
  skillKeys: string[];
  hookKeys: string[];
  memoryKey: string;
  temperature: number;
  maxToolCalls: number;
  publishedVersion: number;
  revision: number;
  updatedAt: string;
};

export type AgentDraftUpdate = Pick<AgentDraft,
  'name' | 'description' | 'frameworkKey' | 'providerKey' | 'modelKey' | 'promptKey' |
  'toolKeys' | 'skillKeys' | 'hookKeys' | 'memoryKey' | 'temperature' | 'maxToolCalls'>;

export type CreateAgentRequest = Pick<AgentDraft, 'agentKey' | 'name' | 'description'>;

export type WorkbenchComponent = {
  type: string;
  componentKey: string;
  displayName: string;
  description: string;
  version: number;
  status: ComponentStatus;
  tags: string[];
  config: Record<string, unknown>;
};

export type WorkbenchComponentUpdate = {
  displayName: string;
  description: string;
  status: ComponentStatus;
  tags: string[];
  config: Record<string, unknown>;
};

export type Provider = {
  providerKey: string;
  displayName: string;
  endpoint: string;
  protocol: 'OPENAI_COMPATIBLE';
  configured: boolean;
  maskedCredential: string;
  status: 'ACTIVE' | 'DISABLED';
  revision: number;
  updatedAt: string;
};

export type Model = {
  modelKey: string;
  providerKey: string;
  modelId: string;
  displayName: string;
  description: string;
  supportsStreaming: boolean;
  supportsToolCalling: boolean;
  supportsVision: boolean;
  status: 'ACTIVE' | 'DISABLED';
  revision: number;
  updatedAt: string;
};

export type Prompt = { promptKey: string; displayName: string; description: string; template: string; status: string; revision: number; updatedAt: string };
export type Skill = { skillKey: string; displayName: string; description: string; whenToUse: string; whenNotToUse: string; content: string; requiredToolKeys: string[]; runtimeReady: boolean; status: string; revision: number; updatedAt: string };
export type Hook = { hookKey: string; displayName: string; description: string; phase: string; mandatory: boolean; runtimeReady: boolean; status: string; revision: number; updatedAt: string };
export type Framework = { frameworkKey: string; displayName: string; description: string; capabilities: Record<string, unknown>; status: string; revision: number; updatedAt: string };
export type Memory = { memoryKey: string; displayName: string; description: string; retentionHours: number; maxTokens: number; status: string; revision: number; updatedAt: string };
export type Tool = { toolKey: string; contractVersion: number; runtimeName: string; displayName: string; description: string; whenToUse: string; whenNotToUse: string; sideEffect: string; riskLevel: string; requiredScopes: string[]; inputSchema: Record<string, unknown>; outputSchema: Record<string, unknown> };

export type ValidationResult = { valid: boolean; errors: string[]; warnings: string[] };
export type Publication = { agentKey: string; publishedVersion: number; publishedAt: string };

export type RunSummary = {
  runId: string;
  agentKey: string;
  agentVersion: number;
  status: string;
  startedAt: string;
  completedAt?: string;
  durationMs: number;
  toolCalls: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  modelKey: string | null;
  errorCode: string | null;
};

export type RunPage = {
  items: RunSummary[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
};

export type TraceEvent = {
  sequence: number;
  type: string;
  title: string;
  detail: string;
  occurredAt: string;
};

export type RunTrace = {
  runId: string;
  agentKey: string;
  agentVersion: number;
  status: string;
  startedAt: string;
  completedAt?: string;
  durationMs: number;
  toolCalls: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  modelKey: string | null;
  frameworkKey: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  inputSummary: string;
  outputSummary: string;
  events: TraceEvent[];
};

export type ConversationSummary = {
  conversationId: string;
  userId: string;
  agentKey: string;
  title: string;
  status: string;
  startedAt: string;
  lastMessageAt: string;
  messageCount: number;
  runCount: number;
};

export type ConversationMessage = {
  messageId: string;
  conversationId: string;
  runId: string | null;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  createdAt: string;
};

export type ConversationDetail = {
  conversation: ConversationSummary;
  messages: ConversationMessage[];
  runs: RunSummary[];
};

export type WorkbenchSnapshot = {
  overview: WorkbenchOverview;
  agents: AgentDraft[];
  components: WorkbenchComponent[];
  providers: Provider[];
  runs: RunSummary[];
};

export type AdminSession = { username: string };

// --- API ---------------------------------------------------------------------

const admin = {
  session: () => request<AdminSession>('/api/admin/auth/session'),
  login: (username: string, password: string) =>
    request<AdminSession>('/api/admin/auth/login', {
      method: 'POST', body: JSON.stringify({ username, password }),
    }),
  logout: () => request<void>('/api/admin/auth/logout', { method: 'POST' }),
  debugMessage: (agentKey: string, message: string) =>
    request<{ message: string }>('/api/admin/playground/messages', {
      method: 'POST', body: JSON.stringify({ agentKey, message }),
    }),
  createPlaygroundRun: (agentKey: string, input: string, idempotencyKey: string) =>
    request<{ runId: string; sessionId?: string; status: string }>('/api/v1/admin/playground/runs', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ agentKey, target: { kind: 'PUBLISHED_VERSION', revision: 1 }, input, businessUserId: '00000000-0000-0000-0000-000000000000' }),
    }),
  decidePlaygroundRunApproval: (runId: string, approvalId: string, decision: 'APPROVE' | 'REJECT', idempotencyKey: string) =>
    request<{ runId: string; status: string }>(`/api/v1/admin/playground/runs/${runId}/approvals/${approvalId}`, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ decision }),
    }),
  listAgents: () => request<AgentDraft[]>('/api/admin/agents'),
  listProviders: () => request<Provider[]>('/api/admin/providers'),
  createProvider: (payload: Pick<Provider, 'providerKey' | 'displayName' | 'endpoint'>) =>
    request<Provider>('/api/admin/providers', { method: 'POST', body: JSON.stringify(payload) }),
  updateProvider: (providerKey: string, payload: Pick<Provider, 'displayName' | 'endpoint' | 'status'>, revision: number) =>
    request<Provider>(`/api/admin/providers/${encodeURIComponent(providerKey)}`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(payload) }),
  listModels: (providerKey?: string) => request<Model[]>(`/api/admin/models${providerKey ? `?providerKey=${encodeURIComponent(providerKey)}` : ''}`),
  createModel: (payload: Omit<Model, 'status' | 'revision' | 'updatedAt'>) =>
    request<Model>('/api/admin/models', { method: 'POST', body: JSON.stringify(payload) }),
  updateModel: (modelKey: string, payload: Omit<Model, 'modelKey' | 'providerKey' | 'revision' | 'updatedAt'>, revision: number) =>
    request<Model>(`/api/admin/models/${encodeURIComponent(modelKey)}`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(payload) }),
  listPrompts: () => request<Prompt[]>('/api/admin/prompts'),
  createPrompt: (payload: Omit<Prompt, 'status' | 'revision' | 'updatedAt'>) =>
    request<Prompt>('/api/admin/prompts', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(payload),
    }),
  updatePrompt: (key: string, payload: Pick<Prompt, 'displayName' | 'description' | 'template' | 'status'>, revision: number) => request<Prompt>(`/api/admin/prompts/${encodeURIComponent(key)}`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(payload) }),
  listSkills: () => request<Skill[]>('/api/admin/skills'),
  createSkill: (payload: Omit<Skill, 'status' | 'revision' | 'updatedAt' | 'runtimeReady'>) =>
    request<Skill>('/api/admin/skills', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(payload),
    }),
  updateSkill: (key: string, payload: Pick<Skill, 'displayName' | 'description' | 'whenToUse' | 'whenNotToUse' | 'content' | 'requiredToolKeys' | 'status'>, revision: number) => request<Skill>(`/api/admin/skills/${encodeURIComponent(key)}`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(payload) }),
  listHooks: () => request<Hook[]>('/api/admin/hooks'),
  updateHook: (key: string, payload: Pick<Hook, 'displayName' | 'description' | 'phase' | 'mandatory' | 'status'>, revision: number) => request<Hook>(`/api/admin/hooks/${encodeURIComponent(key)}`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(payload) }),
  listFrameworks: () => request<Framework[]>('/api/admin/frameworks'),
  listMemories: () => request<Memory[]>('/api/admin/memories'),
  listTools: () => request<Tool[]>('/api/admin/tools'),
  createAgent: (createRequest: CreateAgentRequest) =>
    request<AgentDraft>('/api/admin/agents', { method: 'POST', body: JSON.stringify(createRequest) }),
  updateDraft: (agentKey: string, update: AgentDraftUpdate, revision: number) =>
    request<AgentDraft>(
      `/api/admin/agents/${encodeURIComponent(agentKey)}/draft`,
      { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(update) },
    ),
  validate: (agentKey: string) =>
    request<ValidationResult>(
      `/api/admin/agents/${encodeURIComponent(agentKey)}/validate`,
      { method: 'POST' },
    ),
  publish: (agentKey: string) =>
    request<Publication>(
      `/api/admin/agents/${encodeURIComponent(agentKey)}/publish`,
      { method: 'POST' },
    ),
  saveProviderCredential: (providerKey: string, apiKey: string) =>
    request<Provider>(
      `/api/admin/providers/${encodeURIComponent(providerKey)}/credential`,
      { method: 'PUT', body: JSON.stringify({ apiKey }) },
    ),
  listRuns: (params: { agent?: string; status?: string; from?: string; to?: string; page?: number; size?: number; sort?: string }) => {
    const search = new URLSearchParams();
    if (params.agent) search.set('agent', params.agent);
    if (params.status) search.set('status', params.status);
    if (params.from) search.set('from', params.from);
    if (params.to) search.set('to', params.to);
    if (params.page !== undefined) search.set('page', String(params.page));
    if (params.size !== undefined) search.set('size', String(params.size));
    if (params.sort) search.set('sort', params.sort);
    return request<RunPage>(`/api/admin/runs?${search.toString()}`);
  },
  runTrace: (runId: string) => request<RunTrace>(`/api/admin/runs/${runId}`),
  listConversations: () => request<ConversationSummary[]>('/api/admin/traces/conversations?page=0&size=30'),
  conversationTrace: (conversationId: string) =>
    request<ConversationDetail>(`/api/admin/traces/conversations/${encodeURIComponent(conversationId)}`),
};

export { admin };
