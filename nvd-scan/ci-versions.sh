#!/usr/bin/env bash

set -eou pipefail

# We use the latest release of clj-watson and dependency-check
# We can determine these quite easily with clojure tools cli, but that would
# bring down a bunch of maven deps before our cache has been restored.
# So we use bash-level scripting instead.
mkdir -p target

DEPENDENCY_CHECK_VERSION=$(curl -s \
    https://repo1.maven.org/maven2/org/owasp/dependency-check-core/maven-metadata.xml \
    | grep -o '<release>[^<]*</release>' \
    | sed 's/<release>//;s/<\/release>//')
if [ -z "$DEPENDENCY_CHECK_VERSION" ]; then
    echo "Error: Could not determine latest version of dependency-check."
    exit 1
fi

CLJ_WATSON_VERSION=$(gh release view --repo clj-holmes/clj-watson --json tagName --jq '.tagName')
if [ -z "$CLJ_WATSON_VERSION" ]; then
    echo "Error: Could not determine latest version for clj-watson."
    exit 1
fi

echo "dependency-check ${DEPENDENCY_CHECK_VERSION}" > target/ci-versions.txt
echo "clj-watsion ${CLJ_WATSON_VERSION}" >> target/ci-versions.txt
echo "Wrote target/ci-versions.txt:"
cat target/ci-versions.txt
