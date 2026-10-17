#!/usr/bin/env bash
# bili-noads 一键构建（薄壳，实际逻辑在 src/build.py）
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$here/../src/build.py" "$@"
