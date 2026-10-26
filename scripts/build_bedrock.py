"""Build the Bedrock viewer pack using a pinned, external OSS conversion engine.

Prepare once:
  git clone https://github.com/MCManhunt/packconverter .local/packconverter
  git -C .local/packconverter checkout 60e71c8a1cd3f849affac330847c24e3fb6c618f
  npm install --prefix .local/packconverter/packages/core --ignore-scripts --workspaces=false
  npm ci --prefix web
Requires Pillow, Node 24+, and an existing Java pack built by build_pack.py.
"""
import hashlib
import io
import json
import pathlib
import subprocess
import uuid
import zipfile
from PIL import Image, ImageChops

ROOT = pathlib.Path(__file__).resolve().parents[1]
ENGINE = ROOT / '.local/packconverter'
PIN = '60e71c8a1cd3f849affac330847c24e3fb6c618f'
assert subprocess.check_output(['git', '-C', str(ENGINE), 'rev-parse', 'HEAD'], text=True).strip() == PIN
OUT = ROOT / '.local/bedrock-output'
OUT.mkdir(parents=True, exist_ok=True)
source = ROOT / 'web/public/downloads/kcraft-piano-pack.zip'
modern = OUT / 'modern-input.zip'
with zipfile.ZipFile(source) as original, zipfile.ZipFile(modern, 'w', zipfile.ZIP_DEFLATED) as z:
    for name in original.namelist():
        # Do not convert the old ArmorStand transforms or register a competing legacy mapping.
        if not name.startswith(('assets/minecraft/items/', 'assets/minecraft/models/')):
            z.writestr(name, original.read(name))

paths = ['bench', 'key/piano_black_key', 'key/piano_black_key_down', 'key/piano_key',
         'key/piano_key_down', 'piano/electric_piano', 'piano/grand_piano',
         'piano/grand_piano_close', 'piano/up_right_piano_close']
core = ENGINE / 'packages/core'
entry = core / 'kcraft-convert.mjs'
bundle = core / 'kcraft-convert-bundle.mjs'
entry.write_text(
    "import{readFile,writeFile}from'node:fs/promises';import{convertPack}from'./src/index.ts';\n"
    + 'const result=await convertPack(new Uint8Array(await readFile(' + json.dumps(str(modern)) + ')), '
    + json.dumps({'packName': 'K-Craft Piano', 'modernBaseItem': 'minecraft:leather_horse_armor',
                  'furnitureItems': paths, 'optimizePack': True, 'maxCompression': False}) + ');\n'
    + 'const out=' + json.dumps(str(OUT)) + ';\n'
    + "for(const[n,v]of Object.entries({'raw.mcpack':result.mcpack,'kcraft-piano-items.json':result.geyserMappings,'kcraft-piano-displays.yml':result.displayEntityMappings,'conversion-report.json':JSON.stringify(result.report,null,2)}))await writeFile(out+'/'+n,v);\n",
    encoding='utf-8')
esbuild = (ROOT / 'web/node_modules/esbuild/lib/main.js').as_uri()
subprocess.run(['node', '--input-type=module', '-e',
                'import{build}from' + json.dumps(esbuild) + ';await build(' + json.dumps({
                    'entryPoints': [str(entry)], 'bundle': True, 'platform': 'node', 'format': 'esm',
                    'packages': 'external', 'outfile': str(bundle)}) + ');'], check=True)
subprocess.run(['node', str(bundle)], check=True)

target = ROOT / 'web/public/downloads/kcraft-piano-bedrock-2.3.1.mcpack'
with zipfile.ZipFile(OUT / 'raw.mcpack') as original, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
    tinted = {}
    for name in original.namelist():
        data = original.read(name)
        if name == 'manifest.json':
            obj = json.loads(data)
            for item in [obj['header'], *obj['modules']]:
                item['version'] = [2, 3, 1]
            obj['header']['uuid'] = str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://kcraft-piano.kaisuta-works.workers.dev/bedrock-pack'))
            obj['modules'][0]['uuid'] = str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://kcraft-piano.kaisuta-works.workers.dev/bedrock-resources'))
            data = json.dumps(obj).encode()
        elif name.startswith('models/entity/geyser_custom/') and name.endswith('.json'):
            obj = json.loads(data)
            for geometry in obj['minecraft:geometry']:
                for bone in geometry['bones']:
                    if bone['name'] == 'geysercmd':
                        # The display skeleton already owns the complete transform around (0,8,0).
                        bone['binding'] = "'geyser_z'"
            data = json.dumps(obj).encode()
        elif name.startswith('attachables/') and name.endswith('.json'):
            obj = json.loads(data)
            desc = obj['minecraft:attachable']['description']
            desc.pop('scripts', None)
            desc.pop('animations', None)
            identifier = desc['identifier'].split(':')[1]
            color = ((76, 151, 220) if identifier.startswith('key_') and identifier.endswith('_down')
                     else (22, 22, 22) if identifier == 'key_piano_black_key' else None)
            if color:
                # The Java model is white and dyed by the plugin; bake those fixed key colors.
                image = Image.open(io.BytesIO(original.read(desc['textures']['default'] + '.png'))).convert('RGBA')
                rgb = ImageChops.multiply(image.convert('RGB'), Image.new('RGB', image.size, color))
                rgb.putalpha(image.getchannel('A'))
                buffer = io.BytesIO()
                rgb.save(buffer, format='PNG')
                texture = 'textures/kcraft/piano/' + identifier
                tinted[texture + '.png'] = buffer.getvalue()
                desc['textures']['default'] = texture
            data = json.dumps(obj).encode()
        z.writestr(name, data)
    for name, data in tinted.items():
        z.writestr(name, data)
    z.write(ROOT / 'license.txt', 'JW-Piano-license.txt')
for name in ['kcraft-piano-items.json', 'kcraft-piano-displays.yml']:
    (ROOT / 'bedrock' / name).write_bytes((OUT / name).read_bytes())
print(json.dumps({'pack': str(target), 'bytes': target.stat().st_size,
                  'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'converterCommit': PIN}))
