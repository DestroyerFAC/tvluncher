# Installation sur le Player TV de Free

Testé sur un Player Free en mode débogage. Les commandes valent pour n'importe
quelle box Android TV.

## 1. Activer le débogage

Sur la box :

1. **Paramètres → Préférences relatives à l'appareil → À propos**
2. Appuyez **7 fois** sur *Version de build* → « Vous êtes désormais développeur »
3. Retour → **Options pour les développeurs** → activez **Débogage USB**
   (et **Débogage réseau** / *ADB over network* si vous voulez travailler en Wi-Fi)
4. Notez l'adresse IP dans **Paramètres → Réseau et Internet**

## 2. Connecter ADB

Depuis votre ordinateur (platform-tools Android installés) :

```bash
adb connect 192.168.1.42:5555
```

La box affiche une demande d'autorisation : acceptez, et cochez
« Toujours autoriser ». Vérifiez :

```bash
adb devices
# 192.168.1.42:5555   device
```

## 3. Obtenir l'APK

**Via GitHub Actions** : onglet *Actions* → dernier run *Build APK* → artefact
`tvluncher-apk`. Décompressez, vous obtenez `app-release.apk`.

**En local**, avec le SDK Android :

```bash
./gradlew assembleRelease
```

> Le build de *release* est signé avec la clé de debug par défaut, ce qui suffit
> pour un usage personnel. Pour une clé stable (afin que les mises à jour
> s'installent par-dessus), passez `SIDELOAD_STORE_FILE`, `SIDELOAD_STORE_PASSWORD`,
> `SIDELOAD_KEY_ALIAS` et `SIDELOAD_KEY_PASSWORD` à Gradle.

## 4. Installer

```bash
./scripts/install.sh 192.168.1.42
```

Ou manuellement :

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 5. Définir comme écran d'accueil

Le script s'en charge. Manuellement, dans l'ordre de préférence :

```bash
# a. Directement (fonctionne sur la plupart des ROM Android TV)
adb shell cmd package set-home-activity \
  fr.tvluncher.launcher/fr.tvluncher.launcher.ui.HomeActivity

# b. Sinon : effacer le lanceur par défaut, puis appuyer sur HOME
#    et choisir TV Luncher dans la liste
adb shell pm clear-package-preferred-activities com.google.android.tvlauncher
```

Vous pouvez aussi ouvrir TV Luncher depuis la liste des applications, puis
**Réglages → Définir comme écran d'accueil**.

Vérifier ce qui est actif :

```bash
adb shell cmd package resolve-activity --brief \
  -c android.intent.category.HOME -a android.intent.action.MAIN
```

## 6. Supprimer les bandeaux sponsorisés

**Uniquement après avoir vérifié que TV Luncher fonctionne comme accueil.**

```bash
./scripts/tv-tune.sh --list     # état actuel
./scripts/tv-tune.sh            # désactiver
```

Le script désactive :

| Paquet | Rôle |
|---|---|
| `com.google.android.tvlauncher` | accueil Android TV (rangées sponsorisées) |
| `com.google.android.apps.tv.launcherx` | accueil Google TV |
| `com.google.android.tvrecommendations` | moteur qui alimente ces rangées |

`pm disable-user` **masque** un paquet pour l'utilisateur courant ; il ne le
désinstalle pas. Tout revient avec :

```bash
./scripts/tv-tune.sh --revert
```

## 7. Écran de veille

**Paramètres Android → Préférences relatives à l'appareil → Économiseur d'écran**
→ choisissez *TV Luncher*. Raccourci depuis **Réglages → Écran de veille**.

## En cas de problème

**L'écran reste noir après HOME.** Le lanceur d'origine a été désactivé sans que
le nouveau soit actif. ADB fonctionne toujours :

```bash
adb shell pm enable com.google.android.tvlauncher
# ou
./scripts/tv-tune.sh --revert
```

**Impossible de se connecter en ADB.** Rebranchez en USB, ou redémarrez la box
puis `adb connect`. Le débogage réseau se désactive parfois au redémarrage.

**« App not installed » à l'installation.** Une version signée avec une autre clé
est déjà présente :

```bash
adb uninstall fr.tvluncher.launcher
```

**Tout remettre à zéro.** Une réinitialisation d'usine de la box annule tout ce
qui est décrit ici.
