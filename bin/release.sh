#!/usr/bin/env bash
set -o errexit
set -o pipefail
set -o nounset

src="$PWD"
dst=$(mktemp -d -t ogre.tools)
vrs=$1
url="ws://ogre-tools.herokuapp.com/ws"

npx shadow-cljs release app --config-merge "{:closure-defines {ogre.tools.env/VERSION \"$vrs\" ogre.tools.env/PATH \"/release/$vrs\" ogre.tools.env/SOCKET-URL \"$url\"}}"

git clone --single-branch --branch gh-pages git@github.com:samcf/ogre.tools.git "$dst"
mkdir -p "$dst"/release/"$vrs"
cp -r web/release/. "$dst"/release/"$vrs"

cd "$dst"/release/"$vrs"
rm -rf cljs-runtime manifest.edn
git add --all
git commit -m "Release candidate version $vrs."
git push origin gh-pages

cd "$src"
rm -rf "$dst"
