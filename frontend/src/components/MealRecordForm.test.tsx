import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MealRecordForm } from './MealRecordForm';

describe('MealRecordForm', () => {
  beforeEach(() => {
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:meal-preview') });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('uploads a selected image, exposes editable recognition candidates, and saves their edits', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/v1/app/media-upload-tickets') {
        return json({ mediaId: '11111111-1111-1111-1111-111111111111', method: 'PUT', uploadUrl: '/api/v1/app/media-uploads/11111111-1111-1111-1111-111111111111', headers: [], expiresAt: '2026-08-09T01:00:00Z', maxBytes: 10485760 });
      }
      if (path.startsWith('/api/v1/app/media-uploads/')) return new Response(null, { status: 204 });
      if (path === '/api/v1/app/meal-recognition-jobs') {
        return json({ jobId: '22222222-2222-2222-2222-222222222222', status: 'SUCCEEDED', mediaId: '11111111-1111-1111-1111-111111111111', mealType: 'LUNCH', occurredAt: '2026-08-09T00:00:00Z', candidates: [{ name: '番茄牛肉饭', estimatedKcal: 530, confidence: 0.91 }], createdAt: '2026-08-09T00:00:00Z', updatedAt: '2026-08-09T00:00:01Z' });
      }
      if (path === '/api/v1/app/meal-records') return json({ mealRecordId: '33333333-3333-3333-3333-333333333333' }, 201);
      throw new Error(`unexpected ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    const onSaved = vi.fn();
    const user = userEvent.setup();
    render(<MealRecordForm onSaved={onSaved} />);

    const image = new File(['image'], 'lunch.jpg', { type: 'image/jpeg' });
    await user.upload(screen.getByLabelText('拍照识别'), image);

    expect(await screen.findByDisplayValue('番茄牛肉饭')).toBeInTheDocument();
    await user.upload(screen.getByLabelText('拍照识别'), new File(['another-image'], 'second.jpg', { type: 'image/jpeg' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === '/api/v1/app/media-upload-tickets')).toHaveLength(2));
    await user.clear(screen.getByLabelText('食物名称 1'));
    await user.type(screen.getByLabelText('食物名称 1'), '番茄鸡胸饭');
    await user.click(screen.getByRole('button', { name: '保存饮食记录' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    const ticketCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/v1/app/media-upload-tickets',
    );
    const jobCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/v1/app/meal-recognition-jobs',
    );
    const recordCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/v1/app/meal-records',
    );
    const ticketKey = (ticketCall?.[1] as RequestInit).headers as Record<string, string>;
    const jobKey = (jobCall?.[1] as RequestInit).headers as Record<string, string>;
    const recordKey = (recordCall?.[1] as RequestInit).headers as Record<string, string>;
    expect(ticketKey['Idempotency-Key']).toEqual(expect.any(String));
    expect(jobKey['Idempotency-Key']).toEqual(expect.any(String));
    expect(recordKey['Idempotency-Key']).toEqual(expect.any(String));
    expect(jobKey['Idempotency-Key']).not.toBe(ticketKey['Idempotency-Key']);
    const ticketCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/v1/app/media-upload-tickets');
    const firstTicketKey = ((ticketCalls[0][1] as RequestInit).headers as Record<string, string>)['Idempotency-Key'];
    const secondTicketKey = ((ticketCalls[1][1] as RequestInit).headers as Record<string, string>)['Idempotency-Key'];
    expect(secondTicketKey).not.toBe(firstTicketKey);
    expect(recordKey['Idempotency-Key']).not.toBe(jobKey['Idempotency-Key']);
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/app/meal-records', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      body: expect.stringContaining('RECOGNITION_CONFIRMED'),
    }));
  });

  it('retains the preview on recognition failure and lets the user switch to manual entry', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/v1/app/media-upload-tickets') return json({ mediaId: '11111111-1111-1111-1111-111111111111', method: 'PUT', uploadUrl: '/api/v1/app/media-uploads/11111111-1111-1111-1111-111111111111', headers: [], expiresAt: '2026-08-09T01:00:00Z', maxBytes: 10485760 });
      if (path.startsWith('/api/v1/app/media-uploads/')) return new Response(null, { status: 204 });
      if (path === '/api/v1/app/meal-recognition-jobs') return json({ jobId: '22222222-2222-2222-2222-222222222222', status: 'FAILED', mediaId: '11111111-1111-1111-1111-111111111111', mealType: 'LUNCH', occurredAt: '2026-08-09T00:00:00Z', candidates: [], failure: { code: 'DEPENDENCY_NOT_CONFIGURED', message: '视觉模型未配置', retryable: false }, createdAt: '2026-08-09T00:00:00Z', updatedAt: '2026-08-09T00:00:01Z' });
      throw new Error(`unexpected ${path}`);
    }));
    const user = userEvent.setup();
    render(<MealRecordForm onSaved={vi.fn()} />);

    await user.upload(screen.getByLabelText('拍照识别'), new File(['image'], 'lunch.png', { type: 'image/png' }));
    expect(await screen.findByText('视觉模型未配置')).toBeInTheDocument();
    expect(screen.getByAltText('已选择的饮食照片')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '改为手动填写' }));
    expect(screen.getByLabelText('食物名称 1')).toBeEnabled();
  });

  it('retries a terminal failure with fresh upload and job idempotency keys', async () => {
    let ticketAttempt = 0;
    let jobAttempt = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/v1/app/media-upload-tickets') {
        ticketAttempt += 1;
        return json({ mediaId: ticketAttempt === 1 ? '11111111-1111-1111-1111-111111111111' : '33333333-3333-3333-3333-333333333333', method: 'PUT', uploadUrl: `/api/v1/app/media-uploads/${ticketAttempt}`, headers: [], expiresAt: '2026-08-09T01:00:00Z', maxBytes: 10485760 });
      }
      if (path.startsWith('/api/v1/app/media-uploads/')) return new Response(null, { status: 204 });
      if (path === '/api/v1/app/meal-recognition-jobs') {
        jobAttempt += 1;
        return json(jobAttempt === 1
          ? { jobId: '22222222-2222-2222-2222-222222222222', status: 'FAILED', mediaId: '11111111-1111-1111-1111-111111111111', mealType: 'LUNCH', occurredAt: '2026-08-09T00:00:00Z', candidates: [], failure: { code: 'TIMEOUT', message: '视觉模型超时', retryable: true }, createdAt: '2026-08-09T00:00:00Z', updatedAt: '2026-08-09T00:00:01Z' }
          : { jobId: '44444444-4444-4444-4444-444444444444', status: 'SUCCEEDED', mediaId: '33333333-3333-3333-3333-333333333333', mealType: 'LUNCH', occurredAt: '2026-08-09T00:00:00Z', candidates: [{ name: '重试后的食物', estimatedKcal: 410, confidence: 0.9 }], createdAt: '2026-08-09T00:00:00Z', updatedAt: '2026-08-09T00:00:01Z' });
      }
      throw new Error(`unexpected ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<MealRecordForm onSaved={vi.fn()} />);

    await user.upload(screen.getByLabelText('拍照识别'), new File(['image'], 'retry.png', { type: 'image/png' }));
    await user.click(await screen.findByRole('button', { name: '重试' }));
    expect(await screen.findByDisplayValue('重试后的食物')).toBeInTheDocument();

    const ticketCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/v1/app/media-upload-tickets');
    const jobCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/v1/app/meal-recognition-jobs');
    expect(ticketCalls).toHaveLength(2);
    expect(jobCalls).toHaveLength(2);
    const ticketKeys = ticketCalls.map(([, init]) => ((init as RequestInit).headers as Record<string, string>)['Idempotency-Key']);
    const jobKeys = jobCalls.map(([, init]) => ((init as RequestInit).headers as Record<string, string>)['Idempotency-Key']);
    expect(ticketKeys[1]).not.toBe(ticketKeys[0]);
    expect(jobKeys[1]).not.toBe(jobKeys[0]);
    expect(jobKeys[1]).not.toBe(ticketKeys[1]);
    expect((jobCalls[1][1] as RequestInit).body).toContain('33333333-3333-3333-3333-333333333333');
  });

  it('rejects unsupported and oversized files before creating an upload ticket', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<MealRecordForm onSaved={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('拍照识别'), { target: { files: [new File(['text'], 'note.txt', { type: 'text/plain' })] } });
    expect(await screen.findByText('仅支持 JPEG、PNG 或 WebP 图片。')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
