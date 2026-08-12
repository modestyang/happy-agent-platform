import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dataset = JSON.parse(await readFile(path.join(here, 'dataset.json'), 'utf8'));

const NS_URL = Buffer.from('6ba7b8119dad11d180b400c04fd430c8', 'hex');
const DIFFICULTIES = new Set(['BEGINNER', 'INTERMEDIATE', 'ADVANCED']);
const MOVEMENT_PATTERNS = new Set([
  'SQUAT',
  'HINGE',
  'LUNGE',
  'HORIZONTAL_PUSH',
  'VERTICAL_PUSH',
  'HORIZONTAL_PULL',
  'VERTICAL_PULL',
  'CORE_STABILITY',
  'CORE_FLEXION',
  'ROTATION',
  'LOCOMOTION',
  'MOBILITY',
  'ISOLATION',
]);
const IMPACT_LEVELS = new Set(['LOW', 'MEDIUM', 'HIGH']);

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
const imageUrls = (value, entry) => {
  const frames = [0, 1, 0, 1].map((f) => `${value.assetBasePath}/${entry.slug}/${f}.jpg`);
  if (entry.gifId) {
    frames[0] = `${value.assetBasePath}/${entry.slug}/anim.gif`;
  }
  return frames;
};

function requireNonBlank(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${label} must be a non-blank string`);
  }
}

function requireNonEmptyStrings(value, label) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`${label} must be a non-empty array`);
  }
  value.forEach((item, index) => requireNonBlank(item, `${label}[${index}]`));
}

function requireEnum(value, allowed, label) {
  if (!allowed.has(value)) {
    throw new Error(`${label} has invalid value ${String(value)}`);
  }
}

function validateSelectionMetadata(entry, label) {
  requireNonEmptyStrings(entry.muscleGroups, `${label}.muscleGroups`);
  requireNonEmptyStrings(entry.equipment, `${label}.equipment`);
  requireEnum(entry.difficulty, DIFFICULTIES, `${label}.difficulty`);
  requireEnum(entry.movementPattern, MOVEMENT_PATTERNS, `${label}.movementPattern`);
  requireEnum(entry.impactLevel, IMPACT_LEVELS, `${label}.impactLevel`);
}

function requireUnique(values, label) {
  if (new Set(values).size !== values.length) {
    throw new Error(`${label} must not contain duplicates`);
  }
}

export function validateDataset(value) {
  if (!value || !Array.isArray(value.exercises) || !Array.isArray(value.demoUpgrades)) {
    throw new Error('dataset exercises and demoUpgrades must be arrays');
  }
  value.exercises.forEach((entry, index) => {
    const label = `exercises[${index}]`;
    requireNonBlank(entry.slug, `${label}.slug`);
    requireNonBlank(entry.name, `${label}.name`);
    validateSelectionMetadata(entry, label);
  });
  value.demoUpgrades.forEach((entry, index) => {
    const label = `demoUpgrades[${index}]`;
    requireNonBlank(entry.uuid, `${label}.uuid`);
    requireNonBlank(entry.slug, `${label}.slug`);
    validateSelectionMetadata(entry, label);
  });
  requireUnique(
    [...value.exercises, ...value.demoUpgrades].map((entry) => entry.slug),
    'exercise slugs',
  );
  requireUnique(
    value.exercises.map((entry) => entry.name),
    'exercise names',
  );
  requireUnique(
    value.demoUpgrades.map((entry) => entry.uuid),
    'demo UUIDs',
  );
  return {
    exerciseCount: value.exercises.length,
    demoUpgradeCount: value.demoUpgrades.length,
    totalCount: value.exercises.length + value.demoUpgrades.length,
  };
}

export function buildSql(value) {
  validateDataset(value);
  const statements = [];
  for (const e of value.exercises) {
    const id = uuidV5(`happy-agent/exercise/${e.slug}`);
    statements.push(
      `INSERT INTO fitness.exercises (exercise_id, name, target_area, sets, seconds, steps, errors, image_urls, muscle_groups, equipment, difficulty, movement_pattern, impact_level)\n` +
        `VALUES ('${id}', ${sqlString(e.name)}, ${sqlString(e.targetArea)}, ${e.sets}, ${e.seconds}, ` +
        `${sqlJson(e.steps)}, ${sqlJson(e.errors)}, ${sqlJson(imageUrls(value, e))}, ` +
        `${sqlJson(e.muscleGroups)}, ${sqlJson(e.equipment)}, ${sqlString(e.difficulty)}, ` +
        `${sqlString(e.movementPattern)}, ${sqlString(e.impactLevel)})\n` +
        `ON CONFLICT (exercise_id) DO UPDATE SET name = EXCLUDED.name, target_area = EXCLUDED.target_area, ` +
        `sets = EXCLUDED.sets, seconds = EXCLUDED.seconds, steps = EXCLUDED.steps, ` +
        `errors = EXCLUDED.errors, image_urls = EXCLUDED.image_urls, ` +
        `muscle_groups = EXCLUDED.muscle_groups, equipment = EXCLUDED.equipment, ` +
        `difficulty = EXCLUDED.difficulty, movement_pattern = EXCLUDED.movement_pattern, ` +
        `impact_level = EXCLUDED.impact_level;`,
    );
  }
  for (const d of value.demoUpgrades) {
    const images = d.sourceId ? `, image_urls = ${sqlJson(imageUrls(value, d))}` : '';
    statements.push(
      `UPDATE fitness.exercises SET target_area = ${sqlString(d.targetArea)}, ` +
        `muscle_groups = ${sqlJson(d.muscleGroups)}, equipment = ${sqlJson(d.equipment)}, ` +
        `difficulty = ${sqlString(d.difficulty)}, movement_pattern = ${sqlString(d.movementPattern)}, ` +
        `impact_level = ${sqlString(d.impactLevel)}${images} WHERE exercise_id = '${d.uuid}';`,
    );
  }
  return `BEGIN;\n${statements.join('\n')}\nCOMMIT;\n`;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const sql = buildSql(dataset);
  if (process.argv.includes('--sql')) {
    process.stdout.write(sql);
  } else {
    const container = process.env.SEED_PG_CONTAINER ?? 'deploy-postgres-1';
    const child = spawn(
      'docker',
      [
        'exec',
        '-i',
        container,
        'psql',
        '-U',
        'fitness_app',
        '-d',
        'happy_agent',
        '-v',
        'ON_ERROR_STOP=1',
        '-f',
        '-',
      ],
      { stdio: ['pipe', 'inherit', 'inherit'] },
    );
    child.on('exit', (code) => process.exit(code ?? 1));
    child.stdin.end(sql);
  }
}
