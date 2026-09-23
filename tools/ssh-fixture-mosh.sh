#!/bin/sh
# Test-only remote program; no user shell configuration is loaded.
printf 'MANGOSSH_NATIVE_FIXTURE_READY\n'
exec /bin/cat
