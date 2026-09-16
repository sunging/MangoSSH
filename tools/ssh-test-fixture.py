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
from pathlib import Path
import paramiko
from paramiko.sftp import CMD_INIT, CMD_VERSION

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=22349)
parser.add_argument("--wsl-tmux", action="store_true")
parser.add_argument("--no-posix-rename", action="store_true")
parser.add_argument("--reject-metadata", action="store_true")
parser.add_argument("--stall-forward-cancel", action="store_true")
args = parser.parse_args()
tmux_socket = "mangossh-fixture-" + uuid.uuid4().hex
key = paramiko.RSAKey.generate(2048)
workspace = tempfile.TemporaryDirectory(prefix="mangossh-ssh-test-")
root = Path(workspace.name).resolve()

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
            fd = os.open(self.local(path), flags | getattr(os, "O_BINARY", 0), 0o600)
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
    def __init__(self): self.forward = set()
    def check_channel_exec_request(self, channel, command):
        if args.wsl_tmux and command == b"fixture-mosh":
            def start_mosh():
                relay = None
                try:
                    def linux_path(name):
                        return subprocess.run(["wsl.exe", "--exec", "wslpath", "-a", str(Path(__file__).resolve().parent / name)],
                            capture_output=True, timeout=10, check=True).stdout.decode().strip()
                    server = subprocess.run(["wsl.exe", "--exec", "env", "LANG=C.UTF-8", "MOSH_SERVER_NETWORK_TMOUT=30",
                        "mosh-server", "new", "-s", "-i", "127.0.0.1", "-c", "256", "--", "/bin/sh", linux_path("ssh-fixture-mosh.sh")],
                        capture_output=True, timeout=15)
                    match = re.search(rb"MOSH CONNECT ([0-9]+) ([A-Za-z0-9+/=]+)", server.stdout)
                    if not match: channel.send_exit_status(1); return
                    relay = subprocess.Popen(["wsl.exe", "--exec", "python3", linux_path("ssh-fixture-udp.py"), match[1].decode()],
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
        if not args.wsl_tmux: return False
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
                result = subprocess.run(["wsl.exe", "--exec", "tmux", "-L", tmux_socket, "-f", "/dev/null"] + words[1:],
                    capture_output=True, timeout=15)
                channel.sendall(result.stdout)
                channel.send_exit_status(result.returncode)
            except (OSError, subprocess.TimeoutExpired): channel.send_exit_status(1)
            finally: channel.shutdown_write()
        threading.Thread(target=execute, daemon=True).start()
        return True
    def check_port_forward_request(self, address, port): return port
    def cancel_port_forward_request(self, address, port):
        if args.stall_forward_cancel: threading.Event().wait(60)
    def check_auth_none(self, username): return paramiko.AUTH_SUCCESSFUL
    def get_allowed_auths(self, username): return "none"
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
    try:
        transport.add_server_key(key)
        transport.set_subsystem_handler("sftp", Sftp, Files)
        server = Server()
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
    finally: transport.close()

def terminate(signum, frame):
    raise SystemExit(0)
signal.signal(signal.SIGTERM, terminate)

listener = socket.socket()
listener.bind(("127.0.0.1", args.port))
listener.listen(16)
print("Disposable loopback fixture ready", flush=True)
if args.wsl_tmux: print("Private fixture tmux socket: " + tmux_socket, flush=True)
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
    if args.wsl_tmux:
        subprocess.run(["wsl.exe", "--exec", "tmux", "-L", tmux_socket, "kill-server"], capture_output=True, timeout=10)
    workspace.cleanup()
