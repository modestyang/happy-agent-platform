import { useEffect, useState } from 'react';

export type ExerciseMedia = {
  name: string;
  targetArea: string;
  imageUrls?: string[];
};

type ExerciseVisualProps = {
  exercise: ExerciseMedia;
  step?: number;
  compact?: boolean;
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

export function ExerciseVisual({ exercise, step = 1, compact = false }: ExerciseVisualProps) {
  const imageUrl = exercise.imageUrls?.[step - 1] ?? exercise.imageUrls?.[0];
  const isSeedPlaceholder = Boolean(imageUrl?.startsWith('data:image/svg+xml') && imageUrl.includes('%3Ctext'));
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [imageUrl]);

  return <div className={`exercise-visual${compact ? ' exercise-visual--compact' : ''}`} role="img" aria-label={`${exercise.name}第${step}步动作示意`}>
    {imageUrl && !failed && !isSeedPlaceholder
      ? <img src={imageUrl} alt="" onError={() => setFailed(true)} />
      : <Pose area={exercise.targetArea} step={step} />}
    {!compact && <span>STEP {String(step).padStart(2, '0')}</span>}
  </div>;
}
