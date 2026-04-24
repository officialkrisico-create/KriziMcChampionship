#!/bin/bash
# ================================================================
#  Build all KMC tournament plugins in one go.
#  Jars end up in dist/ ready to copy to plugins/.
# ================================================================
set -e

echo "[KMC] Building all modules..."
mvn clean package

echo ""
echo "[KMC] Collecting jars into dist/..."
mkdir -p dist
find . -path ./dist -prune -o -name "*.jar" -path "*/target/*" ! -name "original-*" -print0 | \
    xargs -0 -I {} cp {} dist/

echo ""
echo "[KMC] Done! Drop the jars in dist/ into your server's plugins/ folder."
ls -la dist/
