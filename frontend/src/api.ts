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
  const response = await fetch(path, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...init?.headers },
    ...init,
  });
  const payload: unknown = await response.json().catch(() => ({}));
  if (!response.ok) {
    const problem = payload as { detail?: string; message?: string; code?: string };
    throw new ApiError(problem.detail ?? problem.message ?? '请求未能完成，请重试。', response.status, problem.code);
  }
  return payload as T;
}

export const api = {
  bootstrap: () => request<unknown>('/api/app/bootstrap'),
  login: (username: string, password: string) => request<unknown>('/api/local/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request<unknown>('/api/local/logout', { method: 'POST' }),
  bodyRecord: (record: { weightJin?: number; waistCm?: number }) => request<unknown>('/api/app/body-records', { method: 'POST', body: JSON.stringify(record) }),
  meal: (mealType: string, items: { name: string; estimatedKcal: number }[]) => request<unknown>('/api/app/meals', { method: 'POST', body: JSON.stringify({ mealType, items }) }),
  completeWorkout: (id: string, completionRatio: number) => request<unknown>(`/api/app/workouts/${id}/complete`, { method: 'POST', body: JSON.stringify({ completionRatio }) }),
  goal: (body: unknown) => request<unknown>('/api/app/goals', { method: 'POST', body: JSON.stringify(body) }),
  aiMessage: (message: string) => request<unknown>('/api/app/ai/messages', { method: 'POST', body: JSON.stringify({ message }) }),
};
