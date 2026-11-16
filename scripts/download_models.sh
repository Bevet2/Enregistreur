#!/usr/bin/env bash
# Télécharge sherpa-onnx (AAR Android) et les modèles d'IA, puis les place dans l'APK.
# Exécuté pendant le build (GitHub Actions) : l'application ne télécharge JAMAIS rien.
set -euo pipefail

SHERPA_VERSION="1.13.8"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${MODELS_CACHE:-$ROOT/models-cache}"
ASSETS="$ROOT/app/src/main/assets/models"
LIBS="$ROOT/app/libs"
mkdir -p "$CACHE" "$LIBS"

download() { # url destination
  local url="$1" out="$2"
  if [ -s "$out" ]; then echo "✔ déjà présent : $(basename "$out")"; return; fi
  echo "⬇ $url"
  curl -fL --retry 5 --retry-delay 10 --connect-timeout 30 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

# --- Bibliothèque sherpa-onnx (AAR officiel) ----------------------------------
AAR="$CACHE/sherpa-onnx-$SHERPA_VERSION.aar"
download "$BASE/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar" "$AAR"
cp "$AAR" "$LIBS/"

# --- Whisper multilingue (int8) ------------------------------------------------
whisper() { # nom (small|base)
  local name="$1" dir="$CACHE/whisper-$1"
  if [ -s "$dir/$name-encoder.int8.onnx" ] && [ -s "$dir/$name-decoder.int8.onnx" ] && [ -s "$dir/$name-tokens.txt" ]; then
    echo "✔ Whisper $name déjà en cache"; return
  fi
  mkdir -p "$dir"
  local tarball="$CACHE/sherpa-onnx-whisper-$name.tar.bz2"
  download "$BASE/asr-models/sherpa-onnx-whisper-$name.tar.bz2" "$tarball"
  tar -xjf "$tarball" -C "$dir" --strip-components=1 \
    "sherpa-onnx-whisper-$name/$name-encoder.int8.onnx" \
    "sherpa-onnx-whisper-$name/$name-decoder.int8.onnx" \
    "sherpa-onnx-whisper-$name/$name-tokens.txt"
  rm -f "$tarball"
}
whisper small
whisper base

# --- VAD Silero ------------------------------------------------------------------
mkdir -p "$CACHE/vad"
download "$BASE/asr-models/silero_vad.onnx" "$CACHE/vad/silero_vad.onnx"

# --- Diarisation : segmentation pyannote 3.0 + empreintes vocales wespeaker -----
mkdir -p "$CACHE/diarization"
if [ ! -s "$CACHE/diarization/segmentation.onnx" ]; then
  download "$BASE/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$CACHE/pyannote.tar.bz2"
  tar -xjf "$CACHE/pyannote.tar.bz2" -C "$CACHE" "sherpa-onnx-pyannote-segmentation-3-0/model.onnx"
  mv "$CACHE/sherpa-onnx-pyannote-segmentation-3-0/model.onnx" "$CACHE/diarization/segmentation.onnx"
  rm -rf "$CACHE/sherpa-onnx-pyannote-segmentation-3-0" "$CACHE/pyannote.tar.bz2"
fi
download "$BASE/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx" "$CACHE/diarization/embedding.onnx"

# --- Copie dans les assets de l'APK ---------------------------------------------
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp -r "$CACHE/whisper-small" "$CACHE/whisper-base" "$CACHE/vad" "$CACHE/diarization" "$ASSETS/"

# --- Vérifications ------------------------------------------------------------------
fail() { echo "::error::$*"; exit 1; }
for f in whisper-small/small-encoder.int8.onnx whisper-small/small-decoder.int8.onnx whisper-small/small-tokens.txt \
         whisper-base/base-encoder.int8.onnx whisper-base/base-decoder.int8.onnx whisper-base/base-tokens.txt \
         vad/silero_vad.onnx diarization/segmentation.onnx diarization/embedding.onnx; do
  [ -s "$ASSETS/$f" ] || fail "Modèle manquant : $f"
done
unzip -l "$LIBS/sherpa-onnx-$SHERPA_VERSION.aar" | grep -q "jni/arm64-v8a/libsherpa-onnx-jni.so" \
  || fail "L'AAR ne contient pas libsherpa-onnx-jni.so pour arm64-v8a"
unzip -p "$LIBS/sherpa-onnx-$SHERPA_VERSION.aar" classes.jar > "$CACHE/classes.jar"
unzip -l "$CACHE/classes.jar" | grep -q "com/k2fsa/sherpa/onnx/OfflineRecognizer.class" \
  || fail "L'AAR ne contient pas l'API Kotlin com.k2fsa.sherpa.onnx"
rm -f "$CACHE/classes.jar"

echo "Modèles intégrés :"
du -ah "$ASSETS" | sort -k2
echo "Bibliothèques natives de l'AAR :"
unzip -l "$LIBS/sherpa-onnx-$SHERPA_VERSION.aar" | grep "jni/arm64-v8a" || true
