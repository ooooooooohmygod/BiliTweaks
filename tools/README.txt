推荐安装（Debian / Ubuntu 及衍生版）:
  sudo apt install libsmali-java

其他系统可下载官方 fat jar 放入本目录（构建脚本会自动使用）:
  curl -LO https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
  curl -LO https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar

备注：Maven Central 上的 org.smali:smali 是库 jar（无 Main-Class），不能独立运行，
请使用上面的官方 fat jar。smali / baksmali 为 BSD-3-Clause 许可的开源项目。
