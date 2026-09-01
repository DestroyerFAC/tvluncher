# TV Luncher

Un lanceur (launcher) Android TV minimaliste, pensé pour le **Player TV de Free**,
dans l'esprit de l'interface Apple TV : un fond d'écran, l'heure, une rangée
d'applications, et rien d'autre.

Aucune publicité, aucun bandeau sponsorisé, aucune analyse, aucun traceur.

## Ce que ça fait

- **Une rangée de favoris** en plein écran, tuiles 16:9 qui utilisent la bannière
  officielle de chaque application TV. La tuile sélectionnée grossit, se soulève
  et reste centrée pendant que la rangée défile dessous.
- **Toutes les applications**, y compris celles installées manuellement qui ne
  déclarent que `CATEGORY_LAUNCHER` et restent donc invisibles dans le lanceur
  d'origine.
- **Bandeau heure / date / météo** optionnel, façon Apple TV.
- **Fonds d'écran vectoriels** intégrés (ou votre propre image).
- **Écran de veille** ambiant : le fond d'écran qui dérive lentement, avec l'heure.
- **Gestion des applications** : favoris, masquage, infos système, désinstallation.

## Installation

Le dépôt ne contient pas d'APK. Deux façons d'en obtenir un :

**A. Via GitHub Actions (aucun outil à installer).** Chaque push déclenche le
workflow *Build APK* ; téléchargez l'artefact `tvluncher-apk` depuis l'onglet
Actions.

**B. En local**, avec le SDK Android (ou Android Studio) :

```bash
./gradlew assembleRelease
```

Puis, la box étant en mode débogage :

```bash
./scripts/install.sh 192.168.1.42   # l'IP de votre Player
```

Le détail (activation du débogage, attribution du rôle « écran d'accueil »,
récupération en cas de problème) est dans **[docs/INSTALLATION.md](docs/INSTALLATION.md)**.

## Supprimer les publicités

Le lanceur lui-même n'affiche rien de sponsorisé. Les bandeaux publicitaires que
vous voyez aujourd'hui viennent du lanceur Google installé sur la box. Une fois
TV Luncher défini comme écran d'accueil et vérifié :

```bash
./scripts/tv-tune.sh          # désactive le lanceur d'origine (réversible)
./scripts/tv-tune.sh --revert # remet tout en place
```

Le script **refuse de s'exécuter** tant que TV Luncher n'est pas l'écran d'accueil
actif, pour ne pas laisser la box sans interface. Rien n'est désinstallé :
`pm disable-user` masque simplement un paquet pour l'utilisateur courant.

## Pourquoi c'est fluide

Les choix qui comptent sur une box à faible puissance sont détaillés dans
**[docs/PERFORMANCE.md](docs/PERFORMANCE.md)**. En résumé :

- vues classiques, une seule activité, aucun fragment, aucun défilement imbriqué ;
- seules `scale`, `alpha` et `translationZ` sont animées, sur une couche
  matérielle : un déplacement du focus ne déclenche ni mesure ni mise en page ;
- icônes et bannières décodées hors du thread principal, à la taille d'affichage,
  dans un cache borné à un huitième du tas ;
- fonds d'écran vectoriels : rien à décoder, rien à garder en mémoire ;
- la liste des applications est préchargée au démarrage du processus, donc la
  première image affichée contient déjà la rangée.

## Fiabilité

Un lanceur est la seule application dont on ne peut pas sortir : s'il tombe,
l'écran reste noir et la télécommande ne répond plus. D'où :

- un garde-fou qui relance l'accueil au lieu de tuer le processus — mais qui rend
  la main au système si un second plantage survient dans les 20 secondes, pour ne
  pas boucler ;
- tout lancement d'application passe par un point unique qui absorbe les
  applications désinstallées entre l'affichage de la tuile et l'appui sur OK ;
- la liste se remet à jour sur les diffusions d'installation/désinstallation, et
  se recharge à chaque retour à l'accueil au cas où l'une aurait été perdue.

## Vie privée

| | |
|---|---|
| Publicités | aucune |
| Analyse / télémétrie | aucune |
| Permissions | `INTERNET` (météo seulement), `ACCESS_NETWORK_STATE`, `REQUEST_DELETE_PACKAGES`, `RECEIVE_BOOT_COMPLETED` |
| Requêtes réseau | une seule, vers `open-meteo.com`, toutes les 30 min, **uniquement si vous activez la météo** |

La météo est désactivée par défaut. Sans elle, l'application ne contacte aucun
serveur ; vous pouvez retirer la ligne `INTERNET` du manifeste et recompiler.

## Développement

```
app/src/main/kotlin/fr/tvluncher/launcher/
├── data/     découverte des apps, cache d'icônes, préférences, météo
├── ui/       accueil, grille des apps, réglages, tuiles
├── dream/    écran de veille
└── util/     focus, lancement sécurisé, garde anti-crash
```

- Kotlin, vues AndroidX classiques (pas de Compose), aucune dépendance tierce
- `minSdk 26` (Android 8), `targetSdk 35`
- Chaînes en français par défaut, anglais dans `values-en/`

## Licence

MIT — voir [LICENSE](LICENSE).
