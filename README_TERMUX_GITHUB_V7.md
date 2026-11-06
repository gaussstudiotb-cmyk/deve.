# DeVE v7 — compilation avec Termux et GitHub Actions

Cette version est préparée pour un flux de travail Android + Termux + GitHub.

## Compilation recommandée : GitHub Actions

1. Décompresser le projet et pousser le contenu du dossier `DeVE` à la racine de ton dépôt GitHub.
2. Vérifier que le dépôt contient `.github/workflows/build.yml`.
3. Faire un commit puis un push.
4. Dans GitHub, ouvrir l'onglet **Actions**, puis le workflow **Build DeVE APK**.
5. À la fin, ouvrir l'exécution réussie et télécharger l'artefact `deve-debug-apk`.
6. Décompresser l'artefact et installer `app-debug.apk` sur le téléphone.

Le workflow télécharge automatiquement le modèle français Vosk officiel, le place dans `app/src/main/assets/model-fr/`, puis compile l'APK. Le modèle n'est donc pas stocké dans Git et n'alourdit pas le dépôt.

## Compilation locale dans Termux

La compilation Android locale depuis Termux demande un JDK compatible, Gradle, Android SDK et les licences SDK correctement configurées. Pour éviter de configurer tout l'environnement Android sur le téléphone, GitHub Actions est le chemin recommandé.

Si l'environnement local est déjà prêt, depuis le dossier racine du projet :

```bash
gradle assembleDebug --no-daemon --stacktrace
```

## À savoir

- La reconnaissance vocale Vosk fonctionne localement après installation du modèle dans l'APK.
- Gemini et Claude nécessitent toujours Internet pour le raisonnement.
- Le workflow produit un APK `debug`, non signé pour une publication Play Store.
- Cette archive n'a pas été compilée dans l'environnement de préparation. Il faut vérifier le résultat réel dans GitHub Actions.
