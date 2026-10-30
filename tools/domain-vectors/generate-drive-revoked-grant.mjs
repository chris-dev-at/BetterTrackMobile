// Regenerates app/src/test/resources/vault-vectors/drive-revoked-grant.fixture.json
// by RUNNING the vendored `hasRevokedGrant` (vendor/web-vault/drive/driveDataHome.ts
// @ PINNED_AT) over a fixed list of Drive error bodies. The inputs are chosen by
// hand; every `revoked` answer is computed by the platform function, never typed.
//
//   node tools/domain-vectors/generate-drive-revoked-grant.mjs
//
// Node >= 22.18 / 23.6 (type stripping on by default: the slice keeps its
// `as { … }` cast, which is erasable syntax).
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const srcRel = 'tools/domain-vectors/vendor/web-vault/drive/driveDataHome.ts';
const src = readFileSync(join(here, 'vendor/web-vault/drive/driveDataHome.ts'), 'utf8');
const start = src.indexOf('async function hasRevokedGrant(');
if (start < 0) throw new Error('hasRevokedGrant not found in the vendored driveDataHome.ts');
const end = src.indexOf('\n}\n', start) + 3;
const fn = src.slice(start, end);

const dir = mkdtempSync(join(tmpdir(), 'bt-revoked-grant-'));
const mod = join(dir, 'hasRevokedGrant.ts');
writeFileSync(mod, `export ${fn}`);
const { hasRevokedGrant } = await import(pathToFileURL(mod).href);

const j = (value) => JSON.stringify(value);
const inputs = [
  ['upstream test: 400 invalid_grant string error', j({ error: 'invalid_grant', error_description: 'authorization revoked' })],
  ['Drive ambiguous 401 Invalid Credentials', j({ error: { code: 401, message: 'Invalid Credentials', status: 'UNAUTHENTICATED' } })],
  ['FakeDriveServer expired body', j({ error: { code: 401 } })],
  ['empty body', ''],
  ['not JSON at all', 'invalid_grant'],
  ['JSON null', 'null'],
  ['JSON string top level', j('invalid_grant')],
  ['JSON array top level', j([{ error: 'invalid_grant' }])],
  ['upper-case string error', j({ error: 'INVALID_GRANT' })],
  ['description carries it', j({ error: 'invalid_request', error_description: 'Invalid token format' })],
  ['object error message', j({ error: { message: 'Invalid token' } })],
  ['object error status', j({ error: { status: 'INVALID_GRANT' } })],
  ['hyphen separator', j({ error: 'invalid-token' })],
  ['no separator', j({ error: 'invalidgrant' })],
  ['double underscore', j({ error: 'invalid__grant' })],
  ['dotless i (no simple case folding to i)', j({ error: 'ınvalid_grant' })],
  ['dotted capital I (no simple case folding to i)', j({ error: 'İnvalid_grant' })],
  ['Kelvin sign folds to k', j({ error: 'invalid_toKen' })],
  ['null error, description would match', j({ error: null, error_description: 'invalid_grant' })],
  ['array error', j({ error: ['invalid_grant'] })],
  ['object error, array message', j({ error: { message: ['invalid_grant'] } })],
  ['object error, nested object message', j({ error: { message: { text: 'invalid_grant' } } })],
  ['string error, null description', j({ error: 'x', error_description: null })],
  ['string error, numeric description', j({ error: 'invalid_grant', error_description: 7 })],
  ['boolean error', j({ error: true, error_description: 'invalid_grant' })],
  ['leading BOM', '﻿' + j({ error: 'invalid_grant' })],
  ['leading whitespace', '  \n' + j({ error: 'invalid_grant' })],
];

const cases = [];
for (const [name, body] of inputs) {
  cases.push({ name, body, revoked: await hasRevokedGrant(new Response(body, { status: 400 })) });
}

const sha = (text) => createHash('sha256').update(text).digest('hex');
const fixture = {
  _source: `${srcRel} hasRevokedGrant (function text sha256 ${sha(fn)}, file sha256 ${sha(src)})`,
  _generator: 'tools/domain-vectors/generate-drive-revoked-grant.mjs',
  _note: 'revoked = the platform function run on new Response(body); inputs hand-chosen, outputs computed.',
  cases,
};
const out = join(here, '../../app/src/test/resources/vault-vectors/drive-revoked-grant.fixture.json');
writeFileSync(out, `${JSON.stringify(fixture, null, 2)}\n`);
console.log(`wrote ${cases.length} cases (${cases.filter((c) => c.revoked).length} revoked) -> ${out}`);
