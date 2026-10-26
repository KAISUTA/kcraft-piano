"""Build a current-version JW Piano resource pack without changing original assets."""
import json, zipfile, pathlib, hashlib
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'web/public/downloads';out.mkdir(parents=True,exist_ok=True)
models={167072:'bench',167073:'flyingnote',167074:'icons/icon',167075:'key/piano_black_key',167076:'key/piano_black_key_down',167077:'key/piano_key',167078:'key/piano_key_down',167081:'pianist/pianist_body',167082:'pianist/pianist_hands',167083:'pianist/pianist_head',167084:'piano/electric_piano',167085:'piano/grand_piano',167086:'piano/grand_piano_close',167087:'piano/up_right_piano_close'}
pack=out/'kcraft-piano-pack.zip'
with zipfile.ZipFile(pack,'w',zipfile.ZIP_DEFLATED) as z:
    for p in (root/'pianopack').rglob('*'):
        if p.is_file() and p.suffix!='.zip' and p.name!='pack.mcmeta':z.write(p,p.relative_to(root/'pianopack').as_posix())
    z.writestr('pack.mcmeta',json.dumps({'pack':{'pack_format':88,'supported_formats':{'min_inclusive':46,'max_inclusive':88},'min_format':[46,0],'max_format':[88,0],'description':'K-Craft Piano / JW Piano - Folia edition'}}))
    entries=[{'threshold':n,'model':{'type':'minecraft:model','model':'minecraft:item/jw/'+m,'tints':[{'type':'minecraft:dye','default':16777215}]}} for n,m in sorted(models.items())]
    # Modern 1.21.4+ item definitions; keep old model overrides too for older clients.
    z.writestr('assets/minecraft/items/leather_horse_armor.json',json.dumps({'model':{'type':'minecraft:range_dispatch','property':'minecraft:custom_model_data','index':0,'entries':entries,'fallback':{'type':'minecraft:model','model':'minecraft:item/leather_horse_armor'}}}))
    z.writestr('assets/kcraft/sounds.json',json.dumps({f'p{slot}.{n}':{'sounds':[{'name':f'minecraft:soma/1/c/{n}','stream':False,'preload':True}]} for slot in range(10) for n in range(21,109)}))
    z.write(root/'license.txt','JW-Piano-license.txt')
print(json.dumps({'file':str(pack),'sha1':hashlib.sha1(pack.read_bytes()).hexdigest(),'bytes':pack.stat().st_size}))
