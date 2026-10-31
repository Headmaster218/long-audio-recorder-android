#!/usr/bin/env python3
"""Narrow source contract, not Android execution."""
from pathlib import Path
s=Path('app/src/main/java/io/github/headmaster218/recorder/android/CaptureEngine.java').read_text()
a=s.index('try { admission=SpoolAdmission.beginCapture(); }')
b=s.index('catch (SpoolAdmission.Busy busy)',a)
c=s.index('throw new CaptureFailure(Failure.INITIALIZATION,busy.getMessage());',b)
d=s.index('evidence.openingSpool();',c)
e=s.index('try { spool = new DirectorySpool',d)
assert a<b<c<d<e and s.count('evidence.openingSpool();')==1
assert 'Failure phase = Failure.STORAGE;' in s and 'classify(e,phase)' in s
assert 'SpoolAdmission.abandonOpening(admission); throw e;' in s
print('PASS narrow pre-admission Busy source ordering; true storage failures retained')
