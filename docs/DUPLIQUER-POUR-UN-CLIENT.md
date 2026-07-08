# 📱 Créer l'APK staff d'un nouveau client — procédure complète

> **Pour Claude** : ce document est la référence pour « fais l'APK staff du client X ».
> Le suivre dans l'ordre. Charger AUSSI les skills `apk-staff-release` et
> `restaurant-apps-method`. Ce repo (brooklyn-staff-app) est le **modèle** à dupliquer.
> Vérifié conforme au code réel le 2026-07-08.

## Vue d'ensemble — 2 volets indissociables

L'app staff d'un client = **une coquille Android** (copie de ce repo) qui affiche
`https://<domaine-client>/staff` **+ le support natif côté site web** du client.
L'APK seul ne suffit PAS : sans le volet web, l'app s'installe mais n'a ni bouton
Imprimante, ni impression auto, ni journal d'appareils.

| Volet | Où | Contenu |
|---|---|---|
| A — Web | Dépôt web du client (`C:\Users\thoub\<Client> Web App`) | détection du pont natif, ESC/POS, réglages imprimante, impression auto, logs appareils |
| B — Coquille | Nouveau repo `<client>-staff-app` (copie de celui-ci) | WebView plein écran, pont d'impression TCP, notifications natives, icône client |

**Fonctionnalités attendues de l'APK fini** (parité avec Brooklyn v1.3) :
plein écran + écran toujours allumé · impression réseau ESC/POS directe (port 9100,
sans RawBT) · impression AUTOMATIQUE du ticket à l'acceptation · notifications
sonores (alarme) même tablette verrouillée, avec redémarrage auto après coupure de
courant · icône = logo du client · journal technique consultable à distance
(Admin → Appareils).

---

## Volet A — Prérequis côté site web du client

⚠️ **État au 2026-07-08 : seul Brooklyn a ce support.** Pour un autre client, porter
d'abord ces éléments (idéalement Brooklyn → Template, puis Template → client, selon
les règles de propagation de `restaurant-apps-method` : conversion `neutral→stone`
pour Merguez/Krusty, fins de ligne CRLF, StaffBoard Merguez sur-mesure à éditer à
la main).

**Fichiers à porter depuis `Web App Brooklyn Food`** :

| Fichier | Rôle |
|---|---|
| `src/lib/nativePrint.ts` | détection du pont, IP imprimante (localStorage), impression auto on/off, erreurs en français |
| `src/lib/escpos.ts` | ticket ESC/POS (CP1252, 48 colonnes, coupe `GS V 66 0`, émojis retirés) |
| `src/lib/ticketData.ts` | conversion commande → ticket (source unique manuel + auto) |
| `src/lib/deviceLog.ts` | journal d'appareils (id tablette, envoi fire-and-forget) |
| `src/components/PrinterSettings.tsx` | écran « 🖨 Imprimante » (IP + test + case impression auto) |
| `src/app/api/device-log/route.ts` | réception des logs (purge auto 14 j) |
| `src/app/admin/appareils/page.tsx` | page Admin → Appareils (en ligne/hors ligne, dernière erreur) |

