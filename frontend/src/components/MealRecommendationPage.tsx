import { useEffect, useState } from 'react';
import { ArrowLeft, ChefHat, Flame, Sparkles, ThumbsDown, ThumbsUp, Utensils } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { api } from '../api';

export type MealRecommendation = {
  id: string;
  recommendationDate: string;
  mealType: 'BREAKFAST' | 'LUNCH' | 'DINNER';
  items: { name: string; estimatedKcal: number }[];
  reason: string;
  status: string;
  generatedAt: string;
  feedback?: MealRecommendationFeedback;
};

type FeedbackReason = 'TASTE' | 'PORTION' | 'INGREDIENT' | 'CALORIES' | 'COOKING' | 'OTHER';
type MealRecommendationFeedback = { sentiment: 'LIKE' | 'DISLIKE'; reason?: FeedbackReason; note?: string };
type FeedbackPayload =
  | { sentiment: 'LIKE' }
  | { sentiment: 'DISLIKE'; reason: Exclude<FeedbackReason, 'OTHER'> }
  | { sentiment: 'DISLIKE'; reason: 'OTHER'; note: string };

const feedbackReasons: Array<{ value: FeedbackReason; label: string }> = [
  { value: 'TASTE', label: '口味' }, { value: 'PORTION', label: '分量' },
  { value: 'INGREDIENT', label: '食材' }, { value: 'CALORIES', label: '热量' },
  { value: 'COOKING', label: '做法' }, { value: 'OTHER', label: '其他' },
];

