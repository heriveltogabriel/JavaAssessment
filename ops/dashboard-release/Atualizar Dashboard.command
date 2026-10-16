#!/bin/zsh
set -eu
assessment_update_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
python3 "$assessment_update_dir/publish-dashboard.py"
