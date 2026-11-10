param([string]$Maven = 'mvn', [string]$Java = 'java')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $Maven -o test-compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath '-Dmdep.outputFile=target/legacy-test-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation/classpath resolution failed' }
    $legacyTestDependencies = (Get-Content -Raw 'target/legacy-test-classpath.txt').Trim()
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.library.assemble.LegacySidebarRegression
    if ($LASTEXITCODE -ne 0) { throw 'Legacy sidebar regression checks failed' }
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.library.assemble.AssembleLifecycleRegression
    if ($LASTEXITCODE -ne 0) { throw 'Scoreboard lifecycle regression checks failed' }
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.visual.nametag.LegacyNametagRegression
    if ($LASTEXITCODE -ne 0) { throw 'Nametag compatibility regression checks failed' }
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.visual.tablist.legacy.LegacyTablistRegression
    if ($LASTEXITCODE -ne 0) { throw 'Legacy Tab regression checks failed' }
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.visual.tablist.legacy.LegacyTablistLayoutRegression
    if ($LASTEXITCODE -ne 0) { throw 'Legacy Tab layout regression checks failed' }
    & $Java -cp "target/test-classes;target/classes;$legacyTestDependencies" dev.revere.alley.common.version.internal.ClientVersionLoginRegression
    if ($LASTEXITCODE -ne 0) { throw 'Pre-join protocol regression checks failed' }
} finally {
    Pop-Location
}
