# Notes de performance

Cibles : le Player Free (Amlogic, 2 Go de RAM, Android TV 9/11) et les box
équivalentes. Le budget est de 16 ms par image, sur un GPU sans marge.

## Ce qui a été choisi, et pourquoi

### Vues classiques plutôt que Compose

Compose serait plus agréable à écrire, mais son coût de première image et de
recomposition se voit sur une box de cette classe. Une `RecyclerView` avec des
vues natives est prévisible : on sait exactement combien de vues existent et
quand elles sont liées.

### Trois propriétés animées, pas une de plus

Le déplacement du focus n'anime que `scaleX/scaleY`, `alpha` et `translationZ`.
Ces trois-là sont traitées par le thread de rendu une fois la vue promue en
couche matérielle (`withLayer()`) : **aucune mesure, aucune mise en page** par
image. Animer une largeur ou une marge, à l'inverse, relance toute la passe de
layout de la rangée à chaque image.

### Le défilement centré est géré par le LayoutManager

`CenteringLayoutManager` redéfinit `requestChildRectangleOnScreen`. C'est le point
d'entrée que RecyclerView utilise déjà pour amener l'enfant focalisé à l'écran :
en le redéfinissant, on remplace son comportement au lieu de le concurrencer.
Appeler `smoothScrollToPosition` depuis un `OnFocusChangeListener` produirait deux
défilements simultanés sur la même liste — visible à l'œil nu.

### Les images sont décodées à la taille d'affichage

`IconLoader` rastérise chaque bannière à la dimension exacte de la tuile. Une
icône de 512 px affichée dans une tuile de 160 px coûterait dix fois la bande
passante mémoire pour un résultat identique. Le cache est borné à un huitième du
tas (4 à 24 Mo) et vidé sur `onTrimMemory`.

Le décodage est plafonné à 3 threads : au-delà, sur 4 cœurs lents, les décodages
se disputent le CPU avec le thread principal au lieu de finir plus vite.

### Les bitmaps du cache nous appartiennent

Réutiliser le bitmap interne d'un `BitmapDrawable` renvoyé par le PackageManager
serait gratuit, mais cette mémoire appartient aux ressources de l'autre
application et peut être libérée à sa désinstallation. On rastérise toujours dans
un bitmap à nous.

### Fonds d'écran vectoriels

Un JPEG 1920×1080 coûte environ 8 Mo de tas une fois décodé — sur 2 Go partagés
avec le lecteur vidéo, ce n'est pas rien. Les cinq fonds intégrés sont des
`VectorDrawable` de quelques kilo-octets, nets en 4K. Une image personnelle est
recopiée en interne et ramenée à 1920×1080 au maximum, décodée en deux passes
(mesure puis `inSampleSize`).

### Le surdessin

Le thème de l'accueil pose `windowBackground` à `@null` : l'`ImageView` du fond
d'écran peint déjà chaque pixel, un fond de fenêtre serait une couche entièrement
recouverte. Le voile de lisibilité est un dégradé statique, pas un flou —
`RenderEffect` n'existe qu'à partir d'Android 12 et coûte cher sur ces GPU.

### L'horloge

Rafraîchie par `ACTION_TIME_TICK`, que le système diffuse déjà à chaque minute.
Un `Handler` battant à la seconde réveillerait le CPU soixante fois plus souvent
pour redessiner les mêmes deux chiffres.

### La liste d'applications est préchargée

`AppRepository.refresh()` est appelé depuis `Application.onCreate()`. La requête
`PackageManager` prend quelques dizaines de millisecondes ; la faire à ce
moment-là plutôt que dans `onCreate()` de l'activité, c'est la différence entre
une rangée déjà remplie à la première image et une rangée qui se peuple après.

## Si ça saccade quand même

1. **Réglages → Animations de focus → désactivé.** Le focus devient instantané ;
   tout le reste est inchangé.
2. **Réglages → Fond d'écran → Minuit.** Le fond le moins coûteux à dessiner.
3. **Réglages → Afficher le nom des applications → désactivé.** Une passe de
   texte en moins par tuile.
4. Réduisez le nombre de favoris : la rangée garde douze tuiles en cache.

## Mesurer

```bash
# Images perdues, en direct
adb shell dumpsys gfxinfo fr.tvluncher.launcher framestats

# Durée du démarrage à froid
adb shell am force-stop fr.tvluncher.launcher
adb shell am start -W -c android.intent.category.HOME -a android.intent.action.MAIN

# Mémoire
adb shell dumpsys meminfo fr.tvluncher.launcher
```
