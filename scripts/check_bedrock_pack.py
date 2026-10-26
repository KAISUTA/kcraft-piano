"""Validate model/sound references in the distributable Bedrock viewer pack."""
import io
import json
import pathlib
import zipfile
from PIL import Image

root = pathlib.Path(__file__).resolve().parents[1]
pack = root / 'web/public/downloads/kcraft-piano-bedrock-2.3.1.mcpack'
mapping = json.loads((root / 'bedrock/kcraft-piano-items.json').read_text(encoding='utf-8'))
models = mapping['items']['minecraft:leather_horse_armor']
with zipfile.ZipFile(pack) as z:
    names = set(z.namelist())
    assert len(names) == len(z.namelist()), 'Duplicate ZIP entries'
    manifest = json.loads(z.read('manifest.json'))
    assert manifest['header']['version'] == [2, 3, 1]
    assert all(0 <= n < 100 for n in manifest['header']['version'])
    geometries = {}
    for name in names:
        if name.startswith('models/') and name.endswith('.json'):
            for geometry in json.loads(z.read(name))['minecraft:geometry']:
                geometries[geometry['description']['identifier']] = geometry
    for model in models:
        assert model['type'] == 'definition' and model['model'].startswith('kcraft:')
        identifier = model['bedrock_identifier']
        attachable = json.loads(z.read('attachables/' + identifier.replace(':', '/') + '.json'))['minecraft:attachable']['description']
        assert attachable['identifier'] == identifier
        assert 'scripts' not in attachable and 'animations' not in attachable
        assert attachable['textures']['default'] + '.png' in names
        geometry = geometries[attachable['geometry']['default']]
        assert any(b.get('binding') == "'geyser_z'" and b['pivot'] == [0, 8, 0] for b in geometry['bones'])
        assert any(b.get('cubes') for b in geometry['bones'])
    sounds = json.loads(z.read('sounds/sound_definitions.json'))['sound_definitions']
    checked = 0
    for slot in range(10):
        for note in range(21, 109):
            sound = sounds[f'kcraft:p{slot}.{note}']['sounds'][0]
            assert z.read(sound['name'] + '.ogg').startswith(b'OggS')
            checked += 1
    black = Image.open(io.BytesIO(z.read('textures/kcraft/piano/key_piano_black_key.png'))).convert('RGB')
    assert max(v[1] for v in black.getextrema()) <= 22, 'Black keys must not use an untinted white atlas'
    assert 'JW-Piano-license.txt' in names
print(json.dumps({'models': len(models), 'soundMappings': checked, 'directDisplayBoneBinding': True,
                  'blackKeyColorBaked': True, 'allReferencesExist': True, 'packVersion': [2, 3, 1]}))
