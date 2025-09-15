const esbuild = require('esbuild');
const path = require('path');
const fs = require('fs');

const isWatch = process.argv.includes('--watch');

const buildOptions = {
  entryPoints: ['src/index.ts'],
  bundle: true,
  outdir: 'dist',
  format: 'esm',
  target: 'es2020',
  sourcemap: true,
  minify: !isWatch,
  loader: {
    '.css': 'css',
    '.png': 'file',
    '.jpg': 'file',
    '.svg': 'file'
  },
  define: {
    'process.env.NODE_ENV': isWatch ? '"development"' : '"production"'
  }
};

// Copy HTML file to dist directory
function copyHtmlFile() {
  const srcHtml = path.join(__dirname, 'src', 'index.html');
  const distHtml = path.join(__dirname, 'dist', 'index.html');
  
  if (fs.existsSync(srcHtml)) {
    fs.copyFileSync(srcHtml, distHtml);
    console.log('Copied index.html to dist/');
  }
}

if (isWatch) {
  esbuild.context(buildOptions).then(ctx => {
    ctx.watch();
    copyHtmlFile();
    console.log('Watching for changes...');
  });
} else {
  esbuild.build(buildOptions).then(() => {
    copyHtmlFile();
  }).catch(() => process.exit(1));
}
