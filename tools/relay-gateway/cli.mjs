// SPDX-License-Identifier: MIT
import { loadConfig } from './config.mjs';
import { createGateway } from './gateway.mjs';

async function main() {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--config') throw new Error('Usage: node cli.mjs --config <configuration.json>');
  const gateway = createGateway(await loadConfig(args[1]), {
    onDiagnostic: event => process.stdout.write(JSON.stringify({ time: new Date().toISOString(), ...event }) + '\n'),
  });
  await gateway.listen();
  process.stdout.write('Relay gateway listening on 127.0.0.1:37841\n');
  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    await gateway.close();
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}

main().catch(() => {
  // Do not emit configuration contents, request headers, local paths or credentials.
  process.stderr.write('Relay gateway could not start. Check the local configuration and loopback port.\n');
  process.exitCode = 1;
});
