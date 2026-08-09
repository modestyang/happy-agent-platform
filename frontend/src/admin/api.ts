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
  configured: boolean;
  maskedCredential: string;
  status: string;
};

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
  snapshot: () => request<WorkbenchSnapshot>('/api/admin/workbench'),
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
  saveComponent: (type: string, key: string, update: WorkbenchComponentUpdate) =>
    request<WorkbenchComponent>(
      `/api/admin/components/${encodeURIComponent(type)}/${encodeURIComponent(key)}`,
      { method: 'PATCH', body: JSON.stringify(update) },
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
  listConversations: (userId: string) =>
    request<ConversationSummary[]>(`/api/admin/traces/conversations?userId=${encodeURIComponent(userId)}`),
  conversationTrace: (conversationId: string) =>
    request<ConversationDetail>(`/api/admin/traces/conversations/${encodeURIComponent(conversationId)}`),
};

export { admin };
