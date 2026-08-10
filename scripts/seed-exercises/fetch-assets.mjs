import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const dataset = JSON.parse(await readFile(path.join(here, 'dataset.json'), 'utf8'));

const gifBaseUrl = 'https://raw.githubusercontent.com/omercotkd/exercises-gifs/main/assets';

const entries = [
  ...dataset.exercises
    .filter((e) => e.sourceId)
    .map((e) => ({ slug: e.slug, sourceId: e.sourceId, gifId: e.gifId })),
  ...dataset.demoUpgrades.filter((d) => d.sourceId).map((d) => ({ slug: d.slug, sourceId: d.sourceId })),
];

const outBase = path.join(repoRoot, 'frontend/public/exercises');
const failures = [];
let downloaded = 0;
let skipped = 0;

async function fetchOne(slug, url, filename) {
  const dir = path.join(outBase, slug);
  const dest = path.join(dir, filename);
  await mkdir(dir, { recursive: true });
  try {
    await access(dest);
    skipped += 1;
    return;
  } catch {
    // missing -> download
  }
  const res = await fetch(url);
  if (!res.ok) {
    failures.push(`${slug} ${filename}: HTTP ${res.status} ${url}`);
    return;
  }
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 5000) {
    failures.push(`${slug} ${filename}: suspiciously small (${buf.length} bytes)`);
    return;
  }
  await writeFile(dest, buf);
  downloaded += 1;
}

const concurrency = 8;
for (let i = 0; i < entries.length; i += concurrency) {
  await Promise.all(
    entries.slice(i, i + concurrency).flatMap((e) => {
      const tasks = [0, 1].map((frame) =>
        fetchOne(e.slug, `${dataset.sourceBaseUrl}/${e.sourceId}/${frame}.jpg`, `${frame}.jpg`),
      );
      if (e.gifId) {
        tasks.push(fetchOne(e.slug, `${gifBaseUrl}/${e.gifId}.gif`, 'anim.gif'));
      }
      return tasks;
    }),
  );
}

console.log(`assets: ${downloaded} downloaded, ${skipped} already present, ${failures.length} failed`);
if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
