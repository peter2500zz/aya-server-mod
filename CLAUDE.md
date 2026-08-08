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

### 对存档零副作用（硬约束）

**本模组只能持有运行时状态，必须保证对存档完全无副作用。** 卸载本模组后，存档须与从未装过一样。

- 模组自身的状态（如待处理的传送请求）一律放在**内存**中，服务器重启即清空。
- **禁止**写入自定义 NBT、`SavedData` / `DimensionDataStorage`、计分板、玩家 `Attachment`，
  也禁止在存档目录下创建任何文件。
- **禁止**为「重启后恢复未完成的状态」这类需求引入持久化 —— 该状态本就应随重启消失。
- 资源目录只放 `assets/`，**不要新增 `data/`**：带 `data/` 会让 Fabric 注册内置数据包，
  其 id 会被写进 `level.dat` 的已启用数据包列表，卸载模组后原版会报 `Missing data pack mod:<id>`。
- 指令改变原版游戏状态（传送坐标、物品位置、药水效果等）**不属于**副作用 ——
  那是指令本身的语义，且写入的都是原版自己能理解的数据。此约束针对的是**模组专属的持久化数据**。

## 代码结构

```
src/main/java/plus/mygo/
├── AyaServerMod.java      # 主入口，仅负责调用各命令的 register()
├── command/
│   └── XxxCommand.java    # 每条命令一个类
├── i18n/
│   ├── ServerLanguage.java  # 服务端翻译表：加载内置 lang 文件、按语言查表
│   └── Messages.java        # 消息门面：构造带 fallback 的文本并发送
└── tpa/
    └── TpaRequests.java     # /tpa 请求的内存登记处与 tick 计时器
```

- 每条命令独立一个类，放在 `plus.mygo.command` 包下。
- 命令类对外只暴露一个静态方法 `register()`，由 `AyaServerMod.onInitialize()` 调用。
- 命令注册使用 `CommandRegistrationCallback.EVENT`（`net.fabricmc.fabric.api.command.v2`）。
- 本模组为纯服务端 mod（功能逻辑全部在服务端），无客户端源集。
- `fabric.mod.json` 中 `environment` 固定为 `"*"`，**不得改为 `"server"`**。
  `"server"` 仅允许 mod 在专用服务器 JVM 上加载，会导致单人/局域网（集成服务器运行于客户端 JVM 内）中 mod 完全不加载、命令无法注册。

## 命令编写规范

- 所有命令统一用 `.requires(Commands.hasPermission(Commands.LEVEL_ALL))`，
  执行者身份在 `execute()` 里用 `getPlayerOrException()` 校验。
- **禁止使用 `.requires(CommandSourceStack::isPlayer)`**，理由见下。
- tick 换算：`秒数 * 20`，常量用具名 `static final int` 声明。
- 命令执行成功返回 `1`，失败返回 `0`。

### 为什么不能用 `requires(isPlayer)` 限制执行者

服务端下发命令树时，按下式给每个节点打 `FLAG_RESTRICTED`（`Commands$1.isRestricted`）：

```java
return !node.getRequirement().test(this.noPermissionSource);
// noPermissionSource = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS)
//   → CommandSource.NULL，entity = null，level = null
```

客户端 `ClientPacketListener.verifyCommand()` 把命令解析两次（正常权限 / 受限权限），
后者失败即判为 `PERMISSIONS_REQUIRED`，于是点击聊天中的 `run_command` 按钮时弹出：

> You are trying to execute a command that requires elevated permissions.
> This might negatively affect your game.

**这个判定与真实权限等级无关**，它问的是「无权限来源能否解析该命令」。
`isPlayer` 对那个合成来源必然返回 `false`（其 `entity == null`），
于是本模组每一条命令都会被误标为高危受限指令。

`Commands.LEVEL_ALL` 即 `PermissionCheck.AlwaysPass.INSTANCE`，对任何来源恒真，
因此 `Commands.hasPermission(Commands.LEVEL_ALL)` 能如实声明「本指令无需任何权限」。

代价是控制台补全里能看到这些命令，非玩家执行时由 `getPlayerOrException()` 抛出原版
本地化错误 `permissions.requires.player`（"A player is required to run this command here"）。
这与大量原版命令的行为一致，可以接受。

### 聊天按钮

可点击按钮一律用 `ClickEvent.SuggestCommand`（把指令填入聊天栏，玩家按回车确认），
**不要用 `RunCommand`**：即便节点不受限，`RunCommand` 在指令解析异常或需要签名时仍可能弹确认框，
而 `SuggestCommand` 从不触发该窗口，且保留了玩家的一次确认机会。

