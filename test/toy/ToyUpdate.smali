.class public Ltest/ToyUpdate;
.super Ljava/lang/Object;

# 测试样本：void 方法，应被 force_void 整体禁用（副作用一并跳过）
.method public static sync()V
    .registers 2

    .line 1
    sget-object v0, Ltest/ToySplash;->banner:Ljava/lang/String;

    return-void
.end method

# 测试样本：返回引用，应被 force_null 整体置空（副作用一并跳过）
.method public static cached()Ljava/lang/String;
    .registers 2

    .line 1
    sget-object v0, Ltest/ToySplash;->banner:Ljava/lang/String;

    return-object v0
.end method
