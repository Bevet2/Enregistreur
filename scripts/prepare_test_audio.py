"""Convertit un WAV en PCM 16 bits mono 16 kHz (en-tête 44 octets) et le découpe."""
import array, sys, wave

src, dst, seconds = sys.argv[1], sys.argv[2], float(sys.argv[3])
with wave.open(src, "rb") as w:
    ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    assert width == 2, f"échantillons {width * 8} bits non gérés"
    data = array.array("h", w.readframes(n))
if ch > 1:
    data = array.array("h", (sum(data[i:i + ch]) // ch for i in range(0, len(data), ch)))
if rate != 16000:
    ratio = rate / 16000
    data = array.array("h", (data[min(int(i * ratio), len(data) - 1)] for i in range(int(len(data) / ratio))))
data = data[: int(16000 * seconds)]
with wave.open(dst, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(data.tobytes())
print(f"{src} ({ch} canaux, {rate} Hz) -> {dst} : {len(data) / 16000:.1f} s")
