#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Explicit-serial, root-only bounded probe control. Does not install/restart."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import secrets

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ADB = None  # Selected explicitly in main; importing the protocol helpers does no I/O.


def root(serial, command, data=None):
    return subprocess.run([ADB, '-s', serial, 'exec-out', 'su', '-c', command],
                          input=data, capture_output=True, check=True, timeout=10).stdout


def status(serial):
    data = root(serial, 'cat /data/adb/hyperspectrum-tap/status')
    if len(data) > 65536:
        raise RuntimeError('oversize status')
    value = json.loads(data)
    if value.get('abi') != 2:
        raise RuntimeError('wrong status ABI')
    return value


def encode(instance, sequence, uid=0, session=0, io=0, seconds=0, not_after_ns=0):
    if not 0 <= seconds <= 60 or (seconds and not (10000 <= uid < 1000000 and
            0 < session < 0x7fffffff and 0 <= io < 0x7fffffff)):
        raise ValueError('requires UID >=10000, positive session, I/O >=0, 1..60 seconds')
    return struct.pack('<4I3Q4IQ', 0x48535450, 2, 64, 0,
                       instance, sequence, not_after_ns, uid, session, io, seconds, 0)


def submit(serial, payload):
    # Directory is created as root:root 0700 by this module. Commands never
    # specify a path, address, executable, or hook. Atomic rename is one-shot.
    # exec-out does not reliably close the remote stdin on host EOF. Transfer
    # a finite file so a remote cat can never wait indefinitely for EOF.
    remote = '/data/local/tmp/hyperspectrum-tap-' + secrets.token_hex(8) + '.command'
    try:
        with tempfile.NamedTemporaryFile(prefix='hyperspectrum-command-', suffix='.bin') as f:
            f.write(payload); f.flush()
            subprocess.run([ADB, '-s', serial, 'push', f.name, remote],
                           capture_output=True, check=True, timeout=10)
        root(serial, 'test "$(stat -c %u:%a /data/adb/hyperspectrum-tap)" = 0:700 && '
             'test ! -L /data/adb/hyperspectrum-tap && umask 077 && '
             'cat ' + remote + ' > /data/adb/hyperspectrum-tap/command.tmp && '
             'chmod 0600 /data/adb/hyperspectrum-tap/command.tmp && '
             'mv /data/adb/hyperspectrum-tap/command.tmp /data/adb/hyperspectrum-tap/command')
    finally:
        root(serial, 'rm -f ' + remote)


def main():
    global ADB
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or
                        os.environ.get('ANDROID_SDK_ROOT'), help='Android SDK root')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'out/native-tap/manifest.json',
                        help='Manifest matching the installed native library')
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('status')
    sub.add_parser('off')
    arm = sub.add_parser('arm')
    arm.add_argument('--uid', type=int, required=True)
    arm.add_argument('--session', type=int, required=True)
    arm.add_argument('--io', type=int, default=0, help='0 selects the unique eligible UID/session track; positive values pin an output')
    arm.add_argument('--seconds', type=int, choices=range(1, 61), default=10)
    args = parser.parse_args()
    if args.sdk is None:
        parser.error('Set ANDROID_HOME or pass --sdk SDK_PATH')
    ADB = args.sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
    if not ADB.is_file():
        parser.error(f'ADB missing: {ADB}')
    current = status(args.serial)
    if args.action == 'status':
        print(json.dumps(current, indent=2)); return
    expected = json.loads(args.manifest.read_text())
    if current.get('build') != expected['source_sha256']:
        raise RuntimeError('producer does not match this exact built source')
    if not current.get('connected') or current.get('health') != 4:
        raise RuntimeError('matching producer is not ready')
    # The companion rewrites status on material changes and otherwise every 5 s idle.
    until = time.monotonic() + 6
    while True:
        time.sleep(0.25)
        fresh = status(args.serial)
        if fresh.get('instance') != current.get('instance') or not fresh.get('connected'):
            raise RuntimeError('producer lifecycle change')
        if fresh.get('sequence', 0) > current.get('sequence', 0):
            break
        if time.monotonic() >= until:
            raise RuntimeError('stale status')
    instance = int(fresh['instance'], 16)
    sequence = max(time.monotonic_ns(), fresh['command_sequence'] + 1)
    payload = encode(instance, sequence, args.uid, args.session, args.io, args.seconds,
                     fresh['now_ns'] + 5000000000) if args.action == 'arm' else encode(instance, sequence)
    submit(args.serial, payload)
    try:
        until = time.monotonic() + 5
        while time.monotonic() < until:
            reply = status(args.serial)
            if reply.get('instance') != fresh['instance']:
                raise RuntimeError('producer changed before acknowledgement')
            if reply.get('command_sequence') == sequence:
                if bool(reply['enabled']) != (args.action == 'arm'):
                    raise RuntimeError('command acknowledged without requested state')
                print(json.dumps(reply, indent=2)); return
            time.sleep(0.2)
        raise RuntimeError('no acknowledgement')
    except BaseException:
        # Retire a potentially queued arm command, including on Ctrl-C.
        submit(args.serial, encode(instance, sequence + 1))
        raise


if __name__ == '__main__':
    main()
