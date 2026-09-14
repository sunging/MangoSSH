"""Test-only length-framed stdin/stdout relay to one local Mosh UDP port."""
import socket, struct, sys, threading
port = int(sys.argv[1])
assert 1 <= port <= 65535
udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.connect(("127.0.0.1", port))
udp.settimeout(35)
def exact(count):
    data = bytearray()
    while len(data) < count:
        chunk = sys.stdin.buffer.read(count - len(data))
        if not chunk: raise EOFError()
        data.extend(chunk)
    return data
def send():
    try:
        while True:
            length = struct.unpack(">I", exact(4))[0]
            if not 0 < length <= 65535: break
            udp.send(exact(length))
    except (OSError, EOFError): pass
    finally: udp.close()
threading.Thread(target=send, daemon=True).start()
try:
    while True:
        data = udp.recv(65535)
        sys.stdout.buffer.write(struct.pack(">I", len(data)) + data)
        sys.stdout.buffer.flush()
except OSError: pass
finally: udp.close()