**Modifications à reporter dans les fichiers existants** :
- `StaffBoard.tsx` : impression auto après accept + battement de cœur 10 min + bouton PrinterSettings
- `StaffOrderCard.tsx` : bouton 🖨 via `orderToTicketData`
- `AlertsControl.tsx` : si `isNativePrinter()` → état « Alertes activées » direct (le push web ne marche pas en WebView)
- `middleware.ts` : protéger `/api/device-log` (groupe staff)
- `AdminNav.tsx` : onglet « Appareils » (FR/NL/EN)
- `prisma/schema.prisma` : modèle `DeviceLog` (**additif**, `db push` en vérifiant l'hôte Neon)
- i18n : clés FR/NL/EN des nouveaux écrans

**Validation (avant de toucher à l'APK)** : en preview, injecter un faux pont
`window.NativePrinter = { print: (h,p,b64) => { window.__cap={h,p,b64}; return "ok"; } }`,
vérifier bouton Imprimante, ticket capturé (octets CP1252), cas d'erreur (`error:...`),
puis `npx tsc --noEmit`. Détail des protocoles : skill `restaurant-apps-method`.

---

## Volet B — Créer la coquille du client

### B1. Copier ce repo (sans l'historique ni les builds)

```powershell
robocopy "C:\Users\thoub\brooklyn-staff-app" "C:\Users\thoub\<client>-staff-app" /E /XD node_modules .git .gradle build
cd "C:\Users\thoub\<client>-staff-app"
npm install
```

### B2. Adapter les valeurs client — LA liste exhaustive

Choisir : **appId** `be.<domainesansbe>.staff` (ex. `be.krustysquad.staff`),
**appName** `<Nom> Staff`, **domaine** `https://<domaine-client>`.

| # | Fichier | Quoi changer |
|---|---|---|
| 1 | `capacitor.config.json` (racine) | `appId`, `appName`, `server.url` = `https://<domaine>/staff` |
| 2 | `android/app/build.gradle` | `namespace` et `applicationId` = appId ; laisser `versionCode 1` / `versionName "1.0"` |
| 3 | `android/app/src/main/res/values/strings.xml` | `app_name`, `title_activity_main`, `package_name`, `custom_url_scheme` |
| 4 | Dossier Java `android/app/src/main/java/be/brooklynfood/staff/` | **déplacer** vers `java/be/<client>/staff/` et changer la ligne `package be.brooklynfood.staff;` dans les 3 fichiers (`MainActivity.java`, `OrderWatchService.java`, `BootReceiver.java`) |
| 5 | `OrderWatchService.java` | `BASE_URL = "https://<domaine>"` + `setContentTitle("<Nom> Staff")` |
| 6 | `www/index.html` | `<title><Nom> Staff</title>` |
| 7 | `package.json` | `name: "<client>-staff-app"` (cosmétique) |
| 8 | `.github/workflows/build-apk.yml` | `name: <client>-staff-apk` de l'artifact (cosmétique) |

> ℹ️ `android/app/src/main/assets/capacitor.config.json` et `assets/public/index.html`
> sont des COPIES régénérées par `npx cap sync android` (le CI le fait à chaque build) —
> la source de vérité est la racine. Le manifest utilise des noms relatifs
> (`.MainActivity`) : rien à y changer.

**Vérification** : `grep -riE "brooklyn" --include="*.java" --include="*.json" --include="*.xml" --include="*.html" .`
(hors node_modules/build) ne doit plus rien retourner.

### B3. Icônes = logo du client (script fourni, testé)

```bash
node scripts/make-icons.mjs "C:\Users\thoub\<Client> Web App\public\icon-app.png" --bg "#111111"
```
Génère les 15 PNG mipmap + la couleur de fond adaptive. Le script **refuse un logo
sans vraie transparence** (piège des PNG IA au damier peint) — dans ce cas, détourer
d'abord. `--bg` : adapter à la charte du client si son logo ne ressort pas sur sombre.

`res/raw/alarm.wav` : **ne pas changer** (même alarme pour tous — choix utilisateur).

### B4. Créer le repo GitHub, copier la clé de signature, pousser

```bash
git init && git add -A && git commit -m "Coquille APK staff <Client> (base brooklyn-staff-app v1.4)"
gh repo create Thouby17/<client>-staff-app --public --source . --push
```
(Public comme brooklyn-staff-app : builds Actions illimités.)

**⚠️ Obligatoire — copier les 3 secrets de signature sur le nouveau repo** (sinon le
build échoue) : la clé permanente vit dans `C:\Users\thoub\APK-SIGNING-KEY\`
(UNE clé pour tous les clients — voir son LISEZMOI) :

```bash
cd C:/Users/thoub/APK-SIGNING-KEY
openssl base64 -A -in staff-apps-release.p12 | gh secret set KEYSTORE_BASE64 --repo Thouby17/<client>-staff-app
gh secret set KEYSTORE_PASSWORD --repo Thouby17/<client>-staff-app --body "$(cat .password.txt)"
gh secret set KEY_ALIAS --repo Thouby17/<client>-staff-app --body "staffapps"
```

### B5. Fichier de version pour la mise à jour in-app

Depuis la v1.4, l'app vérifie au lancement `https://<domaine>/staff-app-version.json`
et propose « Mettre à jour » si une version plus récente est publiée. Dans le dépôt
**web** du client, créer `public/staff-app-version.json` :

```json
{ "versionCode": 2, "versionName": "1.4", "url": "https://<domaine>/staff-app.apk" }
```

**Règle absolue** : à chaque nouvelle version, committer l'APK (`public/staff-app.apk`)
ET ce JSON (versionCode incrémenté, aligné sur build.gradle) **dans le même commit** —
jamais le JSON avant l'APK, sinon les tablettes proposeraient une mise à jour vers
l'ancien fichier.

---

## Volet C — Build, release, installation

1. **Build cloud** (aucun outil Android en local) : le push déclenche
   `.github/workflows/build-apk.yml` (Node 22 + JDK 21, ne pas toucher — il signe
   avec la clé permanente via les secrets, voir B4).
   Suivre : `gh run watch <id> --repo Thouby17/<client>-staff-app --exit-status`
   → récupérer : `gh run download <id> -D <dossier>` → `app-release.apk` (signé).
2. **Release** : `gh release create v1.4` (titre « ⭐ v1.4 — ... », notes FR).
   Toujours garder UNE release de secours connue-bonne ; supprimer les versions
   intermédiaires jamais installées. À chaque nouvelle version : **incrémenter
   `versionCode`** dans build.gradle ET dans `staff-app-version.json` (voir B5) —
   c'est lui qui déclenche la proposition de mise à jour sur les tablettes.
3. **Distribution = le site du client, PAS GitHub** (les liens GitHub bouclent sur
   certaines tablettes) : copier l'APK dans `<dépôt web client>/public/staff-app.apk`,
   commit + push, vérifier le déploiement **READY** sur Vercel. URL stable :
   `https://<domaine>/staff-app.apk`. **À refaire à CHAQUE version.**
4. **Installation tablette** : ouvrir l'URL → « Ouvrir avec » → **« Programme
   installation kit »** (PAS RawBT) → autoriser sources inconnues → installer →
   autoriser les **notifications** au 1er lancement → se connecter au staff,
   sélectionner la cuisine → bouton 🖨 Imprimante → saisir l'IP (ticket d'état de
   l'imprimante) → Tester.
5. **Tests à la maison AVANT la visite** (sans imprimante) :
   - Imprimante → IP bidon `1.2.3.4` → Tester → « Échec : imprimante injoignable… »
     en ~4-8 s = toute la chaîne native validée.
   - Volume à fond, écran verrouillé → commande test depuis un autre appareil →
     alarme ≤ 30 s → **Refuser la commande test** (nettoyage).
   - App ouverte 3-4 min sans toucher → l'écran reste allumé.
6. **Traçabilité** : fiche de visite imprimable sur le Bureau (modèle :
   `FICHE-VISITE-BROOKLYN-IMPRESSION.html`), mémoire projet, et registre ci-dessous.

---

## Registre des coquilles clients

| Client | Repo | appId | APK | Version | Statut |
|---|---|---|---|---|---|
| Brooklyn Food | `Thouby17/brooklyn-staff-app` | `be.brooklynfood.staff` | brooklynfood.be/staff-app.apk | v1.4 | ✅ (secours : v1.3 ; migration v1.3→v1.4 = désinstaller une fois, signature changée) |
| La Merguez Du Chef | — | — | — | — | à créer (volet A à porter d'abord) |
| Krusty Squad | — | — | — | — | à créer (volet A à porter d'abord) |
| Sun Set Burger | — | — | — | — | à créer (volet A à porter d'abord) |

*Mettre ce tableau à jour à chaque nouvelle coquille ou version.*

---

## Pièges connus (résumé — détail dans le skill `apk-staff-release`)

- Canaux de notification **immuables** : changer le son = renommer l'id (`orders_v1` → `orders_v2`).
- Le `reload()` de la WebView à chaque `onCreate` est **obligatoire** (sinon pas de pont JS).
- Réseau natif : thread dédié + timeout (StrictMode constructeurs).
- Coupe ticket : `GS V 66 0`, jamais `GS V 0`.
- Après un déploiement web : **fermer/rouvrir l'app** sur la tablette (cache).
- Android 13+ : permission `POST_NOTIFICATIONS` demandée au lancement (déjà codé).
- Le service lit le cookie staff via `CookieManager` — l'utilisateur doit être
  connecté dans l'app pour que les notifications marchent.
