// Renders settings.html to PNGs the size of the Titan's screen (1440 x 1440): node docs/screenshots/render.js
const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 480, height: 480 }, deviceScaleFactor: 3 });
  // The pointer glyphs come from the app's own data file.
  const src = require('fs').readFileSync(path.join(__dirname, '../../app/src/main/java/com/titanpointer/RetroCursors.java'), 'utf8');
  const glyphs = [...src.matchAll(/new Glyph\("(\w+)", "([^"]+)", (\d+), (\d+),([^)]*)\)/g)].map((m) => (
    { id: m[1], label: m[2], hx: +m[3], hy: +m[4], rows: [...m[5].matchAll(/"([.o#]+)"/g)].map((r) => r[1]) }));
  await page.addInitScript((g) => { window.GLYPHS = g; }, glyphs);
  await page.goto('file://' + path.join(__dirname, 'settings.html'));
  await page.evaluate(() => document.fonts.ready);
  // Each shot opens a tab and, optionally, scrolls so that a section heading is near the top.
  const shots = [['settings-1.png', 0, null], ['settings-2.png', 1, 'Pointer look'], ['settings-3.png', 2, null], ['settings-4.png', 2, 'Keys'],
                 ['settings-5.png', 3, null], ['settings-6.png', 4, null]];
  for (const [file, tab, heading] of shots) {
    await page.evaluate(([t, h]) => {
      showTab(t);
      if (!h) return;
      const s = document.getElementById('scroll');
      const el = [...document.querySelectorAll('.section')].find((e) => e.textContent === h);
      s.scrollTop = el.offsetTop - s.offsetTop - 8;
    }, [tab, heading]);
    await page.screenshot({ path: path.join(__dirname, '..', 'images', file) });
  }
  await browser.close();
})();
