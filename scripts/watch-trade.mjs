import { existsSync, writeFileSync } from 'node:fs';

const [url, tradeId, readyFile, deliveredFile, replayGate] = process.argv.slice(2);
const socket = new WebSocket(url);
let deliveries = 0;

const timer = setTimeout(() => {
  console.error(`Timed out waiting for two deliveries of ${tradeId}; received ${deliveries}`);
  process.exit(1);
}, 90_000);

socket.addEventListener('open', () => socket.send('BTC-NGN'));
socket.addEventListener('error', (event) => {
  console.error(event.message ?? 'WebSocket connection failed');
  process.exit(1);
});
socket.addEventListener('close', () => {
  if (!existsSync(replayGate) || deliveries < 2) process.exit(1);
});
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data);
  if (message.subscribed === 'BTC-NGN') writeFileSync(readyFile, '');
  if (message.tradeId !== tradeId) return;
  if (message.market !== 'BTC-NGN' || message.price !== 49_000_000 ||
      message.quantity !== 1 || !message.executedAt) {
    console.error('Unexpected trade tick', message);
    process.exit(1);
  }
  deliveries++;
  writeFileSync(deliveredFile, String(deliveries));
  if (deliveries >= 2 && existsSync(replayGate)) {
    clearTimeout(timer);
    socket.close();
  }
});
