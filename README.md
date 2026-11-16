# Réunions hors ligne — transcription de réunions 100 % locale

Application Android native (Kotlin, Jetpack Compose, Material 3) qui **enregistre** une réunion,
la **transcrit en français** et **sépare les intervenants**, entièrement sur le téléphone.
Pensée pour un usage professionnel confidentiel, optimisée pour le **Nothing Phone (3a)**
(Snapdragon 7s Gen 3, Android 15 / Nothing OS 3, arm64).

## 🔒 Confidentialité

- L'application **n'a pas la permission INTERNET** : Android lui interdit physiquement tout accès réseau.
  Le build échoue automatiquement si `INTERNET` ou `ACCESS_NETWORK_STATE` apparaît dans le manifeste
  fusionné ou dans l'APK final (`scripts/check_no_network.sh`).
- Les modèles d'IA sont téléchargés **pendant le build** sur GitHub et **intégrés dans l'APK**.
  Aucun téléchargement à l'exécution.
- Aucune analytics, aucun Firebase, aucun SDK tiers communiquant avec un serveur.
- `allowBackup="false"` + règles d'extraction qui excluent tout : rien ne part dans la sauvegarde Google
  ni lors d'un transfert vers un autre téléphone.
- Audio, transcriptions et actions sont stockés uniquement dans le stockage **privé** de l'application.
  Les fichiers exportés sont créés à la demande dans un dossier temporaire privé, effacé à chaque export.

## ✨ Fonctionnalités

1. **Enregistrement fiable** — service de premier plan (type micro) avec notification persistante
   (pause / reprise / arrêt), continue écran verrouillé et en arrière-plan, sans limite de durée.
   Audio 16 kHz mono écrit sur disque au fil de l'eau : en cas de plantage, l'audio déjà enregistré
   est récupéré au prochain lancement.
