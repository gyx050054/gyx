// TB 仪表板截图脚本（puppeteer-core + Edge headless）
const puppeteer = require('puppeteer-core');

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const LOGIN_URL = 'http://localhost:8080/login';
const DASH_URL = 'http://localhost:8080/dashboards/127e22c0-8fcd-11f1-97cb-df667a066d29';
const USER = '15079983758@163.com';
const PASS = '147258';
const OUT = process.argv[2] || 'dash.png';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: true, args: ['--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--disable-setuid-sandbox','--window-size=1600,1200'],
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1600,1200'],
    defaultViewport: { width: 1600, height: 1200 }
  });
  const page = await browser.newPage();
  page.on('console', m => { if (m.type() === 'error') console.log('[CONSOLE-ERR]', m.text().slice(0, 200)); });
  page.on('pageerror', e => console.log('[PAGE-ERR]', String(e).slice(0, 200)));

  // 1. 登录
  await page.goto(LOGIN_URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 3000));
  const inputs = await page.$$('input');
  console.log('login inputs:', inputs.length);
  if (inputs.length >= 2) {
    await inputs[0].click({ clickCount: 3 });
    await inputs[0].type(USER);
    await inputs[1].click({ clickCount: 3 });
    await inputs[1].type(PASS);
  }
  const clicked = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('button')];
    const b = btns.find(x => /sign ?in|登录|log ?in/i.test(x.textContent || ''));
    if (b) { b.click(); return true; }
    return false;
  });
  console.log('login clicked:', clicked);
  await new Promise(r => setTimeout(r, 5000));

  // 2. 打开仪表板
  await page.goto(DASH_URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 15000));

  // 3. 截图
  await page.screenshot({ path: OUT, fullPage: true });
  console.log('saved:', OUT);

  // 4. 页面文本
  const text = await page.evaluate(() => document.body.innerText.slice(0, 4000));
  require('fs').writeFileSync(OUT + '.txt', text, 'utf8');
  console.log('text saved');
  await browser.close();
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
