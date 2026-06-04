# aya-server-mod 开发规范

## 环境

- Minecraft 26.1（Mojang 官方映射，已去混淆，**不使用 Yarn**）
- Fabric Loader 0.19.3 / Fabric API 0.145.1+26.1 / Loom 1.16
- Java 25

## 映射规范

Minecraft 26.1 起使用 Mojang 官方类名，**禁止使用 Yarn 映射名**。编写代码前必须查阅当前版本文档确认 API，禁止凭记忆或推理猜测接口。

常用对照：

| Yarn（旧）               | Mojang（当前）             |
|--------------------------|---------------------------|
| `ServerCommandSource`    | `CommandSourceStack`      |
| `CommandManager.literal` | `Commands.literal`        |
| `StatusEffects`          | `MobEffects`              |
| `StatusEffectInstance`   | `MobEffectInstance`       |
| `addStatusEffect()`      | `addEffect()`             |
| `Text.literal`           | `Component.literal`       |

## 设计原则

**最小侵入，最大兼容。**

- 优先使用原版封装好的高层接口，不直接操作内部状态。
- 非必要**禁止使用 Mixin**。如确需使用，须在 PR/commit 说明中给出理由。
- 不引入任何非必要的第三方依赖。
- 不修改现有原版行为，只扩展。

## 代码结构

```
src/main/java/plus/mygo/
├── AyaSServerManagementMod.java   # 入口，仅负责调用各命令的 register()
└── command/
    └── XxxCommand.java            # 每条命令一个类
```

- 每条命令独立一个类，放在 `plus.mygo.command` 包下。
- 命令类对外只暴露一个静态方法 `register()`，由 `AyaSServerManagementMod.onInitialize()` 调用。
- 命令注册使用 `CommandRegistrationCallback.EVENT`（`net.fabricmc.fabric.api.command.v2`）。

## 命令编写规范

- 使用 `.requires()` 在 Brigadier 层面限制执行者，而非在执行逻辑中抛出异常。
- 仅限实体执行的命令：`.requires(source -> source.getEntity() instanceof LivingEntity)`。
- tick 换算：`秒数 * 20`，常量用具名 `static final int` 声明。
- 命令执行成功返回 `1`，失败返回 `0`。

## Git 规范

- 每个功能里程碑单独 commit，commit message 使用 `feat: / fix: / refactor:` 前缀。
- GPG 签名已在本仓库关闭（`commit.gpgsign=false`）。
- **每次编辑代码前确认 git 状态**；有未提交变更时，先确认处理方式再继续。

## 禁止事项

- 禁止使用 Mixin（除非有充分理由且经过确认）。
- 禁止使用 Yarn 映射名。
- 禁止凭推理猜测 API —— 必须查阅对应版本文档或源码。
- 禁止为假设性未来需求添加抽象层或冗余逻辑。
- 禁止在客户端源集（`src/client`）中实现服务端逻辑。
