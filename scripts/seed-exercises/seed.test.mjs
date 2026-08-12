import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { buildSql, validateDataset } from './seed.mjs';

const dataset = JSON.parse(
  await readFile(new URL('./dataset.json', import.meta.url), 'utf8'),
);

test('all 59 exercises have complete selection metadata', () => {
  assert.deepEqual(validateDataset(dataset), {
    exerciseCount: 55,
    demoUpgradeCount: 4,
    totalCount: 59,
  });
});

test('seed SQL writes metadata for inserts and demo upgrades', () => {
  const sql = buildSql(dataset);
  assert.match(
    sql,
    /muscle_groups, equipment, difficulty, movement_pattern, impact_level/,
  );
  assert.match(sql, /muscle_groups = EXCLUDED\.muscle_groups/);
  assert.match(sql, /movement_pattern = 'SQUAT'/);
  assert.match(sql, /60000000-0000-0000-0000-000000000004/);
});
