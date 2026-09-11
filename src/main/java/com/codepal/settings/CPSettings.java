package com.codepal.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import com.codepal.common.StringUtils;
import com.codepal.db.DBModelConfigRepository;
import com.codepal.mcp.config.McpServerConfig;
import com.codepal.model.ModelConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CP 插件持久化设置
 *
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 聊天模型与内联补全（Ghost Text）模型分开独立配置
 */
@State(
        name = "com.codepal.settings.CPSettings",
        storages = @Storage("CPSettings.xml")
)
public class CPSettings implements PersistentStateComponent<CPSettings> {

    private static final Logger LOG = Logger.getInstance(CPSettings.class);

    // ── 通用字段配置 ──────────────────────────────────────────
    private final String fontStyle = "Microsoft YaHei";

    private final String systemPrompt = "你是 工作助手，一个诚实、友好、干练且会主动使用工具的助手。当用户询问代码工作或相关问题时，你必须使用工具来探索项目，而不是凭空猜测，工具参数也不可凭感觉猜测，需要核实代码找到证据看清逻辑。\n\n" +
            "【深度规则】" +
            "- 通用处理问题骨架：思考问题->分析问题→思考方案→解决方向→选工具→执行→看结果→迭代循环，超过3次工具报错或工具执行无结果→停止→反馈用户。\n" +
            "- 当收到用户的消息时，判别是任务或者需求后，建议先思考，确定真实意图方向，否则实施方向一旦错误，你将可能做无用功，被用户责备，浪费token。这很重要，不要直接回答用户，而是先思考出用户实际意图确认方向，然后再回答用户。\n"+
            "- 但是，请不要陷入长时间自我思考和不断调用工具死循环状态，有任何决策或者想法导致任务节点发生变化，或者不确定下一步怎么做时，请及时向用户提问。\n" +
            "\n\n" +
            "【BUG与问题处理规则】\n" +
            "- 用户发出代码BUG相关的意图时，或者指明让你寻找BUG，或者你自己找到BUG时，必须贯彻一查到底原则，不要猜测，以实际代码为证据，末尾向用户阐释问题产生的根因是什么，该如何解决或者你是如何解决的。\n"+
            "【任务规划规则】\n" +
            "- 当用户要求的任务发出时，你必须在开始修改代码前，先调用 todo 拆解任务为具体步骤清单。\n" +
            "- 每个任务项应包含id(数字)、content(简短描述)、priority(high/medium/low)。\n" +
            "- 每开始一项任务前，调用 todo 将其标记为 in_progress。\n" +
            "- 每完成一项任务后，调用 todo 将其标记为 completed，然后开始下一项。\n" +
            "- 如果执行过程中发现需要新增步骤，可以通过 todo 追加新任务项。\n" +
            "- 同一时间只能有一个任务处于 in_progress 状态。\n\n" +
            "【工具调用规则】\n" +
            "- 尽可能用专用工具而非 bash 命令，这样用户体验更好。文件操作优先用专用文件工具（例如用 read 读文件而不是 cat/head/tail，用 edit 编辑/建文件而不是 sed/awk）。bash 工具只保留给真正需要 shell 执行的系统命令与终端操作。绝不用 bash echo 或其它命令行工具向用户传达想法——所有沟通都直接写在回复文本里。\n" +
            "【向用户提问规则】\n" +
            "- 当你遇到需求不明确、技术选型需要用户拍板、或缺少关键信息会导致走错方向时，调用 ask_user_question 工具向用户提问，而不是猜测。\n" +
            "- 提问时给出清晰的选项（label 简短，description 说明区别），把你推荐的选项放在第一个并标注「(推荐)」。\n" +
            "- 每道题都自动附带「其他」自由输入框，用户可补充说明。\n" +
            "- 只在真正不确定时才提问，能用 read_file_range 确认的就不要问用户。\n" +
            "- 一次提问建议不超过 3 道题，避免给用户造成负担。\n" +
            "【工作流】当用户问代码问题或者有项目需求时，你可按需执行：\n" +
            "  第0步：用 todo 拆解任务清单\n" +
            "  第1步：浏览项目结构 → 了解项目布局\n" +
            "  第2步：全局搜索相关关键词 → 发现涉及哪些文件\n" +
            "  第3步：查看相关文件的大纲 → 了解类结构\n" +
            "  第4步：定位关键方法/类 → 确定具体位置\n" +
            "  第5步：精准读取关键代码行 → 精确分析逻辑\n" +
            "  第6步：给出答案、或修改代码（每完成一项todo标记completed）\n" +
            "  第7步：修改代码，进行代码报错审查，把此步骤加入待办作为最后一项，且必须使用 错误验证子智能体 validate_code 或者编译工具 compile_files 进行审查。请选择其中适合当前问题的审查方案，如果决定使用validate_code，请优先使用快速的linter模式。\n"+
            "  第8步：（审查通过后）将最后一项todo标记为completed，回复用户最终结果\n\n" +
            "【子智能体使用规则】\n" +
            "- 你拥有可独立运行的「子智能体」，用于把繁重的子任务委托出去，避免大量中间结果污染你的主上下文。\n" +
            "- 错误验证子智能体（validate_code 工具）是独立的子智能体。完成代码生成/重构/多文件修改后，调用它对指定文件做 linter 错误检查（仅报 ERROR 级）。它只负责发现错误，不提供修复建议；修复后需再次调用验证。\n" +
            "- 子智能体返回的是可信的精简结论，直接据此继续工作，无需重复发起相同搜索。\n\n" +
            "回复时请使用 Markdown 格式，代码请放在代码块中。";

