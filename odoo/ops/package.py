"""Build distributable archives without machine credentials or local journals."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def include(path):
    return path.is_file() and not any(p in ('__pycache__', 'state', 'private') for p in path.parts) and path.name != 'config.json' and path.suffix != '.pyc'


def archive(name, folder, prefix, extra=()):
    output = ROOT / name
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(folder.rglob('*')):
            if include(path):
                zipped.write(path, str(Path(prefix) / path.relative_to(folder)))
        for path in extra:
            zipped.write(path, path.name)
    print(output.name, output.stat().st_size, 'bytes')


archive('nzqb_label_print-19.0.1.0.0.zip', ROOT / 'addons/nzqb_label_print', 'nzqb_label_print', [ROOT / 'README.md', ROOT / 'TEST_RESULTS.md'])
archive('nzqb_label_connector-1.0.0.zip', ROOT / 'agent', 'nzqb_label_connector', [ROOT / 'README.md', ROOT / 'TEST_RESULTS.md'])
