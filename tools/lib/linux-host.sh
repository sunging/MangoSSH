#!/usr/bin/env bash
# Shared host validation for MangoSSH's pinned Linux native toolchains.

if [[ -z "${BASH_VERSION:-}" ]]; then
    printf 'Bash is required.\n' >&2
    return 1 2>/dev/null || exit 1
fi

## Rejects hosts that cannot run the pinned Linux toolchain binaries.
mangossh_require_linux_x86_64() {
    local kernel
    local architecture

    command -v uname >/dev/null 2>&1 || {
        printf 'Missing required command: uname\n' >&2
        return 1
    }
    kernel="$(uname -s)"
    architecture="$(uname -m)"
    [[ "$kernel" == "Linux" ]] || {
        printf 'Unsupported build host: expected Linux, found %s.\n' "$kernel" >&2
        return 1
    }
    case "$architecture" in
        x86_64|amd64) ;;
        *)
            printf 'Unsupported Linux architecture: expected x86_64, found %s.\n' \
                "$architecture" >&2
            return 1
            ;;
    esac
}

## Reports every missing command required by the calling build stage.
mangossh_require_commands() {
    local command_name
    local missing=()

    for command_name in "$@"; do
        command -v "$command_name" >/dev/null 2>&1 || missing+=("$command_name")
    done
    (( ${#missing[@]} == 0 )) || {
        printf 'Missing required command(s): %s\n' "${missing[*]}" >&2
        return 1
    }
}
