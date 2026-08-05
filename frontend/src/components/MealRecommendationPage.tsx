import { ArrowLeft, ChefHat, Flame, Sparkles, Utensils } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

export type MealRecommendation = {
  id: string;
  recommendationDate: string;
  mealType: 'BREAKFAST' | 'LUNCH' | 'DINNER';
  items: { name: string; estimatedKcal: number }[];
  reason: string;
  status: string;
  generatedAt: string;
};

const mealNames = { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐' } as const;

export function nextMealType(now: Date): MealRecommendation['mealType'] {
  const hour = now.getHours();
  if (hour < 9) return 'BREAKFAST';
  if (hour < 12) return 'LUNCH';
  return 'DINNER';
}

export function nextMealRecommendation(recommendations: MealRecommendation[], now: Date) {
  return recommendations.find((item) => item.mealType === nextMealType(now));
}

export function recommendationKcal(recommendation?: MealRecommendation) {
  return recommendation?.items.reduce((total, item) => total + item.estimatedKcal, 0) ?? 0;
}

export function MealRecommendationPage({ recommendations, now = new Date() }: { recommendations: MealRecommendation[]; now?: Date }) {
  const navigate = useNavigate();
  const nextType = nextMealType(now);

  return <section className="page meal-page">
    <header className="subpage-head">
      <button aria-label="返回首页" onClick={() => navigate('/')}><ArrowLeft /></button>
      <div><small>今天的三餐已经排好</small><h1>今天吃什么</h1></div>
      <span><ChefHat /></span>
    </header>

    {recommendations.length ? <>
      <section className="meal-intro"><Sparkles /><div><strong>下一餐不纠结</strong><p>吃得满足，也给身体留一点轻盈。</p></div></section>
      <div className="meal-recommendations">{recommendations.map((meal) => {
        const isNext = meal.mealType === nextType;
        return <article className={`meal-recommendation${isNext ? ' is-next' : ''}`} key={meal.id}>
          <div className="meal-recommendation__head"><span><Utensils /></span><div><small>{isNext ? '下一餐' : '今日安排'}</small><h2>{mealNames[meal.mealType]}</h2></div><strong><Flame />约 {recommendationKcal(meal)} kcal</strong></div>
          <div className="meal-foods">{meal.items.map((item) => <p key={item.name}><span>{item.name}</span><small>{item.estimatedKcal} kcal</small></p>)}</div>
          <p className="meal-reason">{meal.reason}</p>
        </article>;
      })}</div>
    </> : <section className="empty-card meal-empty"><span><ChefHat /></span><h2>今天还没有饮食建议</h2><p>推荐生成后会在这里展示三餐安排，现在不会用假数据凑一份菜单。</p></section>}
  </section>;
}
