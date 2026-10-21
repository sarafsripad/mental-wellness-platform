// Copies the ASHA web app's interface strings (i18n.js, data only) into src/i18n/strings.json, so the
// web and mobile apps show the same, unchanged wording. Run with `npm run strings`; a Jest test fails
// if the copy is out of date.
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';

const source = fileURLToPath(new URL('../../../backend/wellness-api/src/main/resources/asha/i18n.js', import.meta.url));
const target = fileURLToPath(new URL('../src/i18n/strings.json', import.meta.url));

export function exportStrings() {
  const sandbox = {};
  vm.runInNewContext(readFileSync(source, 'utf8') + '\n;this.out = {LANGUAGES, TRANSLATIONS};', sandbox, {timeout: 1000});
  return JSON.stringify({languages: sandbox.out.LANGUAGES, translations: sandbox.out.TRANSLATIONS}, null, 1) + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  writeFileSync(target, exportStrings());
  console.log(`wrote ${target}`);
}
