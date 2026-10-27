"""Rebuild the single modified AGPL display class against the installed proxy API.

Inputs under .local/: bedrock-output/GeyserDisplayEntity-1.0.14.jar,
Geyser-live.jar, and bedrock-proxy/velocity.jar. See bedrock/extension-src/README.md.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
base = root / '.local/bedrock-output/GeyserDisplayEntity-1.0.14.jar'
assert hashlib.sha256(base.read_bytes()).hexdigest() == '0f98ae79e81551d8fb8b8374861a365ddd9ccbffd2cd256658a6c7870c4b9cd4'
annotation = root / '.local/checker-qual-3.49.5.jar'
if not annotation.exists():
    urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/3.49.5/checker-qual-3.49.5.jar', annotation)
assert hashlib.sha256(annotation.read_bytes()).hexdigest() == '508c83c62c344f6f7ee28f47b88a8797d6116d043bfd1ca0576c828dd1df2880'
compiler = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')) if os.environ.get('JAVA_HOME') else shutil.which('javac')
assert compiler, 'Set JAVA_HOME to a JDK 21 or newer'
classes = root / '.local/native-display-classes'
classes.mkdir(parents=True, exist_ok=True)
deps = [base, root / '.local/Geyser-live.jar', annotation, root / '.local/bedrock-proxy/velocity.jar']
subprocess.run([compiler, '--release', '21', '-cp', os.pathsep.join(map(str, deps)), '-d', str(classes), str(root / 'bedrock/extension-src/ItemDisplayEntity.java')], check=True)
target = root / 'web/public/downloads/GeyserDisplayEntity-1.0.14-kcraft-native1.jar'
patched_class = 'me/geyserextensionists/geyserdisplayentity/entity/ItemDisplayEntity.class'
with zipfile.ZipFile(base) as original, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
    for name in original.namelist():
        data = (classes / name).read_bytes() if name == patched_class else original.read(name)
        if name == 'extension.yml':
            data = data.replace(b'1.0.14', b'1.0.14-kcraft-native1')
        output.writestr(name, data)
print(target.name, hashlib.sha256(target.read_bytes()).hexdigest())
