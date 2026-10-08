"""Bundle the isolated P2 web/backend builds without touching trial services or artifacts."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
root = Path(__file__).resolve().parents[1]
original = root / 'build/p2-target/MusicParty-0.0.1-SNAPSHOT.jar'
output = root / 'build/p2/MusicParty-video-p2.jar'
dist = root / 'build/p2-web'
prefix = 'BOOT-INF/classes/static/'
assert original.is_file() and (dist / 'index.html').is_file(), 'Build isolated P2 outputs first'
output.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(original) as source, ZipFile(output, 'w') as target:
    for entry in source.infolist():
        if not entry.filename.startswith(prefix):
            target.writestr(entry, source.read(entry.filename))
    for path in dist.rglob('*'):
        if path.is_file():
            target.writestr(prefix + path.relative_to(dist).as_posix(), path.read_bytes(), compress_type=ZIP_DEFLATED)
with ZipFile(output) as checked:
    assert checked.testzip() is None
    assert checked.read(prefix + 'index.html') == (dist / 'index.html').read_bytes()
    assert b'JarLauncher' in checked.read('META-INF/MANIFEST.MF')
print({'jar': str(output), 'bytes': output.stat().st_size, 'zipIntegrity': 'PASS'})
