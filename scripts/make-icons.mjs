// Génère les 15 icônes Android de l'app staff depuis le logo du client.
//
// Usage :
//   node scripts/make-icons.mjs <logo.png> [--bg "#111111"] [--out android/app/src/main/res]
//
//   <logo.png>  Logo du client, idéalement carré, PNG avec VRAIE transparence.
//               ⚠️ Vérifier l'alpha réel : un damier visible dans l'aperçu ne prouve
//               rien (piège connu des PNG exportés par des outils IA). Le script
//               refuse un logo sans canal alpha.
//   --bg        Couleur de fond de l'icône adaptive (défaut #111111, sombre).
//   --out       Dossier res/ de sortie (défaut : celui de ce repo).
//
// Produit, pour chaque densité (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) :
//   ic_launcher.png            48dp  (48/72/96/144/192)  logo sur fond plein
//   ic_launcher_round.png      48dp  idem, masque rond
//   ic_launcher_foreground.png 108dp (108/162/216/324/432) logo ~58% centré,
//                              fond transparent (zone de sécurité adaptive)
// + values/ic_launcher_background.xml avec la couleur choisie.

import path from "node:path";
import { fileURLToPath } from "node:url";
import { mkdir, writeFile } from "node:fs/promises";
import sharp from "sharp";

const args = process.argv.slice(2);
const logoPath = args.find((a) => !a.startsWith("--"));
const bg = args.includes("--bg") ? args[args.indexOf("--bg") + 1] : "#111111";
const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const outRes = args.includes("--out")
  ? path.resolve(args[args.indexOf("--out") + 1])
  : path.join(ROOT, "android", "app", "src", "main", "res");

if (!logoPath) {
  console.error("Usage : node scripts/make-icons.mjs <logo.png> [--bg \"#111111\"] [--out <res>]");
  process.exit(1);
}

const meta = await sharp(logoPath).metadata();
if (!meta.hasAlpha) {
  console.error(
    `ERREUR : ${logoPath} n'a PAS de canal alpha (fond opaque).\n` +
    "Un damier visible dans l'aperçu ne prouve rien — exporter un vrai PNG transparent\n" +
    "ou détourer le logo avant de générer les icônes.",
  );
  process.exit(1);
}
console.log(`Logo : ${logoPath} (${meta.width}x${meta.height}, alpha OK) · fond ${bg}`);

const DENSITIES = [
  { dir: "mipmap-mdpi", launcher: 48, foreground: 108 },
  { dir: "mipmap-hdpi", launcher: 72, foreground: 162 },
  { dir: "mipmap-xhdpi", launcher: 96, foreground: 216 },
  { dir: "mipmap-xxhdpi", launcher: 144, foreground: 324 },
  { dir: "mipmap-xxxhdpi", launcher: 192, foreground: 432 },
];

// Logo redimensionné à un ratio du canevas, centré, sur fond au choix.
async function compose(size, logoRatio, background) {
  const inner = Math.round(size * logoRatio);
  const logo = await sharp(logoPath)
    .resize(inner, inner, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
  return sharp({
    create: { width: size, height: size, channels: 4, background },
  })
    .composite([{ input: logo, gravity: "centre" }])
    .png();
}

// Masque rond (cercle plein) pour ic_launcher_round.
function roundMask(size) {
  return Buffer.from(
    `<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="#fff"/></svg>`,
  );
}

for (const d of DENSITIES) {
  const dir = path.join(outRes, d.dir);
  await mkdir(dir, { recursive: true });

  // Icône classique : logo à 84% sur fond plein.
  await (await compose(d.launcher, 0.84, bg)).toFile(path.join(dir, "ic_launcher.png"));

  // Icône ronde : même rendu + masque circulaire.
  const square = await (await compose(d.launcher, 0.72, bg)).toBuffer();
  await sharp(square)
    .composite([{ input: roundMask(d.launcher), blend: "dest-in" }])
    .png()
    .toFile(path.join(dir, "ic_launcher_round.png"));

  // Foreground adaptive : logo à 58% (zone de sécurité), fond TRANSPARENT.
  await (await compose(d.foreground, 0.58, { r: 0, g: 0, b: 0, alpha: 0 }))
    .toFile(path.join(dir, "ic_launcher_foreground.png"));

  console.log(`OK ${d.dir} (${d.launcher}px + foreground ${d.foreground}px)`);
}

// Couleur de fond de l'icône adaptive.
const valuesDir = path.join(outRes, "values");
await mkdir(valuesDir, { recursive: true });
await writeFile(
  path.join(valuesDir, "ic_launcher_background.xml"),
  `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">${bg}</color>\n</resources>\n`,
  "utf-8",
);
console.log(`OK values/ic_launcher_background.xml (${bg})`);
console.log("\n15 icônes générées. Rebuilder l'APK pour les voir sur la tablette.");
