// Run the pinned, external MIT reference without installing its package.
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const [root, output] = process.argv.slice(2);
if (!root || !output) throw new Error("Usage: node reference_np3.mjs reference-root output-dir");
const { deserialize } = await import(pathToFileURL(path.resolve(root, "src/deserialize.ts")));
const { serialize } = await import(pathToFileURL(path.resolve(root, "src/serialize.ts")));
fs.mkdirSync(output, { recursive: true });
const results = [];
for (const name of fs.readdirSync(path.join(root, "samples")).filter(n => n.endsWith(".NP3")).sort()) {
  const file = path.resolve(root, "samples", name);
  results.push({ file, kind: "published-sample", expected: deserialize(fs.readFileSync(file)) });
}
for (let i = 0; i < 96; i++) {
  const tone = i % 3 === 0 ? {
    raw: Array.from({ length: 257 }, (_, x) => Math.round(32767 * (x / 256) ** (1 + i / 48))),
    points: [{ x: 0, y: 0 }, { x: 127, y: 64 }, { x: 255, y: 255 }],
  } : undefined;
  const controls = {
    name: `probe-${i}`, sharpning: -3 + (i % 49) / 4,
    midRangeSharpning: -5 + (i % 41) / 4, clarity: 5 - (i % 41) / 4,
    contrast: (i * 19) % 201 - 100, highlights: (i * 31) % 201 - 100,
    shadows: (i * 47) % 201 - 100, whiteLevel: i - 48, blackLevel: 48 - i,
    saturation: (i * 71) % 201 - 100,
    colorBlender: Object.fromEntries(["red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta"].map((key, n) =>
      [key, { hue: (i * 17 + n * 29) % 201 - 100, chroma: (i * 13 + n * 7) % 201 - 100, brightness: (i * 11 + n * 43) % 201 - 100 }])),
    colorGrading: {
      highlights: { hue: (i * 41) % 361, chroma: i - 48, brightness: 48 - i },
      midTone: { hue: (i * 61) % 361, chroma: 48 - i, brightness: i - 48 },
      shadows: { hue: (i * 89) % 361, chroma: i - 48, brightness: 48 - i },
      blending: i % 101, balance: 48 - i,
    },
    comment: i % 2 ? `probe\u4e2d\u6587\u{1f3ac}-${i}` : undefined, toneCurve: tone,
  };
  const bytes = serialize(controls);
  const file = path.resolve(output, `generated-${i}.NP3`);
  fs.writeFileSync(file, bytes);
  results.push({ file, kind: "reference-generated", expected: deserialize(bytes) });
}
fs.writeFileSync(path.join(output, "expected.json"), JSON.stringify(results, null, 2) + "\n");
console.log(JSON.stringify({ published: results.length - 96, generated: 96 }));
