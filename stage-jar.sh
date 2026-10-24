#!/usr/bin/env bash
set -euo pipefail
SRC="/mnt/c/Users/vaidy/OneDrive/Desktop/SOP/search engine IR/target/indexer.jar"
# Force full read (defeat OneDrive placeholder) then copy into the Linux fs.
cat "$SRC" > /tmp/indexer.jar
echo "size=$(stat -c%s /tmp/indexer.jar)"
echo "md5=$(md5sum /tmp/indexer.jar | cut -d' ' -f1)"
