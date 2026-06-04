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

## i18n 规范

**所有向玩家输出的字符串必须走 i18n，禁止在 Java 代码中硬编码自然语言文本。**
必须同时提供简体中文（`zh_cn`）和英文（`en_us`）两种语言，且两者保持同步。

### 文件位置

```
src/main/resources/assets/aya-server-mod/lang/
├── en_us.json    # 英文（必须）
└── zh_cn.json    # 简体中文（必须）
```

### Translation Key 命名规范

格式：`aya-server-mod.command.<命令名>.<消息类型>`

示例：
```
aya-server-mod.command.tpa.player_not_found
aya-server-mod.command.xxx.success
aya-server-mod.command.xxx.error_no_permission
```

- 全部小写，单词间用下划线分隔。
- `<命令名>` 与指令名称一致（如 `tpa`、`here`）。
- `<消息类型>` 描述消息语义，而非内容（如 `player_not_found` 而非 `player_offline_message`）。

### 代码使用方式

所有向玩家发送的文本必须通过 `Component.translatable()` 构建，禁止使用 `Component.literal()` 传递自然语言：

```java
// ✅ 正确
source.sendFailure(Component.translatable("aya-server-mod.command.tpa.player_not_found", targetName));

// ❌ 错误：硬编码字符串
source.sendFailure(Component.literal("玩家 " + targetName + " 不在线或不存在。"));
```

带参数的字符串在 lang 文件中使用 `%s`（字符串）或 `%d`（整数）作为占位符；
多个参数时使用 `%1$s`、`%2$s` 明确位置：

```json
{
  "aya-server-mod.command.tpa.player_not_found": "Player %s is not online or does not exist."
}
```

### 优先复用原版 i18n Key

若原版已有语义完全吻合的 key，应直接复用，无需自定义：

```java
// ✅ 复用原版 /tp 的成功消息
Component.translatable("commands.teleport.success.entity.single",
    executor.getDisplayName(), target.getDisplayName())

// ✅ 仅在语义不符时才自定义
Component.translatable("aya-server-mod.command.tpa.notified", executor.getDisplayName())
```

原版 lang 文件位于 Minecraft JAR 的 `assets/minecraft/lang/en_us.json`，可通过
`javap` + `jar xf` 提取查阅，禁止凭记忆推测 key 名。

### 工作机制说明

`Component.translatable()` 将翻译键通过网络原样发送给客户端，由客户端在本地语言文件中查找对应文本并渲染。客户端需安装本 mod 或服务器下发的资源包，否则客户端将直接显示翻译键本身。

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
- 禁止在 Java 代码中硬编码自然语言字符串（中文或英文）——必须走 i18n。
- 禁止只更新一种语言的 lang 文件；每次修改必须同时更新 `en_us.json` 和 `zh_cn.json`。
