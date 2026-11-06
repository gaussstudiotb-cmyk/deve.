# DeVE — assistant vocal Android

Dis « DeVE, appelle Koffi », « DeVE, ouvre WhatsApp », « DeVE, enregistre une minute »,
« DeVE, quitte l'appli », « DeVE, réveille-moi à 6h30 ».

## Obtenir l'APK sans ordinateur (Termux + GitHub)

1. Installe **Termux** (depuis F-Droid ou GitHub, pas le Play Store) et crée un dépôt vide sur github.com (ex. `deve`).
2. Crée un jeton : github.com/settings/tokens → *Generate new token (classic)* → coche **repo** et **workflow**.
3. Dans Termux :

```
pkg install -y git unzip
termux-setup-storage
cd ~ && unzip -o ~/storage/downloads/DeVE-android.zip && cd DeVE
git init && git add . && git commit -m "DeVE"
git branch -M main
git remote add origin https://github.com/TON_PSEUDO/deve.git
git push -u origin main
```
(identifiant = ton pseudo, mot de passe = le jeton)

4. Sur GitHub, onglet **Actions** : la compilation démarre toute seule (~5 min).
5. Onglet **Releases** : télécharge `app-debug.apk` et installe-le.

## Premier lancement

Ouvre DeVE et fais les étapes 1 à 4, saisis ta clé API (Gemini gratuite : aistudio.google.com/apikey), puis « Démarrer DeVE ».
La clé reste sur ton téléphone, jamais dans le dépôt.

## Limites connues

- L'écoute permanente passe par la reconnaissance vocale Google : elle consomme de la batterie et peut émettre un petit bip à chaque cycle selon le téléphone.
- « Quitter » renvoie à l'écran d'accueil (Android interdit de tuer une autre appli).
- Les enregistrements vont dans Musique/DeVE.
