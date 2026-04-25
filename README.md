# Whisper Android

Androidデバイス完結型の文字起こしアプリ。[whisper.cpp](https://github.com/ggerganov/whisper.cpp) をネイティブ統合し、FFmpegによる音声変換とForegroundServiceによるバックグラウンド処理で、軽量かつ実用的な日本語文字起こしを実現します。

## 特徴

- 🎙️ **完全オンデバイス**: ネットワーク不要（モデルDL後）、プライバシー配慮
- 📱 **軽量・高速**: whisper.cpp + GGUFの量子化モデル、`use_mmap` によるメモリ節約
- 🔔 **バックグラウンド動作**: ForegroundServiceで画面を閉じても継続、通知バーで進捗表示
- 📂 **柔軟な入出力**: MP3 / M4A / WAV / AAC対応、SAFで任意の場所に.txt保存
- 🗂️ **履歴管理**: Roomで永続化、タップで再表示・共有
- ⚙️ **モデル管理**: Hugging Faceから自動DL、`.bin` のインポートにも対応

## 画面構成

BottomNavigationViewによる3タブ構成:

| タブ | 内容 |
|------|------|
| Transcribe | 音声ファイル選択・開始停止・進捗表示 |
| History | 過去の結果一覧、詳細表示、共有・エクスポート |
| Settings | モデルのDL/インポート/削除、既定の保存先、自動保存 |

## 技術スタック

- **言語**: Kotlin 1.9 / C++ (JNI)
- **最小SDK**: 26 (Android 8.0) / target SDK 34
- **UI**: Material3, ViewBinding, Navigation Component
- **非同期**: Kotlin Coroutines + Flow
- **永続化**: Room
- **バックグラウンド**: ForegroundService (mediaDataSource type)
- **音声変換**: [ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit) (full-gpl)
- **推論**: whisper.cpp (submodule) + CMake + JNI

## セットアップ

### 1. リポジトリを clone

```bash
git clone --recursive https://github.com/<YOUR-USER>/whisper-android.git
cd whisper-android
```

すでにcloneしている場合は submodule を初期化:

```bash
git submodule update --init --recursive
```

### 2. whisper.cpp を追加

whisper.cpp をgit submoduleとして `app/src/main/cpp/whisper.cpp` に配置します:

```bash
git submodule add https://github.com/ggerganov/whisper.cpp.git app/src/main/cpp/whisper.cpp
```

### 3. Android Studio でビルド

- Android Studio Hedgehog (2023.1) 以降推奨
- NDK (Side by side): 26.1.10909125 以降
- CMake 3.22.1 以降

NDKは `SDK Manager > SDK Tools` からインストールしてください。

## モデルの入手

Hugging Face の `ggerganov/whisper.cpp` からGGUFモデルをダウンロードできます。アプリ内の「Settings」タブから自動DLが可能です。

| モデル | サイズ | 推奨RAM |
|--------|--------|--------|
| tiny   | ~75MB  | 2GB+ |
| base   | ~142MB | 3GB+ |
| small  | ~466MB | 4GB+ |
| medium | ~1.5GB | 6GB+ |
| large  | ~2.9GB | 8GB+ |

4GB以下の端末ではtiny/baseの利用を推奨します（アプリ内で警告表示）。

## プロジェクト構成

```
app/src/main/
├── cpp/                          # JNIブリッジ + whisper.cpp submodule
│   ├── CMakeLists.txt
│   ├── whisper_jni.cpp
│   └── whisper.cpp/              # submodule
├── java/com/example/whisperandroid/
│   ├── MainActivity.kt
│   ├── WhisperApplication.kt
│   ├── ui/
│   │   ├── transcribe/           # タブ1: 文字起こし
│   │   ├── history/              # タブ2: 履歴
│   │   └── settings/             # タブ3: 設定・モデル
│   ├── data/
│   │   ├── db/                   # Room
│   │   ├── model/                # モデル管理
│   │   └── prefs/                # SharedPreferences
│   ├── service/                  # ForegroundService + 通知
│   ├── audio/                    # FFmpegラッパ
│   ├── whisper/                  # JNIブリッジ (Kotlin側)
│   └── util/
└── res/
```

## 開発ロードマップ

- [x] フェーズ1: whisper.cpp JNIビルド + FFmpeg音声変換
- [x] フェーズ2: ForegroundService + 通知バー連動
- [x] フェーズ3: モデル自動DL + タブUI
- [x] フェーズ4: Room履歴 + SAFによるテキスト書き出し
- [ ] フェーズ5: マイク直接録音対応
- [ ] フェーズ6: 話者分離 (whisper.cpp diarize)

## ライセンス

本プロジェクト自体は MIT License。
- whisper.cpp: MIT
- ffmpeg-kit (full-gpl): GPLv3 → 本アプリ配布時は GPLv3 互換が必要
- モデル: 各モデルのライセンスに従う

商用配布する場合はffmpeg-kitをLGPL版に差し替え、対応する関数フラグを検討してください。

## 免責

本ソフトウェアは現状のまま提供されます。推論結果の正確性は保証されません。
