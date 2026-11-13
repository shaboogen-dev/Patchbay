#!/bin/bash
# macOS: double-click this file in Finder to start BML. It runs bml-ui.sh beside it, as the
# Terminal line  sh <this folder>/bml-ui.sh  does. Terminal starts it in another folder, so it
# finds its own folder first.
HERE=$(cd -- "$(dirname -- "$0")" && pwd) || exit 1
exec /bin/sh "$HERE/bml-ui.sh" "$@"
