export type ComponentStatus = 'AVAILABLE' | 'DRAFT' | 'UNAVAILABLE' | string;

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

export type RunEvent = { sequence: number; type: string; title: string; detail: string; occurredAt: string };
export type AgentRun = {
  runId: string;
  agentKey: string;
  agentVersion: number;
  status: string;
  startedAt: string;
  completedAt?: string;
  durationMs: number;
  toolCalls: number;
  events: RunEvent[];
};

export type WorkbenchSnapshot = {
  overview: WorkbenchOverview;
  agents: AgentDraft[];
  components: WorkbenchComponent[];
  providers: Provider[];
  runs: AgentRun[];
};

export type ValidationResult = { valid: boolean; errors: string[]; warnings: string[] };
export type Publication = { agentKey: string; publishedVersion: number; publishedAt: string };
