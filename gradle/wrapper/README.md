# gradle-wrapper.jar について

`gradle/wrapper/gradle-wrapper.jar` はバイナリのため、このスケルトンには含めていません。
以下のいずれかの方法で取得してください。

## 方法1: 別のAndroid Studioプロジェクトからコピー

任意のAndroidプロジェクトの `gradle/wrapper/gradle-wrapper.jar` をこのプロジェクトの
同じ場所にコピーしてください。

## 方法2: Gradleで自動生成

Gradle 8.4 がローカルにインストール済みなら、プロジェクトルートで以下を実行:

```bash
gradle wrapper --gradle-version 8.4 --distribution-type bin
```

## 方法3: Android Studio でプロジェクトを開く

Android Studio で `File > Open` からこのフォルダを開くと、wrapper jar が自動補完されます。

## 方法4: 公式リポジトリからダウンロード

```bash
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar
```
