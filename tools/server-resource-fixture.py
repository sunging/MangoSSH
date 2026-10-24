"""Disposable loopback SSH fixture for the application's exact resource commands.

Requires Paramiko. Host keys exist only in memory and authentication is limited
to the fixture account. Linux runs locally (WSL on Windows). Windows modes run
through an explicitly supplied, already-trusted OpenSSH alias, without changing
the target's default shell or configuration. No remote output is logged.
"""
import argparse
import base64
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import textwrap
import threading
import time

def commands():
    """Extract the two fixed raw-string scripts; fail closed if their source shape changes."""
    source = (Path(__file__).resolve().parents[1] / "app/src/main/java/website/sung/mangossh/session/ServerResourceCollector.kt").read_text(encoding="utf-8")
    def script(name):
        match = re.search(r"internal val " + name + r' = """\n(.*?)\n    """\.trimIndent\(\)', source, re.S)
        if match is None:
            raise RuntimeError("Resource script source could not be read")
        return textwrap.dedent(match[1]).replace("${'$'}", "$")
    linux = "sh -c '" + script("linuxScript").replace("'", "'\\''") + "'"
    windows = "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " + base64.b64encode(script("windowsScript").encode("utf-16le")).decode("ascii")
    if len(windows) >= 8191:
        raise RuntimeError("Resource command exceeds cmd.exe limit")
    return linux, windows


def windows_wrapper(command, mode):
    if mode == "windows-cmd":
        return 'cmd.exe /d /s /c "' + command + '"'
    prefix = ""
    if mode == "windows-restricted":
        # Controlled missing fields and CIM denial, without changing account permissions.
        prefix = ("function Get-CimInstance { param([string]$ClassName); switch($ClassName){"
                  "Win32_OperatingSystem {[pscustomobject]@{Caption='Example';Version='1';"
                  "LastBootUpTime=(Get-Date).Date;TotalVisibleMemorySize=4096;FreePhysicalMemory=$null}}"
                  "Win32_PageFileUsage {[pscustomobject]@{AllocatedBaseSize=1024;CurrentUsage=$null}}"
                  "default {throw 'Fixture CIM access denied'}} }; ")
        script = base64.b64decode(command.rsplit(" ", 1)[1]).decode("utf-16le")
        body = prefix + script
    else:
        return 'powershell.exe -NoLogo -NoProfile -NonInteractive -Command "& ' + command + '"'
    return "powershell.exe -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand " + base64.b64encode(body.encode("utf-16le")).decode("ascii")


def main():
    import paramiko

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--mode", choices=("linux", "windows-cmd", "windows-powershell", "windows-restricted", "stall"), required=True)
    parser.add_argument("--windows-host", help="Existing trusted SSH alias; never printed")
    parser.add_argument("--linux-host", help="Optional existing trusted SSH alias instead of local Linux; never printed")
    args = parser.parse_args()
    if args.mode.startswith("windows") and not args.windows_host:
        parser.error("Windows mode requires an explicit trusted SSH alias")
    linux, windows = commands()
    host_key = paramiko.RSAKey.generate(2048)
    stopping = threading.Event()
    transports = []

    class Server(paramiko.ServerInterface):
        def check_auth_none(self, username):
            return paramiko.AUTH_SUCCESSFUL if username == "fixture" else paramiko.AUTH_FAILED

        def check_channel_request(self, kind, chanid):
            return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

        def check_channel_exec_request(self, channel, command):
            text = command.decode("utf-8", errors="strict")
            if text not in (linux, windows):
                return False

            def execute():
                process = None
                try:
                    if args.mode == "stall":
                        while not channel.closed and not stopping.wait(0.05):
                            pass
                        return
                    expected = linux if args.mode == "linux" else windows
                    if text != expected:
                        channel.send_exit_status(127)
                        return
                    if args.mode == "linux":
                        argv = (["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5",
                                 args.linux_host, linux] if args.linux_host else
                                (["wsl.exe", "--exec"] if os.name == "nt" else []) + ["sh", "-c", linux])
                    else:
                        argv = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5",
                                args.windows_host, windows_wrapper(windows, args.mode)]
                    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    deadline = time.monotonic() + 15
                    while not stopping.is_set() and not channel.closed and time.monotonic() < deadline:
                        try:
                            output, errors = process.communicate(timeout=0.1)
                            channel.sendall(output)
                            if errors:
                                channel.sendall_stderr(b"Fixture subprocess reported an error")
                            channel.send_exit_status(process.returncode if process.returncode >= 0 else 1)
                            return
                        except subprocess.TimeoutExpired:
                            continue
                except (OSError, EOFError, paramiko.SSHException):
                    pass
                finally:
                    if process is not None and process.poll() is None:
                        process.kill()
                        process.communicate()
                    try:
                        channel.shutdown_write()
                        channel.close()
                    except OSError:
                        pass
            threading.Thread(target=execute, daemon=True).start()
            return True

    def serve(client):
        transport = paramiko.Transport(client)
        transports.append(transport)
        channels = []
        try:
            transport.add_server_key(host_key)
            transport.start_server(server=Server())
            while transport.is_active() and not stopping.is_set():
                channel = transport.accept(0.2)
                if channel is not None:
                    channels.append(channel)  # Retain accepted channels until the transport closes.
        except (OSError, EOFError, paramiko.SSHException):
            pass
        finally:
            transport.close()

    listener = socket.socket()
    listener.bind(("127.0.0.1", args.port))
    listener.listen(8)
    listener.settimeout(0.2)
    def stop_from_stdin():
        for line in sys.stdin:
            if line.strip() == "stop":
                stopping.set()
                break
    threading.Thread(target=stop_from_stdin, daemon=True).start()
    print("Resource fixture ready", flush=True)
    try:
        while not stopping.is_set():
            try:
                client, _ = listener.accept()
            except socket.timeout:
                continue
            threading.Thread(target=serve, args=(client,), daemon=True).start()
    finally:
        stopping.set()
        listener.close()
        for transport in transports:
            transport.close()


if __name__ == "__main__":
    main()
