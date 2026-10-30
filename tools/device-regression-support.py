"""Diagnostics for CI's disposable emulator; never collect credentials or raw UI text."""
import argparse
import collections
import json
import logging
from pathlib import Path
import re
import subprocess
import sys
import time

SSH_PORTS = (22349, 22350, 22351, 22352, 22353, 22355, 22356, 22358, 22359, 22360, 22361)
REVERSE_PORTS = (*SSH_PORTS, 22354, 22357)


def command(*args):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=20, check=True).stdout.strip()
    except (subprocess.SubprocessError, OSError) as error:
        event('device-command', 'failed', operation=list(args[1:4]), error_type=type(error).__name__)
        raise


def adb(*args):
    return command('adb', *args)


def require(condition, stage):
    if not condition:
        event(stage, 'failed')
        raise RuntimeError(stage)


def event(stage, status, **fields):
    print(json.dumps(dict(stage=stage, status=status, **fields)), flush=True)


def mappings(text):
    return {int(match[0]): int(match[1]) for match in re.findall(r'tcp:(\d+)\s+tcp:(\d+)', text)}


def check_emulator():
    require(adb('get-state') == 'device', 'device-offline')
    require(adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'not-disposable-emulator')
    require(adb('shell', 'getprop', 'sys.boot_completed') == '1', 'device-not-booted')
    event('device', 'online')


def wait_idle(expected, stage, timeout=15):
    deadline = time.monotonic() + timeout
    while True:
        if adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep') == expected:
            return
        if time.monotonic() >= deadline:
            require(False, stage)
        time.sleep(.2)


def current_focus(text):
    match = re.search(r'^\s*mCurrentFocus=(.*)$', text, re.MULTILINE)
    return match.group(1).strip() if match else ''


def window_health(text):
    """Inspect only current focus; never retain window titles or other UI text."""
    focus = current_focus(text)
    anr = 'Application Not Responding:' in focus
    process = re.search(r'Application Not Responding:\s+([A-Za-z0-9_.:]+)(?:\s|})', focus)
    return dict(focused=focus.startswith('Window{'), anr=anr,
                anr_process=process.group(1) if process else None)


def wait_window_ready(timeout=15):
    """Reject an ANR before the suite; never dismiss a dialog or hide app failures."""
    deadline = time.monotonic() + timeout
    while True:
        state = checked_window_state()
        if state['focused']:
            event('device-window', 'ready', **state)
            return
        if time.monotonic() >= deadline:
            event('device-window', 'failed', **state)
            raise RuntimeError('device-window-not-ready')
        time.sleep(.2)


def checked_window_state(text=None):
    """Check current display focus without requiring a sleeping device to be awake yet."""
    state = window_health(adb('shell', 'dumpsys', 'window', 'displays') if text is None else text)
    if state['anr']:
        event('device-window', 'failed', **state)
        raise RuntimeError('device-anr-dialog')
    return state


def settle(stable=5, timeout=120):
    """Wait out post-boot launcher restarts. Input sent to a restarting launcher
    raises an ANR, so never inject key events here or dismiss any dialog."""
    check_emulator()
    for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        adb('shell', 'settings', 'put', 'global', name, '0.0')
    start = time.monotonic()
    previous, since = None, start
    while True:
        text = adb('shell', 'dumpsys', 'window', 'displays')
        state = checked_window_state(text)
        # The focused window identity is compared in memory only, never reported.
        focus = current_focus(text)
        now = time.monotonic()
        if not state['focused'] or focus != previous:
            previous, since = focus, now
        elif now - since >= stable:
            break
        if now - start >= timeout:
            event('boot-settle', 'failed', **state)
            raise RuntimeError('boot-not-settled')
        time.sleep(.5)
    # Insecure keyguard only; this is a WindowManager command, not app input.
    adb('shell', 'wm', 'dismiss-keyguard')
    event('boot-settle', 'ready', seconds=round(time.monotonic() - start, 1))


def prepare():
    check_emulator()
    checked_window_state()
    adb('shell', 'svc', 'wifi', 'enable')
    adb('shell', 'svc', 'data', 'enable')
    for _ in range(30):
        if adb('shell', 'settings', 'get', 'global', 'wifi_on') == '1' and adb('shell', 'settings', 'get', 'global', 'mobile_data') == '1':
            break
        time.sleep(.5)
    else:
        raise RuntimeError('network-preconditions')
    # Full AOSP images can ship with automatic idle modes disabled. Enable deep
    # idle only on this disposable emulator, then prove the real transition.
    adb('shell', 'dumpsys', 'deviceidle', 'enable', 'deep')
    require(adb('shell', 'dumpsys', 'deviceidle', 'enabled', 'deep') == '1', 'doze-disabled')
    # Emulate an unplugged device so force-idle is meaningful, then restore ACTIVE.
    adb('shell', 'dumpsys', 'battery', 'unplug')
    adb('shell', 'dumpsys', 'deviceidle', 'unforce')
    adb('shell', 'input', 'keyevent', '223')
    adb('shell', 'dumpsys', 'deviceidle', 'force-idle')
    wait_idle('IDLE', 'doze-unavailable')
    adb('shell', 'dumpsys', 'deviceidle', 'unforce')
    adb('shell', 'input', 'keyevent', '224')
    wait_idle('ACTIVE', 'doze-not-restored')
    wait_window_ready()
    event('network-and-doze-preconditions', 'ready')


