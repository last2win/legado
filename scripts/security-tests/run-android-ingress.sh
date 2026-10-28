#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?}" "${KOTLIN_COMPILER_CLASSPATH:?}" "${KOTLIN_STDLIB:?}" "${SECURITY_TEST_DEPS:?}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
classpath="$KOTLIN_STDLIB:$SECURITY_TEST_DEPS/coroutines.jar"
"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$classpath" -d "$work/tests.jar" \
  core/src/commonMain/kotlin/io/legado/app/web/api/WebApiRequest.kt \
  app/src/main/java/io/legado/app/api/ReaderProvider.kt \
  app/src/main/java/io/legado/app/lib/cronet/CronetLibraryIntegrity.kt \
  scripts/security-tests/android/*.kt
"$JAVA_HOME/bin/java" -Xmx256m -cp "$work/tests.jar:$classpath" security.IngressRegressionKt
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('app/src/main/AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
provider = next(p for p in root.iter('provider') if p.get(a+'name') == '.api.ReaderProvider')
permission = provider.get(a+'permission')
assert permission and provider.get(a+'grantUriPermissions') != 'true'
assert any(p.get(a+'name') == permission and p.get(a+'protectionLevel') == 'signature' for p in root.iter('permission'))
print('ReaderProvider manifest signature permission: PASS')
PY
