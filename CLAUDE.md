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
| `GameProfile.getName()`  | `GameProfile.name()`（record accessor）|
| `ServerPlayer.serverLevel()` | `ServerPlayer.level()`（协变返回 `ServerLevel`）|

## 设计原则

**最小侵入，最大兼容。**

- 优先使用原版封装好的高层接口，不直接操作内部状态。
- 非必要**禁止使用 Mixin**。如确需使用，须在 commit 说明中给出理由。
- 不引入任何非必要的第三方依赖。
- 不修改现有原版行为，只扩展。

## 代码结构

```
src/main/java/plus/mygo/
├── AyaServerMod.java      # 主入口，仅负责调用各命令的 register()
└── command/
    └── XxxCommand.java    # 每条命令一个类
```

- 每条命令独立一个类，放在 `plus.mygo.command` 包下。
- 命令类对外只暴露一个静态方法 `register()`，由 `AyaServerMod.onInitialize()` 调用。
- 命令注册使用 `CommandRegistrationCallback.EVENT`（`net.fabricmc.fabric.api.command.v2`）。
- 本模组为纯服务端 mod，`fabric.mod.json` 中 `environment` 固定为 `"server"`，无客户端源集。

## 命令编写规范

- 使用 `.requires()` 在 Brigadier 层面限制执行者，而非在执行逻辑中抛出异常。
- 仅限实体执行的命令：`.requires(source -> source.getEntity() instanceof LivingEntity)`。
- tick 换算：`秒数 * 20`，常量用具名 `static final int` 声明。
- 命令执行成功返回 `1`，失败返回 `0`。

## 注释规范

**所有代码必须附有详尽的中文注释，事无巨细。** 具体要求如下：

### 类级注释（Javadoc）
每个类必须有多行 Javadoc，内容包括：
- 该类的职责与用途（是什么）
- 核心行为或限制条件（有什么约束）
- 与其他类的关联（如适用）

```java
/**
 * /xxx 指令。
 * <p>
 * 说明该指令做什么、适用场景、执行限制（如仅限玩家）。
 */
public class XxxCommand { ... }
```

### 字段注释
每个字段（包括私有字段和常量）必须有单行注释，说明其含义和单位（如有）：

```java
/** 效果持续时长：30 秒，换算为游戏刻（1 秒 = 20 刻）。 */
private static final int DURATION_TICKS = 30 * 20;
```

### 方法注释（Javadoc）
每个方法必须有 Javadoc，包括：
- 方法用途说明
- `@param` 对每个参数的解释
- `@return` 对返回值的解释（`void` 方法除外）

```java
/**
 * 向 Fabric 命令系统注册指令。应在 onInitialize() 中调用一次。
 *
 * @param context Brigadier 提供的指令上下文
 * @return 1 表示执行成功
 */
```

### 行内注释
凡是不能望文生义的代码行，必须附加行内注释，说明**为什么**这样写，而非仅重复代码字面意思。重点说明：
- 转型安全性的前提条件
- 参数的含义（尤其是布尔型、数字型魔法参数）
- Brigadier / Fabric API 的行为约定

```java
// requires() 已保证 getEntity() 非空且为 LivingEntity，此处转型安全。
LivingEntity entity = (LivingEntity) context.getSource().getEntity();

// showParticles=false 不显示粒子；showIcon=true 在 HUD 显示效果图标
entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0, false, false, true));
```

## Git 规范

- 每个功能里程碑单独 commit，commit message 使用 `feat: / fix: / refactor: / docs:` 前缀。
- GPG 签名已在本仓库关闭（`commit.gpgsign=false`）。
- **在开始任何任务前，必须先执行 `git status` 检查未提交变更。**
  若存在未提交文件，说明用户进行了手动修改；须先阅读 `git diff` 理解变更内容，
  为其完成提交后，再执行后续任务。

## 禁止事项

- 禁止使用 Mixin（除非有充分理由且经过确认）。
- 禁止使用 Yarn 映射名。
- 禁止凭推理猜测 API —— 必须查阅对应版本文档或源码。
- 禁止为假设性未来需求添加抽象层或冗余逻辑。
- 禁止省略注释或使用英文注释（代码标识符除外）。
