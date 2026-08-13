/*
 * Cake Delight - Web UI LOCAL DEVELOPMENT server.
 *
 * THIS IS A LOCAL DEVELOPMENT CONVENIENCE ONLY. It is not part of the deployment path.
 * `nginx.conf` plus `Dockerfile` remain the real way the Web UI ships: as an nginx
 * container in `docker-compose.yml` and as a Deployment plus Service in `k8s/`.
 *
 * It exists so the UI can be run when no Docker engine is available, which makes the
 * nginx container unreachable. This script replicates the two things nginx does, using
 * nothing but Node built-ins:
 *
 *   1. serve the static client from this directory
 *   2. reverse-proxy /api/ to the API Gateway with the path forwarded UNCHANGED
 *
 * The proxy is the whole point. `app.js` calls `const API = '/api'`, i.e. same-origin
 * relative paths, and the project deliberately has no CORS configuration anywhere. A
 * plain static file server would put the browser on a different origin from the gateway
 * and every call would fail preflight.
 *
 * It is intentionally NOT referenced from the Dockerfile or docker-compose.yml.
 *
 * Usage:
 *   node web-ui/dev-server.js
 *
 * Environment:
 *   PORT        listen port for the UI            (default 8090, matches compose)
 *   API_TARGET  API Gateway base URL              (default http://localhost:8080)
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = Number(process.env.PORT) || 8090;
const API_TARGET = process.env.API_TARGET || 'http://localhost:8080';

const ROOT = __dirname;
const target = new url.URL(API_TARGET);

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg'
};

function log(method, reqPath, outcome) {
  const stamp = new Date().toISOString().slice(11, 23);
  console.log(`${stamp}  ${method.padEnd(6)} ${reqPath.padEnd(44)} ${outcome}`);
}

/**
 * Shared project error shape: { code, message, timestamp, path }.
 */
function sendError(res, status, code, message, reqPath) {
  const body = JSON.stringify({
    code,
    message,
    timestamp: new Date().toISOString(),
    path: reqPath
  });
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

/**
 * Reverse-proxy to the gateway. Method, headers, body, and path all go through
 * unchanged - the gateway strips no prefix, so the path a service sees is the path the
 * browser sent.
 */
function proxy(req, res, reqPath) {
  const upstream = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 80,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: target.host }
    },
    (upstreamRes) => {
      log(req.method, reqPath, `-> gateway ${upstreamRes.statusCode}`);
      res.writeHead(upstreamRes.statusCode, upstreamRes.headers);
      upstreamRes.pipe(res);
    }
  );

  // ECONNREFUSED when the gateway is not up yet. Answer 502 and stay alive.
  upstream.on('error', (err) => {
    log(req.method, reqPath, `-> 502 gateway unreachable (${err.code || err.message})`);
    if (!res.headersSent) {
      sendError(
        res,
        502,
        'GATEWAY_UNREACHABLE',
        `Could not reach the API Gateway at ${API_TARGET}. Is it running on port ${target.port || 80}?`,
        reqPath
      );
    } else {
      res.destroy();
    }
  });

  req.on('error', () => upstream.destroy());
  req.pipe(upstream);
}

function serveStatic(req, res, reqPath) {
  const relative = reqPath === '/' ? 'index.html' : decodeURIComponent(reqPath).replace(/^\/+/, '');
  const resolved = path.resolve(ROOT, relative);

  // Path traversal guard: anything that escapes web-ui/ is refused.
  if (resolved !== ROOT && !resolved.startsWith(ROOT + path.sep)) {
    log(req.method, reqPath, '-> 403 outside web-ui/');
    sendError(res, 403, 'FORBIDDEN', 'Path escapes the web-ui directory.', reqPath);
    return;
  }

  fs.readFile(resolved, (err, data) => {
    if (err) {
      log(req.method, reqPath, `-> 404 ${path.basename(resolved)} not found`);
      sendError(res, 404, 'NOT_FOUND', `No static file at ${reqPath}`, reqPath);
      return;
    }
    const type = CONTENT_TYPES[path.extname(resolved).toLowerCase()] || 'application/octet-stream';
    log(req.method, reqPath, `-> 200 ${path.basename(resolved)}`);
    res.writeHead(200, {
      'Content-Type': type,
      'Content-Length': data.length,
      'X-Content-Type-Options': 'nosniff',
      'Cache-Control': 'no-store'
    });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const reqPath = req.url.split('?')[0];

  if (reqPath === '/api' || reqPath.startsWith('/api/')) {
    proxy(req, res, reqPath);
  } else {
    serveStatic(req, res, reqPath);
  }
});

server.on('clientError', (err, socket) => {
  if (socket.writable) {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
  }
});

server.listen(PORT, () => {
  console.log('');
  console.log('  Cake Delight - Web UI (local development server, no Docker / no nginx)');
  console.log('  ---------------------------------------------------------------------');
  console.log(`  UI            http://localhost:${PORT}`);
  console.log(`  Proxying      /api/*  ->  ${API_TARGET}  (path forwarded unchanged)`);
  console.log(`  Serving from  ${ROOT}`);
  console.log('');
  console.log('  The API Gateway must be running for /api/ calls to succeed.');
  console.log('  Stop with Ctrl+C.');
  console.log('');
});
