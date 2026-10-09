// Node-only test peer imports sit behind platform checks and never execute in Chrome.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { http: false, buffer: false });
