# Vanilla item_display rotation: https://feedback.minecraft.net/hc/en-us/articles/15044886907149-Minecraft-Java-Edition-23w16a
"""Check the actual pack meshes against the plugin transform and keyboard layout."""
import json, math, re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'folia/src/main/java/jp/kcraft/piano/PianoPlugin.java').read_text(encoding='utf-8')
geometry=(root/'folia/src/main/java/jp/kcraft/piano/KeyboardGeometry.java').read_text(encoding='utf-8')
assert 'scale,new Quaternionf().rotationY((float)Math.PI)' in source, 'Missing inverse of vanilla ItemDisplay Y=180 rotation'
assert '4*KeyboardGeometry.WHITE_WIDTH' in source
width=2.475/52
assert 'WHITE_WIDTH=2.475/52' in geometry and 'x[i]=-1.2375+' in geometry
meshes=root/'pianopack/assets/minecraft/models/item/jw'
# Model coordinates are centered at (8,8,8). Inverse and vanilla half-turns cancel.
def body(p): return [.16875+1.8*(p[0]/16-.5),.9+1.8*(p[1]/16-.5),.2+1.8*(p[2]/16-.5)]
checked=[]
for skin in ['grand_piano','grand_piano_close','electric_piano','up_right_piano_close']:
    model=json.loads((meshes/f'piano/{skin}.json').read_text())
    # Both cheek blocks leave the same opening [-4.5,17.5], Z 8..9.25.
    left=body([-4.5,10,8])[0];right=body([17.5,10,8])[0]
    assert math.isclose(right-left,52*width,abs_tol=1e-9)
    assert any(math.isclose(e['to'][0],-4.5) for e in model['elements'])
    assert any(math.isclose(e['from'][0],17.5) for e in model['elements'])
    checked.append(skin)
whites=0;keys=0
for note in range(21,109):
    black=note%12 in [1,3,6,8,10]
    x=-1.2375+(whites+(0 if black else .5))*width
    if not black: whites+=1
    model=json.loads((meshes/('key/piano_black_key.json' if black else 'key/piano_key.json')).read_text())
    floor=1.195 if black else 1.16;z=.20 if black else .24;depth=.13 if black else .20
    for e in model['elements']:
        for p in [e['from'],e['to']]:
            xx=x+4*width*(p[0]/16-.5);yy=floor+.1+.2*(p[1]/16-.5);zz=z+depth*(p[2]/16-.5)
            assert -1.2375-1e-9<=xx<=1.2375+1e-9
            assert floor-1e-9<=yy<=floor+.05+1e-9
            assert .134<=zz<=.341
            # Check the full render and hit frame at cardinal AND arbitrary yaw.
            for yaw in [0,90,180,260.61197,359]:
                a=math.radians(yaw);c=math.cos(a);s=math.sin(a)
                wx=c*xx-s*zz;wz=s*xx+c*zz
                assert abs(c*wx+s*wz-xx)<1e-9 and abs(-s*wx+c*wz-zz)<1e-9
    keys+=1
assert whites==52
print(json.dumps({'keys':keys,'skins':checked,'yawCases':5,'vanillaHalfTurnCancelled':True,'whiteWidth':width,'whiteSurfaceY':1.21,'blackSurfaceY':1.245}))