    // ── 聊天模型列表（落库 model_configs，不再写入 IDE 缓存）──
    @Transient
    private List<ModelConfig> chatModels = new ArrayList<>();
    /** 当前聊天模型 id（唯一真源是 DB 的 is_current，配置读取按此 id 直接查库） */
    @Transient
    private String currentChatModelId = null;

    // ── 补全模型列表（同上，落库）──────────────────────────
    @Transient
    private List<ModelConfig> completionModels = new ArrayList<>();
    @Transient
    private String currentCompletionModelId = null;

    // ── 压缩模型列表（同上，落库；压缩历史时优先使用，未配置则回退聊天模型）────
    @Transient
    private List<ModelConfig> compressionModels = new ArrayList<>();
    @Transient
    private String currentCompressionModelId = null;

    // ── 视觉模型（主模型通过 view_image 工具调用它看图）────────
    private boolean visionEnabled = false;
    private ModelConfig visionModel = null;

    // ── ACP Agent 工具 ──────────────────────────────────────
    private List<String> installedAgentNames = new ArrayList<>();
    private int currentAgentNameIndex = 0;

    // ── MCP 服务器配置（以 JSON 字符串存储，避免 JAXB 序列化问题）──
    private List<String> mcpServerJsons = new ArrayList<>();

    /** 已勾选启用的 skill 名集合（多选）：勾选=注入系统提示词且 load_skill 可读；未勾选=模型不可见且 load_skill 报找不到 */
    private Set<String> enabledSkills = new HashSet<>();

    public Set<String> getEnabledSkills() { return enabledSkills; }
    public void setEnabledSkills(Set<String> s) { this.enabledSkills = s != null ? new HashSet<>(s) : new HashSet<>(); }
    public boolean isSkillEnabled(String name) { return name != null && enabledSkills.contains(name); }
    public void setSkillEnabled(String name, boolean on) {
        if (name == null) return;
        if (on) enabledSkills.add(name); else enabledSkills.remove(name);
    }

    // ── 聊天模型配置 ──────────────────────────────────────────
//    private String apiKey = "";
//    private String apiBase = "https://api.deepseek.com";
//    private String model = "deepseek-chat";
//    private int maxTokens = 4096;
//    private double temperature = 0.7;

    // ── 内联补全（Ghost Text）专用配置 ────────────────────────
//    /** 留空时自动继承聊天 API Key */
//    private String completionApiKey = "";
//    /** 留空时自动继承聊天 API Base */
//    private String completionApiBase = "";
//    /** 补全专用模型，建议用较快的模型 */
//    private String completionModel = "deepseek-chat";
//    /** 补全最大 token（补全片段无需太长） */
//    private int completionMaxTokens = 256;
//    /** 补全 temperature，0.0 最确定性 */
//    private double completionTemperature = 0.0;
    /** 是否启用内联补全 */
    //private boolean enableAutoComplete = true;
    /** 用户停止输入后多少毫秒触发补全（默认 600ms，回车立即触发，普通字符需要停顿） */
    private int completionDelayMs = 600;

    // ── 通用设置 ──────────────────────────────────────────────
    /** 启用会话消息持久化 */
    private boolean enableMessagePersistence = true;
    /** 启用向量库优化 token */
    private boolean enableVectorOptimization = false;
    /** 启用智能内联代码补全 */
    private boolean enableSmartAutoComplete = true;
    /** 编辑/新增文件时自动在编辑器中打开（取消勾选则不打开，已打开的不重复打开） */
    private boolean openFileOnEdit = true;

    public CPSettings() {
        // 注意：模型列表改为从 DB 加载（见 loadState），此处不再种子默认模型，
        // 否则会在 loadState 之前用空列表覆盖已落库的配置。
        ensureDefaultAgents();
    }



    public static CPSettings getInstance() {
        return ApplicationManager.getApplication().getService(CPSettings.class);
    }

    // ── 设置变更监听器 ──
    public interface SettingsChangeListener {
        void onSettingsChanged();
    }
    private final List<SettingsChangeListener> listeners = new ArrayList<>();

    public void addSettingsListener(SettingsChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeSettingsListener(SettingsChangeListener listener) {
        listeners.remove(listener);
    }

    private void fireSettingsChanged() {
        for (SettingsChangeListener l : listeners) {
            try { l.onSettingsChanged(); } catch (Exception ignored) {}
        }
    }

    @Nullable
    @Override
    public CPSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull CPSettings state) {
        XmlSerializerUtil.copyBean(state, this);
        // 模型配置已从 IDE 缓存(xmlb)中剔除，统一从 DB 加载
        migrateLegacyModelsIfNeeded();
        reloadModelsFromDb();
        ensureDefaultModels();
        ensureDefaultAgents();
    }

