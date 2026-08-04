const puppeteer = require('puppeteer-core');
const LOGIN_URL = 'http://localhost:8080/login';
const DASH_URL = 'http://localhost:8080/dashboards/127e22c0-8fcd-11f1-97cb-df667a066d29';
const OUT = 'dash2.png';
(async () => {
  const browser = await puppeteer.launch({
    channel: 'msedge', headless: true,
    args: ['--no-sandbox', '--disable-gpu'],
    defaultViewport: { width: 1600, height: 1200 }
  });
  const page = await browser.newPage();
  page.on('pageerror', e => console.log('[PAGE-ERR]', String(e).slice(0,150)));
  await page.goto(LOGIN_URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 3000));
  const inputs = await page.$$('input');
  console.log('inputs:', inputs.length);
  if (inputs.length >= 2) {
    await inputs[0].click({clickCount:3}); await inputs[0].type('15079983758@163.com');
    await inputs[1].click({clickCount:3}); await inputs[1].type('147258');
  }
  await page.evaluate(() => { const b=[...document.querySelectorAll('button')].find(x=>/sign ?in|登录/i.test(x.textContent||'')); if(b) b.click(); });
  await new Promise(r => setTimeout(r, 5000));
  await page.goto(DASH_URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 15000));
  await page.screenshot({ path: OUT, fullPage: true });
  console.log('saved', OUT);
  require('fs').writeFileSync(OUT+'.txt', await page.evaluate(()=>document.body.innerText.slice(0,4000)), 'utf8');
  await browser.close();
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
