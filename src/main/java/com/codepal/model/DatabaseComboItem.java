package com.codepal.model;

/**
 * 数据源下拉框的单项模型。
 * 既承载已配置的真实数据源（kind=KIND_SOURCE），也承载底部「配置数据源」占位项（kind=KIND_ADD）。
 * 仿 ChatPanel.ModelComboItem 的形态，但为独立类以便跨模块复用。
 */
public class DatabaseComboItem {
    public static final int KIND_SOURCE = 0;
    public static final int KIND_ADD = 1;
    /** 顶部「数据源」入口占位项：始终存于 model 第 0 位，未主动选择具体库时默认选中（selectedIndex=0）。
     *  toString() 返回 "数据源"，配合 CPComboUI.getPreferredSize 正常算出收起态宽度（避免 null 塌陷）。
     *  列表态由 DatabaseComboRenderer 单独分支 fillRect popupSurfaceColor 让它在视觉中"消失"。 */
    public static final int KIND_PLACEHOLDER = 2;

    public final String id;       // 数据源主键（KIND_ADD 时为空）
    public final String name;     // 展示名
    public final String type;     // "mysql" | "sqlite"
    public final int kind;        // KIND_SOURCE | KIND_ADD

    private DatabaseComboItem(String id, String name, String type, int kind) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.kind = kind;
    }

    /** 真实数据源项 */
    public static DatabaseComboItem source(String id, String name, String type)  {
        return new DatabaseComboItem(id, name, type, KIND_SOURCE);
    }

    /** 底部「配置数据源」占位项 */
    public static DatabaseComboItem addItem() {
        return new DatabaseComboItem("", " 配置数据源", "", KIND_ADD);
    }

    /** 顶部「数据源」入口占位项（model 第 0 位） */
    public static DatabaseComboItem placeholder() {
        return new DatabaseComboItem("", "数据源", "", KIND_PLACEHOLDER);
    }

    public boolean isAddItem() {
        return kind == KIND_ADD;
    }

    public boolean isPlaceholder() {
        return kind == KIND_PLACEHOLDER;
    }

    /** 是否为可查询的真实数据源（排除占位项与新增项） */
    public boolean isSourceItem() {
        return kind == KIND_SOURCE;
    }

    @Override
    public String toString() {
        return name;
    }
}