    /**
     * 从 DB 重新读回模型列表与当前索引（内存缓存）。DB 是唯一真源：
     * 任何需要模型列表的地方都应先调用本方法，而不是沿用旧的内存快照。
     */
    public void reloadModelsFromDb() {
        List<ModelConfig> chat = DBModelConfigRepository.loadModels(DBModelConfigRepository.TYPE_CHAT);
        List<ModelConfig> comp = DBModelConfigRepository.loadModels(DBModelConfigRepository.TYPE_COMPLETION);
        chatModels = (chat != null) ? chat : new ArrayList<>();
        completionModels = (comp != null) ? comp : new ArrayList<>();
        // 当前选中项以 DB 的 is_current 为准（按 id，不用下标/序号）
        currentChatModelId = resolveCurrentId(DBModelConfigRepository.TYPE_CHAT, chatModels);
        currentCompletionModelId = resolveCurrentId(DBModelConfigRepository.TYPE_COMPLETION, completionModels);
        List<ModelConfig> compz = DBModelConfigRepository.loadModels(DBModelConfigRepository.TYPE_COMPRESSION);
        compressionModels = (compz != null) ? compz : new ArrayList<>();
        currentCompressionModelId = resolveCurrentId(DBModelConfigRepository.TYPE_COMPRESSION, compressionModels);
    }

    /** 取当前选中 id；若 DB 里没有 is_current（例如刚删掉了它），自愈为第一条并落库 */
    private static String resolveCurrentId(String type, List<ModelConfig> models) {
        String cur = DBModelConfigRepository.loadCurrentId(type);
        if (cur == null && !models.isEmpty()) {
            cur = models.get(0).getId();
            DBModelConfigRepository.setCurrent(type, cur);
        }
        return cur;
    }

    private static int indexOfId(List<ModelConfig> models, String id) {
        if (id == null || models == null) return 0;
        for (int i = 0; i < models.size(); i++) {
            if (id.equals(models.get(i).getId())) return i;
        }
        return 0;
    }

    /** 首次运行：把旧版写在 CPSettings.xml 里的模型配置迁移进 DB（仅一次） */
    private void migrateLegacyModelsIfNeeded() {
        if (!DBModelConfigRepository.loadModels(DBModelConfigRepository.TYPE_CHAT).isEmpty()) {
            return; // 已迁移
        }
        java.io.File legacy = findLegacySettingsFile();
        if (legacy == null || !legacy.exists()) return;
        try {
            org.jdom.Document doc = com.intellij.openapi.util.JDOMUtil.loadDocument(legacy);
            org.jdom.Element root = doc.getRootElement();
            if (root == null) return;
            List<ModelConfig> chat = parseModelList(root, "chatModels");
            List<ModelConfig> comp = parseModelList(root, "completionModels");
            int chatIdx = parseIntChild(root, "currentChatModelIndex", 0);
            int compIdx = parseIntChild(root, "currentCompletionModelIndex", 0);
            if (!chat.isEmpty()) {
                DBModelConfigRepository.saveModels(DBModelConfigRepository.TYPE_CHAT, chat, clampIndex(chatIdx, chat.size()));
            }
            if (!comp.isEmpty()) {
                DBModelConfigRepository.saveModels(DBModelConfigRepository.TYPE_COMPLETION, comp, clampIndex(compIdx, comp.size()));
            }
        } catch (Exception e) {
            LOG.warn("migrateLegacyModelsIfNeeded failed", e);
        }
    }

    private static List<ModelConfig> parseModelList(org.jdom.Element root, String tag) {
        List<ModelConfig> out = new ArrayList<>();
        org.jdom.Element container = root.getChild(tag);
        if (container == null) return out;
        for (org.jdom.Element child : container.getChildren()) {
            try {
                ModelConfig m = com.intellij.util.xmlb.XmlSerializer.deserialize(child, ModelConfig.class);
                if (m != null) out.add(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static int parseIntChild(org.jdom.Element root, String tag, int def) {
        org.jdom.Element e = root.getChild(tag);
        if (e == null || e.getTextTrim().isEmpty()) return def;
        try {
            return Integer.parseInt(e.getTextTrim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static int clampIndex(int idx, int size) {
        if (size <= 0) return 0;
        if (idx < 0 || idx >= size) return 0;
        return idx;
    }

    private static java.io.File findLegacySettingsFile() {
        String[] candidates = {
                com.intellij.openapi.application.PathManager.getOptionsPath() + java.io.File.separator + "CPSettings.xml",
                com.intellij.openapi.application.PathManager.getConfigPath() + java.io.File.separator + "CPSettings.xml"
        };
        for (String p : candidates) {
            java.io.File f = new java.io.File(p);
            if (f.exists()) return f;
        }
        return null;
    }

    public List<ModelConfig> getChatModels() { return chatModels; }

    /**
     * 仅供反序列化/批量替换使用，不写 DB —— 避免空列表把库里的模型清空。
     * 需要保存请用 {@link #addChatModel} / {@link #updateChatModel} / {@link #removeChatModelById}。
     */
    public void setChatModels(List<ModelConfig> chatModels) {
        this.chatModels = chatModels != null ? chatModels : new ArrayList<>();
        fireSettingsChanged();
    }

    public int getCurrentChatModelIndex() { return indexOfId(chatModels, currentChatModelId); }

    /** 切换当前聊天模型：按 id 写 DB 的 is_current，配置随后按 id 重新查库 */
    public void setCurrentChatModelIndex(int index) {
        if (chatModels == null || index < 0 || index >= chatModels.size()) return;
        setCurrentChatModelId(chatModels.get(index).getId());
    }

    public void setCurrentChatModelId(String id) {
        if (id == null || id.isEmpty()) return;
        currentChatModelId = id;
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_CHAT, id);
    }

    /** 当前聊天模型：按 id 直接查库读整行（以数据库为准，不读内存缓存） */
    public ModelConfig getCurrentChatModel() {
        if (currentChatModelId == null || currentChatModelId.isEmpty()) {
            reloadModelsFromDb();
        }
        ModelConfig m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_CHAT, currentChatModelId);
        if (m == null && chatModels != null && !chatModels.isEmpty()) {
            // 当前 id 失效（行被外部删除等）：自愈到第一条
            setCurrentChatModelId(chatModels.get(0).getId());
            m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_CHAT, currentChatModelId);
        }
        return m;
    }
    // ── ACP Agent 工具 getter/setter ──────────────────────────
    public List<String> getInstalledAgentNames() {
        if (installedAgentNames == null) installedAgentNames = new ArrayList<>();
        return installedAgentNames;
    }

    public String[] getInstalledAgentNamesArray() {
        List<String> agentNames = new ArrayList<>();
        agentNames.addAll(installedAgentNames);
        return agentNames.toArray(new String[0]);
    }

    public void setInstalledAgentNames(List<String> installedAgentNames) {
        this.installedAgentNames = installedAgentNames;
        fireSettingsChanged();
    }

    public String getCurrentAgent() {
        if (installedAgentNames == null || installedAgentNames.isEmpty()) return null;
        if (currentAgentNameIndex < 0 || currentAgentNameIndex >= installedAgentNames.size()) {
            currentAgentNameIndex = 0;
        }
        return installedAgentNames.get(currentAgentNameIndex);
    }

    public int getCurrentAgentNameIndex() {
        return currentAgentNameIndex;
    }

    public void setCurrentAgentNameIndex(int currentAgentNameIndex) {
        this.currentAgentNameIndex = currentAgentNameIndex;
    }



    public String getChatApiKey() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getApiKey() : "";
    }

    public String getChatApiBase() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getApiBase() : "https://api.deepseek.com";
    }

    public String getChatModelName() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getName() : "deepseek-v4-flash";
    }

    public String[] getChatModelNames(){
        List<String> modelIds = new ArrayList<>();
        for (ModelConfig modelConfig : chatModels) {
            modelIds.add(modelConfig.getName());
        }
        return modelIds.toArray(new String[0]);
    }

    @Nullable
    public ModelConfig findChatModelById(String id) {
        return DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_CHAT, id);
    }

