本目录中的 jar 来自 Android tools 开源项目（BSD-3-Clause）：
  - smali 2.5.2    https://repo1.maven.org/maven2/com/android/tools/smali/smali/2.5.2/
  - baksmali 2.5.2 https://repo1.maven.org/maven2/com/android/tools/smali/baksmali/2.5.2/

build.py / selftest.py 会在 PATH 找不到同名命令时自动使用本目录的 jar。
