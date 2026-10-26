# K-Craft Piano / Folia edition

[演奏画面](https://kcraft-piano.kaisuta-works.workers.dev/) · [JAR](https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-folia-2.1.1.jar) · [リソースパック](https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-pack.zip)

[jwdeveloper/JW_Piano](https://github.com/jwdeveloper/JW_Piano) のFolia向けフォーク。原版のピアノモデル・テクスチャ・88鍵の音源・鍵盤配置を継承し、日本語のゲーム内操作とブラウザ演奏を追加しました。

原版の公開終了したFluentAPIには依存しません。実行するコードは `folia/`、Web画面とCloudflare中継は `web/` です。ルートの `src/` と `pom.upstream.xml` は原版の履歴・参照用として残しています。ルートの `mvn package` でもFolia版をビルドできます。原版の説明は [README.upstream.md](README.upstream.md) です。

## 2.1.1の修正

- ItemDisplay固有の180度回転を打ち消し、本体・鍵盤・ベンチの座標を一致させました。
- 本体の鍵盤用開口部から白鍵幅と配置を算出し、高さを合わせました。
- 演奏者本人のMinecraft音声を既定で有効に変更。ブラウザと二重になる場合はWeb音量を0にしてください。

## 2.1.0の修正

- PacketEventsへの依存と接続注入を廃止。稼働中サーバーへの後入れを想定した直接音声送信に変更。
- 現行Minecraftのアイテム用アトラスへモデル画像を登録し、市松模様の原因だったMissing texturesを修正。
- ArmorStandの頭部変換に依存する表示をItemDisplayへ移し、鍵盤の描画と当たり判定を同一座標に統一。
- チャンク中央への固定を廃止。設置位置・向きを選択でき、`move`で移動可能。
- USB MIDI鍵盤のリアルタイム入力を画面の主機能にし、機器名・音・強さ・受信数を表示。ゲーム操作中も入力を継続。
- 接続中の2クライアントを残したPlugMan入れ替え、40音の発音・消音、500メッセージの一括入力、88鍵のレイ判定を検証。MIDIイベントはテスト用Web MIDI入力で検証し、物理機器を自動演奏したものではありません。

## 低遅延の経路

1. 手元: キーボード / USB MIDI / タッチ → 読込済みのAudioBufferを即時再生。通信・タイマー・描画を待ちません。
2. ゲーム内音声: 16バイトの入力 → Cloudflare WebSocket → Java WebSocket → サーバー既存接続への送信。PacketEventsや接続受付への注入を使わず、音声のFolia tick待ちもありません。
3. 鍵盤モデル: ItemDisplayを使い、配置・更新・削除を各エンティティを所有するリージョンへ送ります。任意の位置・向きに設置でき、チャンク境界をまたいでも動作します。見た目と同じ鍵盤座標に対してレイと直方体の交差判定を行います。

サーバーはCloudflareへ外向きのWSS接続を張ります。ポート開放や公開用のMinecraft側Webサーバーは不要です。接続URLは256bitのランダム値、発行はゲーム内、1ピアノ1演奏者、最大60分、ログアウト時に失効します。管理用の共有シークレットはブラウザへ配布しません。

### 2.1.0の実測（2026-10-26、Folia 26.2 / Java 25）

PacketEventsなし、チャンク境界をまたぐ配置、接続中の2人を残したPlugMan入れ替え後の測定です。101/101応答、Web往復は中央値22.11ms / p95 24.92ms、サーバー内送信要求まで中央値0.014ms / p95 0.094ms。別クライアントが40音の発音と40音の消音を受信し、500件の一括入力も全件応答しました。これはスピーカーから音が出るまでの時間ではありません。

モデル90個の生成と88鍵の座標判定を検証済みです。新パックの実際のクライアント描画は未確認のため、モデル生成の成功と表示確認は区別しています。

### 2.0.0時点の実測（2026-10-26、Folia 26.2 / Java 25 / PacketEvents 2.14.0）

| 計測 | tickを待つ初期実装 | PacketEventsによる音声分離後 |
|---|---:|---:|
| Web → サーバー → Web 往復・中央値 | 71.20ms | 36.23ms |
| サーバー内の音声送信要求まで・中央値 | 27.98ms | 0.038ms |
| サーバー内処理 p95 | 48.14ms | 0.194ms |
| 正常入力への応答 | 101/101 | 101/101 |

高速版は別セッションでも計測し、往復中央値は18.57ms / 21.58ms / 36.23msでした。表は最後の計測値です。500メッセージの一括入力も500件すべて応答し、近くの別クライアントが指定した40音の発音と40音の消音をすべて受信しました。

Chromiumでの打鍵イベント → AudioBufferSourceNode.start呼出しは中央値0.10ms / p95 0.20ms（23回）。これは耳に届くまでの時間ではありません。テスト環境のAudioContextはbaseLatency 10ms + outputLatency 40msを報告しました。端末・ドライバ・Bluetooth・回線・接続先のCloudflare拠点で数値は変わります。改善前は原版そのものの測定ではなく、このフォークの初期Folia実装です。

実サーバーへのクライアント接続、モデルの生成・保存後の再読込、近くの別クライアントへの発音/消音パケット、Web音源の非無音出力、サステイン、MIDIファイル再生、88鍵、390px幅表示を確認しています。計測結果は [docs/](docs/) にあります。実際のスピーカー出力や物理MIDI機器は自動テストしていません。

## 導入

- Paper/Folia 1.21.4以降向けのAPIでビルド。実機検証はFolia 26.2。Minecraft本体が要求するJavaを使用してください（26.2はJava 25）。
- **2.1.0以降はPacketEvents不要です。** 既存のサーバー接続に音声パケットだけを送信します。高速経路はFolia 26.2で検証済み。対象APIが見つからない版では標準のFoliaスケジューラ経由に切り替えます。
- `kcraft-piano-folia-2.1.1.jar` を `plugins/` へ配置します。2.1.0は接続中プレイヤーがいる状態でのPlugMan入れ替えを検証しています。サーバー全体の再起動は運営者の許可を得てから行ってください。

`plugins/KCraftPiano/config.yml`:

```yaml
web-url: https://kcraft-piano.kaisuta-works.workers.dev
bridge-secret: 'Cloudflare側のBRIDGE_SECRETと同じ値。公開しないこと'
resource-pack-url: https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-pack-2.1.0.zip
resource-pack-sha1: 43674cbc9774365f3c41fcd87a3ad2be0795c8d7
max-pianos: 10
hearing-distance: 32
session-minutes: 60
mute-performer-in-game: false
```

公開されている共有Webサイトへ任意のサーバーが接続できるわけではありません。別サーバーで利用する場合は自分のCloudflareへデプロイし、サーバーとWorker双方に同じシークレットを設定してください。

## 操作

| コマンド | 操作 |
|---|---|
| `/piano` | 日本語の一覧から演奏するピアノを選択 |
| `/piano create [名前]` | 見ているブロックの上、または前方2ブロックへ設置（OP既定） |
| `/piano move 名前` | 指定位置へ移動し、向きも合わせる |
| `/piano connect 名前` | 32ブロック以内から専用URLを発行 |
| `/piano pack` | Java版のモデル・音源パックを適用 |
| `/piano list` | 一覧 |
| `/piano skin 名前 grand/upright/electric/closed` | 外観を変更 |
| `/piano volume 名前 0-100` | ゲーム内音量を変更 |
| `/piano remove 名前` | 削除（設置者か運営） |

PCのA/S/D/F/G/H/J/Kが白鍵、W/E/T/Y/Uが黒鍵、Spaceがサステイン。タッチとUSB MIDI、MIDIファイルの再生・一時停止・シーク・停止にも対応します。MIDI機器にはWeb MIDI対応のChrome / Edgeを使用してください。PCキーやタッチはフォーカス喪失時に離しますが、MIDI入力とペダルはMinecraft操作中も継続します。機器切断やWebSocket切断時には消音します。

権限: `kcraft.piano.play` は全員、`kcraft.piano.create` / `kcraft.piano.admin` はOP既定。接続URLは共有しないでください。

## 原版との差分・制限

- 新しい実行部は旧デスクトップアプリ、FluentAPI/PianoApi、旧保存形式とは互換ではありません。既存JW_Pianoのデータは自動移行せず、別プラグイン名/設定フォルダを使います。
- MIDI曲の管理・再生はWeb側へ移しました。原版の自動ピアニスト、ベンチの移動・着席、粒子エフェクト選択などは新ランタイムに移植していません。
- リソースパックはJavaクライアント用。Bedrock向けのモデル・音源変換は含みません。既存のサーバーパックと併用する場合、leather_horse_armorのアイテム定義は必要に応じて統合してください。サーバー全体の既存resource-pack設定は変更しません。
- 音が届く距離は32ブロック既定。モデルごとに所有リージョンを使うため、チャンク中央への固定はありません。最大10台。それぞれ別のサウンド名を使い、別ピアノの同音を消さないようにしています。
- 高速音声経路で使うプレイヤー位置は、本人のスレッド上で移動イベントから公開したスナップショットです。音声経路からワールド/プレイヤーデータを直接読まず、所有スレッドで取得しておいた接続へ、音声パケットだけを送信します。

## 開発

```powershell
# JDK 21+、Maven 3.9+。26.2で起動する場合はJDK 25。
mvn -f folia/pom.xml clean package
python scripts/build_pack.py
cd web
npm ci
npm run build
npm test
```

JARを `web/public/downloads/` にコピーするとWebサイトから配布できます。

```powershell
npx wrangler whoami
# 値は対話入力。ソースやwrangler.jsoncへ書かない。
npx wrangler secret put BRIDGE_SECRET
npm run deploy
```

`web/scripts/integration.mjs` はSSHでlocalhostへ転送した隔離Foliaと外部公開Workerをつなぐ実機検証用です。ポート35689/35690、テスト用RCONパスワードを `.local/test-rcon.txt` に用意してください。一般サーバーや本番へ無断で実行しないでください。26.2のクライアント側スキーマがminecraft-dataに未収録のため、26.1スキーマ+776ハンドシェイクでチャット・モデル・サウンドを検査し、ログインの追加フィールドには警告が出ます。実際のゲーム描画検証の代替にはなりません。

## クレジット

原作者: jwdeveloper。モデル・テクスチャ・音声素材と原版は [license.txt](license.txt) に従います。原版ライセンスには商用販売/配布制限があり、標準MITとは異なります。本フォークは無償公開です。WebのMIDI解析はMITライセンスの[@tonejs/midi](https://github.com/Tonejs/Midi)。2.1.0以降、PacketEventsへの依存はありません。
