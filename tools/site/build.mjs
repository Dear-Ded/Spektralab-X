// Bundle the project site's script: docs/site/src/main.js -> docs/site/site.min.js
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const site = resolve(here, '../../docs/site');
await build({
  entryPoints: [resolve(site, 'src/main.js')],
  outfile: resolve(site, 'site.min.js'),
  bundle: true, minify: true, format: 'esm', target: ['es2020', 'safari15'],
  nodePaths: [resolve(here, 'node_modules')],
  legalComments: 'eof',
  logLevel: 'info',
});
