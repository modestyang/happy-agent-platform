export function exerciseFrameStep(elapsedSeconds: number, frameCount: number): number {
  if (frameCount <= 1 || elapsedSeconds < 0) return 1;
  const lastFrameIndex = frameCount - 1;
  const offset = Math.floor(elapsedSeconds) % (lastFrameIndex * 2);
  return offset <= lastFrameIndex ? offset + 1 : lastFrameIndex * 2 - offset + 1;
}
