import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dataset = JSON.parse(await readFile(path.join(here, 'dataset.json'), 'utf8'));

const NS_URL = Buffer.from('6ba7b8119dad11d180b400c04fd430c8', 'hex');

function uuidV5(name) {
  const digest = createHash('sha1').update(NS_URL).update(name, 'utf8').digest();
  const b = Buffer.alloc(16);
  digest.copy(b, 0, 0, 16);
  b[6] = (b[6] & 0x0f) | 0x50;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const sqlString = (value) => `'${String(value).replaceAll("'", "''")}'`;
const sqlJson = (value) => `${sqlString(JSON.stringify(value))}::jsonb`;
const imageUrls = (entry) => {
  const frames = [0, 1, 0, 1].map((f) => `${dataset.assetBasePath}/${entry.slug}/${f}.jpg`);
  if (entry.gifId) {
    frames[0] = `${dataset.assetBasePath}/${entry.slug}/anim.gif`;
  }
  return frames;
};

const statements = [];
for (const e of dataset.exercises) {
  const id = uuidV5(`happy-agent/exercise/${e.slug}`);
  statements.push(
    `INSERT INTO fitness.exercises (exercise_id, name, target_area, sets, seconds, steps, errors, image_urls)\n` +
      `VALUES ('${id}', ${sqlString(e.name)}, ${sqlString(e.targetArea)}, ${e.sets}, ${e.seconds}, ` +
      `${sqlJson(e.steps)}, ${sqlJson(e.errors)}, ${sqlJson(imageUrls(e))})\n` +
      `ON CONFLICT (exercise_id) DO UPDATE SET name = EXCLUDED.name, target_area = EXCLUDED.target_area, ` +
      `sets = EXCLUDED.sets, seconds = EXCLUDED.seconds, steps = EXCLUDED.steps, ` +
      `errors = EXCLUDED.errors, image_urls = EXCLUDED.image_urls;`,
  );
}
for (const d of dataset.demoUpgrades) {
  const images = d.sourceId ? `, image_urls = ${sqlJson(imageUrls(d))}` : '';
  statements.push(
    `UPDATE fitness.exercises SET target_area = ${sqlString(d.targetArea)}${images} WHERE exercise_id = '${d.uuid}';`,
  );
}

const sql = `BEGIN;\n${statements.join('\n')}\nCOMMIT;\n`;

if (process.argv.includes('--sql')) {
  process.stdout.write(sql);
  process.exit(0);
}

const container = process.env.SEED_PG_CONTAINER ?? 'deploy-postgres-1';
const child = spawn(
  'docker',
  ['exec', '-i', container, 'psql', '-U', 'fitness_app', '-d', 'happy_agent', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { stdio: ['pipe', 'inherit', 'inherit'] },
);
child.on('exit', (code) => process.exit(code ?? 1));
child.stdin.end(sql);
