.class public Ltest/ToyFlag;
.super Ljava/lang/Object;

# 测试样本：布尔方法，应被 force_false 替换为恒 false
.method public static enabled()Z
    .registers 2

    .line 1
    const/4 v0, 0x1

    return v0
.end method
