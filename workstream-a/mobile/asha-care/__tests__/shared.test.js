// Both checks guard what the mobile app shares with the rest of the platform: the web app's wording,
// and the request bodies that tests/contracts/test_mobile_requests.py validates against OpenAPI.
// Plain JavaScript: it runs in Node (file system, child process), not in the app.
const {execFileSync} = require('node:child_process');
const {readFileSync, writeFileSync} = require('node:fs');
const path = require('node:path');
const {operations} = require('../src/core/visit');
const {completedVisit, fakeIds} = require('../test-support/fixtures');

const root = path.join(__dirname, '..');

test('strings.json is an unchanged copy of the web app strings (run `npm run strings`)', () => {
  const fresh = execFileSync(process.execPath, ['--input-type=module', '-e',
    "import {exportStrings} from './scripts/export-strings.mjs'; process.stdout.write(exportStrings());"], {cwd: root});
  expect(readFileSync(path.join(root, 'src/i18n/strings.json'), 'utf8')).toBe(fresh.toString());
});

test('__contract__/requests.json matches what the app sends (UPDATE_CONTRACT_SAMPLES=1 to refresh)', () => {
  const ops = operations(completedVisit(fakeIds(), 'yes'), '2026-10-20T08:10:00Z', 'kn-IN', fakeIds());
  const samples = ops.filter(o => !o.audio).map(o => ({operation_id: o.operationId, method: o.method, path: o.path, request: o.body}));
  const text = JSON.stringify(samples, null, 1) + '\n';
  const file = path.join(root, '__contract__/requests.json');
  if (process.env.UPDATE_CONTRACT_SAMPLES === '1') writeFileSync(file, text);
  expect(readFileSync(file, 'utf8')).toBe(text);
});