def ssh_probe(port, label):
    import paramiko
    import socket
    stage = 'tcp'
    transport = None
    try:
        sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        transport = paramiko.Transport(sock)
        transport.auth_timeout = 5
        transport.banner_timeout = 5
        stage = 'ssh-handshake'
        transport.start_client(timeout=5)
        require(transport.is_active(), 'ssh-inactive')
        stage = 'authentication'
        transport.auth_none('fixture')
        require(transport.is_authenticated(), 'fixture-authentication')
        event(label, 'ready')
    except Exception as error:
        # Exception messages can contain server material; retain only type and stage.
        event(label, 'failed', phase=stage, error_type=type(error).__name__)
        raise RuntimeError(label) from None
    finally:
        if transport is not None:
            transport.close()


def echo_probe(port):
    import socket
    with socket.create_connection(('127.0.0.1', port), timeout=5) as client:
        client.sendall(b'probe')
        response = bytearray()
        while len(response) < 5:
            chunk = client.recv(5 - len(response))
            require(bool(chunk), 'echo-eof')
            response.extend(chunk)
        require(response == b'probe', 'echo-response')


def probe():
    check_emulator()
    wait_window_ready()
    before = mappings(adb('reverse', '--list'))
    event('reverse-before', 'observed', mappings=before)
    for port in REVERSE_PORTS:
        adb('reverse', f'tcp:{port}', f'tcp:{port}')
    after = mappings(adb('reverse', '--list'))
    require(all(after.get(port) == port for port in REVERSE_PORTS), 'reverse-mapping')
    event('reverse-after', 'ready', mappings=after)
    for port in SSH_PORTS:
        ssh_probe(port, f'host-service-{port}')
        # Reach the reverse listener through adbd, rather than only probing the host.
        local = int(adb('forward', 'tcp:0', f'tcp:{port}'))
        try:
            ssh_probe(local, f'device-reverse-service-{port}')
        finally:
            adb('forward', '--remove', f'tcp:{local}')

    echo_probe(22357)
    local = int(adb('forward', 'tcp:0', 'tcp:22357'))
    try:
        echo_probe(local)
        event('device-reverse-echo', 'ready')
    finally:
        adb('forward', '--remove', f'tcp:{local}')


def safe_log_summary(text):
    """Allowlist event categories, never copy arbitrary log messages into artifacts."""
    counts = collections.Counter()
    for line in text.splitlines():
        for name, pattern in {
            'fatal_exception': r'FATAL EXCEPTION', 'anr': r'\bANR in\b',
            'process_death': r'\b(has died|Process died)\b', 'force_stop': r'Force stopping',
            'ssh_exception': r'\bSSHException\b', 'socket_error': r'\b(ConnectionResetError|ConnectionRefusedError|BrokenPipeError)\b',
            'fixture_ready': r'fixture ready', 'traceback': r'Traceback \(most recent call last\)',
        }.items():
            if re.search(pattern, line):
                counts[name] += 1
    return dict(counts)


def collect(output, fixture_directory):
    output.mkdir(parents=True, exist_ok=True)
    try:
        windows = window_health(adb('shell', 'dumpsys', 'window', 'displays'))
    except Exception as error:
        windows = {'collection_error': type(error).__name__}
    (output / 'window-state.json').write_text(json.dumps(windows, indent=2))
    state = {}
    for name, args in {'device': ('get-state',), 'reverse': ('reverse', '--list'),
                       'idle': ('shell', 'dumpsys', 'deviceidle', 'get', 'deep'),
                       'deep_idle_enabled': ('shell', 'dumpsys', 'deviceidle', 'enabled', 'deep'),
                       'wifi': ('shell', 'settings', 'get', 'global', 'wifi_on'),
                       'mobile_data': ('shell', 'settings', 'get', 'global', 'mobile_data'),
                       'boot': ('shell', 'getprop', 'sys.boot_completed')}.items():
        try:
            value = adb(*args)
            state[name] = mappings(value) if name == 'reverse' else value if value in ('device', 'offline', 'ACTIVE', 'IDLE', '0', '1') else 'other'
        except Exception as error:
            state[name] = type(error).__name__
    (output / 'device-state.json').write_text(json.dumps(state, indent=2))
    try:
        log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', 'AndroidRuntime:E', 'TestRunner:I', '*:S')
        events = []
        for line in log.splitlines():
            categories = safe_log_summary(line)
            if categories:
                # Timestamp and event category only; do not retain arbitrary process messages.
                stamp = re.match(r'\d\d-\d\d \d\d:\d\d:\d\d\.\d+', line)
                events.append({'time': stamp.group(0) if stamp else 'unknown', 'categories': categories})
        (output / 'logcat-summary.json').write_text(json.dumps(events, indent=2))
    except Exception as error:
        (output / 'logcat-summary.json').write_text(json.dumps({'collection_error': type(error).__name__}))
    for path in fixture_directory.glob('fixture-*.log'):
        (output / (path.stem + '.json')).write_text(json.dumps(safe_log_summary(path.read_text(errors='replace')), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('settle', 'prepare', 'probe', 'collect'))
    parser.add_argument('--output', type=Path, default=Path('build/device-diagnostics'))
    parser.add_argument('--fixture-directory', type=Path, default=Path('/tmp'))
    args = parser.parse_args()
    logging.disable(logging.CRITICAL)  # Paramiko must not emit server details.
    try:
        if args.action == 'settle': settle()
        elif args.action == 'prepare': prepare()
        elif args.action == 'probe': probe()
        else: collect(args.output, args.fixture_directory)
    except Exception as error:
        event(args.action, 'failed', error_type=type(error).__name__)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
