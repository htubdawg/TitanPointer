// Renders settings.html to PNGs the size of the Titan's screen (1440 x 1440): node docs/screenshots/render.js
const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 480, height: 480 }, deviceScaleFactor: 3 });
  await page.goto('file://' + path.join(__dirname, 'settings.html'));
  await page.evaluate(() => document.fonts.ready);
  // Each shot scrolls the settings list so that this section heading is near the top.
  const shots = [['settings-1.png', null], ['settings-2.png', 'Pointer'], ['settings-3.png', 'Keys']];
  for (const [file, heading] of shots) {
    await page.evaluate((h) => {
      const s = document.getElementById('scroll');
      if (!h) { s.scrollTop = 0; return; }
      const el = [...document.querySelectorAll('.section')].find((e) => e.textContent === h);
      s.scrollTop = el.offsetTop - s.offsetTop - 8;
    }, heading);
    await page.screenshot({ path: path.join(__dirname, '..', 'images', file) });
  }
  await browser.close();
})();
