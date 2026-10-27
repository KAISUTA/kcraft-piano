# 統合版: 表示・音声

Java側のKCraftPiano 2.4.0はFoliaで動作し、統合版クライアントにはGeyserを通して表示・音声を送ります。統合版からの演奏操作はこの対応の必須範囲には含みません。

1. [K-Craft向け修正版の表示拡張](https://kcraft-piano.kaisuta-works.workers.dev/downloads/GeyserDisplayEntity-1.0.14-kcraft-native1.jar) を取得します。GeyserDisplayEntity 1.0.14の修正版です。元の拡張JARと同時に入れないでください。
2. 拡張JARをGeyserの `extensions/`、[K-Craft Piano専用パック](https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-bedrock-2.5.0.mcpack) を `packs/` へ置きます。専用パックには表示拡張の骨格を同梱しています。別の `GeyserDisplayEntityPack.mcpack` は同時に配布しないでください。
3. `kcraft-piano-items.json` を `custom_mappings/` へ置きます。
4. `kcraft-piano-displays.yml` を `extensions/geyserdisplayentity/Mappings/`、`display-config.yml` を同拡張の `config.yml` に置きます。新規導入用です。既存拡張の設定がある場合は統合してください。
5. 新規拡張・カスタムアイテムの登録にはGeyserを実行するプロセスの再起動が必要です。パックは再参加時に配布されます。

`hide-custom-types` を空にする必要があります。既定値の革の馬鎧を隠す設定は、カスタムピアノも隠してしまいます。

モデルは [MCManhunt/packconverter](https://github.com/MCManhunt/packconverter) のコアを使って変換しました。元コードはGPL-3.0、入力モデル・音源はルートの `license.txt` の条件に従います。入力データの商用制限は残ります。変換ツールのソースSHAは `60e71c8a1cd3f849affac330847c24e3fb6c618f`。GeyserDisplayEntityのAGPL-3.0ソースは上記配布元で提供されています。

この専用パックでは、ピアノ本体・鍵盤・椅子のモデルを通常エンティティの骨格として直接描画します。装備品への結合を通さず、ItemDisplayの位置・回転・拡大率を反映します。マニフェストのバージョンは `[2,5,0]` に固定し、各要素を小さい整数にしています。

本番と同じGeyser 2.11.3-b1245 / Velocity 4.3.0で、統合版1.26.51のパック配布・接続・表示エンティティ・モデル装備・発音/消音を検証しています。検証クライアントは描画機能を持たないため、実機の表示確認とは区別してください。

再生成は `python scripts/build_bedrock.py`、参照検証は `python scripts/check_bedrock_pack.py` です。黒鍵の色と押下時の青は、Java側の固定染色情報をテクスチャへ焼き込んでいます。

2.3.2では、統合版の不安定な初期補間を外し、移動量を16モデル単位/ブロックへ補正しています。上昇演出は座標だけを変更し、同じ向きを保ちます。元のGeyserDisplayEntityパックのライセンスと、再現可能な変更処理は同梱ライセンスおよび `scripts/build_bedrock.py` を参照してください。

## Native 2.5.0

2.3.2の実機表示問題が未解決だったため、装備アイテムへのモデル結合を廃止し、通常のエンティティのメッシュとしてピアノ9種類を描画します。拡張側でモデル番号をVARIANTへ送り、対象の装備スロットは空にします。X座標の向きもJavaの表示座標へ合わせています。

ソースと再生成手順は [extension-src/README.md](extension-src/README.md)。新版はパックUUIDも変更し、旧版のキャッシュから独立させています。クライアントへパック全量をダウンロードし、ハッシュ一致、モデル番号、装備スロット空、発音/消音、長さの異なる帯を検証しています。実機描画の正常性はこれらのデータ検査だけでは証明できないため、実機確認が必要です。
