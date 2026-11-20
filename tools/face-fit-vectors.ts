// Regenerates ios/WireKit/Tests/WireKitTests/face-fit-vectors.json from the site's own code, so the Swift (and
// Android) port of the Watch 3 Pro memory budget, fitForWatch and the ETC2 encoder stays byte-equal to fmc.tools.
//   FMC_FRONTEND=~/Developer/pet/fcm-group/fmc_frontend bun tools/face-fit-vectors.ts > ios/WireKit/Tests/WireKitTests/face-fit-vectors.json
const root = process.env.FMC_FRONTEND ?? `${process.env.HOME}/Developer/pet/fcm-group/fmc_frontend`;
const fmt = `${root}/src/lib/modules/editor/core/format`;
const { buildBin, parseBin, assertFlashable } = await import(`${fmt}/bin.ts`);
const { decodedBytes, screenMemory, overBudget } = await import(`${fmt}/budget.ts`);
const { fitForWatch } = await import(`${fmt}/fit.ts`);
const { encodeEtc2 } = await import(`${fmt}/etc2.ts`);
const { encodePixels } = await import(`${fmt}/pixels-codec.ts`);

const toHex = (d: Uint8Array) => Buffer.from(d).toString("hex");

// a small LCG: the same "random" pixels every run
let seed = 1;
const rnd = () => ((seed = (Math.imul(seed, 1103515245) + 12345) >>> 0) >>> 16) & 0xff;
const pixels = (w: number, h: number, f: (x: number, y: number) => number[]) => {
  const px = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) px.set(f(x, y), (y * w + x) * 4);
  return px;
};
// an 8×8 tile repeated: every ETC2 block path gets work, and LZ4 keeps the file small
const tile = Array.from({ length: 64 }, () => [rnd(), rnd(), rnd()]);
const tiled = (w: number, h: number, alpha = 255) =>
  pixels(w, h, (x, y) => [...tile[(y & 7) * 8 + (x & 7)], alpha]);
const img = (cf: number, w: number, h: number, alpha = 255) => encodePixels(tiled(w, h, alpha), w, h, cf);
const jpeg = (w: number, h: number) => ({ cf: 1, w, h, data: new Uint8Array([0xff, 0xd8, 0xff, 0xd9]) });

const META = "00000000000000000000000000ff";
const struct = (images: number[], refType = 0x61, x = 10, y = -2) => ({ tag: 0x01, x, y, meta: META, refType, images });
const preview = (i: number) => ({ tag: 0x28, subs: [{ tag: 0x08, prefix: "0102030405", refType: 0x61, images: [i] }] });
const face = (screens: object[], resources: object[]) => buildBin({ name: "Fit", screens, resources });

const faces: [string, Uint8Array][] = [
  // cf 5 without a transparent pixel turns cf 4 on every model; the one with alpha stays
  ["drop_alpha", face(
    [
      { tag: 0x21, subs: [{ tag: 0x86, text: "Main" }, preview(0), { tag: 0x30, subs: [struct([1]), struct([2])] }] },
      { tag: 0x22, subs: [preview(0), { tag: 0x30, subs: [struct([3])] }] },
    ],
    [img(4, 30, 30), img(5, 21, 13), img(5, 9, 7, 128), img(14, 11, 5)],
  )],
  // over the budget: the largest shown images go ETC2 until it fits, the preview and a frame run's smaller
  // frames left alone, the first of two equal ones first
  ["compress", face(
    [
      {
        tag: 0x21,
        subs: [
          preview(0),
          { tag: 0x30, subs: [struct([1, 2, 3])] },
          ...[4, 5, 6, 7, 8, 9, 10, 11, 12, 13].map((i) => ({ tag: 0x30, subs: [struct([i])] })),
          { tag: 0x70, subs: [struct([14], 0x01), { tag: 0x05, flag: 1, pivotX: 233, pivotY: 233 }] },
          { tag: 0x40, hex: "0a0b0c" },
        ],
      },
      { tag: 0x22, subs: [preview(0), { tag: 0x30, subs: [struct([5])] }] },
    ],
    [
      img(4, 466, 466),
      img(24, 300, 300), img(24, 410, 410), img(24, 200, 200),
      img(24, 400, 400), img(24, 400, 400), img(24, 390, 390), img(24, 380, 380), img(24, 360, 360),
      img(24, 350, 350), img(24, 340, 340), img(24, 330, 330), img(24, 400, 400), img(5, 300, 300, 200),
      img(4, 20, 160),
    ],
  )],
  // JPEG stays JPEG: nothing to compress, still over
  ["too_heavy", face(
    [{ tag: 0x21, subs: [preview(0), ...Array.from({ length: 12 }, (_, i) => ({ tag: 0x30, subs: [struct([i + 1])] }))] }],
    [img(4, 40, 40), ...Array.from({ length: 12 }, () => jpeg(466, 466))],
  )],
];

const fit = (bin: Uint8Array, device: string | null) => {
  const out = fitForWatch(bin, device);
  let refuse: string | null = null;
  try { assertFlashable(out.bin, device); } catch (e) { refuse = (e as Error).message; }
  const over = device === "watch_3_pro" ? overBudget(parseBin(out.bin))[0] : undefined;
  return {
    hex: out.bin === bin ? null : toHex(out.bin), compressed: out.compressed, refuse,
    over: over ? { tag: over.screen.tag, bytes: over.bytes } : null,
  };
};

const etc2 = (name: string, w: number, h: number, f: (x: number, y: number) => number[]) => {
  const px = pixels(w, h, f);
  return { name, w, h, rgba: toHex(new Uint8Array(px.buffer)), blocks: toHex(encodeEtc2(px, w, h)) };
};

console.log(JSON.stringify({
  decoded: [1, 4, 5, 13, 14, 24, 28, 7].flatMap((cf) => [[7, 5], [466, 466]].map(([w, h]) => ({ cf, w, h, bytes: decodedBytes({ cf, w, h }) }))),
  etc2: [
    etc2("flat", 4, 4, () => [10, 200, 30, 255]),
    etc2("gradient", 9, 7, (x, y) => [x * 28, y * 36, 255 - x * 20, 255 - y * 30]),
    etc2("noise", 8, 8, () => [rnd(), rnd(), rnd(), rnd()]),
    etc2("two_tone", 8, 4, (x, y) => (x < 4 ? [255, 0, 0, y < 2 ? 0 : 255] : [0, 0, 255, 255])),
  ],
  faces: faces.map(([name, bin]) => {
    const f = parseBin(bin);
    return {
      name, hex: toHex(bin), rebuild: toHex(buildBin(parseBin(bin))),
      memory: f.screens.map((s: { tag: number }) => ({ tag: s.tag, ...screenMemory(s, f.resources) })),
      fit: { other: fit(bin, null), watch_3_pro: fit(bin, "watch_3_pro") },
    };
  }),
}, null, 1));
