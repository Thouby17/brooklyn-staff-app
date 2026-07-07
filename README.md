# Brooklyn Food — App Staff (coquille APK)

Application Android **coquille** (Capacitor) qui affiche le site staff
(`https://brooklynfood.be/staff`) comme une vraie app plein écran, et servira
de base à l'impression native des tickets (à venir).

## Comment obtenir l'APK (build cloud, rien à installer)

1. Le workflow **GitHub Actions** (`.github/workflows/build-apk.yml`) fabrique
   l'APK à chaque `push` sur `main` (ou via le bouton **Run workflow**).
2. Onglet **Actions** du dépôt → dernier run → section **Artifacts** →
   télécharger **`brooklyn-food-staff-apk`** (fichier `app-debug.apk`).
3. Copier l'APK sur la tablette → l'ouvrir → autoriser « sources inconnues » →
   installer.

## Configuration

- URL du site staff : `capacitor.config.json` → `server.url`.
- Pour un autre restaurant : dupliquer, changer `appId`, `appName` et l'URL.

## Étapes suivantes (impression)

- [ ] Milestone 1 : l'app ouvre le site staff en plein écran ✅ (cet APK)
- [ ] Milestone 2 : module d'impression réseau (ESC/POS TCP 9100)
- [ ] Milestone 3 : impression automatique à la réception d'une commande
