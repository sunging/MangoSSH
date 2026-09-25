"""Loopback-only disposable SSH/SFTP fixture. Requires Paramiko 4.0.0.

No production host, credentials, keys or files are used. Host keys live only in
memory; all files are inside TemporaryDirectory. Forwarding is restricted to this
fixture's own loopback listener. Run with --port and use adb reverse for devices.
"""
import argparse
import signal
import sys
import os
import socket
import struct
import tempfile
import threading
import subprocess
import shlex
import uuid
import re
import json
import shutil
from pathlib import Path
import paramiko
from paramiko.sftp import CMD_INIT, CMD_VERSION

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=22349)
tool_mode = parser.add_mutually_exclusive_group()
tool_mode.add_argument("--wsl-tmux", action="store_true")
tool_mode.add_argument("--native-tools", action="store_true", help="Run isolated tmux and Mosh fixtures directly on Linux")
parser.add_argument("--no-posix-rename", action="store_true")
parser.add_argument("--reject-metadata", action="store_true")
parser.add_argument("--stall-forward-cancel", action="store_true")
parser.add_argument("--legacy-algorithms", action="store_true")
args = parser.parse_args()
tmux_socket = "mangossh-fixture-" + uuid.uuid4().hex
key = paramiko.RSAKey.generate(2048)
workspace = tempfile.TemporaryDirectory(prefix="mangossh-ssh-test-")
root = Path(workspace.name).resolve()
authentication_password = uuid.uuid4().hex
authentication_otp = uuid.uuid4().hex
authentication_keys = set()
authentication_metadata = None

if args.native_tools:
    # Generate every credential inside this disposable fixture. No private material is vendored or logged.
    entries = []
    for kind, bits in (("rsa", 2048), ("ecdsa", 256), ("ecdsa", 384), ("ecdsa", 521), ("ed25519", 256)):
        base = root / (kind + str(bits))
        def keygen(arguments):
            result = subprocess.run(["ssh-keygen", "-q"] + arguments, capture_output=True, timeout=30)
            if result.returncode: raise RuntimeError("Ephemeral key generation failed")
        keygen(["-t", kind, "-b", str(bits), "-N", "", "-C", "", "-f", str(base)])
        public = Path(str(base) + ".pub").read_text().strip().split()
        import base64
        authentication_keys.add(base64.b64decode(public[1]))
        for format_name in (("openssh", "pem") if kind != "ed25519" else ("openssh",)):
            plain = root / (base.name + "-" + format_name)
            shutil.copyfile(base, plain)
            plain.chmod(0o600)
            if format_name == "pem": keygen(["-p", "-m", "PEM", "-P", "", "-N", "", "-f", str(plain)])
            encrypted = root / (plain.name + "-encrypted")
            shutil.copyfile(plain, encrypted)
            encrypted.chmod(0o600)
            passphrase = uuid.uuid4().hex
            arguments = ["-p", "-P", "", "-N", passphrase, "-f", str(encrypted)]
            if format_name == "pem": arguments.extend(["-m", "PEM"])
            keygen(arguments)
            entries.append(dict(plain="/" + plain.name, encrypted="/" + encrypted.name,
                                passphrase=passphrase, public=" ".join(public[:2])))
    authentication_metadata = json.dumps(dict(entries=entries,
        password=authentication_password, otp=authentication_otp)).encode()

def linux_command(*words):
    """Keep Linux CI tools direct while preserving the Windows/WSL fixture adapter."""
    return (["wsl.exe", "--exec"] if args.wsl_tmux else []) + list(words)

def linux_path(name):
    """Resolve only repository-owned fixture helpers, never a remote command path."""
    path = str(Path(__file__).resolve().parent / name)
    if not args.wsl_tmux:
        return path
    return subprocess.run(linux_command("wslpath", "-a", path),
        capture_output=True, timeout=10, check=True).stdout.decode().strip()

tools_enabled = args.wsl_tmux or args.native_tools

class Files(paramiko.SFTPServerInterface):
    def local(self, path):
        result = (root / path.lstrip("/")).resolve()
        if result != root and root not in result.parents:
            raise PermissionError()
        return result
    def stat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    lstat = stat
    def list_folder(self, path):
        try:
            entries = []
            for child in sorted(self.local(path).iterdir(), reverse=True):
                item = paramiko.SFTPAttributes.from_stat(child.stat())
                item.filename = child.name
                entries.append(item)
            return entries
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def open(self, path, flags, attr):
        try:
            # Honour the client's creation mode like OpenSSH: 0666 when absent, then the umask.
            mode = attr.st_mode & 0o7777 if attr is not None and attr.st_mode is not None else 0o666
            fd = os.open(self.local(path), flags | getattr(os, "O_BINARY", 0), mode)
            stream = os.fdopen(fd, "r+b" if flags & os.O_RDWR else "wb" if flags & os.O_WRONLY else "rb", buffering=0)
            handle = paramiko.SFTPHandle(flags)
            handle.readfile = stream
            handle.writefile = stream
            handle.stat = lambda: paramiko.SFTPAttributes.from_stat(os.fstat(stream.fileno()))
            return handle
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def remove(self, path):
        try: self.local(path).unlink(); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def rename(self, old, new):
        try:
            if self.local(new).exists(): return paramiko.SFTP_FAILURE
            self.local(old).rename(self.local(new)); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def posix_rename(self, old, new):
        try: os.replace(self.local(old), self.local(new)); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def chattr(self, path, attr):
        if args.reject_metadata: return paramiko.SFTP_PERMISSION_DENIED
        try: paramiko.SFTPServer.set_file_attr(str(self.local(path)), attr); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def mkdir(self, path, attr):
        try: self.local(path).mkdir(); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)

