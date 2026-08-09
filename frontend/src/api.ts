import type { AgentDraft, AgentDraftUpdate, AgentRun, Provider, Publication, ValidationResult, WorkbenchComponent, WorkbenchComponentUpdate, WorkbenchSnapshot } from './admin/types';
import type { CreateMealRecommendationFeedbackRequest, MealFeedback } from './api/generated/public';

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

async function upload(path: string, file: File, headers: { name: string; value: string }[]) {
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'PUT',
      credentials: 'include',
      headers: Object.fromEntries(headers.map(({ name, value }) => [name, value])),
      body: file,
    });
  } catch {
    throw new ApiError('图片上传失败，请检查网络后重试。', 0);
  }
  if (!response.ok) throw new ApiError('图片上传失败，请重试。', response.status);
}

export const api = {
  bootstrap: () => request<unknown>('/api/app/bootstrap'),
  login: (username: string, password: string) => request<unknown>('/api/local/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request<unknown>('/api/local/logout', { method: 'POST' }),
  bodyRecord: (record: { weightJin?: number; waistCm?: number }) => request<unknown>('/api/app/body-records', { method: 'POST', body: JSON.stringify(record) }),
  meal: (mealType: string, items: { name: string; estimatedKcal: number }[]) => request<unknown>('/api/app/meals', { method: 'POST', body: JSON.stringify({ mealType, items }) }),
  createMediaUploadTicket: (contentType: string, contentLength: number, sha256: string, idempotencyKey: string) => request<{ mediaId: string; uploadUrl: string; headers: { name: string; value: string }[] }>('/api/v1/app/media-upload-tickets', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ purpose: 'MEAL_RECOGNITION', contentType, contentLength, sha256 }) }),
  uploadMedia: (uploadUrl: string, file: File, headers: { name: string; value: string }[]) => upload(uploadUrl, file, headers),
  completeMediaUpload: (mediaId: string, idempotencyKey: string) => request<unknown>(`/api/v1/app/media-uploads/${mediaId}/complete`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ confirmation: 'DIRECT_UPLOAD_COMPLETED' }) }),
  createMealRecognitionJob: (mediaId: string, mealType: string, occurredAt: string, idempotencyKey: string) => request<{ jobId: string; status: string; candidates: { name: string; estimatedKcal: number; confidence: number }[]; failure?: { message: string } }>('/api/v1/app/meal-recognition-jobs', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ mediaId, mealType, occurredAt }) }),
  getMealRecognitionJob: (jobId: string) => request<{ jobId: string; status: string; candidates: { name: string; estimatedKcal: number; confidence: number }[]; failure?: { message: string } }>(`/api/v1/app/meal-recognition-jobs/${jobId}`),
  createMealRecord: (body: unknown, idempotencyKey: string) => request<unknown>('/api/v1/app/meal-records', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body) }),
  upsertMealRecommendationFeedback: (recommendationId: string, body: CreateMealRecommendationFeedbackRequest, idempotencyKey: string) => request<MealFeedback>(`/api/v1/app/meal-recommendations/${recommendationId}/feedback`, { method: 'PUT', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body) }),
  completeWorkout: (id: string, completionRatio: number) => request<unknown>(`/api/app/workouts/${id}/complete`, { method: 'POST', body: JSON.stringify({ completionRatio }) }),
  goal: (body: unknown) => request<unknown>('/api/app/goals', { method: 'POST', body: JSON.stringify(body) }),
  aiMessage: (message: string) => request<{ message: string }>('/api/app/ai/messages', { method: 'POST', body: JSON.stringify({ message }) }),
  admin: {
    snapshot: () => request<WorkbenchSnapshot>('/api/admin/workbench'),
    updateDraft: (agentKey: string, update: AgentDraftUpdate, revision: number) => request<AgentDraft>(`/api/admin/agents/${agentKey}/draft`, { method: 'PATCH', headers: { 'If-Match': String(revision) }, body: JSON.stringify(update) }),
    validate: (agentKey: string) => request<ValidationResult>(`/api/admin/agents/${agentKey}/validate`, { method: 'POST' }),
    publish: (agentKey: string) => request<Publication>(`/api/admin/agents/${agentKey}/publish`, { method: 'POST' }),
    saveProviderCredential: (providerKey: string, apiKey: string) => request<Provider>(`/api/admin/providers/${providerKey}/credential`, { method: 'PUT', body: JSON.stringify({ apiKey }) }),
    run: (runId: string) => request<AgentRun>(`/api/admin/runs/${runId}`),
    saveComponent: (type: string, key: string, update: WorkbenchComponentUpdate) => request<WorkbenchComponent>(`/api/admin/components/${type}/${key}`, { method: 'PATCH', body: JSON.stringify(update) }),
  },
  appAiMessage: (message: string) => request<{ message: string }>('/api/app/ai/messages', { method: 'POST', body: JSON.stringify({ message }) }),
};
