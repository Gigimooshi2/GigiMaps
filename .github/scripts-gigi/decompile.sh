#!/usr/bin/env bash
set -euo pipefail
W=$PWD/work; mkdir -p $W/dl $W/out; cd $W

# tools
curl -fsSL -o apkeep https://github.com/EFForg/apkeep/releases/download/0.17.0/apkeep-x86_64-unknown-linux-gnu && chmod +x apkeep
curl -fsSL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip && unzip -q jadx.zip -d jadx
curl -fsSL -o apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar

# APK: exact version first, then latest
./apkeep -l -a "$PKG" -d apk-pure 2>&1 | head -40 || true
got() { ls dl/* >/dev/null 2>&1; }
[ -n "${VER:-}" ] && { ./apkeep -a "$PKG@$VER" -d apk-pure dl || true; }
got || { echo "exact version not available, trying latest"; ./apkeep -a "$PKG" -d apk-pure dl || true; }
got || { echo "APKPure gave nothing"; exit 1; }
ls -la dl
F=$(ls dl/* | head -1)
case "$F" in
  *.xapk|*.apks|*.zip) mkdir x && unzip -q "$F" -d x && ls -la x; BASE=$(ls x/*.apk | grep -Ev 'config\.' | head -1); [ -f x/manifest.json ] && cp x/manifest.json out/xapk-manifest.json ;;
  *) BASE="$F" ;;
esac
echo "BASE=$BASE"; ls -la "$BASE"
unzip -p "$BASE" AndroidManifest.xml > /dev/null
java -jar apktool.jar d -f -o out/apktool "$BASE" || java -jar apktool.jar d -f --no-res -o out/apktool "$BASE"
JAVA_OPTS="-Xmx12g" jadx/bin/jadx -j 4 --show-bad-code --no-imports -d out/jadx "$BASE" || echo "jadx finished with errors (normal on big apps)"
du -sh out/*

# pack + encrypt (aes key wrapped with the repo's public key)
tar -C out -cf - . | xz -T0 -6 > maps.tar.xz
openssl rand -out k.bin 32
openssl enc -aes-256-cbc -pbkdf2 -salt -pass file:k.bin -in maps.tar.xz -out maps.tar.xz.enc
openssl pkeyutl -encrypt -pubin -inkey "$GITHUB_WORKSPACE/gigi/decompile-pub.pem" -in k.bin -out key.enc
rm -f k.bin maps.tar.xz
split -b 1900M -d maps.tar.xz.enc maps.part.
ls -la maps.part.* key.enc
gh release upload "$TAG" maps.part.* key.enc --clobber -R "$GITHUB_REPOSITORY"
