import type { AiRun, CreateMealRecommendationFeedbackRequest, CurrentGoalReport, DailyMealPlan, MealFeedback, WorkoutPlanDetail, WorkoutPlanPage } from './api/generated/public';

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
  register: (username: string, password: string, nickname: string) => request<unknown>('/api/local/register', { method: 'POST', body: JSON.stringify({ username, password, nickname }) }),
  logout: () => request<unknown>('/api/local/logout', { method: 'POST' }),
  firstSetup: (weightJin: number, waistCm: number | undefined, targetWeightJin: number, targetDate: string) => request<unknown>('/api/app/first-setup', { method: 'POST', body: JSON.stringify({ weightJin, waistCm, targetWeightJin, targetDate }) }),
  bodyRecord: (record: { weightJin?: number; waistCm?: number }) => request<unknown>('/api/app/body-records', { method: 'POST', body: JSON.stringify(record) }),
  meal: (mealType: string, items: { name: string; estimatedKcal: number }[]) => request<unknown>('/api/app/meals', { method: 'POST', body: JSON.stringify({ mealType, items }) }),
  createMediaUploadTicket: (contentType: string, contentLength: number, sha256: string, idempotencyKey: string) => request<{ mediaId: string; uploadUrl: string; headers: { name: string; value: string }[] }>('/api/v1/app/media-upload-tickets', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ purpose: 'MEAL_RECOGNITION', contentType, contentLength, sha256 }) }),
  uploadMedia: (uploadUrl: string, file: File, headers: { name: string; value: string }[]) => upload(uploadUrl, file, headers),
  completeMediaUpload: (mediaId: string, idempotencyKey: string) => request<unknown>(`/api/v1/app/media-uploads/${mediaId}/complete`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ confirmation: 'DIRECT_UPLOAD_COMPLETED' }) }),
  createMealRecognitionJob: (mediaId: string, mealType: string, occurredAt: string, idempotencyKey: string) => request<{ jobId: string; status: string; candidates: { name: string; estimatedKcal: number; confidence: number }[]; failure?: { message: string } }>('/api/v1/app/meal-recognition-jobs', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ mediaId, mealType, occurredAt }) }),
  getMealRecognitionJob: (jobId: string) => request<{ jobId: string; status: string; candidates: { name: string; estimatedKcal: number; confidence: number }[]; failure?: { message: string } }>(`/api/v1/app/meal-recognition-jobs/${jobId}`),
  createMealRecord: (body: unknown, idempotencyKey: string) => request<unknown>('/api/v1/app/meal-records', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body) }),
  upsertMealRecommendationFeedback: (recommendationId: string, body: CreateMealRecommendationFeedbackRequest, idempotencyKey: string) => request<MealFeedback>(`/api/v1/app/meal-recommendations/${recommendationId}/feedback`, { method: 'PUT', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body) }),
  generateDailyMealPlan: (date: string, idempotencyKey: string) => request<DailyMealPlan>('/api/v1/app/meal-plans/daily/generate', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ date }) }),
  dailyMealPlan: (date: string) => request<DailyMealPlan>(`/api/v1/app/meal-plans/daily?date=${encodeURIComponent(date)}`),
  currentGoalReport: () => request<CurrentGoalReport>('/api/v1/app/reports/current-goal'),
  refreshCurrentGoalReport: (reason: 'USER_REFRESH' | 'RETRY_FAILED', idempotencyKey: string) => request<CurrentGoalReport>('/api/v1/app/reports/current-goal', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ reason }) }),
  completeWorkout: (id: string, completionRatio: number) => request<unknown>(`/api/app/workouts/${id}/complete`, { method: 'POST', body: JSON.stringify({ completionRatio }) }),
  workoutPlans: (date: string) => request<WorkoutPlanPage>(`/api/v1/app/workout-plans?from=${encodeURIComponent(date)}&to=${encodeURIComponent(date)}`),
  workoutPlan: (id: string) => request<WorkoutPlanDetail>(`/api/v1/app/workout-plans/${encodeURIComponent(id)}`),
  goal: (body: unknown) => request<unknown>('/api/app/goals', { method: 'POST', body: JSON.stringify(body) }),
  aiMessage: (message: string) => request<{ message: string }>('/api/app/ai/messages', { method: 'POST', body: JSON.stringify({ message }) }),
  createAiRun: (text: string, idempotencyKey: string) => request<AiRun>('/api/v1/app/ai/runs', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ text, clientMessageId: crypto.randomUUID() }) }),
  decideAiRunApproval: (runId: string, approvalId: string, decision: 'APPROVE' | 'REJECT', idempotencyKey: string) => request<AiRun>(`/api/v1/app/ai/runs/${runId}/approvals/${approvalId}`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({ decision }) }),
  appAiMessage: (message: string) => request<{ message: string }>('/api/app/ai/messages', { method: 'POST', body: JSON.stringify({ message }) }),
};
