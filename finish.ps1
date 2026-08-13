$log = 'finish.txt'
"=== remove scratch ===" | Set-Content $log -Encoding ascii
Remove-Item -Force clean.ps1, clean.txt, package.ps1, package.txt -ErrorAction SilentlyContinue

# The archive is a build artifact and must not be committed.
if (-not (Select-String -Path .gitignore -Pattern '^\*\.zip$' -Quiet)) {
    Add-Content .gitignore "`n# Packaged archive for submission - build artifact, never committed.`n*.zip"
    Add-Content $log "added *.zip to .gitignore" -Encoding ascii
}

Add-Content $log "`n=== git status before commit ===" -Encoding ascii
& git status --porcelain 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }

& git add -A 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }
Add-Content $log "`n=== staged ===" -Encoding ascii
& git diff --cached --name-status 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }

$msg = @"
Add project overview, scrub machine-specific paths, drop IDE metadata

docs/OVERVIEW.md - the high-level guide the repo was missing. Covers what
the project is, an ASCII component diagram, the two and only two ways
services communicate, the seven-step journey, what each of the six
components does and owns, every database table with its purpose and key
columns, why almost no foreign keys exist, the pinned tech stack, the
dependency list per component and why each extra one is there,
cross-cutting concerns, and what was deliberately left out of scope.
Linked from the README as the recommended starting point.

Scrubbed for distribution:
- README.md carried absolute paths containing the developer's Windows
  username in three places. Replaced with <path-to-jdk-21> and
  <path-to-maven> placeholders, and the prerequisites table no longer
  describes one specific machine. The build section now leads with the
  plain `mvn` form and keeps the full-path form as the fallback.
- kafka-run.cmd hardcoded a JDK installation path. It now derives java
  from JAVA_HOME, fails with a clear message if that is unset, allows
  KAFKA, CFG, and KAFKA_LOGS to be overridden, and validates that the
  distribution and config exist before launching.

Removed generated and machine-specific files:
- api-gateway/.idea, catalog-service/.idea, and the empty .vscode
  directory - IDE metadata, none of it needed to build or run.
- All compiled output under the five target/ directories: every .class
  file, generated sources, and test-classes. The five Spring Boot fat
  jars could not be deleted because the services are running and Windows
  holds a lock on a jar being executed; `mvn clean` removes them once the
  processes are stopped. They are gitignored and excluded from the
  archive either way.
- *.zip added to .gitignore so the submission archive is never committed.

Verified afterwards: a repo-wide search for the developer name, username,
corporate email, and JDK vendor path returns no matches.
"@
[System.IO.File]::WriteAllText((Join-Path (Get-Location) 'cm.txt'), $msg)

Add-Content $log "`n=== commit ===" -Encoding ascii
& git commit -F cm.txt 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }
Remove-Item -Force cm.txt -ErrorAction SilentlyContinue

Add-Content $log "`n=== push ===" -Encoding ascii
& git push origin main 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }

Add-Content $log "`n=== after ===" -Encoding ascii
& git log --oneline -2 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }
& git rev-parse HEAD origin/main 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }
& git status --porcelain 2>&1 | ForEach-Object { Add-Content $log $_ -Encoding ascii }
Add-Content $log "DONE" -Encoding ascii
