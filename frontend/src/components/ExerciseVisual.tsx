import { useEffect, useState } from 'react';
import { ExpandableSurface } from './ContentSurface';

export type ExerciseMedia = {
  name: string;
  targetArea: string;
  imageUrls?: string[];
};

type ExerciseVisualProps = {
  exercise: ExerciseMedia;
  step?: number;
  compact?: boolean;
  autoPlay?: boolean;
};

function Pose({ area, step }: { area: string; step: number }) {
  const lowerBody = /下肢|臀|腿/.test(area);
  const core = /核心|腹|呼吸/.test(area);
  const upperBody = /上肢|肩|胸|背/.test(area);
  const bend = step % 2 === 0;

  return <svg viewBox="0 0 220 160" aria-hidden="true">
    <path className="pose-floor" d="M28 140 H192" />
    <circle className="pose-head" cx={bend ? 116 : 109} cy={bend ? 43 : 35} r="13" />
    <path className="pose-line" d={bend ? 'M113 58 C106 79 97 91 87 107' : 'M109 50 C109 73 109 87 108 102'} />
    <path className="pose-line" d={upperBody ? (bend ? 'M107 70 L66 87 M108 71 L151 81' : 'M108 66 L72 53 M110 66 L151 50') : 'M108 68 L79 88 M110 68 L139 88'} />
    <path className="pose-line" d={lowerBody ? (bend ? 'M87 107 L55 118 L39 139 M88 107 L130 116 L156 139' : 'M108 102 L82 139 M108 102 L137 139') : 'M108 102 L86 139 M108 102 L134 139'} />
    {core && <path className="pose-breath" d="M92 79 C105 67 123 69 134 83" />}
    <circle className="pose-dot" cx={lowerBody ? 91 : upperBody ? 109 : 114} cy={lowerBody ? 108 : upperBody ? 69 : 82} r="7" />
  </svg>;
}

export function ExerciseVisual({ exercise, step = 1, compact = false, autoPlay = false }: ExerciseVisualProps) {
  const frameCount = exercise.imageUrls?.length ?? 0;
  const mediaKey = exercise.imageUrls?.join('\n') ?? '';
  const reducedMotion = typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const [previewStep, setPreviewStep] = useState(1);
  useEffect(() => {
    setPreviewStep(1);
    if (!autoPlay || reducedMotion || frameCount <= 1) return;
    const timer = window.setInterval(() => setPreviewStep((current) => current % frameCount + 1), 1500);
    return () => window.clearInterval(timer);
  }, [autoPlay, frameCount, mediaKey, reducedMotion]);
  const activeStep = autoPlay ? previewStep : step;
  const imageUrl = exercise.imageUrls?.[activeStep - 1] ?? exercise.imageUrls?.[0];
  const isSeedPlaceholder = Boolean(imageUrl?.startsWith('data:image/svg+xml') && imageUrl.includes('%3Ctext'));
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [imageUrl]);

  const visual = () => <div className={`exercise-visual${compact ? ' exercise-visual--compact' : ''}`} role="img" aria-label={`${exercise.name}第${activeStep}步动作示意`}>
    {imageUrl && !failed && !isSeedPlaceholder ? <img src={imageUrl} alt="" onError={() => setFailed(true)} /> : <Pose area={exercise.targetArea} step={activeStep} />}
    {!compact && <span>STEP {String(activeStep).padStart(2, '0')}</span>}
  </div>;

  if (compact) return <div className="exercise-carousel exercise-carousel--compact">
    {visual()}
    {autoPlay && frameCount > 1 && <div className="exercise-carousel__pages" aria-label={`${exercise.name}动作帧`}>
      {exercise.imageUrls?.map((imageUrl, index) => <button
        aria-label={`查看${exercise.name}第${index + 1}帧`}
        aria-pressed={activeStep === index + 1}
        key={`${imageUrl}:${index}`}
        onClick={() => setPreviewStep(index + 1)}
        type="button"
      />)}
    </div>}
  </div>;
  return <ExpandableSurface variant="media" className="exercise-visual-surface" label={`${exercise.name}动作示意`} title={`${exercise.name}动作示意详情`} expandedChildren={visual()}>{visual()}</ExpandableSurface>;
}
