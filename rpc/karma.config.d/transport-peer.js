const peerUrl = require('fs').readFileSync(require('path').resolve(config.basePath, '../../../transport-peer-url.txt'), 'utf8').trim();
config.set({ client: Object.assign({}, config.client, {
    args: [peerUrl], mocha: Object.assign({}, config.client && config.client.mocha, { timeout: 10000 })
}) });
