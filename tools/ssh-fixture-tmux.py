"""Attach to the disposable fixture's tmux server through a real POSIX PTY.

The parent SSH fixture owns the pipes. EOF detaches only this client, keeping
the private tmux session available for a later reconnect. No shell is involved.
"""
import errno
import fcntl
import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import time


def main():
    if len(sys.argv) != 3:
        raise SystemExit(2)
    socket_name, session_id = sys.argv[1:]
    if not re.fullmatch(r'mangossh-fixture-[a-f0-9]{32}', socket_name) or not re.fullmatch(r'\$[0-9]{1,10}', session_id):
        raise SystemExit(2)
    pid, terminal = pty.fork()
    if pid == 0:
        os.execvpe('tmux', ['tmux', '-L', socket_name, '-f', '/dev/null', 'attach-session', '-t', session_id],
                   dict(os.environ, TERM='xterm-256color'))
    try:
        fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
        while True:
            readable, _, _ = select.select([sys.stdin.fileno(), terminal], [], [])
            if sys.stdin.fileno() in readable:
                data = os.read(sys.stdin.fileno(), 65536)
                if not data:
                    break
                while data:
                    data = data[os.write(terminal, data):]
            if terminal in readable:
                try:
                    data = os.read(terminal, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
    except (BrokenPipeError, ConnectionResetError):
        pass
    finally:
        os.close(terminal)
        try:
            os.kill(pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 2
        while os.waitpid(pid, os.WNOHANG)[0] == 0:
            if time.monotonic() >= deadline:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                os.waitpid(pid, 0)
                break
            time.sleep(.02)


if __name__ == '__main__':
    main()
