# aya-server-mod 开发规范

## 环境

- Minecraft 26.2（Mojang 官方映射，已去混淆，**不使用 Yarn**）
- Fabric Loader 0.19.3 / Fabric API 0.152.1+26.2 / Loom 1.16
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
- 本模组为纯服务端 mod（功能逻辑全部在服务端），无客户端源集。
- `fabric.mod.json` 中 `environment` 固定为 `"*"`，**不得改为 `"server"`**。
  `"server"` 仅允许 mod 在专用服务器 JVM 上加载，会导致单人/局域网（集成服务器运行于客户端 JVM 内）中 mod 完全不加载、命令无法注册。

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

## 文本输出规范

本模组为纯服务端 mod，**不假定客户端安装了本 mod**。因此向玩家输出文本时，**只复用原版自带的翻译键，不自定义 i18n key、不维护 lang 文件。**

### 工作机制

`Component.translatable(key, args...)` 将翻译键原样发送给客户端，由客户端在其本地语言文件中查找并渲染。

- 原版自带的 key（如 `commands.teleport.success.entity.single`）存在于所有 vanilla 客户端，因此**无论客户端是否安装本 mod 都能正确显示，且自动跟随客户端语言**。
- 自定义 key（如 `aya-server-mod.command.xxx`）只存在于本 mod 的 lang 文件中，未安装本 mod 的客户端会**原样显示键名**而非文本。纯服务端 mod 无法保证客户端安装，故**禁止使用自定义 key**。

### 代码使用方式

优先复用语义吻合的原版翻译键：

```java
// ✅ 复用原版 /tp 的成功消息，任何客户端都能渲染
Component.translatable("commands.teleport.success.entity.single",
    executor.getDisplayName(), target.getDisplayName())
```

原版 lang 文件位于 Minecraft JAR 的 `assets/minecraft/lang/en_us.json`，可通过
`jar xf` 提取查阅，**禁止凭记忆推测 key 名**。

### 无对应原版 key 时

若确实找不到语义吻合的原版 key，使用 `Component.literal()` 硬编码**简体中文**字面文本
（literal 文本原样发送，不经客户端翻译，因此无客户端依赖）：

```java
source.sendSuccess(() -> Component.literal("……"), false);
```

- 拼接玩家名等动态内容时，使用 `MutableComponent.append(Component)` 而非字符串拼接，
  以保留对方显示名的格式（队伍颜色、悬停信息）。
- 原版命令参数（如 `EntityArgument`）自带的错误提示属于原版行为，保持原样，无需干预。

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
- 禁止自定义 i18n key 或维护 lang 文件——纯服务端 mod 不保证客户端安装，自定义 key 会在客户端显示为键名。只复用原版 key，无对应 key 时用 `Component.literal` 硬编码中文。
