// Copies the pre-built browser bundles for pdfjs-dist and @aiden0z/pptx-renderer
// out of node_modules and into the Android app's static assets, where they're
// loaded directly via <script type="module"> (this project has no bundler/build
// step — see TCCA/app/src/main/assets/lib/renderers/*.js).
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const assetsLib = join(repoRoot, 'TCCA', 'app', 'src', 'main', 'assets', 'lib');

const files = [
  ['node_modules/pdfjs-dist/build/pdf.min.mjs', 'lib/pdfjs/pdf.min.mjs'],
  ['node_modules/pdfjs-dist/build/pdf.worker.min.mjs', 'lib/pdfjs/pdf.worker.min.mjs'],
  [
    'node_modules/@aiden0z/pptx-renderer/dist/aiden0z-pptx-renderer.browser.es.js',
    'lib/pptx-renderer/aiden0z-pptx-renderer.browser.es.js',
  ],
];

for (const [src, destRel] of files) {
  const srcPath = join(repoRoot, src);
  const destPath = join(repoRoot, 'TCCA', 'app', 'src', 'main', 'assets', destRel);
  mkdirSync(dirname(destPath), { recursive: true });
  copyFileSync(srcPath, destPath);
  console.log('vendored', destRel);
}
