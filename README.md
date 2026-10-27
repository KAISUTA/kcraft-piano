# K-Craft Piano / Folia edition

[演奏画面](https://kcraft-piano.kaisuta-works.workers.dev/) · [JAR](https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-folia-2.4.0.jar) · [リソースパック](https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-pack.zip)

[jwdeveloper/JW_Piano](https://github.com/jwdeveloper/JW_Piano) のFolia向けフォーク。原版のピアノモデル・テクスチャ・88鍵の音源・鍵盤配置を継承し、日本語のゲーム内操作とブラウザ演奏を追加しました。

原版の公開終了したFluentAPIには依存しません。実行するコードは `folia/`、Web画面とCloudflare中継は `web/` です。ルートの `src/` と `pom.upstream.xml` は原版の履歴・参照用として残しています。ルートの `mvn package` でもFolia版をビルドできます。原版の説明は [README.upstream.md](README.upstream.md) です。

## 2.4.0の変更

上昇する帯の長さが、実際のキー保持時間に応じて伸びます。押している間は鍵盤側を固定して上端が伸び、離すと長さが確定して末尾も上昇します。上3ブロックの表示範囲から出た部分は切り取られます。ペダルは音の余韻だけに作用し、帯は物理キーの解放で止まります。同じキーの再打鍵は別の帯として保持します。停止・切断では押し続け状態を解除します。

Javaパック・統合版パック2.3.2はそのまま使えます。統合版プロトコルで100ms/800msの帯の長さ、ペダルとの独立、連打、全停止後の削除を確認しています。

## 2.3.2の修正

統合版の表示補間を安定した姿勢へ変更し、モデル移動を16単位/ブロックへ補正。本体と鍵盤の位置ずれ・沈み込みを修正します。表示用パックは専用パックへ統合し、重複するエンティティ定義を配布しません。上昇演出は向きや形状を変えず、エンティティの座標を3ブロック上げます。

本番と同一のFolia実行JARを使った環境で40打鍵・即時解放5打鍵・173個の削除・着席を再確認しました。

## 2.3.1の修正

短い打鍵の押下と解放が同じ更新内に重なっても、上昇する残像が消えないようにしました。通常40打鍵、即時解放5打鍵の全件と、上限128個を含む合計173個の削除を確認しています。

## 2.3.0の変更

- 弾いた鍵盤の残像が約1.6秒かけて3ブロック上昇し、消える演出を追加。音声送信と分離し、最大128個/台で制限します。
- GSitを利用したベンチへの着席。右クリックまたは `/piano sit 名前`、スニークで退出します。
- 統合版の表示・音声に対応する専用パック、Geyserカスタムアイテム定義、GeyserDisplayEntity設定を追加。[統合版導入手順](bedrock/README.md)を参照してください。
- テストクライアントで鍵盤の上昇・全件削除、GSitへの乗車、Bedrockのモデル装備と発音/消音パケットを確認。実機の画面・スピーカー出力は未確認です。

## 2.2.0の変更

- 毎回のセッションURL・60分制限・プレイヤー退出による失効を廃止。固定ページで固定パスワードを入力し、ピアノを選びます。
- 各ピアノが自動で常設WebSocketを接続し、切断時は再接続します。ブラウザの認証はHttpOnly Cookieに30日保存します。
- 裏画面への切替でMIDIファイルを停止する処理を削除。AudioWorkletの音声時計で再生を継続し、USB MIDI入力も維持します。
- Java版の参加者へパックを自動追加。KCraftGuideの `kcraft_touring` が有効な間は待ち、終了後に適用します。案内パックを置き換えません。統合版にはGeyser経由で専用パックを配布し、Javaパックは送信しません。

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

サーバーはCloudflareへ外向きのWSS接続を張ります。ポート開放や公開用のMinecraft側Webサーバーは不要です。固定URLへアクセスし、管理者が生成した固定パスワードで認証します。認証後はピアノを選択でき、同じピアノでは最後に接続したページが操作します。管理用の共有シークレットはブラウザへ配布しません。

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
- `kcraft-piano-folia-2.4.0.jar` を `plugins/` へ配置します。2.1.0は接続中プレイヤーがいる状態でのPlugMan入れ替えを検証しています。サーバー全体の再起動は運営者の許可を得てから行ってください。

`plugins/KCraftPiano/config.yml`:

```yaml
web-url: https://kcraft-piano.kaisuta-works.workers.dev
bridge-secret: 'Cloudflare側のBRIDGE_SECRETと同じ値。公開しないこと'
resource-pack-url: https://kcraft-piano.kaisuta-works.workers.dev/downloads/kcraft-piano-pack-2.1.0.zip
resource-pack-sha1: 43674cbc9774365f3c41fcd87a3ad2be0795c8d7
max-pianos: 10
hearing-distance: 32
auto-resource-pack: true
mute-performer-in-game: false
```

公開されている共有Webサイトへ任意のサーバーが接続できるわけではありません。別サーバーで利用する場合は自分のCloudflareへデプロイし、サーバーとWorker双方に同じシークレットを設定してください。

## 操作

| コマンド | 操作 |
|---|---|
| `/piano` | 日本語の一覧から演奏するピアノを選択 |
| `/piano create [名前]` | 見ているブロックの上、または前方2ブロックへ設置（OP既定） |
| `/piano move 名前` | 指定位置へ移動し、向きも合わせる |
| `/piano connect 名前` | 32ブロック以内から固定演奏ページを開く |
| `/piano pack` | Java版のモデル・音源パックを適用 |
| `/piano list` | 一覧 |
| `/piano sit 名前` | ベンチに座る（GSitが必要） |
| `/piano skin 名前 grand/upright/electric/closed` | 外観を変更 |
| `/piano volume 名前 0-100` | ゲーム内音量を変更 |
| `/piano remove 名前` | 削除（設置者か運営） |

PCのA/S/D/F/G/H/J/Kが白鍵、W/E/T/Y/Uが黒鍵、Spaceがサステイン。タッチとUSB MIDI、MIDIファイルの再生・一時停止・シーク・停止にも対応します。MIDI機器にはWeb MIDI対応のChrome / Edgeを使用してください。PCキーやタッチはフォーカス喪失時に離しますが、MIDI入力とペダルはMinecraft操作中も継続します。機器切断やWebSocket切断時には消音します。

権限: `kcraft.piano.play` は全員、`kcraft.piano.create` / `kcraft.piano.admin` はOP既定。固定パスワードは演奏を許可する人だけに渡してください。

## 原版との差分・制限

- 新しい実行部は旧デスクトップアプリ、FluentAPI/PianoApi、旧保存形式とは互換ではありません。既存JW_Pianoのデータは自動移行せず、別プラグイン名/設定フォルダを使います。
- MIDI曲の管理・再生はWeb側へ移しました。原版の自動ピアニスト、ベンチの移動・着席、粒子エフェクト選択などは新ランタイムに移植していません。
- リソースパックはJavaクライアント用。Bedrock向けの専用モデル・音源パックは `bedrock/` の手順で導入します。既存のサーバーパックと併用する場合、leather_horse_armorのアイテム定義は必要に応じて統合してください。サーバー全体の既存resource-pack設定は変更しません。
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
# 固定パスワードのSHA-256（小文字16進64文字）。平文を公開しない。
npx wrangler secret put PLAY_PASSWORD_HASH
npm run deploy
```

`web/scripts/integration.mjs` はSSHでlocalhostへ転送した隔離Foliaと外部公開Workerをつなぐ実機検証用です。ポート35689/35690、テスト用RCONパスワードを `.local/test-rcon.txt` に用意してください。一般サーバーや本番へ無断で実行しないでください。26.2のクライアント側スキーマがminecraft-dataに未収録のため、26.1スキーマ+776ハンドシェイクでチャット・モデル・サウンドを検査し、ログインの追加フィールドには警告が出ます。実際のゲーム描画検証の代替にはなりません。

## クレジット

原作者: jwdeveloper。モデル・テクスチャ・音声素材と原版は [license.txt](license.txt) に従います。原版ライセンスには商用販売/配布制限があり、標準MITとは異なります。本フォークは無償公開です。WebのMIDI解析はMITライセンスの[@tonejs/midi](https://github.com/Tonejs/Midi)。2.1.0以降、PacketEventsへの依存はありません。