class Sftp(paramiko.SFTPServer):
    def _send_server_version(self):
        kind, data = self._read_packet()
        if kind != CMD_INIT: raise IOError("Expected initialization")
        message = paramiko.Message()
        message.add_int(3)
        if not args.no_posix_rename:
            message.add_string("posix-rename@openssh.com")
            message.add_string("1")
        self._send_packet(CMD_VERSION, message)
        return struct.unpack(">I", data[:4])[0]

class Server(paramiko.ServerInterface):
    def __init__(self, transport):
        self.forward = set()
        self.transport = transport
        self.listeners = {}
    def close(self):
        for listener in list(self.listeners.values()):
            try: listener.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            listener.close()
        self.listeners.clear()
    def check_channel_exec_request(self, channel, command):
        if command == b"fixture-auth" and authentication_metadata is not None:
            def credentials():
                try:
                    channel.sendall(authentication_metadata)
                    channel.send_exit_status(0)
                    channel.shutdown_write()
                except (OSError, EOFError): pass
            threading.Thread(target=credentials, daemon=True).start()
            return True
        if command == b"fixture-output":
            def output():
                try:
                    for _ in range(32):
                        channel.sendall(b"o" * 16384)
                        channel.sendall_stderr(b"e" * 8192)
                    channel.send_exit_status(0)
                    channel.shutdown_write()
                    channel.close()
                except (OSError, EOFError): pass
            threading.Thread(target=output, daemon=True).start()
            return True
        if tools_enabled and command == b"fixture-mosh":
            def start_mosh():
                relay = None
                try:
                    server = subprocess.run(linux_command("env", "LANG=C.UTF-8", "MOSH_SERVER_NETWORK_TMOUT=30",
                        "mosh-server", "new", "-s", "-i", "127.0.0.1", "-c", "256", "--", "/bin/sh", linux_path("ssh-fixture-mosh.sh")),
                        capture_output=True, timeout=15)
                    match = re.search(rb"MOSH CONNECT ([0-9]+) ([A-Za-z0-9+/=]+)", server.stdout)
                    if not match: channel.send_exit_status(1); return
                    relay = subprocess.Popen(linux_command("python3", linux_path("ssh-fixture-udp.py"), match[1].decode()),
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
                    channel.sendall(b"MOSH CONNECT 1 " + match[2] + b"\n")
                    def to_udp():
                        try:
                            while True:
                                data = channel.recv(65536)
                                if not data: break
                                relay.stdin.write(data)
                                relay.stdin.flush()
                        except (OSError, EOFError): pass
                        finally: relay.stdin.close()
                    threading.Thread(target=to_udp, daemon=True).start()
                    while True:
                        data = relay.stdout.read(65536)
                        if not data: break
                        channel.sendall(data)
                    channel.send_exit_status(0)
                except (OSError, subprocess.SubprocessError): channel.send_exit_status(1)
                finally:
                    if relay and relay.poll() is None: relay.terminate()
                    channel.shutdown_write()
            threading.Thread(target=start_mosh, daemon=True).start()
            return True
        if not tools_enabled: return False
        try: words = shlex.split(command.decode("utf-8"))
        except (ValueError, UnicodeError): return False
        version = words == ["tmux", "-V"]
        listing = words == ["tmux", "list-sessions", "-F", "#{session_id}\\t#{session_name}"]
        creating = len(words) == 8 and words[:7] == ["tmux", "new-session", "-d", "-P", "-F", "#{session_id}", "-s"] and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", words[7])
        lookup = len(words) == 6 and words[:4] == ["tmux", "display-message", "-p", "-t"] and re.fullmatch(r"=[A-Za-z0-9_-]{1,80}:", words[4]) and words[5] == "#{session_id}"
        if not (version or listing or creating or lookup): return False
        if creating: words.append("/bin/cat")
        def execute():
            try:
                result = subprocess.run(linux_command("tmux", "-L", tmux_socket, "-f", "/dev/null") + words[1:],
                    capture_output=True, timeout=15)
                channel.sendall(result.stdout)
                channel.send_exit_status(result.returncode)
            except (OSError, subprocess.TimeoutExpired): channel.send_exit_status(1)
            finally: channel.shutdown_write()
        threading.Thread(target=execute, daemon=True).start()
        return True
    def check_port_forward_request(self, address, port):
        # 127.0.0.2 lets a test hold two listeners on one port, as a real server may.
        if address not in ("127.0.0.1", "127.0.0.2") or port not in (22500, 22354): return False
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: listener.bind((address, port)); listener.listen(4)
        except OSError: listener.close(); return False
        self.listeners[(address, port)] = listener
        def accept():
            while self.transport.is_active():
                client = None
                try:
                    client, origin = listener.accept()
                    channel = self.transport.open_forwarded_tcpip_channel(origin, (address, port))
                    threading.Thread(target=relay, args=(client, channel), daemon=True).start()
                    threading.Thread(target=relay, args=(channel, client), daemon=True).start()
                except (OSError, EOFError, paramiko.SSHException):
                    if client: client.close()
                    break
        threading.Thread(target=accept, daemon=True).start()
        return port
    def cancel_port_forward_request(self, address, port):
        if args.stall_forward_cancel: threading.Event().wait(60)
        listener = self.listeners.pop((address, port), None)
        if listener:
            try: listener.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            listener.close()
    def check_auth_none(self, username):
        return paramiko.AUTH_FAILED if username in ("fixture-password", "fixture-otp", "fixture-key") else paramiko.AUTH_SUCCESSFUL
    def get_allowed_auths(self, username):
        return {"fixture-password": "password", "fixture-otp": "keyboard-interactive", "fixture-key": "publickey"}.get(username, "none")
    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if username == "fixture-password" and password == authentication_password else paramiko.AUTH_FAILED
    def check_auth_interactive(self, username, submethods):
        if username != "fixture-otp": return paramiko.AUTH_FAILED
        return paramiko.InteractiveQuery("", "", ("Code", False))
    def check_auth_interactive_response(self, responses):
        return paramiko.AUTH_SUCCESSFUL if responses == [authentication_otp] else paramiko.AUTH_FAILED
    def check_auth_publickey(self, username, key):
        return paramiko.AUTH_SUCCESSFUL if username == "fixture-key" and key.asbytes() in authentication_keys else paramiko.AUTH_FAILED
    def check_channel_pty_request(self, channel, term, width, height, pixelwidth, pixelheight, modes): return True
    def check_channel_window_change_request(self, channel, width, height, pixelwidth, pixelheight): return True
    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_direct_tcpip_request(self, chanid, origin, destination):
        if destination == ("127.0.0.1", args.port):
            self.forward.add(chanid)
            return paramiko.OPEN_SUCCEEDED
        return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

def relay(source, destination):
    try:
        while True:
            data = source.recv(32768)
            if not data: break
            destination.sendall(data)
    except OSError: pass
    finally: source.close(); destination.close()

def serve(client):
    transport = paramiko.Transport(client)
    server = Server(transport)
    try:
        if args.legacy_algorithms:
            algorithms = transport.get_security_options()
            algorithms.kex = ("diffie-hellman-group14-sha1",)
            algorithms.key_types = ("ssh-rsa",)
            algorithms.ciphers = ("aes128-cbc",)
            algorithms.digests = ("hmac-sha1",)
        transport.add_server_key(key)
        transport.set_subsystem_handler("sftp", Sftp, Files)
        transport.start_server(server=server)
        channels = []
        while transport.is_active():
            channel = transport.accept(1)
            if channel is None: continue
            channels.append(channel)
            if channel.chanid in server.forward:
                peer = socket.create_connection(("127.0.0.1", args.port), timeout=10)
                peer.settimeout(None)
                threading.Thread(target=relay, args=(channel, peer), daemon=True).start()
                threading.Thread(target=relay, args=(peer, channel), daemon=True).start()
    except (OSError, EOFError, paramiko.SSHException): pass
    finally: server.close(); transport.close()

def terminate(signum, frame):
    raise SystemExit(0)
signal.signal(signal.SIGTERM, terminate)

listener = socket.socket()
listener.bind(("127.0.0.1", args.port))
listener.listen(16)
print("Disposable loopback fixture ready", flush=True)
if tools_enabled: print("Private fixture tmux socket: " + tmux_socket, flush=True)
def stop_from_stdin():
    for line in sys.stdin:
        if line.strip() == "stop":
            listener.close()
            break
threading.Thread(target=stop_from_stdin, daemon=True).start()
try:
    while True:
        try: client, _ = listener.accept()
        except OSError: break
        threading.Thread(target=serve, args=(client,), daemon=True).start()
finally:
    listener.close()
    if tools_enabled:
        subprocess.run(linux_command("tmux", "-L", tmux_socket, "kill-server"), capture_output=True, timeout=10)
    workspace.cleanup()
