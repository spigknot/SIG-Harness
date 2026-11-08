import { appendFileSync } from 'node:fs';

/** Host half of the SIG Harness restart launcher: the restart button and a diagnostic sink. */
export const name = 'dsh-web-sig-restart';

export function apply(ctx) {
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'exact',
      path: '/api/sig/diag',
      handler: async (req, res) => {
        if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
        let body = '';
        req.setEncoding('utf8');
        req.on('data', (chunk) => { if (body.length < 200000) body += chunk; });
        req.on('end', () => {
          try { appendFileSync((process.env.HOME ?? '.') + '/.sig-harness/ui-diag.log', new Date().toISOString() + ' ' + body + '\n'); } catch { /* disco cheio */ }
          res.writeHead(200, { 'content-type': 'text/plain' });
          res.end('ok');
        });
      }
    }));
  });
}
