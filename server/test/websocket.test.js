'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { GameServer } = require('../src/server');

function upgrade(port, requestPath) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1');
    const key = crypto.randomBytes(16).toString('base64');
    let data = '';
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('upgrade timeout')); }, 3000);
    socket.on('connect', () => socket.write(
      'GET ' + requestPath + ' HTTP/1.1\r\n' +
      'Host: 127.0.0.1:' + port + '\r\n' +
      'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
      'Sec-WebSocket-Key: ' + key + '\r\nSec-WebSocket-Version: 13\r\n\r\n'
    ));
    socket.on('data', chunk => {
      data += chunk.toString('latin1');
      if (data.includes('\r\n\r\n')) {
        clearTimeout(timer);
        const expected = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
        socket.destroy();
        resolve({ headers: data, expected });
      }
    });
    socket.on('error', e => { clearTimeout(timer); reject(e); });
  });
}

test('WebSocket /ws endpoint performs a valid RFC6455 101 upgrade', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'avyansh-ws-'));
  const server = new GameServer({ port: 0, httpPort: 0, host: '127.0.0.1', dataDir: dir, log: false });
  try {
    await server.listen();
    const port = server.httpServer.address().port;
    const ok = await upgrade(port, '/ws');
    assert.match(ok.headers, /^HTTP\/1\.1 101 Switching Protocols/m);
    assert.match(ok.headers, new RegExp('Sec-WebSocket-Accept: ' + ok.expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
    const bad = await upgrade(port, '/wrong-path');
    assert.match(bad.headers, /^HTTP\/1\.1 404 Not Found/m);
  } finally {
    await server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