const mealNames = { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐' } as const;
const shanghaiHourFormatter = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai', hour: '2-digit', hourCycle: 'h23' });

function shanghaiHour(now: Date) {
  return Number(shanghaiHourFormatter.format(now));
}

export function nextMealType(now: Date): MealRecommendation['mealType'] {
  const hour = shanghaiHour(now);
  if (hour < 9) return 'BREAKFAST';
  if (hour < 12) return 'LUNCH';
  return 'DINNER';
}

export function nextMealRecommendation(recommendations: MealRecommendation[], now: Date) {
  return recommendations.find((item) => item.mealType === nextMealType(now) && item.status === 'READY' && item.items.length > 0);
}

export function mealTimingLabel(now: Date, mealType: MealRecommendation['mealType']) {
  return mealType === 'DINNER' && shanghaiHour(now) >= 18 ? '今晚' : '下一餐';
}

export function recommendationKcal(recommendation?: MealRecommendation) {
  return recommendation?.items.reduce((total, item) => total + item.estimatedKcal, 0) ?? 0;
}

export function MealRecommendationPage({ recommendations, now = new Date() }: { recommendations: MealRecommendation[]; now?: Date }) {
  const navigate = useNavigate();
  const nextType = nextMealType(now);
  const mealTypes: MealRecommendation['mealType'][] = ['BREAKFAST', 'LUNCH', 'DINNER'];
  const hasReadyMeal = recommendations.some((meal) => meal.status === 'READY' && meal.items.length > 0);
  const [feedback, setFeedback] = useState<Record<string, MealRecommendationFeedback | undefined>>(() => feedbackFrom(recommendations));
  const [pendingId, setPendingId] = useState<string>();
  const [feedbackError, setFeedbackError] = useState<{ recommendationId: string; message: string }>();
  const [dislikeId, setDislikeId] = useState<string>();
  const [reason, setReason] = useState<FeedbackReason>();
  const [note, setNote] = useState('');

  useEffect(() => setFeedback(feedbackFrom(recommendations)), [recommendations]);

  const submitLike = async (recommendationId: string) => {
    setFeedbackError(undefined);
    setPendingId(recommendationId);
    try {
      const saved = await api.upsertMealRecommendationFeedback(recommendationId, { sentiment: 'LIKE' }, idempotencyKey());
      setFeedback((current) => ({ ...current, [recommendationId]: { sentiment: saved.sentiment } }));
    } catch (cause) {
      setFeedbackError({ recommendationId, message: feedbackMessage(cause) });
    } finally {
      setPendingId(undefined);
    }
  };
  const submitDislike = async () => {
    if (!dislikeId || !reason || (reason === 'OTHER' && !note.trim())) return;
    const body: FeedbackPayload = reason === 'OTHER'
      ? { sentiment: 'DISLIKE', reason, note: note.trim() }
      : { sentiment: 'DISLIKE', reason };
    setFeedbackError(undefined);
    setPendingId(dislikeId);
    try {
      const saved = await api.upsertMealRecommendationFeedback(dislikeId, body, idempotencyKey());
      setFeedback((current) => ({ ...current, [dislikeId]: { sentiment: saved.sentiment, reason: saved.reason, note: saved.note } }));
      setDislikeId(undefined);
    } catch (cause) {
      setFeedbackError({ recommendationId: dislikeId, message: feedbackMessage(cause) });
    } finally {
      setPendingId(undefined);
    }
  };
  const selectedDislike = recommendations.find((item) => item.id === dislikeId);

  return <section className="page meal-page">
    <header className="subpage-head">
      <button aria-label="返回首页" onClick={() => navigate('/')}><ArrowLeft /></button>
      <div><small>{hasReadyMeal ? '今天的推荐正在这里' : '根据记录生成三餐建议'}</small><h1>今天吃什么</h1></div>
      <span><ChefHat /></span>
    </header>

    {recommendations.length ? <>
      <section className="meal-intro"><Sparkles /><div><strong>下一餐不纠结</strong><p>吃得满足，也给身体留一点轻盈。</p></div></section>
      <div className="meal-recommendations">{mealTypes.map((mealType) => {
        const meal = recommendations.find((item) => item.mealType === mealType);
        const isNext = mealType === nextType;
        const ready = meal?.status === 'READY' && meal.items.length > 0;
        return <article className={`meal-recommendation${isNext ? ' is-next' : ''}${ready ? '' : ' is-pending'}`} key={mealType}>
          <div className="meal-recommendation__head"><span><Utensils /></span><div><small>{isNext ? mealTimingLabel(now, mealType) : '今日安排'}</small><h2>{mealNames[mealType]}</h2></div>{ready && <strong><Flame />约 {recommendationKcal(meal)} kcal</strong>}</div>
          {ready ? <><div className="meal-foods">{meal.items.map((item) => <p key={item.name}><span>{item.name}</span><small>{item.estimatedKcal} kcal</small></p>)}</div><p className="meal-reason">{meal.reason}</p><div className="meal-feedback-actions"><button type="button" aria-label="赞" aria-pressed={feedback[meal.id]?.sentiment === 'LIKE'} disabled={pendingId === meal.id} onClick={() => void submitLike(meal.id)}><ThumbsUp /></button><button type="button" aria-label="踩" aria-pressed={feedback[meal.id]?.sentiment === 'DISLIKE'} disabled={pendingId === meal.id} onClick={() => { setFeedbackError(undefined); setDislikeId(meal.id); setReason(feedback[meal.id]?.reason); setNote(feedback[meal.id]?.note ?? ''); }}><ThumbsDown /></button>{pendingId === meal.id && <small>正在提交…</small>}</div>{feedbackError?.recommendationId === meal.id && <p className="meal-feedback-error" role="alert">{feedbackError.message}，未保存，请重试。</p>}</> : <div className="meal-pending"><Sparkles /><strong>{meal?.status === 'GENERATING' ? '正在生成推荐…' : meal?.status === 'FAILED' ? '这餐暂时没生成成功' : '等待生成这餐建议'}</strong><small>不会用占位食物代替真实推荐</small></div>}
        </article>;
      })}</div>
      {selectedDislike && <aside className="meal-feedback-sheet" aria-label="不喜欢的原因"><div><strong>这餐哪里不合适？</strong><button type="button" aria-label="关闭反馈面板" onClick={() => setDislikeId(undefined)}>×</button></div><div className="meal-feedback-reasons">{feedbackReasons.map((item) => <button type="button" key={item.value} aria-pressed={reason === item.value} onClick={() => setReason(item.value)}>{item.label}</button>)}</div>{reason === 'OTHER' && <label>补充说明<textarea aria-label="其他原因说明" value={note} maxLength={300} onChange={(event) => setNote(event.target.value)} /></label>}<button type="button" className="primary" disabled={!reason || (reason === 'OTHER' && !note.trim()) || pendingId === selectedDislike.id} onClick={() => void submitDislike()}>{pendingId === selectedDislike.id ? '正在提交…' : '提交反馈'}</button></aside>}
    </> : <section className="empty-card meal-empty"><span><ChefHat /></span><h2>今天还没有饮食建议</h2><p>推荐生成后会在这里展示三餐安排，现在不会用假数据凑一份菜单。</p></section>}
  </section>;
}

function feedbackFrom(recommendations: MealRecommendation[]) {
  return Object.fromEntries(recommendations.filter((item) => item.feedback).map((item) => [item.id, item.feedback]));
}

function idempotencyKey() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function feedbackMessage(cause: unknown) {
  return cause instanceof Error ? cause.message : '反馈提交失败';
}