    public int findChatModelIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < chatModels.size(); i++) {
            if (id.equals(chatModels.get(i).getId())) return i;
        }
        return -1;
    }

    public int findCompletionModelIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < completionModels.size(); i++) {
            if (id.equals(completionModels.get(i).getId())) return i;
        }
        return -1;
    }

    /** 删除单个聊天模型：只删 DB 里那一行 */
    public void removeChatModelById(String id) {
        if (id == null) return;
        DBModelConfigRepository.deleteModel(id);
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 更新单个聊天模型：只改 DB 里那一行 */
    public void updateChatModel(ModelConfig updated) {
        if (updated == null || updated.getId() == null) return;
        DBModelConfigRepository.updateModel(updated);
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 新增聊天模型：插一行并设为当前选中 */
    public void addChatModel(ModelConfig newModel) {
        if (newModel == null) return;
        if (newModel.getId() == null || newModel.getId().isEmpty()) {
            newModel.setId(UUID.randomUUID().toString());
        }
        DBModelConfigRepository.insertModel(DBModelConfigRepository.TYPE_CHAT, newModel);
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_CHAT, newModel.getId());
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 新增补全模型：插一行并设为当前选中 */
    public void addCompletionModel(ModelConfig newModel) {
        if (newModel == null) return;
        if (newModel.getId() == null || newModel.getId().isEmpty()) {
            newModel.setId(UUID.randomUUID().toString());
        }
        DBModelConfigRepository.insertModel(DBModelConfigRepository.TYPE_COMPLETION, newModel);
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_COMPLETION, newModel.getId());
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 更新单个补全模型：只改 DB 里那一行 */
    public void updateCompletionModel(ModelConfig updated) {
        if (updated == null || updated.getId() == null) return;
        DBModelConfigRepository.updateModel(updated);
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    @Nullable
    public ModelConfig findCompletionModelById(String id) {
        return DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_COMPLETION, id);
    }

    public int getChatMaxTokens() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getMaxTokens() : 8152;
    }

    public int getChatMaxOutput() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getMaxOutput() : 8192;
    }

    public double getChatTemperature() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getTemperature() : 0.7;
    }

    public List<ModelConfig> getCompletionModels() { return completionModels; }

    /**
     * 仅供反序列化/批量替换使用，不写 DB —— 避免空列表把库里的模型清空。
     * 需要保存请用 {@link #addCompletionModel} / {@link #updateCompletionModel}。
     */
    public void setCompletionModels(List<ModelConfig> completionModels) {
        this.completionModels = completionModels != null ? completionModels : new ArrayList<>();
        fireSettingsChanged();
    }

    public int getCurrentCompletionModelIndex() { return indexOfId(completionModels, currentCompletionModelId); }

    /** 切换当前补全模型：按 id 写 DB 的 is_current */
    public void setCurrentCompletionModelIndex(int index) {
        if (completionModels == null || index < 0 || index >= completionModels.size()) return;
        setCurrentCompletionModelId(completionModels.get(index).getId());
    }

    public void setCurrentCompletionModelId(String id) {
        if (id == null || id.isEmpty()) return;
        currentCompletionModelId = id;
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_COMPLETION, id);
    }

    /** 当前补全模型：按 id 直接查库读整行 */
    public ModelConfig getCurrentCompletionModel() {
        if (currentCompletionModelId == null || currentCompletionModelId.isEmpty()) {
            reloadModelsFromDb();
        }
        ModelConfig m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_COMPLETION, currentCompletionModelId);
        if (m == null && completionModels != null && !completionModels.isEmpty()) {
            setCurrentCompletionModelId(completionModels.get(0).getId());
            m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_COMPLETION, currentCompletionModelId);
        }
        return m;
    }

    /**
     * 实际生效的补全模型：已配置且自身 API Key 非空时返回，否则 null。
     * 口径与 {@link #getEffectiveVisionModel()} 一致：只认补全模型自己的 key，不继承聊天模型配置。
     */
    @Nullable
    public ModelConfig getEffectiveCompletionModel() {
        ModelConfig m = getCurrentCompletionModel();
        if (m == null) return null;
        if (m.getApiKey() == null || m.getApiKey().trim().isEmpty()) return null;
        return m;
    }

    public String getCompletionApiKey() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getApiKey() : "";
    }

    public String getCompletionApiBase() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getApiBase() : "";
    }

    public String getCompletionModelName() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getName() : "deepseek-v4-flash";
    }

    public int getCompletionMaxTokens() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getMaxOutput() : 256;
    }

    public double getCompletionTemperature() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getTemperature() : 0.0;
    }

    // ── 压缩模型（镜像补全模型：独立 TYPE_COMPRESSION 表 + 当前 id）─────────
    public int findCompressionModelIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < compressionModels.size(); i++) {
            if (id.equals(compressionModels.get(i).getId())) return i;
        }
        return -1;
    }

    /** 新增压缩模型：插一行并设为当前选中 */
    public void addCompressionModel(ModelConfig newModel) {
        if (newModel == null) return;
        if (newModel.getId() == null || newModel.getId().isEmpty()) {
            newModel.setId(UUID.randomUUID().toString());
        }
        DBModelConfigRepository.insertModel(DBModelConfigRepository.TYPE_COMPRESSION, newModel);
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_COMPRESSION, newModel.getId());
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 更新单个压缩模型：只改 DB 里那一行 */
    public void updateCompressionModel(ModelConfig updated) {
        if (updated == null || updated.getId() == null) return;
        DBModelConfigRepository.updateModel(updated);
        reloadModelsFromDb();
        fireSettingsChanged();
    }

    /** 当前压缩模型：按 id 直接查库读整行（未配置返回 null） */
    @Nullable
    public ModelConfig getCurrentCompressionModel() {
        if (currentCompressionModelId == null || currentCompressionModelId.isEmpty()) {
            reloadModelsFromDb();
        }
        ModelConfig m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_COMPRESSION, currentCompressionModelId);
        if (m == null && compressionModels != null && !compressionModels.isEmpty()) {
            setCurrentCompressionModelId(compressionModels.get(0).getId());
            m = DBModelConfigRepository.loadModelById(DBModelConfigRepository.TYPE_COMPRESSION, currentCompressionModelId);
        }
        return m;
    }

    /**
     * 实际生效的压缩模型：已配置且自身 API Key 非空时返回，否则 null。
     * 口径与 getEffectiveCompletionModel / getEffectiveVisionModel 一致：只认模型自己的 key。
     */
    @Nullable
    public ModelConfig getEffectiveCompressionModel() {
        ModelConfig m = getCurrentCompressionModel();
        if (m == null) return null;
        if (m.getApiKey() == null || m.getApiKey().trim().isEmpty()) return null;
        return m;
    }

    /**
     * 压缩实际使用的模型配置：优先已生效的压缩模型，否则回退当前聊天模型。
     * 供 CompressionManager 发起压缩请求使用。
     */
    @Nullable
    public ModelConfig getCompressionOrChatModel() {
        ModelConfig m = getEffectiveCompressionModel();
        if (m != null) return m;
        return getCurrentChatModel();
    }

    /** 压缩模型名称（供日志/回退）；未配置压缩模型时返回聊天模型名 */
    public String getCompressionModelName() {
        ModelConfig m = getEffectiveCompressionModel();
        return m != null ? m.getName() : getChatModelName();
    }

    public void setCurrentCompressionModelId(String id) {
        if (id == null || id.isEmpty()) return;
        currentCompressionModelId = id;
        DBModelConfigRepository.setCurrent(DBModelConfigRepository.TYPE_COMPRESSION, id);
    }

    public List<ModelConfig> getCompressionModels() { return compressionModels; }

    // ── 视觉模型 getter/setter ────────────────────────────────
    public boolean isVisionEnabled() { return visionEnabled; }
    public void setVisionEnabled(boolean visionEnabled) { this.visionEnabled = visionEnabled; fireSettingsChanged(); }

    public ModelConfig getVisionModel() { return visionModel; }
    public void setVisionModel(ModelConfig visionModel) { this.visionModel = visionModel; fireSettingsChanged(); }

    /**
     * 获取实际生效的视觉模型配置：仅在开关开启且已配置时返回，否则 null。
     * ToolExecutor 据此决定是否真的能调用视觉模型。
     */
    @Nullable
    public ModelConfig getEffectiveVisionModel() {
        if (!visionEnabled || visionModel == null) return null;
        if (visionModel.getApiKey() == null || visionModel.getApiKey().trim().isEmpty()) return null;
        return visionModel;
    }

    public int getCompletionDelayMs() { return completionDelayMs; }
    public void setCompletionDelayMs(int completionDelayMs) { this.completionDelayMs = completionDelayMs; }

    // ── 通用设置 getter/setter ────────────────────────────────
    public boolean isEnableMessagePersistence() { return enableMessagePersistence; }
    public void setEnableMessagePersistence(boolean enableMessagePersistence) { this.enableMessagePersistence = enableMessagePersistence; }

    public boolean isEnableVectorOptimization() { return enableVectorOptimization; }
    public void setEnableVectorOptimization(boolean enableVectorOptimization) { this.enableVectorOptimization = enableVectorOptimization; }

    public boolean isEnableSmartAutoComplete() { return enableSmartAutoComplete; }
    public void setEnableSmartAutoComplete(boolean enableSmartAutoComplete) { this.enableSmartAutoComplete = enableSmartAutoComplete; }

    public boolean isOpenFileOnEdit() { return openFileOnEdit; }
    public void setOpenFileOnEdit(boolean openFileOnEdit) { this.openFileOnEdit = openFileOnEdit; }



    public String getFontStyle() {
        return fontStyle;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 构建包含项目上下文的 System Prompt（动态注入项目路径、名称等）
     * <p>
     * 调用时机：ChatPanel 初始化 & clearChat 时
     */
    public String getSystemPrompt(Project project) {
        StringBuilder sb = new StringBuilder(systemPrompt);
        // 视觉能力段落动态注入：仅当「当前聊天模型自身不具备视觉能力」且「已配置独立视觉模型」时，
        // 才引导主模型通过 view_image 工具调用视觉子智能体；否则主模型自带视觉，无需依赖子智能体。
        appendVisionGuidance(sb);
        sb.append("\n\n---\n【当前项目信息】\n");

        if (project != null) {
            String projectName = project.getName();
            sb.append("- 项目名称：").append(projectName).append("\n");

            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                String basePath = baseDir.getPath();
                sb.append("- 项目根目录：").append(basePath).append("\n");
            }

            // 列出项目根目录下的顶层结构：只列目录；若存在规则文件 codepal.md 也列出，其它单文件不列出
            if (baseDir != null) {
                VirtualFile[] children = baseDir.getChildren();
                if (children != null && children.length > 0) {
                    List<String> topLevel = new ArrayList<>();
                    for (VirtualFile child : children) {
                        if (child.isDirectory()) {
                            topLevel.add(child.getName() + "/");
                        } else if ("codepal.md".equals(child.getName())) {
                            topLevel.add(child.getName());
                        }
                        // 其它单文件不列出
                    }
                    if (!topLevel.isEmpty()) {
                        sb.append("- 项目顶层结构（仅列出目录，及规则文件 codepal.md）：\n");
                        int limit = Math.min(topLevel.size(), 30);
                        for (int i = 0; i < limit; i++) {
                            sb.append("  - ").append(topLevel.get(i)).append("\n");
                        }
                        if (topLevel.size() > limit) {
                            sb.append("  ... 还有 ").append(topLevel.size() - limit).append(" 个目录\n");
                        }
                    }
                }
            }
        } else {
            sb.append("- 未打开项目\n");
        }

        sb.append("\n【路径使用规范】\n");
        if (project != null && project.getBasePath() != null) {
            sb.append("- ⚠️ 当前工作目录（项目根目录）绝对路径：").append(project.getBasePath()).append("\n");
            sb.append("- 所有 write_file、create_new_file、edit_file 创建的「文件」都会落在这个目录下\n");
        }
        sb.append("- 写入类工具（write_file/create_new_file/edit_file/delete_file）的 file_path 必须用「项目相对路径」，不要拼接绝对路径（这些文件默认落在上面项目根目录下）；\n");
        sb.append("- 读取类工具（read_file_range/view_file_outline）的 file_path「既支持项目相对路径，也支持 IDEA 之外的任意本地文件绝对路径」（如 D:/repos/other/foo.java）—— 插件不限制目录，项目外文件同样可读；\n");
        sb.append("- 例如：src/main/java/com/example/UserService.java（项目内相对路径）或 D:/repos/other/foo.java（项目外绝对路径）；\n");
                    sb.append("- 如果你不确定某个文件的相对路径，先用 locate_code_by_symbol 或 search_tool 定位\n");
        sb.append("- 阅读大文件（数百行以上）：先用 view_file_outline 看结构（含行号），再用 read_file_range 按行号精确读取你关心的那个方法/字段，不要顺序通读整文件（否则上下文消耗大、节奏慢）。\n");

        // 项目规则文件（对标 Claude 的 CLAUDE.md）：直接注入项目根目录 codepal.md 全文，
        // 让模型无条件遵守其中约定，不依赖模型自行读取。
        if (project != null && project.getBaseDir() != null) {
            VirtualFile rulesFile = project.getBaseDir().findFileByRelativePath("codepal.md");
            if (rulesFile != null) {
                try {
                    String rules = new String(rulesFile.contentsToByteArray(), rulesFile.getCharset());
                    sb.append("\n---\n【项目规则文件 codepal.md —— 必须严格遵守】\n")
                            .append(rules).append("\n");
                } catch (IOException e) {
                    LOG.warn("[CPSettings] 读取 codepal.md 失败", e);
                }
            }
        }

        return sb.toString();
    }

    /**
     * 构建包含项目上下文 + 当前模式的 System Prompt
     * Plan 模式：只读，禁止修改文件
     * Craft 模式：可修改文件，累积后统一展示 Diff 供用户确认
     */
    public String getSystemPrompt(Project project, boolean isCraftMode) {
        StringBuilder sb = new StringBuilder(getSystemPrompt(project));

        sb.append("\n---\n【当前模式】");
        if (isCraftMode) {
            sb.append(" ✨ Craft 模式\n");
            sb.append("- 你可以使用 edit_file 精确修改现有文件，用 write_file 覆盖写入文件（适合大改动），用 create_new_file 创建新文件。\n");
            sb.append("- 验证「你修改过的文件」能否编译通过，优先使用 compile_files 工具：它调用 IDEA 增量编译器，只编译你指定的文件（及其必需依赖），不受项目其他位置的预存错误干扰，能精确反映你改动的文件本身的编译状态。\n");
            sb.append("- 只有在需要跑全量构建、运行测试套件（mvn test / gradle test）或对未纳入源码根的脚本做编译时，才用 run_command 执行命令。\n");
            sb.append("- run_command 分级执行：只读查询静默执行，构建/测试类命令在IDEA控制台显示输出，高风险命令需用户确认。\n");
            sb.append("- 所有文件修改会先累积，待你完成所有任务、回复最终文字后，统一在 Diff 面板中展示给用户确认。\n");
            sb.append("- 用户确认后所有修改一次性应用，拒绝则全部撤销。\n");
            sb.append("- 修改过程中无需等待用户确认，请连续完成所有编辑任务，修改后运行编译验证。\n");
        } else {
            sb.append(" 📋 Plan 模式（只读）\n");
            sb.append("- 你只能阅读和分析代码，**绝对不能**调用 edit_file、write_file 或 create_new_file 修改文件。\n");
            sb.append("- run_command 命令权限：\n");
            sb.append("  - ✅ 允许：只读查询类命令（ls、cat、grep、git status、git log、git diff、查看版本号等）—— 静默快速执行\n");
            sb.append("  - ⚠️ 允许但用户可见：构建/测试类命令（mvn compile、npm install、gradle test等）—— 在IDEA控制台执行，用户全程可见输出\n");
            sb.append("  - 💡 若只想确认某个文件能否编译通过（而非全量构建），优先用 compile_files 工具（增量编译、不受项目其他错误干扰）。\n");
            sb.append("  - ❌ 禁止：高风险命令（rm -rf、git push、git reset --hard、sudo、重定向写文件等）—— 会被拦截或需用户确认\n");
            sb.append("- 如果用户要求修改代码，请以 Markdown 代码块的形式提供代码建议。\n");
            sb.append("- 如果用户坚持要你直接修改文件，请提示用户切换到 Craft 模式。\n");
        }

        // 注入已勾选启用的技能正文（勾选=模型可见；取消勾选=不可见且 load_skill 报找不到）
        appendEnabledSkills(sb);

        // 注入可用数据源清单 + 当前默认选择，让模型知道有哪些数据源可用以及用哪个（无需每次重复 db_name）。
        appendAvailableDataSources(sb);

        return sb.toString();
    }

    /**
     * 把当前已配置的数据库数据源清单注入系统提示词。
     * 格式：
     *   【可用数据源】
     *   - 当前默认：codepalSQL（mysql）；若已选，请直接用 db_name="codepalSQL"，无需重复指定。
     *   全部可用：codepalSQL（mysql）、xxx（sqlite）。db_type 不填默认按数据源实际类型。
     * 模型能力：调用 query_database 时 db_name 可留空，工具会自动用"当前默认"数据源。
     */
    private void appendAvailableDataSources(StringBuilder sb) {
        try {
            // getAll() 返回 DatabaseComboItem（仅 id/name/type），清单只需 name+type，够用
            java.util.List<com.codepal.model.DatabaseComboItem> sources =
                    com.codepal.db.DataSourceDao.getAll();
            if (sources.isEmpty()) return;
            String defaultName = com.codepal.tools.ToolExecutor.getDefaultDataSourceName();
            sb.append("\n---\n【可用数据源】\n");
            if (defaultName != null && !defaultName.isBlank()) {
                sb.append("- **当前默认**：`").append(defaultName).append("`（已由用户在工具栏左侧选择）；");
                sb.append("调用 `query_database` 时 **db_name 可留空**，工具会自动用默认数据源（仍可显式传 db_name 覆盖）。\n");
            } else {
                sb.append("- 当前未默认选中数据源：调用 `query_database` 时请显式传 `db_name`，或让用户先在工具栏选一个。\n");
            }
            sb.append("- 全部已配置数据源（`db_name / db_type`）：\n");
            for (com.codepal.model.DatabaseComboItem info : sources) {
                if (info == null || info.name == null) continue;
                sb.append("  - `").append(info.name).append("` (").append(
                        info.type == null ? "mysql" : info.type).append(")");
                if (defaultName != null && defaultName.equals(info.name)) {
                    sb.append(" ← 当前默认");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            // 数据源读取失败不影响系统提示主流程，吞掉异常
        }
    }

    /** 把当前已启用（勾选）的 skill 摘要注入系统提示词（只取 SKILL.md 头部简介段，省 token）。
     *  勾选 = 模型知道有此技能及用途；需要完整细节时再调 load_skill 拉全文。 */
    private void appendEnabledSkills(StringBuilder sb) {
        Set<String> enabled = enabledSkills;
        if (enabled == null || enabled.isEmpty()) return;
        List<String> names = new ArrayList<>(enabled);
        Collections.sort(names);
        sb.append("\n---\n【已启用技能】\n");
        sb.append("以下技能已启用，你可直接使用其能力；若需要某个技能的完整操作细节，"
                + "请调用 load_skill 工具（name=\"<技能名>\"）加载全文：\n");
        for (String name : names) {
            sb.append("\n # [技能]：").append(name).append("\n");
            String summary = com.codepal.skills.SkillStore.readSkillSummary(name);
            if (summary != null && !summary.isBlank()) {
                sb.append(summary).append("\n");
            } else {
                sb.append("（内容为空或读取失败）\n");
            }
        }
    }

    /**
     * 主模型是否应直接以多模态 content 接收图片（原生看图），而非走 view_image 子智能体。
     * 仅当当前聊天模型勾选了「自带视觉能力(supportsVision)」时为真。
     */
    public boolean shouldInlineImages() {
        ModelConfig current = getCurrentChatModel();
        return current != null && current.isSupportsVision();
    }

    /**
     * 是否应引导主模型使用 view_image 工具来看图 —— 视觉引导的【唯一决策源】。
     * 决策：主模型自带视觉(直传) 时不引导；否则仅当独立视觉子智能体生效时才引导。
     * 图片附件提示(buildImageHint)与 system prompt(appendVisionGuidance) 都以此为准，保证一致。
     */
    public boolean shouldGuideViewImage() {
        if (shouldInlineImages()) return false;
        return getEffectiveVisionModel() != null;
    }

    /**
     * 动态注入「图片/视觉能力」段落（与 buildImageHint 共用 shouldGuideViewImage 决策）。
     */
    private void appendVisionGuidance(StringBuilder sb) {
        if (!shouldGuideViewImage()) {
            return; // 未配置生效的视觉模型，不引导
        }
        sb.append("\n\n【图片/视觉能力】\n")
          .append("- 当用户在消息中附带图片（消息中含「[图片N: <路径>]」标记）时，你本身看不到图片字节；")
          .append("若需要理解图片内容，请调用 view_image 工具（image_path 填对应路径），")
          .append("把视觉模型返回的内容纳入你的回复。\n");
    }

    // ── 便捷方法 ──────────────────────────────────────────────
    /** 聊天模型是否已配置 */
    public boolean isConfigured() {
        return !getChatApiKey().trim().isEmpty();
    }

    /** 补全 API Key：只认补全模型自己的 key，不继承聊天模型（对齐视觉子智能体口径） */
    public String getEffectiveCompletionApiKey() {
        return getCompletionApiKey();
    }

    /** 补全 API Base：只认补全模型自己的 base，不继承聊天模型 */
    public String getEffectiveCompletionApiBase() {
        return getCompletionApiBase();
    }

    private void ensureDefaultModels() {
        if (chatModels == null) chatModels = new ArrayList<>();
        if (completionModels == null) completionModels = new ArrayList<>();
        boolean seeded = false;
        if (chatModels.isEmpty()) {
            // DeepSeek 推荐默认：1M 上下文 / 384K 输出
            ModelConfig def = new ModelConfig("deepseek-v4-flash", "",
                    "https://api.deepseek.com", 1_048_576, 0.7);
            def.setMaxOutput(393_216);
            DBModelConfigRepository.insertModel(DBModelConfigRepository.TYPE_CHAT, def);
            seeded = true;
        }
        if (completionModels.isEmpty()) {
            ModelConfig compDefault = new ModelConfig("deepseek-v4-flash", "",
                    "https://api.deepseek.com/beta", 1_048_576, 0.0);
            compDefault.setMaxOutput(256);
            DBModelConfigRepository.insertModel(DBModelConfigRepository.TYPE_COMPLETION, compDefault);
            seeded = true;
        }
        if (seeded) {
            // 种子写入后重新以 DB 为准读回（含 is_current 自愈）
            reloadModelsFromDb();
        }
        // 视觉模型默认值（开关默认关闭，用户需主动开启并填 key）
        if (visionModel == null) {
            visionModel = new ModelConfig("gpt-4o", "",
                    "https://api.openai.com/v1/chat/completions", 128_000, 0.2);
            visionModel.setMaxOutput(4096);
            visionModel.setApiFormat(ModelConfig.FORMAT_OPENAI);
        }
    }

    public void ensureDefaultAgents() {
        if (installedAgentNames == null) installedAgentNames = new ArrayList<>();
        if (installedAgentNames.isEmpty()) {
            installedAgentNames.add("CodePal Agent");
        }
    }

    public String getCurrentAgentName() {
        String m = getCurrentAgent();
        return StringUtils.isBlank(m) ? "CodePal Agent" : m;
    }

    // ── MCP 服务器配置 getter/setter ──────────────────────────
    public List<String> getMcpServerJsons() {
        if (mcpServerJsons == null) mcpServerJsons = new ArrayList<>();
        return mcpServerJsons;
    }

    public void setMcpServerJsons(List<String> mcpServerJsons) {
        this.mcpServerJsons = mcpServerJsons;
        fireSettingsChanged();
    }

    /** 以 McpServerConfig 列表形式获取 */
    public List<McpServerConfig> getMcpServers() {
        List<McpServerConfig> list = new ArrayList<>();
        for (String json : getMcpServerJsons()) {
            try {
                list.add(McpServerConfig.fromJson(json));
            } catch (Exception e) {
                System.err.println("[MCP] Failed to deserialize config: " + e.getMessage());
            }
        }
        return list;
    }

    /** 以 McpServerConfig 列表形式保存 */
    public void setMcpServers(List<McpServerConfig> configs) {
        List<String> jsons = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            jsons.add(cfg.toJsonString());
        }
        setMcpServerJsons(jsons);
    }

}
