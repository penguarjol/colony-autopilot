// Renders the board in a real browser and reports what a person would see.
//
// Every other check here is HTTP-level, and HTTP-level checks cannot see the
// two failures that actually produced blank panels: a frame refused by the
// browser's content policy, and a single-page app whose API calls were
// answered by a different server. Both return 200 to curl.
//
// Reads the shared credential from the server itself rather than taking it
// on the command line, so it never reaches argv, shell history, or this
// script's output.
//
// Usage: node render-board.mjs [outDir]
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright';

const outDir = process.argv[2] ?? '/tmp/board-shots';
const REPO = new URL('../..', import.meta.url).pathname;

function secret(key) {
  const out = execFileSync(
    `${REPO}/deploy/scripts/ccssh`,
    [`grep "^${key}=" /etc/colony-agent/agent.env | cut -d= -f2-`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  );
  return out.trim().replace(/^"|"$/g, '');
}

const user = secret('OPENCODE_SERVER_USERNAME');
const pass = secret('OPENCODE_SERVER_PASSWORD');
if (!user || !pass) {
  console.error('could not read the shared credential from the server');
  process.exit(2);
}
const HOST = '192.168.1.171.sslip.io';
const BOARD = `http://${HOST}:3000/`;
const AUTH = `http://auth.${HOST}:3010`;

const viewports = {
  desktop: { width: 1440, height: 900 },
  mobile: { width: 390, height: 844 },
};

// Chromium resolves names itself rather than through the system resolver,
// and on a network that blocks or filters that lookup the navigation fails
// before any of this is exercised. Pin the name so the test measures
// rendering rather than whatever DNS the runner happens to have.
// The issuer's certificate comes from Caddy's internal CA, which this runner
// has no reason to trust. Accepting it here keeps the test about whether the
// panels render; whether a given device trusts the CA is a separate concern.
const browser = await chromium.launch({
  args: [
    `--host-resolver-rules=MAP *${HOST} 192.168.1.171,MAP *.${HOST} 192.168.1.171`,
    '--ignore-certificate-errors',
  ],
});
let failed = 0;

for (const [label, viewport] of Object.entries(viewports)) {
  const ctx = await browser.newContext({ viewport, ignoreHTTPSErrors: true });
  const page = await ctx.newPage();

  const consoleErrors = [];
  const badResponses = [];
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
  page.on('response', (r) => {
    if (r.status() >= 400) badResponses.push(`${r.status()} ${r.url()}`);
  });

  await page.goto(BOARD, { waitUntil: 'domcontentloaded', timeout: 45000 });

  if (page.url().startsWith(AUTH)) {
    await page.fill('input[type="text"], input[name="username"]', user);
    await page.fill('input[type="password"]', pass);
    await page.click('button[type="submit"]');

    // Older configurations stop on an interstitial instead of returning to
    // the requested page. Click through it so this test measures rendering
    // either way; the smoke test is what asserts it is switched off.
    const cont = page.getByRole('button', { name: /continue/i });
    await cont.waitFor({ timeout: 8000 }).then(
      async () => {
        console.log('  note: login stopped on the continue interstitial');
        await cont.click();
      },
      () => {},
    );
    await page.waitForURL((u) => !u.toString().startsWith(AUTH), { timeout: 45000 });
  }

  // Homarr keeps its own credential store, so passing the perimeter gate is
  // not the same as being signed in here. Take the OIDC route: that is the
  // whole point of the provider, and the round trip should be silent
  // because the session already exists.
  if (page.url().includes('/auth/login')) {
    const sso = page.getByRole('button', { name: /command center/i })
      .or(page.getByRole('link', { name: /command center/i }));
    if (await sso.count()) {
      console.log('  homarr showed its own login; using the OIDC button');
      await sso.first().click();
      await page.waitForURL((u) => !u.toString().includes('/auth/login'), {
        timeout: 45000,
      }).catch(() => {});
    } else {
      console.log('  homarr showed its own login with no OIDC option');
    }
  }

  await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(6000);

  const frames = page.frames().filter((f) => f !== page.mainFrame());
  console.log(`\n${label} (${viewport.width}x${viewport.height})`);
  console.log(`  url: ${page.url()}`);
  console.log(`  iframes attached: ${frames.length}`);

  for (const f of frames) {
    // A refused or empty frame still counts as attached, so measure what it
    // actually painted rather than trusting its presence.
    const info = await f
      .evaluate(() => ({
        text: document.body ? document.body.innerText.trim().length : 0,
        nodes: document.body ? document.body.querySelectorAll('*').length : 0,
      }))
      .catch((e) => ({ error: String(e).slice(0, 80) }));
    const url = f.url();
    if (info.error) {
      console.log(`    BLOCKED ${url} (${info.error})`);
      failed++;
    } else if (info.nodes < 5) {
      console.log(`    BLANK   ${url} (${info.nodes} nodes)`);
      failed++;
    } else {
      console.log(`    ok      ${url} (${info.nodes} nodes, ${info.text} chars)`);
    }
  }
  if (frames.length === 0) {
    console.log('    no iframes found on the board');
    failed++;
  }

  const shot = `${outDir}/${label}.png`;
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  screenshot: ${shot}`);

  const relevant = badResponses.filter((r) => !r.includes('favicon'));
  if (relevant.length) {
    console.log(`  failed requests: ${relevant.length}`);
    relevant.slice(0, 8).forEach((r) => console.log(`    ${r}`));
  }
  if (consoleErrors.length) {
    console.log(`  console errors: ${consoleErrors.length}`);
    consoleErrors.slice(0, 5).forEach((e) => console.log(`    ${e.slice(0, 140)}`));
  }

  await ctx.close();
}

await browser.close();
console.log(failed ? `\n${failed} panel problem(s)` : '\nall panels rendered');
process.exit(failed ? 1 : 0);