### 其他

- `requires()` 在 **Brigadier 解析期**用**原始来源**判定，因此 `/execute as <玩家> run <命令>`
  的子命令是拿**外层来源**做可见性检查的。这是原版既定行为，非缺陷。

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

本模组为纯服务端 mod，**不假定客户端安装了本 mod**。所有面向玩家的文本一律通过
`plus.mygo.i18n.Messages` 发出，**调用处只写翻译键，不写字面文本**。

### 工作机制：服务端查表 + fallback

`Component.translatableWithFallback(key, fallback, args...)` 会把「翻译键 + 兜底文本」一并下发。
客户端渲染时：能查到 key 就用自己的翻译，查不到就渲染 fallback。

据此，`Messages` 在发送前按**接收方玩家的客户端语言**从服务端内置语言表里查出文本充当 fallback：

| 客户端情况 | 渲染结果 |
|-----------|---------|
| 未安装本 mod | 显示 fallback —— 而 fallback 已按该玩家的语言选好，**显示正确且语言匹配** |
| 安装了本 mod / 同名资源包 | 查到 key，用客户端自己的翻译渲染，玩家可用资源包自定义文案 |

关键前提（已用 26.2 字节码确认）：原版对**翻译文本与 fallback 文本走同一套 `decomposeTemplate`**，
因此 `%s` 占位符替换对 fallback 同样生效，参数可照常传递。

### 语言文件

路径固定为 `src/main/resources/assets/aya-server-mod/lang/<语言代码>.json`（标准资源包路径，
一份文件同时供服务端查表与客户端资源系统使用）。

- **必须完整维护 `en_us` 与 `zh_cn` 两份**，键集保持一致。
- **`zh_cn` 是基准语言（文案母本）**：新增或改写文案一律先改中文，再据此翻译其余语言。
  启动时 `ServerLanguage` 以它为准校验其余语言，缺键或多键都记 WARN。
- **`en_us` 是兜底语言**：玩家语言未收录时（如 `ja_jp`）回退到英文。
  这与基准语言是两回事 —— 基准决定「以谁为准」，兜底决定「查不到时给谁的文本」。
- 新增语言需在 `ServerLanguage.BUNDLED_LANGUAGES` 中登记。
- 正文与按钮之间用 `\n` 分行。已确认 `StringSplitter.splitLines` 按换行符切分，聊天栏会正常换行。

### 键名规范

`aya-server-mod.command.<命令名>.<用途>`，例如：

```
aya-server-mod.command.tpa.success.self
aya-server-mod.command.back.no_death
```

每个键在使用它的命令类中声明为具名 `private static final String KEY_XXX` 常量，禁止在调用处写裸字符串。

### 代码使用方式

```java
// 给指令执行者的成功回执
Messages.sendSuccess(source, KEY_SUCCESS, target.getDisplayName());

// 给指令执行者的失败提示（原版渲染为红色）
Messages.sendFailure(source, KEY_NO_DEATH);

// 给执行者以外的玩家发消息，按【该玩家自己的】语言渲染
Messages.send(target, KEY_SUCCESS_TARGET, executor.getDisplayName());
```

**参数约定**：可变参数会随消息过网络序列化，只应传 `String` 或 `Component` ——
数字先自行格式化成字符串（浮点数用 `String.format(Locale.ROOT, "%.2f", v)` 固定小数点格式），
玩家名传 `getDisplayName()` 以保留队伍颜色与悬停信息。

### 例外

- 原版命令参数（如 `EntityArgument`）自带的错误提示属于原版行为，保持原样，无需干预。
- 仍可直接复用语义完全吻合的原版翻译键，但**不得凭记忆推测键名**：原版 lang 文件位于
  Minecraft JAR 的 `assets/minecraft/lang/en_us.json`，须 `jar xf` 提取查阅后使用。

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
- 禁止在命令类中直接调用 `sendSuccess` / `sendFailure` / `sendSystemMessage` 发送自造文本 ——
  一律经由 `Messages`，否则会绕过服务端查表，未装本 mod 的客户端将看到裸露的翻译键。
- 禁止只补一种语言的 lang 文件：`en_us` 与 `zh_cn` 必须同步增删，保持键集一致。
- 禁止任何形式的持久化 —— 见「对存档零副作用」。模组状态只能存在于内存。
