#!/bin/bash
set -euo pipefail
git update-index --refresh
git diff-index --quiet HEAD --
clojure -M:test
clojure -T:release
