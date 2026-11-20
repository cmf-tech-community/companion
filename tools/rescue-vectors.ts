// Regenerates ios/WireKit/Tests/WireKitTests/rescue-vectors.json from the site's own code: the frames Watch.authenticate,
// Watch.fetchInfo's time (0x8004, without which the watch ignores the reset), Watch.factoryReset (ble.ts) and the bind
// of Watch.pair write against a scripted watch, what that watch notifies, and the dial-list set sealed by the site's
// Codec. Keys, secret, randoms and the clock are synthetic. The Swift and Android RescuePlan must write these frames.
//   (cd ~/Developer/pet/fcm-group/fmc_frontend && bun $OLDPWD/tools/rescue-vectors.ts) > ios/WireKit/Tests/WireKitTests/rescue-vectors.json
// Run from the site's checkout: its tsconfig resolves $lib.
(globalThis as { window?: object }).window = {};
// pair() saves the authkey there
(globalThis as { localStorage?: object }).localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const log = console.log;
console.log = console.debug = () => {};
const root = process.env.FMC_FRONTEND ?? `${process.env.HOME}/Developer/pet/fcm-group/fmc_frontend`;
const { CMD, Codec, UUID, MARKER, cat, fromHex, sha256, toHex, utf8 } = await import(
  `${root}/src/lib/modules/device/lib/ble-protocol.ts`
);
const { Watch } = await import(`${root}/src/lib/modules/device/lib/ble.ts`);

// the clock fetchInfo's time reads: 2026-11-20 12:00:00 UTC, UTC+3
const NOW = 1795176000, TZ = 3 * 3600;
Date.now = () => NOW * 1000;
Date.prototype.getTimezoneOffset = () => -TZ / 60;
const k1 = fromHex("00112233445566778899aabbccddeeff");
const secret = fromHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
const random1 = fromHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
const random2 = fromHex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
const nonce = fromHex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf");

// one conversation: the watch answers each request the way the stock firmware does
async function run(start: (w: InstanceType<typeof Watch>) => Promise<void>, authKey: () => Uint8Array) {
  const watch = new Codec();
  const steps: { notify?: string; writes: string[] }[] = [{ writes: [] }];
  const w = new Watch();
  const reply = async (k: number, payload: Uint8Array, key: Uint8Array | null) => {
    await watch.setKey(key);
    for (const f of await watch.encode(k, payload)) {
      steps.push({ notify: toHex(f), writes: [] });
      await w.onNotify(UUID.cmdRead, f);
    }
  };
  const write = async (f: Uint8Array) => {
    steps[steps.length - 1].writes.push(toHex(f));
    const key = (f[9] << 8) | f[10];
    const k = authKey();
    const session = (await sha256(cat(nonce, k))).slice(0, 16);
    if (key === (CMD.authPairReq & 0xffff))
      setTimeout(async () => reply(CMD.authPairRep, cat(random2, await sha256(cat(random2, secret))), null));
    if (key === (CMD.authName & 0xffff)) setTimeout(() => reply(CMD.authMac, new Uint8Array([MARKER]), k));
    if (key === (CMD.authNonceReq & 0xffff)) setTimeout(() => reply(CMD.authNonceRep, nonce, k));
    if (key === (CMD.authConfirmReq & 0xffff)) setTimeout(() => reply(CMD.authConfirmRep, new Uint8Array([1]), session));
    if (key === (CMD.fwGet & 0xffff)) setTimeout(() => reply(CMD.fwRet, new Uint8Array([1, 0, 1, 77]), session));
    if (key === (CMD.serialGet & 0xffff) && f[4] === 0xde) setTimeout(() => reply(CMD.serialRet, new Uint8Array([0]), session));
  };
  w.chars = {
    [UUID.cmdWrite]: { writeValueWithoutResponse: write },
    [UUID.shellWrite]: {
      writeValue: async () =>
        setTimeout(() => w.onNotify(UUID.shellRead, utf8(`GETSECRET:${toHex(secret)},OK`))),
    },
  } as never;
  await start(w);
  await new Promise((r) => setTimeout(r, 20));
  return steps.filter((s) => s.notify || s.writes.length);
}

// authenticate with a saved authkey, then the reset
const known = await run(async (w) => {
  await w.authenticate(k1);
  await w.fetchInfo();
  await w.factoryReset();
}, () => k1);

// the bind with the pairing secret (pair() reads it from the shell first; the app has it saved), then the reset
const realRandom = crypto.getRandomValues.bind(crypto);
crypto.getRandomValues = (<T extends ArrayBufferView>(a: T) => {
  if (a instanceof Uint8Array && a.length === 16) a.set(random1);
  else realRandom(a);
  return a;
}) as typeof crypto.getRandomValues;
const bound = (await sha256(cat(random1, random2, secret))).slice(0, 16);
const bind = await run(async (w) => {
  await w.pair();
  await w.fetchInfo();
  await w.factoryReset();
}, () => bound);

// the dial-list set (StockSession.setDials payload), sealed with the session key of the known-key conversation
const session = (await sha256(cat(nonce, k1))).slice(0, 16);
const c = new Codec();
await c.setKey(session);
const ids = [273, 0xffffffff, 280];
const p = new Uint8Array(4 + ids.length * 4);
p.set([1, 0, ids.length, 0]);
ids.forEach((id, i) => new DataView(p.buffer).setUint32(4 + i * 4, id, true));
const keep = (await c.encode(CMD.wfList ?? ((0xffff << 16) | 0x9055), p)).map(toHex);

// one conversation by command: the frames the site wrote and the ones the watch notified
const NAMES: Record<number, string> = {
  0xffff8047: "pair", 0xffff8049: "authName", 0xffff804b: "nonce", 0xffff804d: "confirm", 0xffff8004: "time",
  0x009a0001: "factoryReset", 0xffff0048: "pairReply", 0xffff0049: "authMac", 0xffff004c: "nonceReply",
  0xffff0004: "confirmReply",
};
const nameOf = (f: string) => NAMES[parseInt(f.slice(6, 10) + f.slice(18, 22), 16)];
const byCmd = (steps: { notify?: string; writes: string[] }[]) => {
  const writes: Record<string, string> = {}, notifies: Record<string, string> = {};
  for (const st of steps) {
    for (const f of st.writes) { const n = nameOf(f); if (n) writes[n] = f; }
    if (st.notify) { const n = nameOf(st.notify); if (n) notifies[n] = st.notify; }
  }
  return { writes, notifies };
};

log(JSON.stringify({
  k1: toHex(k1), secret: toHex(secret), random1: toHex(random1), random2: toHex(random2), nonce: toHex(nonce),
  session: toHex(session), bound_k1: toHex(bound), now: NOW, tz_seconds: TZ,
  known: byCmd(known), bind: byCmd(bind),
  keep_dials: { ids, active: 0, frames: keep },
}, null, 1));