2. **Transcription locale** avec [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 et Whisper
   multilingue **forcé en français** :
   - **Qualité** : Whisper *small* int8 (par défaut) ;
   - **Rapide** : Whisper *base* int8, pour les longues réunions ;
   - 4 threads CPU, découpage préalable par le VAD Silero (réunions d'1 h et plus) ;
   - en arrière-plan avec pourcentage et **temps restant estimé** dans la notification (la vitesse réelle
     du téléphone est mesurée et mémorisée pour affiner les estimations) ;
   - **reprise** automatique là où elle s'était arrêtée (chaque étape et chaque segment sont sauvegardés).
3. **Séparation par intervenant** : diarisation sherpa-onnx (segmentation pyannote 3.0 + empreintes vocales
   wespeaker). Nombre de participants indiqué ou détecté automatiquement. Renommer un intervenant met à jour
   toute la transcription (donner le nom d'un intervenant existant fusionne les deux) ; l'intervenant d'un
   segment se corrige en touchant son nom.
4. **Consultation et édition** : liste des réunions avec recherche dans les titres, noms et transcriptions ;
   vue dialogue colorée et horodatée ; toucher un segment rejoue l'audio à cet endroit ; texte modifiable
   (crayon ou appui long) ; renommer / supprimer une réunion (audio inclus, avec confirmation).
5. **Export et envoi** : `.docx` (généré par l'app, sans bibliothèque) et `.txt`, partagés via le menu de
   partage Android (Gmail, WhatsApp, Drive…).
6. **Actions** : tâche, responsable, échéance, avec bouton « Ajouter à l'agenda » (ouvre l'agenda du téléphone,
   sans Internet).

## 📲 Installer l'application sur le téléphone

1. Sur le téléphone, ouvrez la page **Releases** du dépôt GitHub (lien « Releases » à droite de la page
   d'accueil du dépôt, ou `https://github.com/<votre-compte>/Enregistreur/releases`).
2. Dans la version la plus récente, touchez **`Reunions-hors-ligne-vX.Y.Z.apk`** pour le télécharger
   (≈ 500 Mo : les modèles d'IA sont inclus, utilisez le Wi-Fi).
3. Ouvrez le fichier téléchargé (notification ou application **Fichiers**).
4. Android demande d'**autoriser les sources inconnues** : touchez **Paramètres**, activez
   **« Autoriser cette source »** pour le navigateur (ou l'app Fichiers), puis revenez et touchez **Installer**.
5. Si Play Protect affiche un avertissement (application inconnue), choisissez **« Installer quand même »**.
6. Au premier enregistrement, autorisez le **micro** et les **notifications**.

## 🔋 Désactiver l'optimisation de la batterie (Nothing OS)

Nothing OS peut arrêter les applications en arrière-plan. Pour que l'enregistrement et la transcription
ne soient jamais interrompus :

1. Dans l'application, touchez **« Désactiver l'optimisation »** sur la carte jaune de l'écran d'accueil,
   puis **Autoriser**.
2. Vérifiez ensuite : **Paramètres → Applications → Réunions → Batterie** → choisissez
   **« Sans restriction »** (ou *Unrestricted*).
3. Facultatif : dans l'écran des applications récentes, appui long sur l'icône de l'app → **Verrouiller**
   pour qu'elle ne soit pas fermée par « Tout effacer ».

## 🔄 Mettre à jour l'application

Chaque push sur la branche `main` produit automatiquement une nouvelle release (`v1.0.N`).
Pour mettre à jour : téléchargez le nouvel APK depuis **Releases** et installez-le **par-dessus**
l'ancien (touchez *Mettre à jour*). Vos réunions sont conservées.

> ⚠️ La mise à jour par-dessus ne fonctionne que si toutes les versions sont signées avec **la même clé**.
> Configurez les secrets de signature (ci-dessous) **avant** d'installer l'application pour de bon.
> Une version signée avec la clé de *debug* temporaire doit être désinstallée avant d'installer une autre
> version (ce qui efface les réunions).

## 🔑 Signature de l'APK (secrets GitHub)

Le workflow signe l'APK avec votre clé si ces 4 secrets existent ; sinon il utilise une clé de debug
temporaire pour que le build fonctionne quand même.

Depuis le navigateur du téléphone :

1. Ouvrez le dépôt sur GitHub → **Settings** → **Secrets and variables** → **Actions**
   (en mode « Version pour ordinateur » si le menu n'apparaît pas).
2. Touchez **New repository secret** et créez, un par un :

| Nom | Valeur |
|---|---|
| `KEYSTORE_BASE64` | le contenu base64 du keystore (une longue ligne) |
| `KEYSTORE_PASSWORD` | le mot de passe du keystore |
| `KEY_ALIAS` | `reunions` |
| `KEY_PASSWORD` | le mot de passe de la clé (identique au précédent) |

3. Relancez le build : **Actions → Build APK → Run workflow** (ou faites un nouveau push sur `main`).
   La release indiquera « Signé avec la clé de release ».

**Conservez précieusement** ces valeurs (gestionnaire de mots de passe) : sans elles, il est impossible de
publier une mise à jour qui s'installe par-dessus.

Pour générer un nouveau keystore (sur n'importe quel ordinateur avec Java) :

```bash
keytool -genkeypair -keystore release.jks -alias reunions -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # -> valeur de KEYSTORE_BASE64
```

## 🛠️ Build (GitHub Actions)

`.github/workflows/build.yml`, à chaque push :

1. télécharge l'AAR sherpa-onnx v1.13.8 et les modèles depuis les releases officielles de sherpa-onnx
   (`scripts/download_models.sh`, mis en cache entre deux builds) :
   - `asr-models/sherpa-onnx-whisper-small.tar.bz2` et `sherpa-onnx-whisper-base.tar.bz2` (fichiers int8 uniquement),
   - `asr-models/silero_vad.onnx`,
   - `speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`,
   - `speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx` ;
2. lance les tests unitaires (export docx/txt, renommage d'intervenants, découpage des segments…) ;
3. compile l'APK release (arm64-v8a, minSdk 29, targetSdk 36) ;
4. vérifie l'absence de toute permission réseau (manifeste fusionné + `aapt2` sur l'APK) ;
5. sur `main` uniquement : publie l'APK dans une GitHub Release `v1.0.<numéro de build>`.

## 🧱 Architecture

```
app/src/main/java/fr/reunion/enregistreur/
├── audio/          RecordingService (service micro), écriture/lecture WAV
├── transcription/  TranscriptionService, pipeline reprenable, modèles sherpa-onnx, découpage
├── data/           Room (réunions, intervenants, segments, actions), Repository, logique intervenants
├── export/         Générateurs .docx (OOXML minimal) et .txt
├── ui/             Écrans Compose + ViewModels
└── util/           Formatage (dates en français, durées)
```

Pipeline de transcription (chaque étape est persistée pour la reprise) :
diarisation (par tranches de 30 min, intervenants reliés entre tranches par empreinte vocale)
→ VAD Silero → plan de segments mono-intervenant (≤ 28 s) → Whisper segment par segment.
