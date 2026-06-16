#!/usr/bin/env bash
set -euo pipefail

EXAMPLE_PROJECT="${EXAMPLE_PROJECT:-/Users/lianganran/codes/2_SJTU_code/FM-agent/ExampleCppApp}"

if [[ ! -d "$EXAMPLE_PROJECT" ]]; then
  echo "Example project not found: $EXAMPLE_PROJECT" >&2
  exit 1
fi

open -a "/Applications/DevEco-Studio.app" "$EXAMPLE_PROJECT"
