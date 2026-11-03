#!/usr/bin/env python3
"""Verify synthetic recovered deliveries with an existing backend checkout.

Requires that checkout's runtime and test dependencies. Uses in-process ASGI
only, never native lifespan; stops before seal/finish. Outputs local synthetic
media/state/report: do not commit or upload the output directory.
"""
import argparse
import collections
import hashlib
import json
import socket
import sys
from pathlib import Path

if not __debug__:
    raise SystemExit('Run without Python -O: verification assertions must be enabled')

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--backend-source', required=True, type=Path)
parser.add_argument('--fixtures', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path,
                    help='New private output directory; must not already exist')
options = parser.parse_args()
backend = options.backend_source.resolve(strict=True)
fixtures = options.fixtures.resolve(strict=True)
root = options.output.absolute()
assert (backend / 'audio_backend/recorder_capture_intake.py').is_file()
assert (backend / 'tests/test_strict_uploader_admission.py').is_file()
sys.dont_write_bytecode = True
sys.path[:0] = [str(backend), str(backend / 'tests')]
from audio_backend import recorder_capture_intake as capture, recording_uploader as u
from audio_backend.recorder_delivery import inspect_delivery
from test_recording_session_contract import handler_app, session, HEADERS
from test_strict_uploader_admission import StrictRoutes, AdmissionBoundary

def deny_network(*a,**k): raise AssertionError('Network forbidden')
socket.create_connection=deny_network
socket.socket.connect=deny_network
root.mkdir(mode=0o700, exist_ok=False)
paths=sorted(fixtures.glob('case-*/r-*'),key=lambda p:int(p.parent.name.split('-')[1]))
assert len(paths)==11
rows=[]
backend_hashes = {
 str(p.relative_to(backend)): hashlib.sha256(p.read_bytes()).hexdigest()
 for base in (backend/'audio_backend', backend/'tests')
 for p in sorted(base.rglob('*.py'))
}
(root/'backend-source-sha256.json').write_text(json.dumps(backend_hashes, indent=2)+'\n')
def hashes(p): return {x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in p.iterdir() if x.is_file()}
for p in paths:
 before=hashes(p);report=inspect_delivery(p,validate_metadata=True)
 assert report['metadata_semantics_validated'] and report['transport_bytes_match']
 assert report['frames']==4 and not report['strict_import_ready'] and not report['immutable_publication_proven']
 case=root/p.parent.name;case.mkdir(mode=0o700)
 row={'case':p.parent.name,'preflight':report}
 with handler_app(case/'server') as (client,app):
  routes=StrictRoutes(client,lose_put=True);sid=session(client)
  args=dict(job_dir=case/'job',api='http://127.0.0.1:8765',session_id=sid,capture_id='recovered-check',exclusive_writer=True,limits=u.Limits(disk_floor=0,deadline_seconds=30,poll_seconds=.001),transport=routes)
  if report['close_reason']=='INTERRUPTION':
   try: capture.run(**args,delivery=p,start=True)
   except capture.Refused as exc:
    assert str(exc)=='capture_interrupted_or_recovered_tail_unresolved'
    row['refusal']=str(exc)
   else: raise AssertionError('Interruption accepted')
   assert not routes.calls and not (case/'job').exists()
  else:
   result=capture.run(**args,delivery=p,start=True)
   assert result['status']=='delivery_staged_without_eos' and result['staged_frames']==4 and result['published_frames']==0
   assert not result['capture_finalized'] and not result['physical_continuity_proven']
   assert not routes.puts and all(m=='GET' for m,_,_ in routes.calls)
   replay=capture.run(**args,delivery=p)
   assert replay==result
   row['staging']=result;row['exact_staging_replay']=True
   if report['close_reason']=='ROTATION':
    try: capture.run(**args,finalize=True)
    except capture.Refused as exc:
     assert 'stop_required' in str(exc);row['finalize_refusal']=str(exc)
    else: raise AssertionError('Rotation finalized')
    assert not routes.puts and all(m=='GET' for m,_,_ in routes.calls)
   else:
    for attempt in range(2):
     try: capture.run(**args,finalize=True)
     except AdmissionBoundary: pass
     else: raise AssertionError('Seal boundary missing')
     assert len(routes.puts)==1
    page=client.get(f'/sessions/{sid}/recordings/recovered-check/pages/1',headers=HEADERS).json()
    assert page['state']=='open' and not page['released'] and len(page['sources'])==1
    state=json.loads((case/'job/uploader/uploader.json').read_text())
    assert state['finalize_requested']
    row['strict_admission']={'sources':1,'frames':4,'source_puts':len(routes.puts),'lost_put_reconciled':True,'replay_no_additional_put':True,'page_state':page['state'],'seal_native_execution':'intentionally_not_run'}
  assert not app.state.worker.thread.is_alive() and not app.state.instance_lock_lifespan_started
  with app.state.store.db() as db:
   work=db.execute('SELECT COUNT(*) FROM recording_work').fetchone()[0]
   assert work==0
  row['worker_started']=False;row['recording_work_rows']=work;row['calls']=routes.calls
 assert hashes(p)==before
 row['originals_unchanged']=True
 rows.append(row)
 print(p.parent.name,report['close_reason'],row.get('refusal',row.get('finalize_refusal','strict source admission passed')),flush=True)
(root/'report.json').write_text(json.dumps(rows,indent=2)+'\n')
counts = collections.Counter((method, status) for row in rows for method, _, status in row['calls'])
summary = {
 'deliveries': 11, 'semantic_preflight_passed': 11,
 'stop_source_admitted': 9, 'interruption_refused': 1,
 'rotation_staged_finalize_refused': 1,
 'staging_calls': 10, 'staging_replay_calls': 10,
 'explicit_finalize_calls': 19, 'pre_seal_boundaries': 18,
 'actual_seal_finish_requests': 0, 'strict_source_puts': 9,
 'lost_successful_puts_reconciled': 9,
 'intake_transport_requests': sum(counts.values()),
 'intake_transport_request_counts': {f'{m} {s}': n for (m, s), n in sorted(counts.items())},
 'harness_session_creation_requests': 11,
 'harness_page_inspection_requests': 9,
 'all_in_process_asgi_requests': sum(counts.values()) + 20,
 'native_execution': False, 'network_connections': False,
 'originals_unchanged': True, 'eos_publication_verified': False,
}
assert summary['all_in_process_asgi_requests'] == 577
(root/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
print(json.dumps(summary, indent=2))
print('ALL 11 VERIFIED; local report:', root/'report.json')
