# Traveling — application mobile

Projet Android de voyage et de partage développé dans le cadre de la programmation mobile.

L'application regroupe des fonctionnalités de cartographie, lieux, photos, profils, groupes, favoris, notifications et administration/modération, avec Firebase comme backend principal.

## Fonctionnalités visibles dans le dépôt

- inscription et authentification ;
- profils utilisateurs ;
- carte, itinéraires et lieux ;
- galerie et gestion de photos ;
- favoris ;
- groupes ;
- partage ;
- notifications push via FCM ;
- météo via OpenWeather ;
- outils d'administration et de modération ;
- prise en charge de plusieurs langues.
## Stack principale

- Android / Kotlin et Java
- ViewBinding
- Firebase Authentication
- Cloud Firestore
- Firebase Storage
- Firebase Cloud Messaging
- Firebase Functions
- osmdroid pour la cartographie
- Retrofit / OkHttp
- Glide
- ML Kit Image Labeling
- OpenWeather

## Configuration

### Firebase

Le projet contient un exemple de configuration Firebase. Créez votre propre projet Firebase et placez le fichier `google-services.json` attendu dans le module `app/`.

Ne commitez pas de secrets privés dans le dépôt.

### OpenWeather

Ajoutez votre clé OpenWeather dans `local.properties` :

```properties
OPENWEATHER_API_KEY=your_key_here
```

## Build

Sous Linux/macOS :

```bash
./gradlew assembleDebug
```

Sous Windows :

```powershell
.\gradlew.bat assembleDebug
```

Vous pouvez également ouvrir le dépôt directement dans Android Studio et lancer le module `app` sur un émulateur ou un appareil Android.

## Architecture du dépôt

```text
app/
├── src/main/        # code, ressources et manifeste Android
└── build.gradle.kts # configuration du module
```

Le projet cible Android API 35 et utilise un `minSdk` 24.
