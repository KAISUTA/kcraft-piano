"""Validate model/sound references in the distributable Bedrock viewer pack."""
import io
import json
import pathlib
import re
import zipfile
from PIL import Image

root = pathlib.Path(__file__).resolve().parents[1]
pack = root / 'web/public/downloads/kcraft-piano-bedrock-2.5.0.mcpack'
mapping = json.loads((root / 'bedrock/kcraft-piano-items.json').read_text(encoding='utf-8'))
models = mapping['items']['minecraft:leather_horse_armor']
with zipfile.ZipFile(pack) as z:
    names = set(z.namelist())
    assert len(names) == len(z.namelist()), 'Duplicate ZIP entries'
    manifest = json.loads(z.read('manifest.json'))
    assert manifest['header']['version'] == [2, 5, 0]
    assert all(0 <= n < 100 for n in manifest['header']['version'])
    actor = json.loads(z.read('entity/item_display.entity.json'))['minecraft:client_entity']['description']
    assert actor['scripts']['animate'] == ['kcraft_stable']
    animation = json.loads(z.read('animations/kcraft_display.animation.json'))['animations']['animation.kcraft.piano.display.stable']['bones']
    assert all('* 1.6' in expr for expr in animation['geyser']['position'])
    assert actor['render_controllers'] == ['controller.render.kcraft.native_piano']
    controller=json.loads(z.read('render_controllers/kcraft_native.render_controllers.json'))['render_controllers']['controller.render.kcraft.native_piano']
    assert len(controller['arrays']['geometries']['Array.kcraft_models']) == 10
    assert animation['geyser']['position'][0].startswith('-')
    geometries = {}
    for name in names:
        if name.startswith('models/') and name.endswith('.json'):
            for geometry in json.loads(z.read(name))['minecraft:geometry']:
                geometries[geometry['description']['identifier']] = geometry
    native_models = json.loads((root/'bedrock/native-models.json').read_text(encoding='utf-8'))['models']
    extension_source=(root/'bedrock/extension-src/ItemDisplayEntity.java').read_text(encoding='utf-8')
    extension_models=re.findall(r'"([^"]+)"',re.search(r'NATIVE_PIANO_MODELS = List.of\(([^;]+)\)',extension_source).group(1))
    assert extension_models == native_models
    for index,identifier in enumerate(native_models,1):
        assert controller['arrays']['geometries']['Array.kcraft_models'][index] == 'Geometry.native_'+identifier
        assert actor['geometry']['native_'+identifier] in geometries
    for model in models:
        assert model['type'] == 'definition' and model['model'].startswith('kcraft:')
        identifier = model['bedrock_identifier']
        attachable = json.loads(z.read('attachables/' + identifier.replace(':', '/') + '.json'))['minecraft:attachable']['description']
        assert attachable['identifier'] == identifier
        assert 'scripts' not in attachable and 'animations' not in attachable
        assert attachable['textures']['default'] + '.png' in names
        geometry = geometries[attachable['geometry']['default']]
        if identifier.split(':')[1] in native_models:
            assert geometry['bones'][0]['name'] == 'geyser' and all('binding' not in b for b in geometry['bones'])
        else:
            assert any(b.get('binding') == "'geyser_z'" and b['pivot'] == [0, 8, 0] for b in geometry['bones'])
        assert any(b.get('cubes') for b in geometry['bones'])
    sounds = json.loads(z.read('sounds/sound_definitions.json'))['sound_definitions']
    checked = 0
    for slot in range(10):
        for note in range(21, 109):
            sound = sounds[f'kcraft:p{slot}.{note}']['sounds'][0]
            assert z.read(sound['name'] + '.ogg').startswith(b'OggS')
            checked += 1
    # Compare actual source meshes and atlas alpha: opaque body panels must not vanish.
    with zipfile.ZipFile(root / 'web/public/downloads/kcraft-piano-pack.zip') as java:
        face_count = 0
        for model in models:
            name = model['model'].split(':')[1]
            source = json.loads(java.read('assets/kcraft/models/' + name + '.json'))
            attachable = json.loads(z.read('attachables/' + model['bedrock_identifier'].replace(':', '/') + '.json'))['minecraft:attachable']['description']
            geometry = geometries[attachable['geometry']['default']]
            cubes = [cube for bone in geometry['bones'] for cube in bone.get('cubes', [])]
            assert len(cubes) == len(source['elements']), 'Model parts dropped: ' + name
            atlas = Image.open(io.BytesIO(z.read(attachable['textures']['default'] + '.png'))).convert('RGBA')
            for element, cube in zip(source['elements'], cubes):
                for face, data in element['faces'].items():
                    uv = data.get('uv')
                    if uv is None:
                        continue
                    texture = source['textures'][data['texture'][1:]].split(':')[-1]
                    image = Image.open(io.BytesIO(java.read('assets/minecraft/textures/' + texture + '.png'))).convert('RGBA')
                    def alpha(image, u, v):
                        return image.getpixel((max(0, min(image.width - 1, int(u))), max(0, min(image.height - 1, int(v)))))[3]
                    expected = alpha(image, (uv[0]+uv[2])/32*image.width, (uv[1]+uv[3])/32*image.height)
                    b = cube['uv'][face]
                    actual = alpha(atlas, b['uv'][0]+b['uv_size'][0]/2, b['uv'][1]+b['uv_size'][1]/2)
                    assert expected < 128 or actual >= 128, 'Opaque source face became transparent: ' + name
                    face_count += 1
    # The root pivot is 8px; y-offset=-.5 cancels its unscaled half block.
    # Applying the pack's real expression must put the grand-piano feet at floor Y=0.
    expr = animation['geyser']['position'][1].replace("q.property('geyser:t_y')", '9')
    translation_pixels = eval(expr, {'__builtins__': {}}, {})
    floor = -.5 + .5 + 1.8*(0/16-.5) + translation_pixels/16
    assert abs(floor) < 1e-7, 'Piano feet must not sink below the Java floor'
    black = Image.open(io.BytesIO(z.read('textures/kcraft/piano/key_piano_black_key.png'))).convert('RGB')
    assert max(v[1] for v in black.getextrema()) <= 22, 'Black keys must not use an untinted white atlas'
    assert 'JW-Piano-license.txt' in names
print(json.dumps({'models': len(models), 'soundMappings': checked, 'nativeEntityMeshes': 9,
                  'blackKeyColorBaked': True, 'allReferencesExist': True, 'packVersion': [2, 5, 0], 'opaqueFacesChecked': face_count, 'grandPianoFeetY': floor, 'stablePoseOnly': True}))
