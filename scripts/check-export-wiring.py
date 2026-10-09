#!/usr/bin/env python3
"""Narrow source contracts only, never a replacement for Android runtime/instrumentation tests."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
p = root/'app/src/main/java/io/github/headmaster218/recorder'
ui = (p/'android/ExportControls.java').read_text()
coordinator = (p/'android/ExportCoordinator.java').read_text()
main = (p/'android/MainActivity.java').read_text()
core = '\n'.join((p/('core/'+name+'.java')).read_text() for name in ('CommittedSegments','ExportSession','ExportPickerTicket','VerifiedExport'))
all_export = ui + coordinator + core
assert 'Intent.ACTION_CREATE_DOCUMENT' in ui and 'Intent.CATEGORY_OPENABLE' in ui
assert '"content".equals(uri.getScheme())' in ui and 'DocumentsContract.isDocumentUri(activity, uri)' in ui
assert 'Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION' in ui
assert not re.search(r'takePersistableUriPermission|ACTION_OPEN_DOCUMENT_TREE|MANAGE_EXTERNAL_STORAGE|delete\(|deleteIfExists|deleteDocument|renameDocument|\.seek\(', ui + coordinator)
# The word fsync appears only in the no-assumption comment.
assert "Files.readAllBytes" not in all_export and "new byte[VerifiedExport.BUFFER_BYTES]" in core
assert "Executors.newSingleThreadExecutor" in coordinator
assert "THREAD_PRIORITY_BACKGROUND" in coordinator
assert "exports.saveState(out)" in main and "exports.result(request,result,data)" in main and "exports.render()" in main
assert "ticket.consume(request)" in ui and "ticket.code()" in ui and 'saved.getInt("export-picker-code",10000)' in ui
assert 'putBoolean("in-flight", true).commit()' in coordinator
assert 'getBoolean("in-flight", false)' in coordinator
assert "No automatic retry" in ui
assert "SegmentStore.Published" not in coordinator and "DeletionGate" not in all_export
print("PASS export wiring: one-shot SAF document target, temporary grants, bounded worker, saved picker tickets, interruption marker and no deletion/retention handoff")
print("Source inspection only; Android lifecycle, provider behavior, screen UI and permission grants remain untested.")
