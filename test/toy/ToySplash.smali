.class public Ltest/ToySplash;
.super Ljava/lang/Object;

# 测试样本：两个 return-object 出口，应全部被 null_returns 置空
.method public static current()Ljava/lang/String;
    .registers 2

    .line 1
    sget-object v0, Ltest/ToySplash;->banner:Ljava/lang/String;

    return-object v0

    .line 2
    :cond_0
    const/4 v0, 0x0

    return-object v0
.end method

.field public static banner:Ljava/lang/String;
