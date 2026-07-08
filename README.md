# Brooklyn Food — App Staff (coquille APK)

Application Android **coquille** (Capacitor 8) qui affiche le site staff
(`https://brooklynfood.be/staff`) en plein écran et ajoute le natif :

- **Impression réseau ESC/POS** directe (TCP 9100, sans RawBT) via le pont
  `window.NativePrinter` — impression manuelle ET automatique à l'acceptation.
- **Notifications sonores natives** (alarme même tablette verrouillée), service en
  arrière-plan avec redémarrage automatique après coupure de courant.
- **Écran toujours allumé**, icône du client.

Version en service : **v1.3** (release de secours : v1.0).
Distribution : `https://brooklynfood.be/staff-app.apk` (pas les liens GitHub —
ils bouclent sur la tablette).

## Règle d'or

- Changement d'**interface / logique / contenu du ticket** → côté **site web**
  (déploiement Vercel, aucun nouvel APK).
- Changement **natif** (Java, icône, permissions) → nouvel APK → réinstallation
  sur la tablette.

## Build (100 % cloud)

Chaque `push` sur `main` déclenche `.github/workflows/build-apk.yml`
(**Node 22 + JDK 21**, ne pas changer) → l'APK est dans les **Artifacts** du run.
Aucun outil Android n'est nécessaire en local.

## Dupliquer pour un nouveau client

👉 **Procédure complète : [`docs/DUPLIQUER-POUR-UN-CLIENT.md`](docs/DUPLIQUER-POUR-UN-CLIENT.md)**
(prérequis côté site web, liste exhaustive des valeurs à changer, icônes via
`scripts/make-icons.mjs`, release, installation, tests).

## Contexte Claude

Skills : `apk-staff-release` (procédure build/release/pièges Android) et
`restaurant-apps-method` (méthode générale multi-clients).
