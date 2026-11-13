#!/usr/bin/env python3
"""Regression for redirected-stdin Windows CMD launch / JVMTI readiness.

On Linux this verifies the checked-in script contract. On Windows it *also*
executes the exact PowerShell readiness poll used by launch-bitwig-agent.cmd,
with stdin redirected, a delayed control-file write and a bounded timeout.
Does not launch Bitwig or use user data.
"""
from __future__ import annotations
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time

ROOT=Path(__file__).resolve().parents[1]
LAUNCH=ROOT/'jvmti'/'launch-bitwig-agent.cmd'
CONTROL=ROOT/'jvmti'/'control-tracer.cmd'
launch=LAUNCH.read_text(encoding='utf-8')
control=CONTROL.read_text(encoding='utf-8')

# Input is redirected by Java's ProcessBuilder. timeout.exe must never be used
# to wait in any launch/control branch, because Windows exits immediately.
for path, data in ((LAUNCH,launch),(CONTROL,control)):
    assert not re.search(r'(?im)^\s*(?:>nul\s+)?timeout(?:\.exe)?\s+/t\b',data),path
assert 'Start-Sleep -Milliseconds 1000' in launch
assert 'Start-Sleep -Milliseconds 1000' in control
assert 'if not "%TRACE_ENABLED%"=="1" goto loader_ready' in launch
assert 'if not errorlevel 1 goto agent_ready' in launch
assert 'AddSeconds(90)' in launch
assert "StartsWith('loaded')" in launch
assert 'set "BML_WAIT_TRACE_CONTROL=!CONTROL!"' in launch
assert 'if exist "!CONTROL!" goto agent_ready' not in launch
assert 'exit /b 8' in launch
assert 'BitwigStudioApp.exe did not appear within 30 seconds' in launch
print('PASS: launcher contract (noninteractive waits, 90s populated-control gate, normal mode preserved)')

if os.name != 'nt':
    print('SKIP: native Windows redirected-stdin PowerShell integration (requires Windows)')
    sys.exit(0)

line=next(ln.strip() for ln in launch.splitlines() if ln.strip().startswith('powershell -NoProfile -NonInteractive -Command "$deadline='))
match=re.fullmatch(r'powershell -NoProfile -NonInteractive -Command "(.*)"',line)
assert match,line
poll=match.group(1)
assert 'AddSeconds(90)' in poll

with tempfile.TemporaryDirectory(prefix='BML launch wait spaces ') as temp:
    file=Path(temp)/'tracer status.txt'
    env=os.environ.copy();env['BML_WAIT_TRACE_CONTROL']=str(file)
    # Empty file must not pass as initialized; real tracer writes "loaded".
    file.write_text('',encoding='utf-8')
    def mark_ready():
        time.sleep(0.8)
        file.write_text('loaded installed=0/160 trace=example.tsv\n',encoding='utf-8')
    worker=threading.Thread(target=mark_ready,daemon=True);worker.start()
    started=time.monotonic()
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',poll],env=env,
                          stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                          text=True,timeout=10)
    duration=time.monotonic()-started;worker.join(2)
    assert result.returncode==0,(result.stdout,result.stderr,result.returncode)
    assert duration>=0.6,duration
    print('PASS: actual redirected-stdin PowerShell wait waits for populated control then succeeds')

    file.unlink()
    # Shorten only the test timeout, not the shipped script.
    test_poll=poll.replace('AddSeconds(90)','AddSeconds(2)')
    started=time.monotonic()
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',test_poll],env=env,
                          stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                          text=True,timeout=9)
    duration=time.monotonic()-started
    assert result.returncode==8,(result.stdout,result.stderr,result.returncode)
    assert duration>=1.8,duration
    print('PASS: missing control waits its real timeout and exits 8 (does not spin)')

    started=time.monotonic()
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command','Start-Sleep -Milliseconds 1000'],
                          stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=8)
    assert result.returncode==0,(result.stdout,result.stderr,result.returncode)
    assert time.monotonic()-started>=0.8
    print('PASS: redirected-stdin PID polling sleep')
